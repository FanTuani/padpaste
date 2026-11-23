package io.github.fantuani.padpaste;

import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** One authenticated link, kept open while the canvas can receive or screenshot monitoring is on. */
public final class DuplexSync {
    private static final int PORT = 5036, QUESTION_MAX = 6 * 1024 * 1024, CAPTURE_MAX = 50 * 1024 * 1024;
    private static final int QUESTION = 1, CAPTURE = 2, QUESTION_ACK = 3, CAPTURE_ACK = 4, HEARTBEAT = 5, AVAILABILITY = 6;
    private static final int NETWORK_READY = 7, NETWORK_REPLY = 8;
    private static final long BLUETOOTH_IDLE_MS = 24_000;
    private static final byte[] VERSION = "PADSYNC2".getBytes(StandardCharsets.US_ASCII);
    private static final String SERVICE = "_padquestion._tcp.";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static DuplexSync instance;

    public interface Listener {
        void status(boolean connected);
        // The image is validated on the reader thread before acknowledging it.
        boolean image(byte[] data);
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
    private volatile Client client;
    private boolean monitoring;

    private DuplexSync(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("question_link", Context.MODE_PRIVATE);
    }

    public boolean paired() { return preferences.contains("pairing"); }
    public boolean connected() { Client current = client; return current != null && current.connected; }
    public boolean duplex() { Client current = client; return current != null && current.connected && current.duplex; }

    public boolean bluetoothEnabled() { return preferences.getBoolean("bluetooth_fallback", false); }
    public boolean bluetoothAllowed() { return BluetoothSyncLink.allowed(context); }
    public boolean bluetoothRadioOn() { return BluetoothSyncLink.radioOn(context); }

    public boolean connectedViaBluetooth() {
        Client current = client;
        Session link = current == null ? null : current.session;
        return link != null && current.connected && link.bluetooth;
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
        if (socket == null || !current.connected) return "";
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
        if (current == null || !duplex()) throw new IOException("Mac 尚未连接");
        current.upload(name, data);
    }

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
        private volatile BluetoothSyncLink bluetoothLink;
        private volatile String preferredNetwork;
        // Access to the handoff flag and reservations for an outbound screenshot is serialized.
        private final Object routing = new Object();
        private boolean switchingToNetwork;
        private long switchRequestedAt;

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
            reader.start();
        }

        void stop() {
            stopped = true;
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
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))) return true;
            }
            return false;
        }

        private void run() {
            int backoff = 450;
            while (!stopped) {
                boolean fallback = bluetoothEnabled() && bluetoothRadioOn();
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
                            socket = null;
                            disconnected();
                        }
                        if (wasConnected) {
                            backoff = 450;
                            break;
                        }
                    }
                }
                if (stopped) return;
                if (fallback && bluetoothEnabled()) {
                    connectBluetooth();
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

        private void connectBluetooth() {
            if (stopped) return;
            try (BluetoothSyncLink next = new BluetoothSyncLink(context,
                    MessageDigest.getInstance("SHA-256").digest(pairing.key))) {
                bluetoothLink = next;
                if (stopped || !bluetoothEnabled()) return;
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
                disconnected();
            }
        }

        private void disconnected() {
            boolean changed = connected;
            connected = duplex = false;
            session = null;
            synchronized (routing) {
                switchingToNetwork = false;
                CompletableFuture<Boolean> pending = receipt.getAndSet(null);
                if (pending != null) pending.complete(false);
            }
            if (changed) publish();
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
            if (stopped) return;
            session = link;
            connected = true;
            publish();
            if (duplex) availability();
            ScheduledFuture<?> check = bluetooth
                    ? maintenance.scheduleWithFixedDelay(() -> maintainBluetooth(link), 3, 3, TimeUnit.SECONDS)
                    : null;
            try {
                while (!stopped) {
                    int length = input.readInt();
                    if (length <= 28 || length > QUESTION_MAX + 29) throw new IOException("无效同步数据");
                    byte[] frame = new byte[length];
                    input.readFully(frame);
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
            synchronized (routing) {
                if (switchingToNetwork) {
                    if (now - switchRequestedAt >= BLUETOOTH_IDLE_MS) close(link.transport);
                    return;
                }
            }
            if (!link.writing && now - link.lastWrite >= 7000 && receipt.get() == null) {
                writer.execute(() -> sendBluetoothControl(link, HEARTBEAT));
            }
            if (receipt.get() != null || !hasLocalNetwork() || now - link.lastProbe < 12_000) return;
            link.lastProbe = now;
            String address = probeNetwork();
            if (address == null) return;
            synchronized (routing) {
                if (stopped || session != link || receipt.get() != null) return;
                link.networkCandidate = address;
                switchingToNetwork = true;
                switchRequestedAt = SystemClock.elapsedRealtime();
                writer.execute(() -> sendBluetoothControl(link, NETWORK_READY));
            }
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
                case QUESTION -> link.send(QUESTION_ACK, new byte[]{(byte) (deliver(Arrays.copyOfRange(frame, 1, frame.length)) ? 1 : 0)});
                case CAPTURE_ACK -> {
                    if (frame.length != 2) throw new IOException("无效同步回执");
                    CompletableFuture<Boolean> pending = receipt.getAndSet(null);
                    if (pending != null) pending.complete(frame[1] == 1);
                }
                case HEARTBEAT -> { if (frame.length != 1) throw new IOException("无效同步心跳"); }
                case NETWORK_REPLY -> {
                    synchronized (routing) {
                        if (!link.bluetooth || !switchingToNetwork || frame.length != 2) {
                            throw new IOException("无效的 Wi-Fi 切换回执");
                        }
                        if (frame[1] == 1) {
                            preferredNetwork = link.networkCandidate;
                            close(link.transport);
                        } else {
                            switchingToNetwork = false;
                        }
                    }
                }
                default -> throw new IOException("无效同步类型");
            }
        }

        private boolean deliver(byte[] data) {
            Listener current = listener;
            return !stopped && current != null && current.image(data);
        }

        private void availability() {
            Session link = session;
            if (link == null || !duplex) return;
            try { link.send(AVAILABILITY, new byte[]{(byte) (listener == null ? 0 : 1)}); }
            catch (Exception failure) { close(link.transport); }
        }

        private void upload(String name, byte[] data) throws IOException {
            Session link = session;
            byte[] filename = name.getBytes(StandardCharsets.UTF_8);
            if (!connected || !duplex || link == null || filename.length > 255 || data.length > CAPTURE_MAX) {
                throw new IOException("Mac 尚未连接或截图过大");
            }
            if (link.bluetooth && data.length > QUESTION_MAX) throw new IOException("蓝牙截图超过 6 MB");
            var pending = new CompletableFuture<Boolean>();
            synchronized (routing) {
                if (session != link || switchingToNetwork) throw new IOException("正在切换同步连接");
                if (!receipt.compareAndSet(null, pending)) throw new IOException("上一张截图仍在传输");
            }
            if (link.bluetooth) link.lastWrite = SystemClock.elapsedRealtime();
            writer.execute(() -> {
                try {
                    if (session != link || receipt.get() != pending) return;
                    byte[] header = new byte[2 + filename.length];
                    header[0] = CAPTURE;
                    header[1] = (byte) filename.length;
                    System.arraycopy(filename, 0, header, 2, filename.length);
                    link.sendParts(header, data);
                } catch (Exception failure) {
                    android.util.Log.w("PadPasteSync", "Screenshot send failed: " + failure.getClass().getSimpleName());
                    close(link.transport);
                    pending.complete(false);
                }
            });
            try {
                if (!(link.bluetooth ? waitForBluetoothReceipt(link, pending) : pending.get(9, TimeUnit.SECONDS))) {
                    throw new IOException("Mac 未接收截图");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                close(link.transport);
                throw new IOException("同步已中断", interrupted);
            } catch (TimeoutException | ExecutionException failure) {
                close(link.transport);
                throw new IOException("Mac 未确认截图", failure);
            } finally { receipt.compareAndSet(pending, null); }
        }
        private boolean waitForBluetoothReceipt(Session link, CompletableFuture<Boolean> pending)
                throws InterruptedException, ExecutionException, TimeoutException {
            while (true) {
                try { return pending.get(4, TimeUnit.SECONDS); }
                catch (TimeoutException timeout) {
                    if (SystemClock.elapsedRealtime() - link.lastWrite >= BLUETOOTH_IDLE_MS) throw timeout;
                }
            }
        }
    }

    private static final class Session {
        private final Closeable transport;
        private final DataOutputStream output;
        private final boolean bluetooth;
        volatile boolean writing;
        volatile long lastRead = SystemClock.elapsedRealtime(), lastWrite = lastRead;
        long lastProbe;
        String networkCandidate;
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
            System.arraycopy(nonce, 0, context, 0, nonce.length);
            output.write(nonce);
            input.readFully(context, 32, 32);
            byte[] answer = new byte[32];
            input.readFully(answer);
            if (!MessageDigest.isEqual(answer, proof("server", null))) throw new IOException("配对不匹配");
            return Arrays.equals(VERSION, Arrays.copyOfRange(context, 32, 32 + VERSION.length));
        }

        void send(int kind, byte[] data) throws Exception {
            sendParts(new byte[]{(byte) kind}, data);
        }

        synchronized void sendParts(byte[] header, byte[] data) throws Exception {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, "tablet-frame");
            // Android's GCM provider can buffer update() output until doFinal().
            // Finish encryption before writing a frame header to the live connection.
            byte[] prefix = cipher.update(header);
            byte[] sealed = cipher.doFinal(data);
            int prefixSize = prefix == null ? 0 : prefix.length;
            writing = true;
            if (bluetooth) lastWrite = SystemClock.elapsedRealtime();
            try {
                output.writeInt(nonce.length + prefixSize + sealed.length);
                output.write(nonce);
                if (prefixSize != 0) output.write(prefix);
                if (bluetooth) {
                    for (int offset = 0; offset < sealed.length; offset += 8192) {
                        output.write(sealed, offset, Math.min(8192, sealed.length - offset));
                        lastWrite = SystemClock.elapsedRealtime();
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

    private static void close(Closeable transport) {
        if (transport != null) try { transport.close(); } catch (IOException ignored) {}
    }
}
