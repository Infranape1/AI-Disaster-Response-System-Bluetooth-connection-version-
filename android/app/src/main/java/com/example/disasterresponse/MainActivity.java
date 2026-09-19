package com.example.disasterresponse;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Home screen of the AI Disaster Response System.
 *
 * It controls the mesh, shows live status, and routes into the two very
 * different message paths:
 *
 * <ul>
 *   <li><b>Local Emergency Chat</b> travels over Bluetooth between nearby
 *       devices and never needs the internet.</li>
 *   <li><b>Emergency Report</b> goes to the main system instead, and is
 *       queued until the internet is reachable.</li>
 * </ul>
 */
public class MainActivity extends AppCompatActivity implements MeshListener {

    private static final int REQUEST_BLE_PERMISSIONS = 501;

    private MeshEngine mesh;
    private EmergencyDb db;
    private BackendSync sync;

    private TextView statusText;
    private TextView hubIdText;
    private TextView memberCountText;
    private TextView nodeBadge;
    private TextView reportBadge;
    private TextView chatBadge;
    private TextView alertHint;
    private Button alertButton;

    private View dotMesh;
    private View dotBt;
    private View dotNet;

    /** Set when the user pressed ALERT and we had to ask for permissions. */
    private boolean startMeshAfterPermission = false;

    private int pendingCount = 0;

    /** Fires when a network appears, so queued reports go out immediately. */
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        mesh = MeshEngine.get(this);
        db = mesh.db();
        sync = new BackendSync(this, db);

        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status);
        hubIdText = findViewById(R.id.hubId);
        memberCountText = findViewById(R.id.memberCount);
        nodeBadge = findViewById(R.id.nodeBadge);
        reportBadge = findViewById(R.id.reportBadge);
        chatBadge = findViewById(R.id.chatBadge);
        alertHint = findViewById(R.id.alertHint);
        alertButton = findViewById(R.id.alert);

        dotMesh = findViewById(R.id.dotMesh);
        dotBt = findViewById(R.id.dotBt);
        dotNet = findViewById(R.id.dotNet);

        alertButton.setOnClickListener(v -> toggleAlertSystem());

        findViewById(R.id.chat).setOnClickListener(v -> openChat());
        findViewById(R.id.report).setOnClickListener(v -> showReportDialog());
        findViewById(R.id.info).setOnClickListener(v -> showInformation());
        findViewById(R.id.settings).setOnClickListener(v -> showSettings());

        findViewById(R.id.btnScanHubs).setOnClickListener(v -> showHubScan());
        findViewById(R.id.btnCreateHub).setOnClickListener(v -> confirmCreateHub());
        findViewById(R.id.btnLeaveHub).setOnClickListener(v -> confirmLeaveHub());

        nodeBadge.setText(db.node());

        watchForNetwork();
    }

    /**
     * Wi-Fi usually comes back while the app is already open, so waiting for
     * the next onResume would leave reports sitting in the queue.
     */
    private void watchForNetwork() {

        try {
            ConnectivityManager manager = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);

            if (manager == null) {
                return;
            }

            networkCallback = new ConnectivityManager.NetworkCallback() {

                @Override
                public void onAvailable(Network network) {
                    sync.flushQueue((success, detail) -> {
                        toast(detail);
                        refresh();
                    });
                }
            };

            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .build();

            manager.registerNetworkCallback(request, networkCallback);

        } catch (Exception ignored) {
            /* Reports still retry on resume. */
        }
    }

    @Override
    protected void onDestroy() {

        super.onDestroy();

        if (networkCallback == null) {
            return;
        }

        try {
            ConnectivityManager manager = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);

            if (manager != null) {
                manager.unregisterNetworkCallback(networkCallback);
            }

        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {

        super.onResume();

        mesh.addListener(this);

        refresh();

        /* Reports that never made it out try again whenever we are online. */
        sync.flushQueue((success, detail) -> refresh());
    }

    @Override
    protected void onPause() {
        super.onPause();
        mesh.removeListener(this);
    }

    /* =====================================================
     * ALERT SYSTEM
     * ===================================================== */

    private void toggleAlertSystem() {

        if (mesh.isActive()) {
            confirmStopMesh();
            return;
        }

        startMesh();
    }

    private void startMesh() {

        if (!mesh.isBluetoothSupported()) {
            toast("This device does not support Bluetooth.");
            return;
        }

        if (!mesh.isBluetoothReady()) {

            toast("Please turn Bluetooth on.");

            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception ignored) {
            }

            return;
        }

        if (!mesh.hasPermissions()) {
            startMeshAfterPermission = true;
            requestBlePermissions();
            return;
        }

        if (!db.hasHub()) {
            promptForHub();
            return;
        }

        if (!mesh.start()) {
            toast("The mesh could not start. Check Bluetooth and permissions.");
        }

        refresh();
    }

    private void confirmStopMesh() {

        new AlertDialog.Builder(this)
                .setTitle("Turn off the Alert System?")
                .setMessage(
                        "This device will stop advertising, delivering and relaying " +
                                "messages. Anything you have written stays stored."
                )
                .setPositiveButton("TURN OFF", (dialog, which) -> {
                    mesh.stop();
                    refresh();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void promptForHub() {

        new AlertDialog.Builder(this)
                .setTitle("No emergency hub yet")
                .setMessage(
                        "A hub is the group every nearby device shares.\n\n" +
                                "Scan for a hub that already exists, or create one " +
                                "and let the others find it."
                )
                .setPositiveButton("SCAN NEARBY", (dialog, which) -> showHubScan())
                .setNeutralButton("CREATE HUB", (dialog, which) -> confirmCreateHub())
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void requestBlePermissions() {

        ActivityCompat.requestPermissions(
                this,
                MeshEngine.requiredPermissions(),
                REQUEST_BLE_PERMISSIONS
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_BLE_PERMISSIONS) {
            return;
        }

        boolean granted = grantResults.length > 0;

        for (int result : grantResults) {
            if (result != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }

        if (!granted) {
            startMeshAfterPermission = false;
            toast("Bluetooth permissions are required for offline messaging.");
            return;
        }

        if (startMeshAfterPermission) {
            startMeshAfterPermission = false;
            startMesh();
        }

        refresh();
    }

    /* =====================================================
     * HUB DISCOVERY
     * ===================================================== */

    private void showHubScan() {

        if (!mesh.hasPermissions()) {
            startMeshAfterPermission = false;
            requestBlePermissions();
            return;
        }

        if (!mesh.isBluetoothReady()) {

            toast("Please turn Bluetooth on.");

            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception ignored) {
            }

            return;
        }

        View view = LayoutInflater.from(this)
                .inflate(R.layout.dialog_hub_scan, null);

        TextView scanStatus = view.findViewById(R.id.scanStatus);
        ListView hubList = view.findViewById(R.id.hubList);

        List<MeshEngine.HubInfo> hubs = new ArrayList<>();

        HubAdapter hubAdapter = new HubAdapter(hubs);
        hubList.setAdapter(hubAdapter);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .create();

        MeshEngine.HubScanListener listener = new MeshEngine.HubScanListener() {

            @Override
            public void onHubsFound(List<MeshEngine.HubInfo> found) {

                hubs.clear();
                hubs.addAll(found);
                hubAdapter.notifyDataSetChanged();

                scanStatus.setText(
                        found.size() + " hub(s) found. Tap one to join."
                );
            }

            @Override
            public void onHubScanFinished(List<MeshEngine.HubInfo> found) {

                hubs.clear();
                hubs.addAll(found);
                hubAdapter.notifyDataSetChanged();

                if (found.isEmpty()) {
                    scanStatus.setText(
                            "No hub found nearby.\n" +
                                    "Make sure another device has its Alert System on, " +
                                    "or create a hub here."
                    );
                } else {
                    scanStatus.setText(
                            found.size() + " hub(s) found. Tap one to join."
                    );
                }
            }

            @Override
            public void onHubScanFailed(int errorCode) {
                scanStatus.setText("Scan failed (code " + errorCode + ").");
            }
        };

        hubList.setOnItemClickListener((parent, itemView, position, id) -> {

            if (position < 0 || position >= hubs.size()) {
                return;
            }

            String hubId = hubs.get(position).hubId;

            mesh.stopHubScan();
            dialog.dismiss();

            joinHub(hubId);
        });

        view.findViewById(R.id.btnRescan).setOnClickListener(v -> {
            scanStatus.setText("Scanning for active hubs...");
            hubs.clear();
            hubAdapter.notifyDataSetChanged();
            mesh.stopHubScan();
            mesh.startHubScan(listener);
        });

        view.findViewById(R.id.btnManual).setOnClickListener(v -> {
            mesh.stopHubScan();
            dialog.dismiss();
            showManualJoin();
        });

        view.findViewById(R.id.btnCloseScan).setOnClickListener(v -> {
            mesh.stopHubScan();
            dialog.dismiss();
        });

        dialog.setOnDismissListener(d -> mesh.stopHubScan());

        dialog.show();

        mesh.startHubScan(listener);
    }

    /** Fallback for when a hub is out of advertising range but the id is known. */
    private void showManualJoin() {

        EditText input = new EditText(this);

        input.setHint("DISASTER-HUB-XXXXXX");
        input.setSingleLine(true);
        input.setTextColor(ContextCompat.getColor(this, R.color.text));

        new AlertDialog.Builder(this)
                .setTitle("Enter Hub ID")
                .setMessage("Use this when the hub is known but not in range yet.")
                .setView(input)
                .setPositiveButton("JOIN", (dialog, which) ->
                        joinHub(input.getText().toString()))
                .setNeutralButton("SCAN INSTEAD", (dialog, which) -> showHubScan())
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void joinHub(String hubId) {

        if (hubId == null || hubId.trim().isEmpty()) {
            return;
        }

        String cleaned = hubId.trim().toUpperCase(Locale.US);

        if (!mesh.joinHub(cleaned)) {
            toast("That is not a valid Hub ID.");
            return;
        }

        toast("Joined " + db.hub());

        refresh();

        if (!mesh.isActive()) {
            startMesh();
        }
    }

    private void confirmCreateHub() {

        if (db.hasHub()) {

            new AlertDialog.Builder(this)
                    .setTitle("Replace the current hub?")
                    .setMessage(
                            "This device is already in " + db.hub() +
                                    ".\n\nCreating a new hub will leave that one."
                    )
                    .setPositiveButton("CREATE NEW", (dialog, which) -> createHub())
                    .setNegativeButton("CANCEL", null)
                    .show();

            return;
        }

        createHub();
    }

    private void createHub() {

        String hubId = mesh.createHub();

        new AlertDialog.Builder(this)
                .setTitle("Emergency Hub Created")
                .setMessage(
                        hubId + "\n\n" +
                                "Other devices will now see this hub when they scan. " +
                                "They do not need to type the ID."
                )
                .setPositiveButton("START ALERT SYSTEM", (dialog, which) -> startMesh())
                .setNegativeButton("LATER", null)
                .show();

        refresh();
    }

    private void confirmLeaveHub() {

        if (!db.hasHub()) {
            toast("No hub selected.");
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Leave " + db.hub() + "?")
                .setMessage(
                        "This device will stop receiving that hub's messages.\n\n" +
                                "You can scan and join a different hub right after, " +
                                "which is useful for checking conditions in another area."
                )
                .setPositiveButton("LEAVE", (dialog, which) -> {

                    mesh.leaveHub();

                    toast("Left the hub.");

                    refresh();

                    showHubScan();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    /* =====================================================
     * CHAT
     * ===================================================== */

    private void openChat() {

        if (!db.hasHub()) {
            promptForHub();
            return;
        }

        startActivity(new Intent(this, ChatActivity.class));
    }

    /* =====================================================
     * EMERGENCY REPORT  (main system, not the chat)
     * ===================================================== */

    private void showReportDialog() {

        final String[] labels = {
                "Medical Emergency",
                "Rescue / People Trapped",
                "Fire",
                "Flood",
                "Earthquake",
                "Road Blocked",
                "Food / Water / Supplies",
                "General Emergency"
        };

        final String[] values = {
                "MEDICAL", "RESCUE", "FIRE", "FLOOD",
                "EARTHQUAKE", "ROAD_BLOCKED", "SUPPLIES", "GENERAL"
        };

        View view = LayoutInflater.from(this)
                .inflate(R.layout.dialog_report, null);

        Spinner spinner = view.findViewById(R.id.typeSpinner);
        EditText input = view.findViewById(R.id.reportInput);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels
        );

        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );

        spinner.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .create();

        view.findViewById(R.id.btnCancelReport)
                .setOnClickListener(v -> dialog.dismiss());

        view.findViewById(R.id.btnSendReport).setOnClickListener(v -> {

            String content = input.getText().toString().trim();

            if (content.isEmpty()) {
                toast("Please describe the emergency.");
                return;
            }

            int position = spinner.getSelectedItemPosition();

            if (position < 0 || position >= values.length) {
                position = values.length - 1;
            }

            dialog.dismiss();

            createReport(content, values[position]);
        });

        dialog.show();
    }

    private void createReport(String content, String type) {

        String id = MeshEngine.newMessageId();

        /*
         * Stored first so the report survives even with no connectivity,
         * then pushed to the main system. It is never placed in the mesh
         * outbox, which keeps it out of Local Emergency Chat.
         */
        db.saveOwn(id, type, content, "QUEUED");

        refresh();

        if (!sync.isOnline()) {

            new AlertDialog.Builder(this)
                    .setTitle("Report Stored")
                    .setMessage(
                            "There is no internet right now.\n\n" +
                                    "The report is saved on this device and will be " +
                                    "sent to the main system automatically once a " +
                                    "connection is available."
                    )
                    .setPositiveButton("OK", null)
                    .show();

            return;
        }

        toast("Sending report to the main system...");

        sync.send(id, type, content, (success, detail) -> {
            toast(detail);
            refresh();
        });
    }

    /* =====================================================
     * INFO AND SETTINGS
     * ===================================================== */

    private void showInformation() {

        new AlertDialog.Builder(this)
                .setTitle("Emergency Information")
                .setMessage(
                        "ALERT SYSTEM\n" +
                                "The master switch. It starts Bluetooth advertising, " +
                                "message delivery and relaying for other devices.\n\n" +

                                "LOCAL EMERGENCY CHAT\n" +
                                "A group conversation shared by every device in the " +
                                "hub. It works with no internet and no mobile network. " +
                                "Any number of devices can join.\n\n" +

                                "HOW DISTANT DEVICES ARE REACHED\n" +
                                "Each device passes on what it receives. If A and D " +
                                "are too far apart, a message still arrives through " +
                                "B or C in between. Duplicates are discarded.\n\n" +

                                "EMERGENCY REPORT\n" +
                                "Goes to the main system, not to the chat. If there " +
                                "is no internet it is stored and sent later.\n\n" +

                                "RANGE\n" +
                                "Bluetooth Low Energy usually covers 10 to 50 metres " +
                                "outdoors between phones, less through walls. Keep the " +
                                "screen on for the most reliable delivery."
                )
                .setPositiveButton("OK", null)
                .show();
    }

    private void showSettings() {

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        TextView nameLabel = new TextView(this);
        nameLabel.setText("Display name (optional)");
        nameLabel.setTextSize(12);

        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint(db.node());
        nameInput.setText(db.displayName());

        TextView urlLabel = new TextView(this);
        urlLabel.setText(
                "Main system address (auto-found on same Wi-Fi; "
                        + "set manually for a phone elsewhere)"
        );
        urlLabel.setTextSize(12);
        urlLabel.setPadding(0, pad / 2, 0, 0);

        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setText(db.backendUrl());

        layout.addView(nameLabel);
        layout.addView(nameInput);
        layout.addView(urlLabel);
        layout.addView(urlInput);

        new AlertDialog.Builder(this)
                .setTitle("Settings / Privacy")
                .setMessage(
                        "Node: " + db.node() + "\n" +
                                "Hub: " + (db.hasHub() ? db.hub() : "none") + "\n\n" +
                                "Messages are stored only on this device and on the " +
                                "devices they reach. Nothing is uploaded unless you " +
                                "create an Emergency Report."
                )
                .setView(layout)
                .setPositiveButton("SAVE", (dialog, which) -> {

                    db.setDisplayName(nameInput.getText().toString());
                    db.setBackendUrl(urlInput.getText().toString());

                    toast("Settings saved.");

                    refresh();
                })
                .setNeutralButton("TEST CONNECTION", (dialog, which) -> {

                    db.setDisplayName(nameInput.getText().toString());
                    db.setBackendUrl(urlInput.getText().toString());

                    toast("Checking the main system...");

                    sync.testConnection((success, detail) ->
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle(success
                                            ? "Main System Connected"
                                            : "Main System Unreachable")
                                    .setMessage(detail)
                                    .setPositiveButton("OK", null)
                                    .show());
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    /* =====================================================
     * UI REFRESH
     * ===================================================== */

    private void refresh() {

        boolean meshOn = mesh.isActive();
        boolean btOn = mesh.isBluetoothReady();
        boolean online = sync.isOnline();

        dotMesh.setBackgroundResource(
                meshOn ? R.drawable.dot_green : R.drawable.dot_grey
        );

        dotBt.setBackgroundResource(
                btOn ? R.drawable.dot_green : R.drawable.dot_red
        );

        dotNet.setBackgroundResource(
                online ? R.drawable.dot_green : R.drawable.dot_amber
        );

        ((TextView) findViewById(R.id.labelMesh))
                .setText(meshOn ? "Mesh on" : "Mesh off");

        ((TextView) findViewById(R.id.labelBt))
                .setText(btOn ? "Bluetooth" : "BT off");

        ((TextView) findViewById(R.id.labelNet))
                .setText(online ? "Internet" : "Offline");

        if (meshOn) {
            alertButton.setText("ALERT SYSTEM ACTIVE");
            alertButton.setBackgroundResource(R.drawable.btn_alert_active);
            alertButton.setTextColor(ContextCompat.getColor(this, R.color.green));
            alertHint.setText("Tap to turn the emergency mesh off");
        } else {
            alertButton.setText("ACTIVATE ALERT SYSTEM");
            alertButton.setBackgroundResource(R.drawable.btn_alert);
            alertButton.setTextColor(0xFFFFFFFF);
            alertHint.setText("Starts BLE advertising, chat delivery and relaying");
        }

        hubIdText.setText(db.hasHub() ? db.hub() : "No hub selected");

        int members = db.hasHub() ? db.members(db.hub()).size() : 0;
        int inRange = mesh.onlinePeerCount();

        memberCountText.setText(
                members + (members == 1 ? " node" : " nodes")
                        + (inRange > 0 ? " · " + inRange + " in range" : "")
        );

        nodeBadge.setText(db.label());

        reportBadge.setText(String.valueOf(db.reportCount()));

        chatBadge.setText(pendingCount > 0 ? pendingCount + " QUEUED" : "OPEN");

        statusText.setText(mesh.status());
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /* =====================================================
     * MESH CALLBACKS
     * ===================================================== */

    @Override
    public void onMeshStatus(String status) {
        statusText.setText(status);
    }

    @Override
    public void onChatChanged() {
        refresh();
    }

    @Override
    public void onPeersChanged(List<String> peerNames) {
        refresh();
    }

    @Override
    public void onOutboxChanged(int pending) {
        pendingCount = pending;
        refresh();
    }

    /* =====================================================
     * HUB LIST ADAPTER
     * ===================================================== */

    private class HubAdapter extends BaseAdapter {

        private final List<MeshEngine.HubInfo> data;

        HubAdapter(List<MeshEngine.HubInfo> data) {
            this.data = data;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public MeshEngine.HubInfo getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            View view = convertView;

            if (view == null) {
                view = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_hub, parent, false);
            }

            MeshEngine.HubInfo hub = getItem(position);

            TextView name = view.findViewById(R.id.hubName);
            TextView meta = view.findViewById(R.id.hubMeta);
            TextView action = view.findViewById(R.id.hubAction);

            name.setText(hub.hubId);
            meta.setText(signalLabel(hub.rssi) + " · " + hub.rssi + " dBm");

            boolean current = hub.hubId.equalsIgnoreCase(db.hub());

            action.setText(current ? "CURRENT" : "JOIN");

            return view;
        }

        private String signalLabel(int rssi) {

            if (rssi > -60) {
                return "Very close";
            }

            if (rssi > -75) {
                return "Nearby";
            }

            if (rssi > -88) {
                return "In range";
            }

            return "Far";
        }
    }
}
