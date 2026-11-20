package io.github.fantuani.draftcanvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashSet;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** A foreground-only link to a paired Mac. Only confirmed imports are written to disk. */
final class QuestionConnection {
    static final int PORT = 5036;
    private static final String SERVICE = "_padquestion._tcp.";
    private static final SecureRandom RANDOM = new SecureRandom();

    record Candidate(byte[] data, Bitmap bitmap) {}

    record Pairing(byte[] key, String host, String[] addresses) {
        static Pairing parse(String code) throws Exception {
            JSONObject config = new JSONObject(new String(Base64.decode(code.trim(), Base64.DEFAULT),
                    StandardCharsets.UTF_8));
            byte[] key = Base64.decode(config.getString("key"), Base64.DEFAULT);
            if (key.length != 32) throw new IOException("Invalid pairing key");
            JSONArray array = config.getJSONArray("addresses");
            String[] addresses = new String[Math.min(12, array.length())];
            for (int i = 0; i < addresses.length; i++) addresses[i] = array.getString(i);
            return new Pairing(key, config.getString("host"), addresses);
        }

        String serviceName() throws Exception {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            StringBuilder name = new StringBuilder("PadPaste-");
            for (int i = 0; i < 6; i++) name.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
            return name.toString();
        }
    }

    interface Listener {
        void status(boolean connected);
        void candidate(Candidate image);
    }

    private final Pairing pairing;
    private final Listener listener;
    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String serviceName;
    private final Thread worker = new Thread(this::run, "question-connection");
    private volatile boolean stopped;
    private volatile String discoveredAddress;
    private volatile Socket socket;

    QuestionConnection(Context context, Pairing pairing, Listener listener) throws Exception {
        this.pairing = pairing;
        this.listener = listener;
        serviceName = pairing.serviceName();
        nsd = context.getSystemService(NsdManager.class);
    }

    private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
        @Override public void onDiscoveryStarted(String type) {}
        @Override public void onDiscoveryStopped(String type) {}
        @Override public void onStartDiscoveryFailed(String type, int error) {}
        @Override public void onStopDiscoveryFailed(String type, int error) {}
        @Override public void onServiceLost(NsdServiceInfo info) {}
        @Override public void onServiceFound(NsdServiceInfo info) {
            if (stopped || !info.getServiceName().startsWith(serviceName)) return;
            try {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo service, int error) {}
                    @Override public void onServiceResolved(NsdServiceInfo service) {
                        if (service.getPort() == PORT && service.getHost() != null) {
                            discoveredAddress = service.getHost().getHostAddress();
                        }
                    }
                });
            } catch (RuntimeException ignored) {
                // Direct addresses still work on networks that block discovery.
            }
        }
    };

    void start() {
        if (nsd != null) {
            try { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery); }
            catch (RuntimeException ignored) {}
        }
        worker.start();
    }

    void stop() {
        stopped = true;
        worker.interrupt();
        close(socket);
        if (nsd != null) {
            try { nsd.stopServiceDiscovery(discovery); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    private void run() {
        int backoff = 500;
        while (!stopped) {
            LinkedHashSet<String> targets = new LinkedHashSet<>();
            if (discoveredAddress != null) targets.add(discoveredAddress);
            targets.addAll(Arrays.asList(pairing.addresses));
            if (!pairing.host.isEmpty()) targets.add(pairing.host);
            for (String address : targets) {
                if (stopped) return;
                try (Socket next = new Socket()) {
                    socket = next;
                    if (stopped) return;
                    next.connect(new InetSocketAddress(address, PORT), 1600);
                    next.setSoTimeout(3500);
                    next.setKeepAlive(true);
                    exchange(next);
                } catch (Exception ignored) {
                    if (!stopped) main.post(() -> { if (!stopped) listener.status(false); });
                } finally {
                    socket = null;
                }
            }
            try { Thread.sleep(backoff); } catch (InterruptedException done) { return; }
            backoff = Math.min(2500, backoff * 2);
        }
    }

    private void exchange(Socket socket) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        OutputStream output = socket.getOutputStream();
        byte[] clientNonce = new byte[32];
        RANDOM.nextBytes(clientNonce);
        output.write(clientNonce);
        byte[] context = Arrays.copyOf(clientNonce, 64);
        input.readFully(context, 32, 32);
        byte[] response = new byte[32];
        input.readFully(response);
        if (!MessageDigest.isEqual(response, proof("server", context, null))) throw new IOException("Wrong Mac");
        output.write(proof("tablet", context, null));
        socket.setSoTimeout(0);
        main.post(() -> { if (!stopped) listener.status(true); });

        while (!stopped) {
            int length = input.readInt();
            if (length <= 28 || length > QuestionAssets.MAX_BYTES + 28) throw new IOException("Invalid frame length");
            byte[] frame = new byte[length];
            input.readFully(frame);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(pairing.key, "AES"),
                    new GCMParameterSpec(128, frame, 0, 12));
            cipher.updateAAD(message("image", context));
            byte[] data = cipher.doFinal(frame, 12, frame.length - 12);
            Candidate candidate = new Candidate(data, QuestionAssets.decode(data));
            main.post(() -> { if (!stopped) listener.candidate(candidate); });
            output.write(proof("accepted", context, Arrays.copyOfRange(frame, frame.length - 16, frame.length)));
        }
    }

    private byte[] proof(String purpose, byte[] context, byte[] trailer) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(pairing.key, "HmacSHA256"));
        mac.update(message(purpose, context));
        if (trailer != null) mac.update(trailer);
        return mac.doFinal();
    }

    private static byte[] message(String purpose, byte[] context) {
        byte[] label = ("padpaste-question-1/" + purpose).getBytes(StandardCharsets.UTF_8);
        byte[] message = Arrays.copyOf(label, label.length + context.length);
        System.arraycopy(context, 0, message, label.length, context.length);
        return message;
    }

    private static void close(Socket socket) {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}
