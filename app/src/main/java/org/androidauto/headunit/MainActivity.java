package org.androidauto.headunit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 40;
    private static final int REQUEST_ENABLE_BT = 41;

    private TextView status;
    private TextView devices;
    private TextView connectionPill;
    private BluetoothAdapter bluetooth;
    private WifiManager wifi;
    private WifiManager.LocalOnlyHotspotReservation hotspot;
    private AndroidAutoWirelessBridge wirelessBridge;
    private boolean receiverRegistered;
    private String hotspotSsid;
    private String hotspotPassword;
    private final ArrayList<BluetoothDevice> discoveredDevices = new ArrayList<>();

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) appendDevice(device);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                setStatus("Scan complete. Select your phone from the device list and pair.");
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                if (device != null) {
                    String name = device.getName() == null ? device.getAddress() : device.getName();
                    if (state == BluetoothDevice.BOND_BONDED) {
                        setStatus("Paired with " + name + ". Android Auto session setup is the next development step.");
                        if (connectionPill != null) connectionPill.setText("PHONE PAIRED");
                    } else if (state == BluetoothDevice.BOND_BONDING) {
                        setStatus("Pairing with " + name + "… approve the request on both devices.");
                    } else if (state == BluetoothDevice.BOND_NONE) {
                        setStatus("Pairing not completed or bond removed for " + name + ".");
                        if (connectionPill != null) connectionPill.setText("NOT CONNECTED");
                    }
                }
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setNavigationBarColor(Color.rgb(8, 13, 28));
            getWindow().setStatusBarColor(Color.rgb(8, 13, 28));
        }
        bluetooth = BluetoothAdapter.getDefaultAdapter();
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        buildUi();
        registerDiscoveryReceiver();
        requestRuntimePermissions();
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(26), dp(20), dp(26), dp(18));
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(12, 20, 43), Color.rgb(18, 30, 55), Color.rgb(7, 13, 29)}));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(page);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView logo = new TextView(this);
        logo.setText("◈");
        logo.setTextColor(Color.rgb(130, 218, 255));
        logo.setTextSize(27);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(glassDrawable(0x302e9dff, 0x8059caff, 22));
        header.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        TextView heading = text("OLA PLAY", 21, Color.WHITE, true);
        TextView subheading = text("WIRELESS CONNECTIVITY", 10, 0xff9eafcf, true);
        subheading.setLetterSpacing(0.16f);
        titles.addView(heading);
        titles.addView(subheading);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout rightHeader = new LinearLayout(this);
        rightHeader.setOrientation(LinearLayout.VERTICAL);
        rightHeader.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        TextView creator = text("By Midhun Sankar", 11, 0xffd8eaff, true);
        creator.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rightHeader.addView(creator);
        TextView version = text("ANDROID 10  •  ARM64", 9, 0xffc4d8ee, true);
        version.setGravity(Gravity.CENTER);
        version.setPadding(dp(10), dp(7), dp(10), dp(7));
        version.setBackground(glassDrawable(0x222c9bd2, 0x5548a9d5, 30));
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(-2, -2);
        versionParams.topMargin = dp(5);
        rightHeader.addView(version, versionParams);
        header.addView(rightHeader);
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(60)));
        addSpace(page, 18);

        LinearLayout hero = glassCard();
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(20), dp(17), dp(20), dp(18));

        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView eyebrow = text("CONNECTION STATUS", 11, 0xff9fb4d4, true);
        eyebrow.setLetterSpacing(0.14f);
        heroTop.addView(eyebrow, new LinearLayout.LayoutParams(0, -2, 1f));
        connectionPill = text("READY TO PAIR", 10, 0xff8ee6c1, true);
        connectionPill.setPadding(dp(11), dp(7), dp(11), dp(7));
        connectionPill.setBackground(glassDrawable(0x2636d9a0, 0x6659dcb0, 30));
        heroTop.addView(connectionPill);
        hero.addView(heroTop);

        status = text("Preparing Bluetooth and Wi-Fi services…", 15, 0xffe4edff, false);
        status.setPadding(0, dp(13), 0, dp(2));
        status.setLineSpacing(dp(3), 1f);
        hero.addView(status);
        page.addView(hero, new LinearLayout.LayoutParams(-1, -2));
        addSpace(page, 18);

        TextView actionsTitle = text("QUICK CONTROLS", 11, 0xff9fb4d4, true);
        actionsTitle.setLetterSpacing(0.16f);
        page.addView(actionsTitle);
        addSpace(page, 9);

        LinearLayout rowOne = new LinearLayout(this);
        rowOne.setOrientation(LinearLayout.HORIZONTAL);
        Button enableBt = actionButton("◉   Enable Bluetooth", "Turn on Bluetooth");
        enableBt.setOnClickListener(v -> enableBluetooth());
        Button scanBt = actionButton("⌕   Scan devices", "Find nearby phones");
        scanBt.setOnClickListener(v -> scanBluetooth());
        rowOne.addView(enableBt, weightedButton());
        addHorizontalSpace(rowOne, 10);
        rowOne.addView(scanBt, weightedButton());
        page.addView(rowOne, new LinearLayout.LayoutParams(-1, dp(62)));

        LinearLayout rowTwo = new LinearLayout(this);
        rowTwo.setOrientation(LinearLayout.HORIZONTAL);
        Button pairBt = actionButton("⇄   Pair phone", "Choose a discovered phone");
        pairBt.setOnClickListener(v -> chooseDeviceToPair());
        Button hotspotBt = actionButton("⌁   Start Android Auto", "Start hotspot and wireless handoff");
        hotspotBt.setOnClickListener(v -> startHotspot());
        rowTwo.addView(pairBt, weightedButton());
        addHorizontalSpace(rowTwo, 10);
        rowTwo.addView(hotspotBt, weightedButton());
        page.addView(rowTwo, new LinearLayout.LayoutParams(-1, dp(62)));
        addSpace(page, 9);
        Button wifiQrButton = actionButton("▦   Show Wi-Fi QR / Password", "Display hotspot credentials as a QR code");
        wifiQrButton.setOnClickListener(v -> showHotspotQr());
        page.addView(wifiQrButton, new LinearLayout.LayoutParams(-1, dp(58)));
        addSpace(page, 18);

        LinearLayout deviceCard = glassCard();
        deviceCard.setOrientation(LinearLayout.VERTICAL);
        deviceCard.setPadding(dp(18), dp(15), dp(18), dp(15));
        LinearLayout deviceHeader = new LinearLayout(this);
        deviceHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView deviceTitle = text("NEARBY DEVICES", 11, 0xffb8cbe6, true);
        deviceTitle.setLetterSpacing(0.14f);
        deviceHeader.addView(deviceTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView scanHint = text("BLUETOOTH", 9, 0xff80cfff, true);
        deviceHeader.addView(scanHint);
        deviceCard.addView(deviceHeader);
        devices = text("No devices found yet.\nTap “Scan devices” to search nearby.", 13, 0xffdce8fb, false);
        devices.setPadding(0, dp(14), 0, dp(4));
        devices.setLineSpacing(dp(5), 1f);
        deviceCard.addView(devices);
        page.addView(deviceCard, new LinearLayout.LayoutParams(-1, -2));
        addSpace(page, 13);

        TextView note = text("WIRELESS HANDOFF  •  Bluetooth-to-Wi-Fi setup is active. Encrypted projection and media channels are still being integrated.", 10, 0xff8192b0, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(8), dp(8), dp(8), dp(4));
        page.addView(note, new LinearLayout.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private LinearLayout glassCard() {
        LinearLayout card = new LinearLayout(this);
        card.setBackground(glassDrawable(0x24ffffff, 0x55ffffff, 22));
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(7));
            card.setClipToOutline(true);
        }
        return card;
    }

    private GradientDrawable glassDrawable(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private Button actionButton(String label, String description) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setTextColor(0xffeaf5ff);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackground(glassDrawable(0x332e5f91, 0x665da9dc, 17));
        if (Build.VERSION.SDK_INT >= 21) b.setElevation(dp(3));
        b.setContentDescription(description);
        return b;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private LinearLayout.LayoutParams weightedButton() {
        return new LinearLayout.LayoutParams(0, -1, 1f);
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        View space = new View(this);
        parent.addView(space, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private void addHorizontalSpace(LinearLayout parent, int widthDp) {
        View space = new View(this);
        parent.addView(space, new LinearLayout.LayoutParams(dp(widthDp), 1));
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message);
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSIONS);
        }
    }

    @SuppressWarnings("MissingPermission")
    private void enableBluetooth() {
        if (bluetooth == null) { setStatus("This tablet does not expose a Bluetooth adapter."); return; }
        if (!bluetooth.isEnabled()) startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
        else setStatus("Bluetooth is enabled and ready.");
    }

    private void registerDiscoveryReceiver() {
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        registerReceiver(discoveryReceiver, f);
        receiverRegistered = true;
    }

    @SuppressWarnings("MissingPermission")
    private void scanBluetooth() {
        if (bluetooth == null) { setStatus("Bluetooth adapter unavailable."); return; }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions();
            setStatus("Location permission is required by Android 10 for Bluetooth discovery.");
            return;
        }
        if (!bluetooth.isEnabled()) { enableBluetooth(); return; }
        if (bluetooth.isDiscovering()) bluetooth.cancelDiscovery();
        discoveredDevices.clear();
        devices.setText("Searching nearby…");
        boolean started = bluetooth.startDiscovery();
        setStatus(started ? "Searching for nearby Bluetooth devices…" : "Could not start Bluetooth discovery.");
        if (connectionPill != null) connectionPill.setText("SCANNING");
    }

    @SuppressWarnings("MissingPermission")
    private void appendDevice(BluetoothDevice device) {
        String address = device.getAddress();
        for (BluetoothDevice existing : discoveredDevices) {
            if (existing.getAddress().equals(address)) return;
        }
        discoveredDevices.add(device);
        String name = device.getName();
        devices.append("\n" + (name == null ? "Unknown device" : name) + "   •   " + address
                + (device.getBondState() == BluetoothDevice.BOND_BONDED ? "   ✓ PAIRED" : ""));
    }

    @SuppressWarnings("MissingPermission")
    private void chooseDeviceToPair() {
        if (bluetooth == null || !bluetooth.isEnabled()) {
            enableBluetooth();
            return;
        }
        if (discoveredDevices.isEmpty()) {
            setStatus("Scan for nearby Bluetooth devices first, then select your phone.");
            return;
        }
        String[] labels = new String[discoveredDevices.size()];
        for (int i = 0; i < discoveredDevices.size(); i++) {
            BluetoothDevice device = discoveredDevices.get(i);
            String name = device.getName() == null ? "Unknown device" : device.getName();
            labels[i] = name + "  •  " + device.getAddress()
                    + (device.getBondState() == BluetoothDevice.BOND_BONDED ? "  [PAIRED]" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Select your phone")
                .setItems(labels, (dialog, which) -> pairDevice(discoveredDevices.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    @SuppressWarnings("MissingPermission")
    private void pairDevice(BluetoothDevice device) {
        String name = device.getName() == null ? device.getAddress() : device.getName();
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            setStatus(name + " is already paired. Wireless Android Auto protocol setup is the next step.");
            if (connectionPill != null) connectionPill.setText("PHONE PAIRED");
            return;
        }
        boolean started = device.createBond();
        setStatus(started ? "Pairing requested for " + name + ". Confirm the system pairing prompt on both devices."
                : "Android could not start Bluetooth pairing with " + name + ".");
    }

    @SuppressWarnings("MissingPermission")
    private void startHotspot() {
        if (wifi == null) { setStatus("Wi-Fi service unavailable."); return; }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions();
            setStatus("Location permission is required to start a local-only hotspot on Android 10.");
            return;
        }
        if (hotspot != null) { setStatus("Temporary Wi-Fi hotspot is already running."); return; }
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    hotspot = reservation;
                    if (wirelessBridge != null) wirelessBridge.close();
                    wirelessBridge = new AndroidAutoWirelessBridge(bluetooth, new AndroidAutoWirelessBridge.Listener() {
                        @Override public void onStatus(String message) {
                            runOnUiThread(() -> {
                                setStatus(message);
                                if (connectionPill != null) {
                                    if (message.contains("TCP transport")) connectionPill.setText("PHONE CONNECTED");
                                    else if (message.contains("advertised")) connectionPill.setText("WAITING FOR PHONE");
                                    else if (message.contains("Bluetooth")) connectionPill.setText("HANDSHAKE");
                                }
                            });
                        }
                        @Override public void onTcpConnected(java.net.Socket socket) {
                            runOnUiThread(() -> {
                                if (connectionPill != null) connectionPill.setText("TRANSPORT OPEN");
                            });
                        }
                    });
                    wirelessBridge.start(reservation);
                    android.net.wifi.WifiConfiguration config = reservation.getWifiConfiguration();
                    hotspotSsid = config != null ? unquoteWifiValue(config.SSID) : null;
                    hotspotPassword = config != null ? unquoteWifiValue(config.preSharedKey) : null;
                    String ssid = hotspotSsid != null ? hotspotSsid : "(SSID unavailable)";
                    String key = hotspotPassword != null ? hotspotPassword : "(password unavailable)";
                    setStatus("Wi-Fi hotspot ready. SSID: " + ssid + "  Password: " + key
                            + "\nTap “Show Wi-Fi QR / Password” to scan and connect. OLA Play is starting the Android Auto Bluetooth handoff.");
                    if (connectionPill != null) connectionPill.setText("WAITING FOR PHONE");
                    if (hotspotSsid != null && hotspotPassword != null) showHotspotQr();
                }
                @Override public void onStopped() {
                    hotspot = null;
                    hotspotSsid = null;
                    hotspotPassword = null;
                    setStatus("Temporary Wi-Fi hotspot stopped by Android.");
                }
                @Override public void onFailed(int reason) {
                    setStatus("Android could not start the temporary hotspot. Error code: " + reason + ".");
                }
            }, null);
            setStatus("Requesting temporary Wi-Fi hotspot…");
        } catch (SecurityException e) {
            setStatus("Hotspot permission denied: " + e.getMessage());
        }
    }

    private void showHotspotQr() {
        if (hotspotSsid == null || hotspotPassword == null) {
            setStatus("Start the temporary hotspot first. Android has not provided its Wi-Fi credentials yet.");
            return;
        }
        try {
            String wifiPayload = "WIFI:T:WPA;S:" + escapeWifiQr(hotspotSsid)
                    + ";P:" + escapeWifiQr(hotspotPassword) + ";;";
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            android.graphics.Bitmap qrBitmap;
            com.google.zxing.common.BitMatrix matrix =
                    new QRCodeWriter().encode(wifiPayload, BarcodeFormat.QR_CODE, 720, 720, hints);
            qrBitmap = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < 720; y++) {
                for (int x = 0; x < 720; x++) {
                    qrBitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            content.setPadding(dp(18), dp(8), dp(18), dp(8));
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(qrBitmap);
            qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(qr, new LinearLayout.LayoutParams(dp(250), dp(250)));
            TextView network = text("NETWORK  " + hotspotSsid, 14, Color.WHITE, true);
            network.setGravity(Gravity.CENTER);
            network.setPadding(0, dp(10), 0, dp(6));
            content.addView(network);
            TextView password = text("PASSWORD  " + hotspotPassword, 14, Color.WHITE, false);
            password.setGravity(Gravity.CENTER);
            password.setTextIsSelectable(true);
            content.addView(password);
            TextView hint = text("Scan this QR code with your phone camera to join the temporary Wi-Fi hotspot.", 12, 0xffc5d4ea, false);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, dp(12), 0, 0);
            content.addView(hint);
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Connect to OLA Play Wi-Fi")
                    .setView(content)
                    .setPositiveButton("Done", null)
                    .setNeutralButton("Copy password", null)
                    .create();
            dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Wi-Fi password", hotspotPassword));
                setStatus("Wi-Fi password copied to clipboard.");
            }));
            dialog.show();
        } catch (WriterException e) {
            setStatus("Could not generate the Wi-Fi QR code: " + e.getMessage());
        }
    }

    private String escapeWifiQr(String value) {
        return value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace(":", "\\:");
    }

    private String unquoteWifiValue(String value) {
        if (value == null) return null;
        if (value.length() >= 2 && value.startsWith("\\\"") && value.endsWith("\\\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    @Override protected void onDestroy() {
        if (bluetooth != null && bluetooth.isDiscovering()) bluetooth.cancelDiscovery();
        if (wirelessBridge != null) wirelessBridge.close();
        if (hotspot != null) hotspot.close();
        if (receiverRegistered) unregisterReceiver(discoveryReceiver);
        super.onDestroy();
    }
}