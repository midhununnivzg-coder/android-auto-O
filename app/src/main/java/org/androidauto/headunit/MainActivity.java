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
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 40;
    private static final int REQUEST_ENABLE_BT = 41;

    private TextView status;
    private TextView devices;
    private BluetoothAdapter bluetooth;
    private WifiManager wifi;
    private WifiManager.LocalOnlyHotspotReservation hotspot;
    private boolean receiverRegistered;
    private final ArrayList<BluetoothDevice> discoveredDevices = new ArrayList<>();

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) appendDevice(device);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                setStatus("Bluetooth discovery finished. Select your Vivo X300 and use Pair with selected phone.");
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                if (device != null) {
                    String name = device.getName() == null ? device.getAddress() : device.getName();
                    if (state == BluetoothDevice.BOND_BONDED) {
                        setStatus("Bluetooth pairing complete with " + name + ". Wireless Android Auto session is a separate next step.");
                    } else if (state == BluetoothDevice.BOND_BONDING) {
                        setStatus("Pairing with " + name + "… approve the pairing prompt on both devices.");
                    } else if (state == BluetoothDevice.BOND_NONE) {
                        setStatus("Pairing not completed or bond removed for " + name + ".");
                    }
                }
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        bluetooth = BluetoothAdapter.getDefaultAdapter();
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        buildUi();
        registerDiscoveryReceiver();
        requestRuntimePermissions();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 22, 32, 22);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xff101820);

        TextView heading = new TextView(this);
        heading.setText("ANDROID AUTO  •  HEAD UNIT");
        heading.setTextColor(0xffffffff);
        heading.setTextSize(25);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading, new LinearLayout.LayoutParams(-1, 58));

        status = new TextView(this);
        status.setTextColor(0xffc6d5e0);
        status.setTextSize(15);
        status.setPadding(8, 12, 8, 18);
        status.setText("Android 10 / ARM64 prototype. Checking device capabilities…");
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        Button enableBt = button("Enable Bluetooth");
        enableBt.setOnClickListener(v -> enableBluetooth());
        root.addView(enableBt, buttonParams());

        Button scanBt = button("Scan nearby Bluetooth devices");
        scanBt.setOnClickListener(v -> scanBluetooth());
        root.addView(scanBt, buttonParams());

        Button pairBt = button("Pair with selected phone");
        pairBt.setOnClickListener(v -> chooseDeviceToPair());
        root.addView(pairBt, buttonParams());

        Button startWifi = button("Start temporary Wi-Fi hotspot (band selected by Android)");
        startWifi.setOnClickListener(v -> startHotspot());
        root.addView(startWifi, buttonParams());

        devices = new TextView(this);
        devices.setTextColor(0xffffffff);
        devices.setTextSize(14);
        devices.setPadding(8, 16, 8, 8);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(devices);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView footer = new TextView(this);
        footer.setTextColor(0xffffcc80);
        footer.setTextSize(12);
        footer.setText("DEVELOPER PROTOTYPE — Bluetooth pairing diagnostics added; Android Auto projection protocol is not implemented.");
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 5, 0, 5);
        return p;
    }

    private void setStatus(String message) { status.setText(message); }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSIONS);
        }
    }

    private void enableBluetooth() {
        if (bluetooth == null) { setStatus("This tablet does not expose a Bluetooth adapter."); return; }
        if (!bluetooth.isEnabled()) startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
        else setStatus("Bluetooth is enabled.");
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
        devices.setText("Nearby Bluetooth devices:\n");
        boolean started = bluetooth.startDiscovery();
        setStatus(started ? "Scanning for nearby Bluetooth devices…" : "Could not start Bluetooth discovery.");
    }

    @SuppressWarnings("MissingPermission")
    private void appendDevice(BluetoothDevice device) {
        String address = device.getAddress();
        for (BluetoothDevice existing : discoveredDevices) {
            if (existing.getAddress().equals(address)) return;
        }
        discoveredDevices.add(device);
        String name = device.getName();
        devices.append((name == null ? "Unknown device" : name) + "  •  " + address
                + (device.getBondState() == BluetoothDevice.BOND_BONDED ? "  [PAIRED]" : "") + "\n");
    }

    @SuppressWarnings("MissingPermission")
    private void chooseDeviceToPair() {
        if (bluetooth == null || !bluetooth.isEnabled()) {
            enableBluetooth();
            return;
        }
        if (discoveredDevices.isEmpty()) {
            setStatus("Scan for nearby Bluetooth devices first, then select your Vivo X300.");
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
            setStatus(name + " is already paired. Wireless Android Auto protocol setup is not implemented yet.");
            return;
        }
        boolean started = device.createBond();
        setStatus(started ? "Pairing requested for " + name + ". Confirm the system pairing prompt on both devices."
                : "Android could not start Bluetooth pairing with " + name + ".");
    }

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
                    android.net.wifi.WifiConfiguration config = reservation.getWifiConfiguration();
                    String ssid = config != null ? config.SSID : "(SSID unavailable)";
                    String key = config != null ? config.preSharedKey : "(password unavailable)";
                    String band = "band selected by Android (frequency not exposed by this API)";
                    setStatus("Android local-only hotspot started (" + band + "). SSID: " + ssid + "  Password: " + key
                            + "\nThis verifies hotspot capability only; Android Auto's Bluetooth-to-Wi-Fi handoff is not implemented.");
                }
                @Override public void onStopped() {
                    hotspot = null;
                    setStatus("Temporary Wi-Fi hotspot stopped by Android.");
                }
                @Override public void onFailed(int reason) {
                    setStatus("Android could not start the temporary hotspot. Error code: " + reason
                            + ". Device firmware may restrict app-controlled hotspots.");
                }
            }, null);
            setStatus("Requesting Android local-only hotspot…");
        } catch (SecurityException e) {
            setStatus("Hotspot permission denied: " + e.getMessage());
        }
    }

    @Override protected void onDestroy() {
        if (bluetooth != null && bluetooth.isDiscovering()) bluetooth.cancelDiscovery();
        if (hotspot != null) hotspot.close();
        if (receiverRegistered) unregisterReceiver(discoveryReceiver);
        super.onDestroy();
    }
}
