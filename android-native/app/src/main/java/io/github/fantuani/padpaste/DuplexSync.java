package io.github.fantuani.padpaste;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
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
        private final NsdManager nsd = context.getSystemService(NsdManager.class);
        private final Thread reader = new Thread(this::run, "padpaste-reader");
        private final AtomicReference<CompletableFuture<Boolean>> receipt = new AtomicReference<>();
        private volatile boolean stopped, connected, duplex;
        private volatile String discoveredAddress;
        private volatile Socket socket;
        private volatile Session session;

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
            reader.interrupt();
            if (nsd != null) {
                try { nsd.stopServiceDiscovery(discovery); }
                catch (RuntimeException ignored) {}
            }
        }

        private void run() {
            int backoff = 450;
            while (!stopped) {
                LinkedHashSet<String> targets = new LinkedHashSet<>();
                String discovered = discoveredAddress;
                if (discovered != null) targets.add(discovered);
                targets.add(preferences.getString("last_address", ""));
                targets.add(pairing.host);
                targets.addAll(pairing.addresses);
                targets.remove("");
                for (String address : targets) {
                    if (stopped) return;
                    try (Socket next = new Socket()) {
                        socket = next;
                        if (stopped) return;
                        next.connect(new InetSocketAddress(address, PORT), 1100);
                        next.setSoTimeout(3500);
                        next.setKeepAlive(true);
                        next.setTcpNoDelay(true);
                        exchange(next, address);
                    } catch (Exception ignored) {
                        if (connected) backoff = 450;
                    } finally {
                        socket = null;
                        session = null;
                        boolean changed = connected;
                        connected = duplex = false;
                        CompletableFuture<Boolean> pending = receipt.getAndSet(null);
                        if (pending != null) pending.complete(false);
                        if (changed) publish();
                    }
                    if (backoff == 450 && address.equals(preferences.getString("last_address", ""))) break;
                }
                try { Thread.sleep(backoff); } catch (InterruptedException done) { return; }
                backoff = Math.min(2400, backoff * 2);
            }
        }

        private void exchange(Socket socket, String address) throws Exception {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            byte[] nonce = new byte[32];
            RANDOM.nextBytes(nonce);
            System.arraycopy(VERSION, 0, nonce, 0, VERSION.length);
            output.write(nonce);
            byte[] context = Arrays.copyOf(nonce, 64);
            input.readFully(context, 32, 32);
            byte[] answer = new byte[32];
            input.readFully(answer);
            Session link = new Session(socket, output, context, pairing.key);
            if (!MessageDigest.isEqual(answer, link.proof("server", null))) throw new IOException("配对不匹配");
            output.write(link.proof("tablet", null));
            duplex = Arrays.equals(VERSION, Arrays.copyOfRange(context, 32, 32 + VERSION.length));
            preferences.edit().putString("last_address", address).apply();
            socket.setSoTimeout(duplex ? 24000 : 0);
            session = link;
            connected = true;
            publish();
            if (duplex) availability();
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
            catch (Exception failure) { close(link.socket); }
        }

        private void upload(String name, byte[] data) throws IOException {
            Session link = session;
            byte[] filename = name.getBytes(StandardCharsets.UTF_8);
            if (!connected || !duplex || link == null || filename.length > 255 || data.length > CAPTURE_MAX) {
                throw new IOException("Mac 尚未连接或截图过大");
            }
            var pending = new CompletableFuture<Boolean>();
            if (!receipt.compareAndSet(null, pending)) throw new IOException("上一张截图仍在传输");
            writer.execute(() -> {
                try {
                    if (session != link || receipt.get() != pending) return;
                    byte[] header = new byte[2 + filename.length];
                    header[0] = CAPTURE;
                    header[1] = (byte) filename.length;
                    System.arraycopy(filename, 0, header, 2, filename.length);
                    link.sendParts(header, data);
                } catch (Exception failure) {
                    close(link.socket);
                    pending.complete(false);
                }
            });
            try {
                if (!pending.get(9, TimeUnit.SECONDS)) throw new IOException("Mac 未接收截图");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                close(link.socket);
                throw new IOException("同步已中断", interrupted);
            } catch (TimeoutException | ExecutionException failure) {
                close(link.socket);
                throw new IOException("Mac 未确认截图", failure);
            } finally { receipt.compareAndSet(pending, null); }
        }
    }

    private static final class Session {
        private final Socket socket;
        private final DataOutputStream output;
        private final byte[] context;
        private final byte[] key;

        Session(Socket socket, DataOutputStream output, byte[] context, byte[] key) {
            this.socket = socket; this.output = output; this.context = context; this.key = key;
        }

        void send(int kind, byte[] data) throws Exception {
            sendParts(new byte[]{(byte) kind}, data);
        }

        synchronized void sendParts(byte[] header, byte[] data) throws Exception {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, "tablet-frame");
            output.writeInt(nonce.length + header.length + data.length + 16);
            output.write(nonce);
            byte[] buffer = new byte[Math.min(65536, data.length) + header.length + 32];
            output.write(buffer, 0, cipher.update(header, 0, header.length, buffer));
            for (int offset = 0; offset < data.length;) {
                int count = Math.min(65536, data.length - offset);
                output.write(buffer, 0, cipher.update(data, offset, count, buffer));
                offset += count;
            }
            output.write(buffer, 0, cipher.doFinal(buffer, 0));
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

    private static void close(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    }
}
