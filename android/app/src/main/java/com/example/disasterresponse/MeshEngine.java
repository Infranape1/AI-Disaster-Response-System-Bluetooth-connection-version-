package com.example.disasterresponse;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Offline BLE mesh for the disaster response system.
 *
 * <p>Why this is a singleton: the mesh must survive screen changes. The home
 * screen and the chat screen are two views onto one running radio.</p>
 *
 * <h3>How many devices can talk</h3>
 * Any number. Every device runs a GATT server and advertises, so every device
 * is reachable. Delivery works in rounds:
 *
 * <ol>
 *   <li>Scan and collect <em>every</em> nearby node (not just the first one).</li>
 *   <li>Visit each node in turn and hand over every message it has not
 *       already received.</li>
 *   <li>A node that receives a message re-queues it for its own neighbours
 *       with TTL reduced by one.</li>
 * </ol>
 *
 * Step 3 is what makes this a mesh rather than a pair of phones: a message
 * from device A reaches device D even when A and D are far apart, as long as
 * B or C sits between them. Duplicates are dropped by message id, and a
 * message is never handed back to the device it arrived from.
 */
public class MeshEngine {

    private static final String TAG = "DISASTER_MESH";

    /* ---------------- protocol ---------------- */

    private static final UUID SERVICE_UUID =
            UUID.fromString("7d4a1000-8a21-4f6c-9d01-123456789001");

    private static final UUID TRANSFER_UUID =
            UUID.fromString("7d4a1001-8a21-4f6c-9d01-123456789001");

    /** Hub id travels in the scan response so hubs are discoverable. */
    private static final int HUB_MANUFACTURER_ID = 0x1234;

    private static final int MAX_MESSAGE_BYTES = 4096;

    /** 4 bytes total length + 4 bytes offset. */
    private static final int PACKET_HEADER_BYTES = 8;

    private static final int REQUESTED_MTU = 247;
    private static final int MIN_CHUNK_BYTES = 12;

    /* ---------------- timing ---------------- */

    /** How long each delivery round listens for nearby nodes. */
    private static final long DISCOVERY_MS = 6000;

    /** Give up on a single peer after this long. */
    private static final long PEER_TIMEOUT_MS = 20000;

    /** Pause between delivery rounds while messages are still queued. */
    private static final long ROUND_GAP_MS = 9000;

    /** Heartbeat so members list stays fresh. */
    private static final long PRESENCE_INTERVAL_MS = 45000;

    /** Queued messages are dropped after this long. */
    private static final long OUTBOX_MAX_AGE_MS = 15 * 60 * 1000L;

    /**
     * De-duplication records are kept for a day. A message id not heard in
     * that time will not turn up again, and presence heartbeats would
     * otherwise grow the table without limit.
     */
    private static final long SEEN_MAX_AGE_MS = 24 * 60 * 60 * 1000L;

    /** Re-offer a message for this many rounds so late joiners get it. */
    private static final int MAX_ROUNDS = 3;

    /** A peer is considered gone after this long without being seen. */
    private static final long PEER_STALE_MS = 90000;

    public static final long HUB_SCAN_DURATION_MS = 12000;

    /* ---------------- singleton ---------------- */

    private static MeshEngine instance;

    public static synchronized MeshEngine get(Context context) {
        if (instance == null) {
            instance = new MeshEngine(context.getApplicationContext());
        }
        return instance;
    }

    /* ---------------- state ---------------- */

    private final Context context;
    private final EmergencyDb db;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;

    private boolean active = false;
    private boolean advertising = false;

    private enum Phase {IDLE, DISCOVERING, DELIVERING}

    private Phase phase = Phase.IDLE;

    /** Nodes found in the current and recent rounds. */
    private final Map<String, Peer> peers = new LinkedHashMap<>();

    /** Peers still to visit in the current round. */
    private final Deque<String> roundQueue = new ArrayDeque<>();

    /* current peer connection */
    private BluetoothGatt activeGatt;
    private BluetoothGattCharacteristic activeCharacteristic;
    private String activeAddress;
    private final Deque<OutboxItem> peerQueue = new ArrayDeque<>();
    private OutboxItem sendingItem;
    private byte[] sendingBytes;
    private int sendOffset;
    private int chunkSize = 20;

    /** Reassembly buffers for incoming transfers, keyed by peer address. */
    private final Map<String, Incoming> incoming = new HashMap<>();

    private final CopyOnWriteArrayList<MeshListener> listeners =
            new CopyOnWriteArrayList<>();

    private String lastStatus = "Emergency mesh is offline.";

    /* hub discovery */
    private boolean hubScanning = false;
    private HubScanListener hubScanListener;
    private final Map<String, HubInfo> foundHubs = new LinkedHashMap<>();

    private MeshEngine(Context context) {
        this.context = context;
        this.db = new EmergencyDb(context);
        setupAdapter();
    }

    /* =====================================================
     * SMALL TYPES
     * ===================================================== */

    private static class Peer {
        String address;
        String name;
        String hubId;
        int rssi;
        long lastSeen;
    }

    private static class Incoming {
        int total;
        int nextOffset;
        byte[] data;

        Incoming(int total) {
            this.total = total;
            this.data = new byte[total];
        }
    }

    /** A hub advertised by a nearby device. */
    public static class HubInfo {
        public String hubId;
        public int devices;
        public int rssi;
    }

    public interface HubScanListener {
        void onHubsFound(List<HubInfo> hubs);

        void onHubScanFinished(List<HubInfo> hubs);

        void onHubScanFailed(int errorCode);
    }

    /* =====================================================
     * SETUP
     * ===================================================== */

    private void setupAdapter() {

        BluetoothManager manager = (BluetoothManager)
                context.getSystemService(Context.BLUETOOTH_SERVICE);

        if (manager == null) {
            return;
        }

        adapter = manager.getAdapter();

        if (adapter == null) {
            return;
        }

        try {
            scanner = adapter.getBluetoothLeScanner();
            advertiser = adapter.getBluetoothLeAdvertiser();
        } catch (SecurityException ignored) {
        }
    }

    public EmergencyDb db() {
        return db;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isBluetoothReady() {

        if (adapter == null) {
            return false;
        }

        try {
            return adapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    public boolean isBluetoothSupported() {
        return adapter != null;
    }

    public String status() {
        return lastStatus;
    }

    public int onlinePeerCount() {

        int count = 0;
        long now = System.currentTimeMillis();

        for (Peer peer : peers.values()) {
            if (now - peer.lastSeen < PEER_STALE_MS) {
                count++;
            }
        }

        return count;
    }

    public boolean hasPermissions() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            return granted(Manifest.permission.BLUETOOTH_SCAN)
                    && granted(Manifest.permission.BLUETOOTH_CONNECT)
                    && granted(Manifest.permission.BLUETOOTH_ADVERTISE);
        }

        return granted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static String[] requiredPermissions() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            };
        }

        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    /* =====================================================
     * LISTENERS
     * ===================================================== */

    public void addListener(MeshListener listener) {

        if (listener == null || listeners.contains(listener)) {
            return;
        }

        listeners.add(listener);

        listener.onMeshStatus(lastStatus);
        listener.onOutboxChanged(db.outboxSize());
        listener.onPeersChanged(peerNames());
    }

    public void removeListener(MeshListener listener) {
        listeners.remove(listener);
    }

    private void setStatus(String status) {

        lastStatus = status;

        handler.post(() -> {
            for (MeshListener listener : listeners) {
                listener.onMeshStatus(status);
            }
        });
    }

    private void notifyChat() {
        handler.post(() -> {
            for (MeshListener listener : listeners) {
                listener.onChatChanged();
            }
        });
    }

    private void notifyPeers() {

        List<String> names = peerNames();

        handler.post(() -> {
            for (MeshListener listener : listeners) {
                listener.onPeersChanged(names);
            }
        });
    }

    private void notifyOutbox() {

        int size = db.outboxSize();

        handler.post(() -> {
            for (MeshListener listener : listeners) {
                listener.onOutboxChanged(size);
            }
        });
    }

    public List<String> peerNames() {

        List<String> names = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Peer peer : peers.values()) {
            if (now - peer.lastSeen < PEER_STALE_MS) {
                names.add(peer.name);
            }
        }

        return names;
    }

    /* =====================================================
     * START / STOP
     * ===================================================== */

    /**
     * Turn the emergency mesh on. Safe to call repeatedly.
     *
     * @return false when it could not start (no bluetooth, no permission,
     * or no hub selected)
     */
    public boolean start() {

        if (adapter == null || !isBluetoothReady() || !hasPermissions()) {
            return false;
        }

        if (!db.hasHub()) {
            return false;
        }

        active = true;

        startGattServer();
        startAdvertising();

        setStatus("Emergency mesh active. Advertising as " + db.node() + ".");

        handler.removeCallbacks(presenceRunnable);
        handler.post(presenceRunnable);

        kick();

        return true;
    }

    public void stop() {

        active = false;

        handler.removeCallbacks(presenceRunnable);
        handler.removeCallbacks(endDiscoveryRunnable);
        handler.removeCallbacks(peerTimeoutRunnable);
        handler.removeCallbacks(nextRoundRunnable);

        stopDeliveryScan();
        closeActiveGatt();

        stopAdvertising();

        if (gattServer != null) {
            try {
                gattServer.close();
            } catch (Exception ignored) {
            }
            gattServer = null;
        }

        phase = Phase.IDLE;
        peers.clear();
        incoming.clear();

        setStatus("Emergency mesh is offline.");
        notifyPeers();
    }

    /** Restart advertising, needed after the hub id changes. */
    public void refreshAdvertising() {

        if (!active) {
            return;
        }

        stopAdvertising();
        startAdvertising();
    }

    /* =====================================================
     * GATT SERVER  (receiving side)
     * ===================================================== */

    @SuppressLint("MissingPermission")
    private void startGattServer() {

        if (gattServer != null || !hasPermissions()) {
            return;
        }

        BluetoothManager manager = (BluetoothManager)
                context.getSystemService(Context.BLUETOOTH_SERVICE);

        if (manager == null) {
            return;
        }

        try {
            gattServer = manager.openGattServer(context, serverCallback);

            if (gattServer == null) {
                setStatus("Unable to open the BLE server on this device.");
                return;
            }

            BluetoothGattService service = new BluetoothGattService(
                    SERVICE_UUID,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY
            );

            BluetoothGattCharacteristic transfer =
                    new BluetoothGattCharacteristic(
                            TRANSFER_UUID,
                            BluetoothGattCharacteristic.PROPERTY_WRITE
                                    | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                            BluetoothGattCharacteristic.PERMISSION_WRITE
                    );

            service.addCharacteristic(transfer);
            gattServer.addService(service);

        } catch (SecurityException e) {
            setStatus("Bluetooth permission error while starting the server.");
        }
    }

    private final BluetoothGattServerCallback serverCallback =
            new BluetoothGattServerCallback() {

                @Override
                public void onConnectionStateChange(
                        BluetoothDevice device, int status, int newState) {

                    if (device == null) {
                        return;
                    }

                    String address = addressOf(device);

                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        incoming.remove(address);
                    }
                }

                @Override
                public void onCharacteristicWriteRequest(
                        BluetoothDevice device,
                        int requestId,
                        BluetoothGattCharacteristic characteristic,
                        boolean preparedWrite,
                        boolean responseNeeded,
                        int offset,
                        byte[] value) {

                    if (!TRANSFER_UUID.equals(characteristic.getUuid())) {
                        respond(device, requestId,
                                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                                offset, responseNeeded);
                        return;
                    }

                    String address = addressOf(device);

                    if (value == null || value.length <= PACKET_HEADER_BYTES) {
                        respond(device, requestId,
                                BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH,
                                offset, responseNeeded);
                        return;
                    }

                    ByteBuffer buffer = ByteBuffer.wrap(value)
                            .order(ByteOrder.BIG_ENDIAN);

                    int total = buffer.getInt();
                    int packetOffset = buffer.getInt();
                    int payloadLength = value.length - PACKET_HEADER_BYTES;

                    if (total <= 0 || total > MAX_MESSAGE_BYTES
                            || packetOffset < 0 || packetOffset > total) {

                        respond(device, requestId,
                                BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH,
                                offset, responseNeeded);
                        return;
                    }

                    Incoming transfer = incoming.get(address);

                    /* A fresh message always restarts at offset zero. */
                    if (packetOffset == 0) {
                        transfer = new Incoming(total);
                        incoming.put(address, transfer);
                    }

                    boolean usable = transfer != null
                            && transfer.total == total
                            && packetOffset == transfer.nextOffset
                            && packetOffset + payloadLength <= transfer.total;

                    if (!usable) {
                        incoming.remove(address);
                        respond(device, requestId,
                                BluetoothGatt.GATT_INVALID_OFFSET,
                                offset, responseNeeded);
                        return;
                    }

                    System.arraycopy(
                            value, PACKET_HEADER_BYTES,
                            transfer.data, packetOffset, payloadLength
                    );

                    transfer.nextOffset += payloadLength;

                    respond(device, requestId, BluetoothGatt.GATT_SUCCESS,
                            offset, responseNeeded);

                    if (transfer.nextOffset >= transfer.total) {

                        byte[] complete = transfer.data;
                        incoming.remove(address);

                        handleIncoming(
                                new String(complete, StandardCharsets.UTF_8),
                                address
                        );
                    }
                }
            };

    @SuppressLint("MissingPermission")
    private void respond(BluetoothDevice device, int requestId,
                         int status, int offset, boolean needed) {

        if (!needed || gattServer == null) {
            return;
        }

        try {
            gattServer.sendResponse(device, requestId, status, offset, null);
        } catch (SecurityException ignored) {
        }
    }

    /* =====================================================
     * ADVERTISING
     * ===================================================== */

    @SuppressLint("MissingPermission")
    private void startAdvertising() {

        if (advertising || !hasPermissions()) {
            return;
        }

        String hubId = db.hub();

        if (hubId == null || hubId.trim().isEmpty()) {
            setStatus("Join or create an emergency hub before activating the mesh.");
            return;
        }

        if (advertiser == null) {
            try {
                advertiser = adapter.getBluetoothLeAdvertiser();
            } catch (SecurityException ignored) {
            }
        }

        if (advertiser == null) {
            setStatus("This device cannot advertise over BLE. It can still receive.");
            return;
        }

        try {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();

            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build();

            /*
             * The hub id goes in the scan response. Keeping it out of the
             * primary advertisement avoids the 31 byte limit.
             */
            AdvertiseData scanResponse = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addManufacturerData(
                            HUB_MANUFACTURER_ID,
                            hubId.getBytes(StandardCharsets.UTF_8)
                    )
                    .build();

            advertiser.startAdvertising(settings, data, scanResponse,
                    advertiseCallback);

        } catch (SecurityException e) {
            setStatus("Bluetooth advertising permission error.");
        } catch (IllegalArgumentException e) {
            setStatus("Hub id is too long to advertise.");
        }
    }

    @SuppressLint("MissingPermission")
    private void stopAdvertising() {

        advertising = false;

        if (advertiser == null || !hasPermissions()) {
            return;
        }

        try {
            advertiser.stopAdvertising(advertiseCallback);
        } catch (Exception ignored) {
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {

        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
            setStatus("Mesh active. Nearby nodes can reach this device.");
        }

        @Override
        public void onStartFailure(int errorCode) {

            advertising = false;

            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                advertising = true;
                return;
            }

            setStatus("Advertising failed (code " + errorCode
                    + "). This device can still receive messages.");
        }
    };

    /* =====================================================
     * SENDING
     * ===================================================== */

    /** Send a chat message to everyone in the hub. */
    public void sendChat(String text) {

        if (text == null || text.trim().isEmpty()) {
            return;
        }

        String id = newMessageId();

        db.saveOwn(id, EmergencyDb.TYPE_CHAT, text.trim(), "SENDING");

        String payload = buildPayload(
                id, EmergencyDb.TYPE_CHAT, text.trim(), 8
        );

        db.enqueueOutbox(id, payload, null);

        notifyChat();
        notifyOutbox();

        kick();
    }

    /** Announce this node so other devices can list it as a member. */
    public void sendPresence() {

        if (!active || !db.hasHub()) {
            return;
        }

        String id = newMessageId();

        String payload = buildPayload(
                id, EmergencyDb.TYPE_PRESENCE, db.label(), 3
        );

        db.markSeen(id);
        db.enqueueOutbox(id, payload, null);

        kick();
    }

    private final Runnable presenceRunnable = new Runnable() {
        @Override
        public void run() {

            if (!active) {
                return;
            }

            sendPresence();
            handler.postDelayed(this, PRESENCE_INTERVAL_MS);
        }
    };

    public static String newMessageId() {
        return "MSG-" + UUID.randomUUID()
                .toString()
                .substring(0, 8)
                .toUpperCase(Locale.US);
    }

    private String buildPayload(String id, String type, String content, int ttl) {

        try {
            JSONObject object = new JSONObject();

            object.put("messageId", id);
            object.put("hubId", db.hub());
            object.put("senderNodeId", db.node());
            object.put("senderName", db.label());
            object.put("timestamp", System.currentTimeMillis());
            object.put("type", type);
            object.put("content", content);
            object.put("ttl", ttl);

            return object.toString();

        } catch (Exception e) {
            return null;
        }
    }

    /* =====================================================
     * RECEIVING AND RELAYING
     * ===================================================== */

    private void handleIncoming(String payload, String fromAddress) {

        try {
            JSONObject object = new JSONObject(payload);

            String id = object.optString("messageId", "");
            String hubId = object.optString("hubId", "");
            String senderId = object.optString("senderNodeId", "");
            String senderName = object.optString("senderName", senderId);
            String type = object.optString("type", "GENERAL");
            String content = object.optString("content", "");
            long timestamp = object.optLong("timestamp", System.currentTimeMillis());
            int ttl = object.optInt("ttl", 1);

            if (id.isEmpty()) {
                return;
            }

            /* Never process or relay a message twice. */
            if (db.alreadySeen(id)) {
                return;
            }

            db.markSeen(id);

            boolean mine = hubId.equalsIgnoreCase(db.hub());

            if (mine) {

                boolean known = db.memberLastSeen(hubId, senderId) > 0;

                db.touchMember(hubId, senderId);

                if (EmergencyDb.TYPE_PRESENCE.equals(type)) {

                    if (!known) {
                        storeSystem(hubId, senderName + " joined the hub");
                        notifyChat();
                    }

                    notifyPeers();

                } else {

                    db.saveReceived(id, senderId, hubId, type,
                            content, timestamp, ttl);

                    notifyChat();
                    notifyPeers();

                    setStatus("Message received from " + senderName + ".");
                }
            }

            /*
             * Relay for everyone, including hubs we are not part of.
             * Carrying a neighbour's traffic is what extends the range of
             * the whole network during a disaster.
             */
            if (ttl > 1) {

                object.put("ttl", ttl - 1);

                db.enqueueOutbox(id, object.toString(), fromAddress);

                notifyOutbox();
                kick();
            }

        } catch (Exception e) {
            Log.e(TAG, "Bad mesh payload", e);
        }
    }

    private void storeSystem(String hubId, String text) {

        db.saveReceived(
                newMessageId(),
                "SYSTEM",
                hubId,
                EmergencyDb.TYPE_SYSTEM,
                text,
                System.currentTimeMillis(),
                1
        );
    }

    /** Local-only notice, e.g. after creating a hub. */
    public void addLocalSystemMessage(String text) {

        if (!db.hasHub()) {
            return;
        }

        storeSystem(db.hub(), text);
        notifyChat();
    }

    /* =====================================================
     * DELIVERY ROUNDS
     * ===================================================== */

    /** Start a delivery round if there is anything to deliver. */
    public void kick() {

        if (!active || phase != Phase.IDLE) {
            return;
        }

        db.expireOutbox(OUTBOX_MAX_AGE_MS);
        db.pruneSeen(SEEN_MAX_AGE_MS);

        if (db.outboxSize() == 0) {
            return;
        }

        if (!hasPermissions() || !isBluetoothReady()) {
            return;
        }

        phase = Phase.DISCOVERING;

        startDeliveryScan();

        handler.removeCallbacks(endDiscoveryRunnable);
        handler.postDelayed(endDiscoveryRunnable, DISCOVERY_MS);
    }

    @SuppressLint("MissingPermission")
    private void startDeliveryScan() {

        if (scanner == null) {
            try {
                scanner = adapter.getBluetoothLeScanner();
            } catch (SecurityException ignored) {
            }
        }

        if (scanner == null) {
            return;
        }

        try {
            List<ScanFilter> filters = new ArrayList<>();

            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build());

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();

            scanner.startScan(filters, settings, deliveryScanCallback);

            setStatus("Looking for nearby nodes ("
                    + db.outboxSize() + " message(s) queued)...");

        } catch (SecurityException ignored) {
        }
    }

    @SuppressLint("MissingPermission")
    private void stopDeliveryScan() {

        if (scanner == null || !hasPermissions()) {
            return;
        }

        try {
            scanner.stopScan(deliveryScanCallback);
        } catch (Exception ignored) {
        }
    }

    private final ScanCallback deliveryScanCallback = new ScanCallback() {

        @Override
        public void onScanResult(int callbackType, ScanResult result) {

            if (result == null || result.getDevice() == null) {
                return;
            }

            String address = addressOf(result.getDevice());

            Peer peer = peers.get(address);

            /*
             * Scan results arrive many times per second. Only a genuinely new
             * node is worth telling the UI about, otherwise the home screen
             * would rebuild itself continuously.
             */
            boolean isNew = peer == null;

            if (isNew) {
                peer = new Peer();
                peer.address = address;
                peers.put(address, peer);
            }

            peer.name = nameOf(result.getDevice(), address);
            peer.rssi = result.getRssi();
            peer.lastSeen = System.currentTimeMillis();

            ScanRecord record = result.getScanRecord();

            if (record != null) {

                byte[] raw = record.getManufacturerSpecificData(HUB_MANUFACTURER_ID);

                if (raw != null && raw.length > 0) {
                    peer.hubId = new String(raw, StandardCharsets.UTF_8).trim();
                }
            }

            if (isNew) {
                notifyPeers();
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            setStatus("Scan failed (code " + errorCode + ").");
        }
    };

    private final Runnable endDiscoveryRunnable = this::endDiscovery;

    private void endDiscovery() {

        if (phase != Phase.DISCOVERING) {
            return;
        }

        stopDeliveryScan();

        phase = Phase.DELIVERING;

        roundQueue.clear();

        long now = System.currentTimeMillis();

        for (Peer peer : peers.values()) {
            if (now - peer.lastSeen < PEER_STALE_MS) {
                roundQueue.add(peer.address);
            }
        }

        if (roundQueue.isEmpty()) {

            setStatus("No nearby node in range. "
                    + db.outboxSize()
                    + " message(s) stored and will be delivered automatically.");

            finishRound();
            return;
        }

        setStatus("Delivering to " + roundQueue.size() + " nearby node(s)...");

        deliverNext();
    }

    private void deliverNext() {

        closeActiveGatt();

        if (!active) {
            phase = Phase.IDLE;
            return;
        }

        String address = roundQueue.poll();

        if (address == null) {
            finishRound();
            return;
        }

        peerQueue.clear();

        for (OutboxItem item : db.outbox()) {

            /* Never hand a message back to where it came from. */
            if (address.equals(item.sourceAddr)) {
                continue;
            }

            if (db.wasDelivered(item.messageId, address)) {
                continue;
            }

            peerQueue.add(item);
        }

        if (peerQueue.isEmpty()) {
            deliverNext();
            return;
        }

        connectTo(address);
    }

    @SuppressLint("MissingPermission")
    private void connectTo(String address) {

        if (adapter == null || !hasPermissions()) {
            deliverNext();
            return;
        }

        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);

            activeAddress = address;

            handler.removeCallbacks(peerTimeoutRunnable);
            handler.postDelayed(peerTimeoutRunnable, PEER_TIMEOUT_MS);

            activeGatt = device.connectGatt(
                    context, false, clientCallback, BluetoothDevice.TRANSPORT_LE
            );

            if (activeGatt == null) {
                handler.removeCallbacks(peerTimeoutRunnable);
                deliverNext();
            }

        } catch (Exception e) {
            handler.removeCallbacks(peerTimeoutRunnable);
            deliverNext();
        }
    }

    private final Runnable peerTimeoutRunnable = () -> {
        Log.d(TAG, "Peer timed out: " + activeAddress);
        deliverNext();
    };

    private final BluetoothGattCallback clientCallback = new BluetoothGattCallback() {

        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

            /*
             * Closing a connection ourselves also fires this callback. Acting
             * on it would advance the queue a second time and silently skip
             * the next peer, so only the live connection is allowed through.
             */
            if (gatt != activeGatt) {
                try {
                    gatt.close();
                } catch (Exception ignored) {
                }
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {

                try {
                    if (!gatt.requestMtu(REQUESTED_MTU)) {
                        gatt.discoverServices();
                    }
                } catch (SecurityException e) {
                    handler.post(MeshEngine.this::deliverNext);
                }

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.post(MeshEngine.this::deliverNext);
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {

            int usable = mtu - 3 - PACKET_HEADER_BYTES;
            chunkSize = Math.max(MIN_CHUNK_BYTES, usable);

            try {
                gatt.discoverServices();
            } catch (SecurityException e) {
                handler.post(MeshEngine.this::deliverNext);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {

            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post(MeshEngine.this::deliverNext);
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);

            if (service == null) {
                handler.post(MeshEngine.this::deliverNext);
                return;
            }

            activeCharacteristic = service.getCharacteristic(TRANSFER_UUID);

            if (activeCharacteristic == null) {
                handler.post(MeshEngine.this::deliverNext);
                return;
            }

            handler.post(MeshEngine.this::sendNextMessage);
        }

        @Override
        public void onCharacteristicWrite(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                int status) {

            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post(MeshEngine.this::deliverNext);
                return;
            }

            handler.post(MeshEngine.this::onChunkAcknowledged);
        }
    };

    private void sendNextMessage() {

        sendingItem = peerQueue.poll();

        if (sendingItem == null) {
            /* Everything this peer needed has been handed over. */
            deliverNext();
            return;
        }

        if (sendingItem.payload == null) {
            sendNextMessage();
            return;
        }

        sendingBytes = sendingItem.payload.getBytes(StandardCharsets.UTF_8);

        if (sendingBytes.length > MAX_MESSAGE_BYTES) {
            db.removeFromOutbox(sendingItem.messageId);
            sendNextMessage();
            return;
        }

        sendOffset = 0;

        sendChunk();
    }

    private void sendChunk() {

        if (activeGatt == null || activeCharacteristic == null
                || sendingBytes == null) {
            deliverNext();
            return;
        }

        int remaining = sendingBytes.length - sendOffset;
        int length = Math.min(chunkSize, remaining);

        ByteBuffer packet = ByteBuffer
                .allocate(PACKET_HEADER_BYTES + length)
                .order(ByteOrder.BIG_ENDIAN);

        packet.putInt(sendingBytes.length);
        packet.putInt(sendOffset);
        packet.put(sendingBytes, sendOffset, length);

        if (!writeChunk(packet.array())) {
            deliverNext();
        }
    }

    @SuppressLint("MissingPermission")
    private boolean writeChunk(byte[] data) {

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                int result = activeGatt.writeCharacteristic(
                        activeCharacteristic,
                        data,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                );

                return result == BluetoothStatusCodes.SUCCESS;
            }

            activeCharacteristic.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            );

            activeCharacteristic.setValue(data);

            return activeGatt.writeCharacteristic(activeCharacteristic);

        } catch (Exception e) {
            return false;
        }
    }

    private void onChunkAcknowledged() {

        if (sendingBytes == null || sendingItem == null) {
            return;
        }

        /* Progress is being made, so give this peer a fresh window. */
        handler.removeCallbacks(peerTimeoutRunnable);
        handler.postDelayed(peerTimeoutRunnable, PEER_TIMEOUT_MS);

        int remaining = sendingBytes.length - sendOffset;
        sendOffset += Math.min(chunkSize, remaining);

        if (sendOffset < sendingBytes.length) {
            sendChunk();
            return;
        }

        /* Whole message accepted by this peer. */
        db.markDelivered(sendingItem.messageId, activeAddress);
        db.updateSyncStatus(sendingItem.messageId, "DELIVERED");

        notifyChat();

        sendingBytes = null;
        sendingItem = null;

        sendNextMessage();
    }

    @SuppressLint("MissingPermission")
    private void closeActiveGatt() {

        handler.removeCallbacks(peerTimeoutRunnable);

        if (activeGatt != null) {

            try {
                activeGatt.disconnect();
            } catch (Exception ignored) {
            }

            try {
                activeGatt.close();
            } catch (Exception ignored) {
            }
        }

        activeGatt = null;
        activeCharacteristic = null;
        activeAddress = null;
        sendingItem = null;
        sendingBytes = null;
        peerQueue.clear();
    }

    private void finishRound() {

        phase = Phase.IDLE;

        /*
         * A message is retried for a few rounds even after a successful
         * delivery, so devices that arrive late still receive it.
         */
        for (OutboxItem item : db.outbox()) {

            db.bumpRounds(item.messageId);

            boolean delivered = db.deliveryCount(item.messageId) > 0;

            if (delivered && item.rounds + 1 >= MAX_ROUNDS) {
                db.removeFromOutbox(item.messageId);
            }
        }

        notifyOutbox();
        notifyPeers();

        int pending = db.outboxSize();

        if (pending > 0) {
            handler.removeCallbacks(nextRoundRunnable);
            handler.postDelayed(nextRoundRunnable, ROUND_GAP_MS);
        } else {
            setStatus("Mesh active. All messages delivered.");
        }
    }

    private final Runnable nextRoundRunnable = this::kick;

    /* =====================================================
     * HUB DISCOVERY
     * ===================================================== */

    /**
     * Scan for hubs that nearby devices are advertising, so the user can pick
     * one from a list rather than typing a hub id.
     */
    @SuppressLint("MissingPermission")
    public void startHubScan(HubScanListener listener) {

        hubScanListener = listener;

        if (!hasPermissions() || !isBluetoothReady()) {
            if (listener != null) {
                listener.onHubScanFailed(-1);
            }
            return;
        }

        if (scanner == null) {
            try {
                scanner = adapter.getBluetoothLeScanner();
            } catch (SecurityException ignored) {
            }
        }

        if (scanner == null) {
            if (listener != null) {
                listener.onHubScanFailed(-2);
            }
            return;
        }

        /*
         * Make sure this device is discoverable too, otherwise a hub created
         * here would be invisible to everyone else.
         */
        if (db.hasHub() && !advertising) {
            startGattServer();
            startAdvertising();
        }

        foundHubs.clear();
        hubScanning = true;

        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();

            /*
             * No filter: the hub id lives in the scan response, and some
             * devices will not match a service filter against it.
             */
            scanner.startScan(null, settings, hubScanCallback);

            handler.removeCallbacks(stopHubScanRunnable);
            handler.postDelayed(stopHubScanRunnable, HUB_SCAN_DURATION_MS);

        } catch (SecurityException e) {
            hubScanning = false;
            if (listener != null) {
                listener.onHubScanFailed(-3);
            }
        }
    }

    private final Runnable stopHubScanRunnable = this::stopHubScan;

    @SuppressLint("MissingPermission")
    public void stopHubScan() {

        handler.removeCallbacks(stopHubScanRunnable);

        if (!hubScanning) {
            return;
        }

        hubScanning = false;

        if (scanner != null && hasPermissions()) {
            try {
                scanner.stopScan(hubScanCallback);
            } catch (Exception ignored) {
            }
        }

        HubScanListener listener = hubScanListener;

        if (listener != null) {
            List<HubInfo> hubs = sortedHubs();
            handler.post(() -> listener.onHubScanFinished(hubs));
        }
    }

    public boolean isHubScanning() {
        return hubScanning;
    }

    private List<HubInfo> sortedHubs() {

        List<HubInfo> hubs = new ArrayList<>(foundHubs.values());

        Collections.sort(hubs, (a, b) -> Integer.compare(b.rssi, a.rssi));

        return hubs;
    }

    private final ScanCallback hubScanCallback = new ScanCallback() {

        @Override
        public void onScanResult(int callbackType, ScanResult result) {

            if (!hubScanning || result == null || result.getScanRecord() == null) {
                return;
            }

            byte[] raw = result.getScanRecord()
                    .getManufacturerSpecificData(HUB_MANUFACTURER_ID);

            if (raw == null || raw.length == 0) {
                return;
            }

            String hubId;

            try {
                hubId = new String(raw, StandardCharsets.UTF_8)
                        .trim()
                        .toUpperCase(Locale.US);
            } catch (Exception ignored) {
                return;
            }

            if (!hubId.startsWith("DISASTER-HUB-") || hubId.length() <= 13) {
                return;
            }

            HubInfo info = foundHubs.get(hubId);

            if (info == null) {
                info = new HubInfo();
                info.hubId = hubId;
                info.devices = 0;
                info.rssi = result.getRssi();
                foundHubs.put(hubId, info);
            }

            info.devices = Math.max(info.devices, 1);
            info.rssi = Math.max(info.rssi, result.getRssi());

            HubScanListener listener = hubScanListener;

            if (listener != null) {
                List<HubInfo> hubs = sortedHubs();
                handler.post(() -> listener.onHubsFound(hubs));
            }
        }

        @Override
        public void onScanFailed(int errorCode) {

            hubScanning = false;

            HubScanListener listener = hubScanListener;

            if (listener != null) {
                handler.post(() -> listener.onHubScanFailed(errorCode));
            }
        }
    };

    /* =====================================================
     * HUB ACTIONS
     * ===================================================== */

    public String createHub() {

        String hubId = db.createHub();

        refreshAdvertising();
        addLocalSystemMessage("Hub " + hubId + " created on this device");
        sendPresence();

        return hubId;
    }

    public boolean joinHub(String hubId) {

        if (!db.joinHub(hubId)) {
            return false;
        }

        refreshAdvertising();
        addLocalSystemMessage("Joined " + db.hub());

        if (active) {
            sendPresence();
        }

        notifyPeers();

        return true;
    }

    public void leaveHub() {

        String hubId = db.hub();

        if (hubId != null && !hubId.isEmpty()) {
            db.clearMembers(hubId);
        }

        db.leaveHub();

        stopAdvertising();

        peers.clear();

        notifyPeers();
        notifyChat();

        setStatus("Left the hub. Scan to join another emergency hub.");
    }

    /* =====================================================
     * HELPERS
     * ===================================================== */

    private String addressOf(BluetoothDevice device) {

        try {
            return device.getAddress();
        } catch (SecurityException e) {
            return "UNKNOWN";
        }
    }

    @SuppressLint("MissingPermission")
    private String nameOf(BluetoothDevice device, String address) {

        try {
            String name = device.getName();

            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
        } catch (SecurityException ignored) {
        }

        String tail = address.replace(":", "");

        if (tail.length() > 4) {
            tail = tail.substring(tail.length() - 4);
        }

        return "NODE-" + tail.toUpperCase(Locale.US);
    }
}
