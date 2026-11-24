package io.github.fantuani.padpaste;

import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.net.NetworkRequest;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** One authenticated link, kept open while the canvas can receive or screenshot monitoring is on. */
public final class DuplexSync {
    private static final int PORT = 5036, QUESTION_MAX = 6 * 1024 * 1024, CAPTURE_MAX = 50 * 1024 * 1024;
    private static final int QUESTION = 1, CAPTURE = 2, QUESTION_ACK = 3, CAPTURE_ACK = 4, HEARTBEAT = 5, AVAILABILITY = 6;
    private static final int QUESTION_START = 9, QUESTION_PERMISSION = 10, CAPTURE_START = 11, TRANSFER_CONFLICT = 12;
    private static final int CAPTURE_PROGRESS = 13, CAPTURE_READY = 14, CAPTURE_COMMIT = 15;
    private static final int CAPTURE_CHUNK = 16, QUESTION_CHUNK = 17, CANCEL = 18, CANCEL_ACK = 19;
    private static final int BLUETOOTH_IMAGE_EDGE = 1280;
    private static final byte[] CHUNK_FEATURE = "XFERPRT3".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LIVE_FEATURE = "XFERLIVE".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TRANSFER_FEATURE = "XFERLOCK".getBytes(StandardCharsets.US_ASCII);
    private static final long BLUETOOTH_IDLE_MS = 24_000;
    private static final byte[] VERSION = "PADSYNC2".getBytes(StandardCharsets.US_ASCII);
    private static final String SERVICE = "_padquestion._tcp.";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static DuplexSync instance;

    public interface Listener {
        void status(boolean connected);
        // The image is validated on the reader thread before acknowledging it.
        boolean image(byte[] data, BooleanSupplier accept);
    }

    public static final class TransferProgress {
        public final boolean uploading;
        public final long displayAfter;
        private final int total;
        private volatile int transferred;
        private volatile long finishedAt;
        private volatile boolean successful;
        private volatile boolean cancelled;
        private volatile boolean committing;
        private volatile long activity = SystemClock.elapsedRealtime();

        private TransferProgress(boolean uploading, int total, boolean bluetooth) {
            this.uploading = uploading;
            this.total = total;
            displayAfter = SystemClock.uptimeMillis() + (bluetooth ? 0 : 500);
        }

        public boolean finished() { return finishedAt != 0; }
        public long finishedAt() { return finishedAt; }
        public boolean cancelled() { return cancelled; }
        public boolean canCancel() { return !finished() && !committing; }

        public float fraction() {
            return successful ? 1f : Math.min(.94f, (float) transferred / total * .94f);
        }

        private void finish(boolean success) {
            if (finishedAt == 0) {
                successful = success;
                finishedAt = SystemClock.uptimeMillis();
            }
        }
    }

    static final class TransferCancelled extends IOException {
        TransferCancelled() { super("传输已取消"); }
    }

    static final class TransferConflict extends IOException {
        TransferConflict() { super("正在接收题图，本次截图未上传"); }
    }

    private record Pairing(byte[] key, String host, List<String> addresses) {
        static Pairing parse(String code) throws Exception {
            JSONObject config = new JSONObject(new String(Base64.decode(code.trim(), Base64.DEFAULT),
                    StandardCharsets.UTF_8));
            byte[] key = Base64.decode(config.getString("key"), Base64.DEFAULT);
            if (key.length != 32) throw new IOException("配对码无效");
            JSONArray array = config.getJSONArray("addresses");
            List<String> addresses = new ArrayList<>();
            for (int i = 0; i < Math.min(12, array.length()); i++) addresses.add(array.getString(i));
            return new Pairing(key, config.getString("host"), addresses);
        }

        String serviceName() throws Exception {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            StringBuilder name = new StringBuilder("PadPaste-");
            for (int i = 0; i < 6; i++) name.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
            return name.toString();
        }
    }

    public static synchronized DuplexSync get(Context context) {
        if (instance == null) instance = new DuplexSync(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> new Thread(task, "padpaste-writer"));
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(
            task -> new Thread(task, "padpaste-link-maintenance"));
    private volatile Listener listener;
    private volatile TransferProgress transferProgress;
    private Runnable progressListener;
    private Toast conflictToast;
    private volatile Client client;
    private boolean monitoring;
    private Boolean lastBluetoothRoute;
    private volatile QuestionResult previousQuestion;
    private record QuestionResult(byte[] id, boolean accepted) {}

    private DuplexSync(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("question_link", Context.MODE_PRIVATE);
    }

    public TransferProgress transferProgress() { return transferProgress; }

    // Observed only while the canvas activity is resumed.
    public void observeProgress(Runnable listener) {
        progressListener = listener;
        if (listener != null) listener.run();
    }

    private void startProgress(TransferProgress progress) {
        transferProgress = progress;
        main.post(() -> {
            if (progressListener != null) progressListener.run();
        });
    }

    public boolean cancelTransfer(TransferProgress progress) {
        Client current = client;
        if (current == null || !current.cancelTransfer(progress)) return false;
        showTransferMessage("传输已取消");
        return true;
    }

    private void showTransferMessage(String text) {
        main.post(() -> {
            if (conflictToast != null) conflictToast.cancel();
            conflictToast = Toast.makeText(context, text, Toast.LENGTH_SHORT);
            conflictToast.show();
        });
    }

    public boolean paired() { return preferences.contains("pairing"); }
    public boolean connected() {
        Client current = client;
        return current != null && current.connected && current.cancellationRecovery == null;
    }

    public boolean duplex() {
        Client current = client;
        return current != null && current.connected && current.duplex && current.cancellationRecovery == null;
    }

    public boolean bluetoothEnabled() { return preferences.getBoolean("bluetooth_fallback", false); }
    public boolean bluetoothAllowed() { return BluetoothSyncLink.allowed(context); }
    public boolean bluetoothRadioOn() { return BluetoothSyncLink.radioOn(context); }

    public boolean connectedViaBluetooth() {
        Client current = client;
        Session link = current == null ? null : current.session;
        return link != null && current.connected && current.cancellationRecovery == null && link.bluetooth;
    }

    public synchronized void setBluetoothEnabled(boolean enabled) {
        preferences.edit().putBoolean("bluetooth_fallback", enabled).apply();
        Client current = client;
        if (!enabled && current != null) close(current.bluetoothLink);
        publish();
    }

    public String connectedAddress() {
        Client current = client;
        Socket socket = current == null ? null : current.socket;
        if (socket == null || !current.connected || current.cancellationRecovery != null) return "";
        InetAddress peer = socket.getInetAddress();
        if (peer == null) return "";
        String host = peer.getHostAddress();
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + socket.getPort();
    }

    public synchronized void pair(String code) throws Exception {
        Pairing.parse(code);
        preferences.edit().putString("pairing", code.trim()).remove("last_address").apply();
        reconnect();
    }

    public synchronized void reconnect() {
        if (client != null) client.stop();
        client = null;
        reconcile();
        publish();
    }

    public synchronized void listen(Listener next) {
        listener = next;
        reconcile();
        sendAvailability();
        publish();
    }

    synchronized void monitor(boolean on) {
        monitoring = on;
        reconcile();
    }

    private void reconcile() {
        if ((listener == null && !monitoring) || !paired()) {
            if (client != null) client.stop();
            client = null;
        } else if (client == null) {
            try {
                client = new Client(Pairing.parse(preferences.getString("pairing", "")));
                client.start();
            } catch (Exception ignored) { publish(); }
        }
    }

    private void publish() {
        main.post(() -> {
            Client active = client;
            Session route = active == null ? null : active.session;
            if (route != null && active.connected && active.cancellationRecovery == null) {
                if (lastBluetoothRoute != null && lastBluetoothRoute != route.bluetooth) {
                    showTransferMessage(route.bluetooth ? "已切换到蓝牙" : "已切换到 Wi-Fi");
                }
                lastBluetoothRoute = route.bluetooth;
            }
            Listener current = listener;
            if (current != null) current.status(connected());
            SyncState.publish(context);
        });
    }

    private void sendAvailability() {
        Client current = client;
        if (current != null && current.duplex && current.connected) {
            writer.execute(() -> current.availability());
        }
    }

    void upload(String name, byte[] data) throws IOException {
        Client current = client;
        if (current == null) throw new IOException("Mac 尚未连接");
        current.upload(name, data);
    }

    private static final class RouteChanged extends IOException {}

    private final class Client {
        private final Pairing pairing;
        private final String serviceName;
        private final ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        private final NsdManager nsd = context.getSystemService(NsdManager.class);
        private final Thread reader = new Thread(this::run, "padpaste-reader");
        private final AtomicReference<CompletableFuture<Boolean>> receipt = new AtomicReference<>();
        private volatile boolean stopped, connected, duplex;
        private volatile String discoveredAddress;
        private volatile Socket socket;
        private volatile Session session;
        private volatile CompletableFuture<Session> cancellationRecovery;
        private volatile BluetoothSyncLink bluetoothLink;
        private final AtomicBoolean probing = new AtomicBoolean();
        private final ExecutorService networkProbe = Executors.newSingleThreadExecutor(task -> new Thread(task, "padpaste-wifi-probe"));
        private boolean networkCallbackRegistered;
        private final ConnectivityManager.NetworkCallback networkEvents = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { requestNetworkUpgrade(); }
            @Override public void onLinkPropertiesChanged(Network network, android.net.LinkProperties properties) {
                requestNetworkUpgrade();
            }
            @Override public void onLost(Network network) {
                try {
                    networkProbe.execute(() -> {
                        Session current = session;
                        if (current != null && !current.bluetooth && !hasLocalNetwork()) changeRoute(current, null);
                    });
                } catch (RejectedExecutionException stoppedClient) {
                    // The old client has already stopped.
                }
            }
        };
        private volatile String preferredNetwork;
        // Access to the handoff flag and reservations for an outbound screenshot is serialized.
        private final Object routing = new Object();
        private volatile int questionLength;
        private TransferProgress download;
        private byte[] questionBytes;
        private int questionOffset;
        private byte[] questionId;
        private TransferProgress upload;

        Client(Pairing pairing) throws Exception { this.pairing = pairing; serviceName = pairing.serviceName(); }

        private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStartDiscoveryFailed(String type, int error) {}
            @Override public void onStopDiscoveryFailed(String type, int error) {}
            @Override public void onServiceLost(NsdServiceInfo info) {
                if (info.getServiceName().startsWith(serviceName)) discoveredAddress = null;
            }
            @Override public void onServiceFound(NsdServiceInfo info) {
                if (stopped || !info.getServiceName().startsWith(serviceName)) return;
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo service, int error) {}
                        @Override public void onServiceResolved(NsdServiceInfo service) {
                            if (!stopped && service.getPort() == PORT && service.getHost() != null) {
                                discoveredAddress = service.getHost().getHostAddress();
                                requestNetworkUpgrade();
                            }
                        }
                    });
                } catch (RuntimeException ignored) {}
            }
        };

        void start() {
            if (nsd != null) {
                try { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery); }
                catch (RuntimeException ignored) {}
            }
            if (connectivity != null) {
                try {
                    connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                            .build(), networkEvents);
                    networkCallbackRegistered = true;
                } catch (RuntimeException ignored) {}
            }
            reader.start();
        }

        void stop() {
            stopped = true;
            synchronized (routing) {
                if (cancellationRecovery != null) {
                    cancellationRecovery.complete(null);
                    cancellationRecovery = null;
                }
            }
            if (networkCallbackRegistered) connectivity.unregisterNetworkCallback(networkEvents);
            networkProbe.shutdownNow();
            close(socket);
            close(bluetoothLink);
            reader.interrupt();
            if (nsd != null) {
                try { nsd.stopServiceDiscovery(discovery); }
                catch (RuntimeException ignored) {}
            }
        }

        private LinkedHashSet<String> networkTargets() {
            LinkedHashSet<String> targets = new LinkedHashSet<>();
            if (preferredNetwork != null) targets.add(preferredNetwork);
            if (discoveredAddress != null) targets.add(discoveredAddress);
            targets.add(preferences.getString("last_address", ""));
            targets.add(pairing.host);
            targets.addAll(pairing.addresses);
            targets.remove("");
            return targets;
        }

        @android.annotation.SuppressLint("MissingPermission") // Both app manifests declare ACCESS_NETWORK_STATE.
        @SuppressWarnings("deprecation") // Check all local transports, including Wi-Fi without internet.
        private boolean hasLocalNetwork() {
            if (connectivity == null) return true;
            for (var network : connectivity.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                if (capabilities != null && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return true;
            }
            return false;
        }

        private void run() {
            int backoff = 450;
            while (!stopped) {
                boolean fallback = bluetoothEnabled() && bluetoothRadioOn();
                boolean reconnectNetwork = false;
                if (!fallback || hasLocalNetwork()) {
                    var targets = networkTargets();
                    preferredNetwork = null;
                    for (String address : targets) {
                        if (stopped) return;
                        boolean wasConnected;
                        try (Socket next = new Socket()) {
                            socket = next;
                            if (stopped) return;
                            next.connect(new InetSocketAddress(address, PORT), 1100);
                            next.setSoTimeout(3500);
                            next.setKeepAlive(true);
                            next.setTcpNoDelay(true);
                            exchange(next, next.getInputStream(), next.getOutputStream(), next, address);
                        } catch (Exception ignored) {
                            // Try the next known address unless an active connection just closed.
                        } finally {
                            wasConnected = connected;
                            reconnectNetwork = disconnected();
                            socket = null;
                        }
                        if (wasConnected) {
                            backoff = 450;
                            break;
                        }
                    }
                }
                if (stopped) return;
                if (reconnectNetwork) {
                    backoff = 450;
                    continue;
                }
                if (fallback && bluetoothEnabled()) {
                    boolean cancelledOnBluetooth;
                    do {
                        cancelledOnBluetooth = connectBluetooth();
                    } while (cancelledOnBluetooth && preferredNetwork == null
                            && !stopped && bluetoothEnabled() && bluetoothRadioOn());
                    backoff = Math.max(backoff, 1800);
                    if (preferredNetwork != null) {
                        backoff = 450;
                        continue;
                    }
                }
                try { Thread.sleep(backoff); } catch (InterruptedException done) { return; }
                backoff = Math.min(fallback ? 15_000 : 2400, backoff * 2);
            }
        }

        private boolean connectBluetooth() {
            if (stopped) return false;
            boolean cancelled;
            try (BluetoothSyncLink next = new BluetoothSyncLink(context,
                    MessageDigest.getInstance("SHA-256").digest(pairing.key))) {
                bluetoothLink = next;
                if (stopped || !bluetoothEnabled()) return false;
                var opening = maintenance.schedule(next::close, 27, TimeUnit.SECONDS);
                BluetoothSocket stream;
                try { stream = next.connect(); }
                finally { opening.cancel(false); }
                var handshake = maintenance.schedule(next::close, 8, TimeUnit.SECONDS);
                try {
                    exchange(next, stream.getInputStream(), stream.getOutputStream(), null, null, handshake);
                } finally { handshake.cancel(false); }
            } catch (Exception ignored) {
                // Wi-Fi gets another opportunity before the next scan.
            } finally {
                bluetoothLink = null;
                cancelled = disconnected();
            }
            return cancelled;
        }

        private boolean disconnected() {
            boolean changed;
            boolean cancelled;
            synchronized (routing) {
                changed = connected;
                Session previous = session;
                cancelled = previous != null && (previous.cancelledByUser || previous.routeChanged);
                connected = duplex = false;
                session = null;
                questionLength = 0;
                questionBytes = null;
                questionId = null;
                if (download != null) download.finish(false);
                if (upload != null) upload.finish(false);
                download = upload = null;
                CompletableFuture<Boolean> pending = receipt.getAndSet(null);
                if (pending != null) pending.complete(false);
            }
            if (changed) publish();
            return cancelled;
        }

        private void exchange(Closeable transport, InputStream stream, OutputStream outputStream,
                              Socket tcp, String address) throws Exception {
            exchange(transport, stream, outputStream, tcp, address, null);
        }

        private void exchange(Closeable transport, InputStream stream, OutputStream outputStream,
                              Socket tcp, String address, Future<?> handshake) throws Exception {
            boolean bluetooth = tcp == null;
            Session link = new Session(transport, outputStream, new byte[64], pairing.key, bluetooth);
            DataOutputStream output = link.output;
            DataInputStream input = link.input(stream);
            duplex = link.verifyMac(input);
            output.write(link.proof("tablet", null));
            if (bluetooth) {
                if (!duplex) throw new IOException("Mac 尚未支持蓝牙双向同步");
                handshake.cancel(false);
            } else {
                preferredNetwork = null;
                preferences.edit().putString("last_address", address).apply();
                tcp.setSoTimeout(duplex ? 24000 : 0);
            }
            synchronized (routing) {
                if (stopped) return;
                session = link;
                connected = true;
                if (cancellationRecovery != null) {
                    cancellationRecovery.complete(duplex ? link : null);
                    cancellationRecovery = null;
                }
            }
            publish();
            if (duplex) availability();
            ScheduledFuture<?> check = bluetooth
                    ? maintenance.scheduleWithFixedDelay(() -> maintainBluetooth(link), 1, 2, TimeUnit.SECONDS)
                    : null;
            try {
                while (!stopped && !link.routeChanged) {
                    int length = input.readInt();
                    if (length <= 28 || length > QUESTION_MAX + 29) throw new IOException("无效同步数据");
                    byte[] frame = new byte[length];
                    if (!link.chunked && download != null && questionLength == length) {
                        for (int offset = 0; offset < length;) {
                            int count = input.read(frame, offset, Math.min(65536, length - offset));
                            if (count <= 0) throw new EOFException();
                            offset += count;
                            download.transferred = offset;
                        }
                    } else {
                        input.readFully(frame);
                    }
                    if (duplex) receive(link, link.decrypt(frame, "mac-frame"));
                    else {
                        deliver(link.decrypt(frame, "image"));
                        output.write(link.proof("accepted", Arrays.copyOfRange(frame, length - 16, length)));
                    }
                }
            } finally {
                if (check != null) check.cancel(false);
            }
        }

        private void maintainBluetooth(Session link) {
            if (stopped || session != link) return;
            long now = SystemClock.elapsedRealtime();
            if (!bluetoothEnabled() || now - link.lastRead >= BLUETOOTH_IDLE_MS
                    || link.writing && now - link.lastWrite >= BLUETOOTH_IDLE_MS) {
                close(link.transport);
                return;
            }
            if (!link.writing && now - link.lastWrite >= 7000 && receipt.get() == null) {
                writer.execute(() -> sendBluetoothControl(link, HEARTBEAT));
            }
            requestNetworkUpgrade();
        }

        private void requestNetworkUpgrade() {
            Session current = session;
            if (stopped || current == null || !current.bluetooth || current.routeChanged || !hasLocalNetwork()
                    || !probing.compareAndSet(false, true)) return;
            try {
                networkProbe.execute(() -> {
                    try {
                        String address = probeNetwork();
                        if (address != null) changeRoute(current, address);
                    } finally { probing.set(false); }
                });
            } catch (RejectedExecutionException stoppedClient) {
                probing.set(false);
            }
        }

        private void changeRoute(Session current, String preferredAddress) {
            synchronized (routing) {
                if (stopped || session != current || current.routeChanged || current.cancelledByUser) return;
                current.routeChanged = true;
                preferredNetwork = preferredAddress;
                if (cancellationRecovery == null) cancellationRecovery = new CompletableFuture<>();
            }
            close(current.transport);
        }

        private String probeNetwork() {
            for (String address : networkTargets()) {
                if (stopped) break;
                try (Socket probe = new Socket()) {
                    probe.connect(new InetSocketAddress(address, PORT), 550);
                    probe.setSoTimeout(650);
                    Session candidate = new Session(probe, probe.getOutputStream(), new byte[64], pairing.key, false);
                    if (candidate.verifyMac(candidate.input(probe.getInputStream()))) return address;
                } catch (Exception ignored) {}
            }
            return null;
        }

        private void sendBluetoothControl(Session link, int kind) {
            if (stopped || session != link) return;
            if (kind == HEARTBEAT && receipt.get() != null) return;
            try { link.send(kind, new byte[0]); }
            catch (Exception failure) { close(link.transport); }
        }

        private void receive(Session link, byte[] frame) throws Exception {
            switch (frame[0]) {
                case QUESTION -> receiveQuestion(link, frame, 1);
                case QUESTION_START -> requestQuestion(link, frame);
                case QUESTION_CHUNK -> receiveQuestionChunk(link, frame);
                case CANCEL_ACK -> {
                    if (!link.chunked || frame.length != 2) throw new IOException("无效取消回执");
                    synchronized (routing) {
                        if (frame[1] == CAPTURE && upload != null && upload.cancelled) {
                            CompletableFuture<Boolean> pending = receipt.get();
                            if (pending != null) pending.complete(false);
                        } else if (frame[1] == QUESTION && download != null && download.cancelled) {
                            questionLength = 0;
                            questionBytes = null;
                            questionId = null;
                            download = null;
                        }
                    }
                }
                case TRANSFER_CONFLICT -> {
                    if (!link.transferLock || frame.length != 1) throw new IOException("无效的传输状态");
                    showTransferMessage("正在上传截图，本次题图未接收");
                }
                case CAPTURE_PROGRESS -> updateCaptureProgress(link, frame);
                case CAPTURE_READY -> commitCapture(link, frame);
                case CAPTURE_ACK -> {
                    if (frame.length != 2) throw new IOException("无效同步回执");
                    synchronized (routing) {
                        boolean ok = frame[1] == 1;
                        if (upload != null && !upload.cancelled) upload.finish(ok);
                        upload = null;
                        CompletableFuture<Boolean> pending = receipt.getAndSet(null);
                        if (pending != null) pending.complete(ok);
                    }
                }
                case HEARTBEAT -> { if (frame.length != 1) throw new IOException("无效同步心跳"); }
                default -> throw new IOException("无效同步类型");
            }
        }

        private void updateCaptureProgress(Session link, byte[] frame) throws IOException {
            if (!link.liveProgress || frame.length != 5) throw new IOException("无效的上传进度");
            int transferred = ByteBuffer.wrap(frame, 1, 4).getInt();
            synchronized (routing) {
                TransferProgress current = upload;
                if (current == null || current.cancelled || current.committing || link.routeChanged) return;
                if (transferred < 0 || transferred > current.total) throw new IOException("无效的上传进度");
                if (transferred > current.transferred) {
                    current.transferred = transferred;
                    current.activity = SystemClock.elapsedRealtime();
                }
            }
        }

        private void commitCapture(Session link, byte[] frame) throws Exception {
            if (!link.liveProgress || frame.length != 1) throw new IOException("无效的上传确认");
            synchronized (routing) {
                TransferProgress current = upload;
                if (current == null || current.cancelled || current.committing || link.routeChanged) return;
                current.transferred = current.total;
                current.activity = SystemClock.elapsedRealtime();
                current.committing = true;
            }
            link.send(CAPTURE_COMMIT, new byte[0]);
        }

        private boolean cancelTransfer(TransferProgress progress) {
            Session current;
            synchronized (routing) {
                if (progress == null || (progress != upload && progress != download) || !progress.canCancel()) return false;
                current = session;
                if (current == null) return false;
                progress.cancelled = true;
                progress.finish(false);
                if (!progress.uploading && questionId != null) previousQuestion = new QuestionResult(questionId, false);
                if (!current.chunked) {
                    current.cancelledByUser = true;
                    cancellationRecovery = new CompletableFuture<>();
                    CompletableFuture<Boolean> pending = receipt.get();
                    if (pending != null) pending.complete(false);
                }
            }
            if (current.chunked) {
                writer.execute(() -> {
                    if (session != current) return;
                    try { current.send(CANCEL, new byte[]{(byte) (progress.uploading ? CAPTURE : QUESTION)}); }
                    catch (Exception failure) { close(current.transport); }
                });
            } else {
                new Thread(() -> close(current.transport), "padpaste-cancel-link").start();
            }
            publish();
            return true;
        }

        private void requestQuestion(Session link, byte[] frame) throws Exception {
            if (!link.transferLock || frame.length != (link.chunked ? 21 : 5)) throw new IOException("无效的题图请求");
            byte[] id = link.chunked ? Arrays.copyOfRange(frame, 5, 21) : null;
            QuestionResult previous = previousQuestion;
            if (id != null && previous != null && Arrays.equals(id, previous.id)) {
                link.send(QUESTION_PERMISSION, new byte[]{(byte) (previous.accepted ? 2 : 0)});
                return;
            }
            int size = ByteBuffer.wrap(frame, 1, 4).getInt();
            if (size <= 0 || size > QUESTION_MAX) throw new IOException("题图尺寸无效");
            boolean busy;
            boolean granted;
            synchronized (routing) {
                busy = receipt.get() != null || questionLength != 0;
                granted = !busy && !link.routeChanged && listener != null;
                if (granted) {
                    questionLength = size + 29;
                    questionId = id;
                    if (link.chunked) {
                        questionBytes = new byte[size];
                        questionOffset = 0;
                    }
                    download = new TransferProgress(false, questionLength, link.bluetooth);
                    startProgress(download);
                }
            }
            if (busy) showTransferMessage("正在上传截图，本次题图未接收");
            final byte[] reply = {(byte) (granted ? 1 : 0)};
            // A refusal must not block the reader while the writer is sending a screenshot.
            if (granted) {
                link.send(QUESTION_PERMISSION, reply);
            } else {
                writer.execute(() -> {
                    if (session != link) return;
                    try { link.send(QUESTION_PERMISSION, reply); }
                    catch (Exception failure) { close(link.transport); }
                });
            }
        }

        private void receiveQuestionChunk(Session link, byte[] frame) throws Exception {
            if (!link.chunked || questionBytes == null || frame.length <= 1
                    || questionOffset + frame.length - 1 > questionBytes.length) throw new IOException("无效的题图数据");
            System.arraycopy(frame, 1, questionBytes, questionOffset, frame.length - 1);
            questionOffset += frame.length - 1;
            download.transferred = questionOffset;
            if (questionOffset == questionBytes.length && !download.cancelled) receiveQuestion(link, questionBytes, 0);
        }

        private void receiveQuestion(Session link, byte[] data, int offset) throws Exception {
            if (link.transferLock && (download == null || data.length - offset + 29 != questionLength)) {
                throw new IOException("题图未获传输许可");
            }
            TransferProgress current = download;
            boolean accepted = deliver(offset == 0 ? data : Arrays.copyOfRange(data, offset, data.length), () -> {
                synchronized (routing) {
                    if (current == null) return !stopped;
                    if (current.cancelled || session != link || link.routeChanged) return false;
                    current.committing = true;
                    return true;
                }
            });
            // Publish the acknowledgement on the wire before any newly reserved upload can write.
            synchronized (routing) {
                if (current != null && current.cancelled) return;
                if (questionId != null) previousQuestion = new QuestionResult(questionId, accepted);
                link.send(QUESTION_ACK, new byte[]{(byte) (accepted ? 1 : 0)});
                questionId = null;
                if (current != null) current.finish(accepted);
                download = null;
                questionBytes = null;
                questionLength = 0;
            }
        }

        private boolean deliver(byte[] data) {
            return deliver(data, () -> !stopped);
        }

        private boolean deliver(byte[] data, BooleanSupplier accept) {
            Listener current = listener;
            return !stopped && current != null && current.image(data, accept);
        }

        private void availability() {
            Session link = session;
            if (link == null || !duplex) return;
            try { link.send(AVAILABILITY, new byte[]{(byte) (listener == null ? 0 : 1)}); }
            catch (Exception failure) { close(link.transport); }
        }

        private Session sessionAfterCancellation() throws IOException {
            CompletableFuture<Session> recovery = cancellationRecovery;
            if (recovery == null) return session;
            try {
                return recovery.get(16, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("同步已中断", interrupted);
            } catch (ExecutionException | TimeoutException failure) {
                throw new IOException("Mac 暂未重新连接", failure);
            }
        }

        private void upload(String name, byte[] original) throws IOException {
            for (int attempt = 0; attempt < 3; attempt++) {
                Session link = sessionAfterCancellation();
                if (link == null) throw new IOException("Mac 尚未连接");
                try {
                    upload(link, name, original);
                    return;
                } catch (RouteChanged changed) {
                    if (attempt == 2) throw new IOException("连接反复切换，截图未上传", changed);
                }
            }
        }

        private void upload(Session link, String name, byte[] original) throws IOException {
            if (link.routeChanged) throw new RouteChanged();
            byte[] data = link.bluetooth ? compactScreenshot(original) : original;
            String wireName = link.bluetooth ? name + ".jpg" : name;
            byte[] filename = wireName.getBytes(StandardCharsets.UTF_8);
            if (!connected || !duplex || link == null || filename.length > 255 || data.length > CAPTURE_MAX) {
                throw new IOException("Mac 尚未连接或截图过大");
            }
            if (link.bluetooth && data.length > QUESTION_MAX) throw new IOException("蓝牙截图超过 6 MB");
            var pending = new CompletableFuture<Boolean>();
            var progress = new TransferProgress(true, data.length + filename.length + 30, link.bluetooth);
            synchronized (routing) {
                if (link.routeChanged) throw new RouteChanged();
                if (session != link || link.cancelledByUser) throw new IOException("正在切换同步连接");
                if (questionLength != 0) throw new TransferConflict();
                if (!receipt.compareAndSet(null, pending)) throw new IOException("上一张截图仍在传输");
                upload = progress;
                startProgress(progress);
            }
            if (link.bluetooth) link.lastWrite = SystemClock.elapsedRealtime();
            writer.execute(() -> {
                try {
                    if (session != link || receipt.get() != pending || (progress.cancelled && !link.chunked)) return;
                    byte[] header = new byte[2 + filename.length];
                    header[0] = CAPTURE;
                    header[1] = (byte) filename.length;
                    System.arraycopy(filename, 0, header, 2, filename.length);
                    if (link.transferLock) {
                        byte[] announcement = link.liveProgress
                                ? ByteBuffer.allocate(4).putInt(progress.total).array() : new byte[0];
                        link.send(CAPTURE_START, announcement);
                    }
                    if (link.chunked) {
                        if (!progress.cancelled) {
                            byte[] kind = {(byte) CAPTURE_CHUNK};
                            link.sendParts(kind, header, 1, header.length - 1, null);
                            int chunk = link.bluetooth ? 8192 : 65536;
                            for (int offset = 0; offset < data.length && !progress.cancelled && !link.routeChanged; offset += chunk) {
                                link.sendParts(kind, data, offset, Math.min(chunk, data.length - offset), null);
                            }
                        }
                    } else {
                        link.sendParts(header, data, 0, data.length, progress);
                    }
                } catch (Exception failure) {
                    if (!progress.cancelled) {
                        android.util.Log.w("PadPasteSync", "Screenshot send failed: " + failure.getClass().getSimpleName());
                        close(link.transport);
                    }
                    pending.complete(false);
                }
            });
            try {
                if (!(link.liveProgress || link.bluetooth ? waitForReceipt(link, pending, progress)
                        : pending.get(9, TimeUnit.SECONDS))) {
                    if (progress.cancelled) throw new TransferCancelled();
                    if (link.routeChanged) throw new RouteChanged();
                    throw new IOException("Mac 未接收截图");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                close(link.transport);
                throw new IOException("同步已中断", interrupted);
            } catch (TimeoutException | ExecutionException failure) {
                close(link.transport);
                if (progress.cancelled) throw new TransferCancelled();
                if (link.routeChanged) throw new RouteChanged();
                android.util.Log.w("PadPasteSync", "Upload stalled: receiver confirmed "
                        + progress.transferred + "/" + progress.total + " bytes, commit=" + progress.committing);
                throw new IOException("Mac 未确认截图", failure);
            } finally {
                synchronized (routing) {
                    receipt.compareAndSet(pending, null);
                    if (upload == progress) {
                        progress.finish(false);
                        upload = null;
                    }
                }
            }
        }
        private boolean waitForReceipt(Session link, CompletableFuture<Boolean> pending, TransferProgress progress)
                throws InterruptedException, ExecutionException, TimeoutException {
            long idle = link.bluetooth ? 35_000 : 12_000;
            while (true) {
                try { return pending.get(2, TimeUnit.SECONDS); }
                catch (TimeoutException timeout) {
                    long activity = link.liveProgress ? progress.activity : link.lastWrite;
                    if (SystemClock.elapsedRealtime() - activity >= idle) throw timeout;
                }
            }
        }
    }

    private static final class Session {
        private final Closeable transport;
        private final DataOutputStream output;
        private final boolean bluetooth;
        private boolean transferLock;
        private boolean liveProgress;
        private boolean chunked;
        volatile boolean routeChanged;
        private boolean cancelledByUser;
        volatile boolean writing;
        volatile long lastRead = SystemClock.elapsedRealtime(), lastWrite = lastRead;
        long lastProbe;
        private final byte[] context;
        private final byte[] key;

        Session(Closeable transport, OutputStream output, byte[] context, byte[] key, boolean bluetooth) {
            this.transport = transport;
            this.output = new DataOutputStream(output);
            this.context = context;
            this.key = key;
            this.bluetooth = bluetooth;
        }

        DataInputStream input(InputStream stream) {
            if (!bluetooth) return new DataInputStream(stream);
            return new DataInputStream(new FilterInputStream(stream) {
                @Override public int read() throws IOException {
                    int result = in.read();
                    if (result >= 0) lastRead = SystemClock.elapsedRealtime();
                    return result;
                }

                @Override public int read(byte[] data, int offset, int count) throws IOException {
                    int result = in.read(data, offset, count);
                    if (result > 0) lastRead = SystemClock.elapsedRealtime();
                    return result;
                }
            });
        }

        boolean verifyMac(DataInputStream input) throws Exception {
            byte[] nonce = new byte[32];
            RANDOM.nextBytes(nonce);
            System.arraycopy(VERSION, 0, nonce, 0, VERSION.length);
            System.arraycopy(TRANSFER_FEATURE, 0, nonce, VERSION.length, TRANSFER_FEATURE.length);
            System.arraycopy(CHUNK_FEATURE, 0, nonce, VERSION.length + TRANSFER_FEATURE.length, CHUNK_FEATURE.length);
            System.arraycopy(nonce, 0, context, 0, nonce.length);
            output.write(nonce);
            input.readFully(context, 32, 32);
            byte[] answer = new byte[32];
            input.readFully(answer);
            if (!MessageDigest.isEqual(answer, proof("server", null))) throw new IOException("配对不匹配");
            boolean duplex = Arrays.equals(VERSION, Arrays.copyOfRange(context, 32, 32 + VERSION.length));
            int capability = 32 + VERSION.length;
            transferLock = duplex && Arrays.equals(TRANSFER_FEATURE,
                    Arrays.copyOfRange(context, capability, capability + TRANSFER_FEATURE.length));
            int live = capability + TRANSFER_FEATURE.length;
            byte[] feature = Arrays.copyOfRange(context, live, live + LIVE_FEATURE.length);
            chunked = transferLock && Arrays.equals(CHUNK_FEATURE, feature);
            liveProgress = chunked || (transferLock && Arrays.equals(LIVE_FEATURE, feature));
            return duplex;
        }

        void send(int kind, byte[] data) throws Exception {
            sendParts(new byte[]{(byte) kind}, data, 0, data.length, null);
        }

        synchronized void sendParts(byte[] header, byte[] data, int dataOffset, int dataLength,
                                    TransferProgress progress) throws Exception {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, "tablet-frame");
            // Android's GCM provider can buffer update() output until doFinal().
            // Finish encryption before writing a frame header to the live connection.
            byte[] prefix = cipher.update(header);
            byte[] sealed = cipher.doFinal(data, dataOffset, dataLength);
            int prefixSize = prefix == null ? 0 : prefix.length;
            writing = true;
            if (bluetooth) lastWrite = SystemClock.elapsedRealtime();
            try {
                output.writeInt(nonce.length + prefixSize + sealed.length);
                output.write(nonce);
                if (prefixSize != 0) output.write(prefix);
                if (bluetooth || progress != null) {
                    int chunk = bluetooth ? 8192 : 65536;
                    for (int offset = 0; offset < sealed.length;) {
                        int count = Math.min(chunk, sealed.length - offset);
                        output.write(sealed, offset, count);
                        offset += count;
                        if (bluetooth) lastWrite = SystemClock.elapsedRealtime();
                        if (progress != null) {
                            if (progress.cancelled) throw new TransferCancelled();
                            if (!liveProgress) progress.transferred = nonce.length + prefixSize + offset;
                        }
                    }
                } else {
                    output.write(sealed);
                }
            } finally {
                writing = false;
            }
        }

        byte[] decrypt(byte[] payload, String purpose) throws Exception {
            return cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(payload, 12), purpose)
                    .doFinal(payload, 12, payload.length - 12);
        }

        private Cipher cipher(int mode, byte[] nonce, String purpose) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(message(purpose));
            return cipher;
        }

        byte[] proof(String purpose, byte[] trailer) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(message(purpose));
            if (trailer != null) mac.update(trailer);
            return mac.doFinal();
        }

        private byte[] message(String purpose) {
            byte[] label = ("padpaste-question-1/" + purpose).getBytes(StandardCharsets.UTF_8);
            byte[] message = Arrays.copyOf(label, label.length + context.length);
            System.arraycopy(context, 0, message, label.length, context.length);
            return message;
        }
    }

    private static byte[] compactScreenshot(byte[] original) throws IOException {
        var bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(original, 0, original.length, bounds);
        var options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, Integer.highestOneBit(
                Math.max(bounds.outWidth, bounds.outHeight) / BLUETOOTH_IMAGE_EDGE));
        Bitmap decoded = BitmapFactory.decodeByteArray(original, 0, original.length, options);
        if (decoded == null) throw new IOException("无法缩小蓝牙截图");
        Bitmap image = decoded;
        try (var encoded = new ByteArrayOutputStream()) {
            float scale = (float) BLUETOOTH_IMAGE_EDGE / Math.max(decoded.getWidth(), decoded.getHeight());
            if (scale < 1) image = Bitmap.createScaledBitmap(decoded,
                    Math.max(1, Math.round(decoded.getWidth() * scale)),
                    Math.max(1, Math.round(decoded.getHeight() * scale)), true);
            if (!image.compress(Bitmap.CompressFormat.JPEG, 75, encoded)) throw new IOException("无法压缩蓝牙截图");
            return encoded.toByteArray();
        } finally {
            if (image != decoded) image.recycle();
            decoded.recycle();
        }
    }

    private static void close(Closeable transport) {
        if (transport != null) try { transport.close(); } catch (IOException ignored) {}
    }
}
