package org.androidauto.headunit;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android-native first stage of the Android Auto wireless transport.
 *
 * This advertises the Android Auto RFCOMM service, sends the Wi-Fi handoff
 * information, and waits for the phone's TCP connection. The encrypted
 * Android Auto session and projection channels are handled by a later layer.
 */
final class AndroidAutoWirelessBridge implements AutoCloseable {
    private static final String TAG = "OLAAutoBridge";
    private static final UUID ANDROID_AUTO_UUID =
            UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66");
    private static final UUID HFP_HF_UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb");
    private static final int AA_TCP_PORT = 5288;
    private static final int MSG_WIFI_START_REQUEST = 1;
    private static final int MSG_WIFI_INFO_REQUEST = 2;
    private static final int MSG_WIFI_INFO_RESPONSE = 3;
    private static final int MSG_WIFI_START_RESPONSE = 7;
    private static final int MSG_WIFI_VERSION_REQUEST = 4;
    private static final int MSG_WIFI_VERSION_RESPONSE = 5;
    private static final int MSG_WIFI_CONNECT_STATUS = 6;
    private static final int MSG_WIFI_PING_REQUEST = 8;
    private static final int MSG_WIFI_PING_RESPONSE = 9;

    interface Listener {
        void onStatus(String message);
        void onTcpConnected(Socket socket);
    }

    private final BluetoothAdapter bluetooth;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private BluetoothServerSocket bluetoothServer;
    private BluetoothServerSocket hfpServer;
    private Thread hfpThread;
    private ServerSocket tcpServer;
    private Socket projectionSocket;

    AndroidAutoWirelessBridge(BluetoothAdapter bluetooth, Listener listener) {
        this.bluetooth = bluetooth;
        this.listener = listener;
    }

    void start(WifiManager.LocalOnlyHotspotReservation reservation) {
        if (bluetooth == null || !bluetooth.isEnabled()) {
            status("Enable Bluetooth before starting Android Auto.");
            return;
        }
        WifiConfiguration config = reservation.getWifiConfiguration();
        if (config == null || config.SSID == null || config.preSharedKey == null) {
            status("Android did not expose the hotspot SSID/password; wireless handoff cannot start.");
            return;
        }
        final String ssid = unquote(config.SSID);
        final String password = unquote(config.preSharedKey);
        worker.execute(() -> runBridge(ssid, password));
    }

    private void runBridge(String ssid, String password) {
        try {
            String hostIp = findHotspotIpv4();
            String mac = findHotspotMac();
            if (hostIp == null || mac == null) {
                status("Hotspot is running, but its access-point IP/MAC could not be identified. No handoff was advertised.");
                return;
            }

            tcpServer = new ServerSocket(AA_TCP_PORT, 1, InetAddress.getByName("0.0.0.0"));
            tcpServer.setSoTimeout(120000);
            hfpServer = bluetooth.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_HF_UUID);
            startHfpAcceptLoop();
            bluetoothServer = bluetooth.listenUsingRfcommWithServiceRecord("Android Auto", ANDROID_AUTO_UUID);
            Log.i(TAG, "SDP RFCOMM listeners ready: HFP-AG=" + HFP_AG_UUID + ", Android Auto=" + ANDROID_AUTO_UUID);
            status("Hands-Free Unit and Android Auto Bluetooth services advertised. Waiting for phone…");

            while (!closed) {
                try (BluetoothSocket bt = bluetoothServer.accept()) {
                    Log.i(TAG, "Android Auto RFCOMM connected from " + bt.getRemoteDevice().getAddress());
                    status("Phone opened Android Auto Bluetooth channel. Sending Wi-Fi handoff…");
                    performHandshake(bt, hostIp, ssid, password, mac);
                    status("Bluetooth handoff accepted. Waiting for the phone to join Wi-Fi and connect to TCP " + AA_TCP_PORT + "…");
                    Socket tcp = tcpServer.accept();
                    tcp.setTcpNoDelay(true);
                    projectionSocket = tcp;
                    status("Phone reached the Android Auto TCP transport. Encrypted projection session is the next layer.");
                    listener.onTcpConnected(tcp);
                    // Keep the accepted socket alive for the projection-session layer.
                    while (!closed && !tcp.isClosed() && !tcp.isInputShutdown()) {
                        Thread.sleep(500);
                    }
                    try { tcp.close(); } catch (IOException ignored) {}
                    projectionSocket = null;
                } catch (IOException e) {
                    if (!closed) {
                        Log.w(TAG, "Wireless client attempt ended", e);
                        status("Wireless connection attempt ended: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            if (!closed) {
                Log.e(TAG, "Could not start wireless bridge", e);
                status("Could not start Android Auto wireless transport: " + e.getMessage());
            }
        } finally {
            closeSockets();
        }
    }

    private void performHandshake(BluetoothSocket socket, String ip, String ssid,
                                  String password, String mac) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());

        // Modern WPP 4.2 opening: Type 4 -> Type 5 -> Type 1 -> Type 2 -> Type 3.
        byte[] carInfo = new ProtoWriter()
                .string(1, "Generic")
                .string(2, "OLA Play")
                .string(3, "2026")
                .string(5, "OLA Play")
                .string(6, "Wireless Head Unit")
                .string(7, "1")
                .string(8, "0.2")
                .toByteArray();
        byte[] endpoint = new ProtoWriter().string(1, "0.0.0.0").varint(2, 5288).toByteArray();
        byte[] version = new ProtoWriter()
                .varint(1, 4).varint(2, 2)
                .varint(3, 2) // dual-band
                .message(5, carInfo)
                .message(6, endpoint)
                .toByteArray();
        writePacket(output, MSG_WIFI_VERSION_REQUEST, version);
        status("WPP 4.2 version request sent. Waiting for phone response…");

        boolean startSent = false;
        long deadline = System.currentTimeMillis() + 15000;
        while (!closed) {
            int length = input.readUnsignedShort();
            int messageId = input.readUnsignedShort();
            if (length > 8192) throw new IOException("Oversized Bluetooth WPP packet");
            byte[] payload = new byte[length];
            input.readFully(payload);
            Log.i(TAG, "WPP RX: type=" + messageId + ", bytes=" + length);

            if (messageId == MSG_WIFI_VERSION_RESPONSE && !startSent) {
                long phoneStatus = ProtoReader.readVarintField(payload, 4);
                Log.i(TAG, "WPP version response status=" + phoneStatus);
                byte[] start = new ProtoWriter()
                        .string(1, "0.0.0.0")
                        .varint(2, AA_TCP_PORT)
                        .varint(3, 0)
                        .toByteArray();
                writePacket(output, MSG_WIFI_START_REQUEST, start);
                startSent = true;
                status("Phone accepted WPP negotiation. Waiting for Wi-Fi credential request…");
            } else if (messageId == MSG_WIFI_INFO_REQUEST) {
                byte[] info = new ProtoWriter()
                        .string(1, ssid)
                        .string(2, password)
                        .string(3, mac)
                        .varint(4, 8)
                        .varint(5, 1)
                        .toByteArray();
                writePacket(output, MSG_WIFI_INFO_RESPONSE, info);
                status("Wi-Fi credentials sent. Waiting for phone to join and open TCP…");
            } else if (messageId == MSG_WIFI_START_RESPONSE) {
                long statusCode = ProtoReader.readVarintField(payload, 3);
                Log.i(TAG, "WPP start response status=" + statusCode);
                if (statusCode != 0) throw new IOException("Phone rejected WPP start (status " + statusCode + ")");
            } else if (messageId == MSG_WIFI_CONNECT_STATUS) {
                long statusCode = ProtoReader.readVarintField(payload, 1);
                Log.i(TAG, "WPP connect status=" + statusCode);
                if (statusCode != 0) throw new IOException("Phone could not join Wi-Fi (status " + statusCode + ")");
                return;
            } else if (messageId == MSG_WIFI_PING_REQUEST) {
                writePacket(output, MSG_WIFI_PING_RESPONSE, payload);
            }
            if (!startSent && System.currentTimeMillis() > deadline) {
                throw new IOException("Timed out waiting for WPP version response");
            }
        }
        throw new IOException("Wireless handoff stopped");
    }

    /** Experimental HFP Audio-Gateway RFCOMM endpoint. The public Android API cannot register the privileged native HFP-AG profile; this publishes its UUID and implements a minimal SLC exchange only. */
    private void startHfpAcceptLoop() {
        hfpThread = new Thread(() -> { while (!closed) { try {
            BluetoothSocket client = hfpServer.accept();
            Log.i(TAG, "HFP-HF RFCOMM connected: " + client.getRemoteDevice().getAddress());
            status("Phone connected to HFP hands-free service. Negotiating Bluetooth link…");
            handleHfpClient(client);
        } catch (IOException e) { if (!closed) { Log.w(TAG, "HFP-HF accept failed", e); status("HFP link ended: " + e.getMessage()); } } } }, "OLA-HFP-HF");
        hfpThread.setDaemon(true); hfpThread.start();
    }

    /** Implements the Hands-Free Unit side of HFP SLC. The phone is the Audio Gateway. */
    private void handleHfpClient(BluetoothSocket socket) {
        try (BluetoothSocket client = socket) {
            java.io.InputStream in = client.getInputStream();
            java.io.OutputStream out = client.getOutputStream();
            StringBuilder pending = new StringBuilder();
            boolean brsf = false, cindTest = false, cindRead = false, cmer = false;
            // The HF initiates SLC; waiting silently after accept leaves Android Auto asleep.
            writeAt(out, "AT+BRSF=0\\r");
            byte[] buf = new byte[512];
            while (!closed) {
                int n = in.read(buf);
                if (n < 0) break;
                pending.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
                int cut;
                while ((cut = pending.indexOf("\\r")) >= 0 || (cut = pending.indexOf("\\n")) >= 0) {
                    String line = pending.substring(0, cut).trim();
                    pending.delete(0, cut + 1);
                    if (line.isEmpty()) continue;
                    Log.i(TAG, "HFP RX <- " + line);
                    if (line.startsWith("+BRSF:")) {
                        brsf = true;
                    } else if ("OK".equalsIgnoreCase(line) || "ERROR".equalsIgnoreCase(line)) {
                        if (!brsf) { brsf = true; writeAt(out, "AT+CIND=?\\r"); }
                        else if (!cindTest) { cindTest = true; writeAt(out, "AT+CIND?\\r"); }
                        else if (!cindRead) { cindRead = true; writeAt(out, "AT+CMER=3,0,0,1\\r"); }
                        else if (!cmer) { cmer = true; status("Bluetooth hands-free service-level connection established. Waiting for Android Auto…"); }
                    } else if (line.startsWith("AT+BRSF=")) {
                        writeAt(out, "\\r\\n+BRSF: 0\\r\\nOK\\r\\n");
                    } else if ("AT+CIND=?".equals(line)) {
                        writeAt(out, "\\r\\n+CIND: (\\"service\\",(0,1)),(\\"call\\",(0,1)),(\\"callsetup\\",(0-3)),(\\"callheld\\",(0-2)),(\\"signal\\",(0-5)),(\\"roam\\",(0,1)),(\\"battchg\\",(0-5))\\r\\nOK\\r\\n");
                    } else if ("AT+CIND?".equals(line)) {
                        writeAt(out, "\\r\\n+CIND: 1,0,0,0,5,0,5\\r\\nOK\\r\\n");
                    } else if (line.startsWith("AT+CMER=") || line.startsWith("AT+CHLD=") || line.startsWith("AT+BAC=") || line.startsWith("AT+CLIP=") || line.startsWith("AT+CCWA=") || line.startsWith("AT+VGS=") || line.startsWith("AT+VGM=") || line.startsWith("AT+NREC=") || "AT".equals(line)) {
                        writeAt(out, "\\r\\nOK\\r\\n");
                    } else {
                        Log.w(TAG, "Unrecognised HFP line: " + line);
                    }
                }
            }
        } catch (IOException e) { Log.i(TAG, "HFP client disconnected", e); }
    }

    private static void writeAt(java.io.OutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void writePacket(DataOutputStream output, int messageId, byte[] payload)
            throws IOException {
        if (payload.length > 65535) throw new IOException("Bluetooth packet too large");
        output.writeShort(payload.length);
        output.writeShort(messageId);
        output.write(payload);
        output.flush();
    }

    private static String findHotspotIpv4() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = network.getName().toLowerCase();
                if (!(name.contains("wlan") || name.contains("ap") || name.contains("wifi"))) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String value = address.getHostAddress();
                        if (value.startsWith("192.168.") || value.startsWith("10.")) return value;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not inspect hotspot interface address", e);
        }
        return null;
    }

    private static String findHotspotMac() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = network.getName().toLowerCase();
                if (!(name.contains("wlan") || name.contains("ap") || name.contains("wifi"))) continue;
                byte[] address = network.getHardwareAddress();
                if (address == null || address.length != 6) continue;
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < address.length; i++) {
                    if (i > 0) result.append(':');
                    result.append(String.format(java.util.Locale.US, "%02X", address[i] & 0xff));
                }
                return result.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not inspect hotspot MAC address", e);
        }
        return null;
    }

    private static String unquote(String value) {
        if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private void status(String message) {
        if (listener != null) listener.onStatus(message);
    }

    @Override public void close() {
        closed = true;
        closeSockets();
        worker.shutdownNow();
    }

    private void closeSockets() {
        try { if (bluetoothServer != null) bluetoothServer.close(); } catch (IOException ignored) {}
        try { if (hfpServer != null) hfpServer.close(); } catch (IOException ignored) {}
        if (hfpThread != null) hfpThread.interrupt();
        try { if (tcpServer != null) tcpServer.close(); } catch (IOException ignored) {}
        try { if (projectionSocket != null) projectionSocket.close(); } catch (IOException ignored) {}
    }

    /** Minimal protobuf wire encoder for the scalar/string fields in the BT handoff. */
    private static final class ProtoWriter {
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

        ProtoWriter string(int field, String value) {
            byte[] data = value.getBytes(StandardCharsets.UTF_8);
            tag(field, 2);
            rawVarint(data.length);
            bytes.write(data, 0, data.length);
            return this;
        }

        ProtoWriter message(int field, byte[] value) {\n            tag(field, 2);\n            rawVarint(value.length);\n            bytes.write(value, 0, value.length);\n            return this;\n        }\n\n        ProtoWriter varint(int field, long value) {
            tag(field, 0);
            rawVarint(value);
            return this;
        }

        private void tag(int field, int wireType) {
            rawVarint(((long) field << 3) | wireType);
        }

        private void rawVarint(long value) {
            while ((value & ~0x7fL) != 0) {
                bytes.write((int) ((value & 0x7f) | 0x80));
                value >>>= 7;
            }
            bytes.write((int) value);
        }

        byte[] toByteArray() { return bytes.toByteArray(); }
    }

    /** Skips protobuf fields and reads a varint field without depending on generated code. */
    private static final class ProtoReader {
        static long readVarintField(byte[] data, int wantedField) throws IOException {
            int[] position = {0};
            while (position[0] < data.length) {
                long tag = readVarint(data, position);
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                if (wire == 0) {
                    long value = readVarint(data, position);
                    if (field == wantedField) return value;
                } else if (wire == 2) {
                    long size = readVarint(data, position);
                    if (size < 0 || size > data.length - position[0]) throw new IOException("Invalid protobuf length");
                    position[0] += (int) size;
                } else if (wire == 1) {
                    position[0] += 8;
                } else if (wire == 5) {
                    position[0] += 4;
                } else {
                    throw new IOException("Unsupported protobuf wire type " + wire);
                }
                if (position[0] > data.length) throw new IOException("Truncated protobuf field");
            }
            return -1;
        }

        private static long readVarint(byte[] data, int[] position) throws IOException {
            long value = 0;
            int shift = 0;
            while (position[0] < data.length && shift < 64) {
                int b = data[position[0]++] & 0xff;
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return value;
                shift += 7;
            }
            throw new IOException("Malformed protobuf varint");
        }
    }
}
