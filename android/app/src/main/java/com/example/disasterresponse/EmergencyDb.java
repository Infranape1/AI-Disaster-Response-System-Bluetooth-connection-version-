package com.example.disasterresponse;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Local offline store for the disaster response node.
 *
 * Tables
 * ------
 * messages  : everything this device knows about (chat + reports)
 * meta      : node id, hub id, display name
 * seen      : message ids we have already processed (mesh de-duplication)
 * outbox    : messages waiting to be handed to nearby devices
 * delivered : which peers already received which message
 * members   : nodes seen inside the current hub
 */
public class EmergencyDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "disaster.db";

    /*
     * v4 adds the multi-device mesh tables:
     * seen / outbox / delivered / members.
     */
    private static final int DB_VERSION = 4;

    /* Message types. */
    public static final String TYPE_CHAT = "CHAT";
    public static final String TYPE_SYSTEM = "SYSTEM";
    public static final String TYPE_PRESENCE = "PRESENCE";

    public EmergencyDb(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createAll(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        /*
         * Non-destructive: existing emergency data is never dropped.
         * Missing tables are simply added.
         */
        createAll(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        createAll(db);
    }

    private void createAll(SQLiteDatabase db) {

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS messages (" +
                        "message_id TEXT PRIMARY KEY," +
                        "hub_id TEXT," +
                        "sender_id TEXT," +
                        "timestamp INTEGER," +
                        "message_type TEXT," +
                        "content TEXT," +
                        "ttl INTEGER," +
                        "forward_count INTEGER," +
                        "sync_status TEXT" +
                        ")"
        );

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS meta (" +
                        "k TEXT PRIMARY KEY," +
                        "v TEXT" +
                        ")"
        );

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS seen (" +
                        "message_id TEXT PRIMARY KEY," +
                        "seen_at INTEGER" +
                        ")"
        );

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS outbox (" +
                        "message_id TEXT PRIMARY KEY," +
                        "payload TEXT," +
                        "source_addr TEXT," +
                        "created_at INTEGER," +
                        "rounds INTEGER" +
                        ")"
        );

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS delivered (" +
                        "message_id TEXT," +
                        "peer_addr TEXT," +
                        "PRIMARY KEY (message_id, peer_addr)" +
                        ")"
        );

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS members (" +
                        "hub_id TEXT," +
                        "node_id TEXT," +
                        "last_seen INTEGER," +
                        "PRIMARY KEY (hub_id, node_id)" +
                        ")"
        );

        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_messages_hub " +
                        "ON messages (hub_id, timestamp)"
        );
    }


    /* =====================================================
     * META
     * ===================================================== */

    private synchronized String getMeta(String key, String fallback) {

        Cursor cursor = getReadableDatabase().query(
                "meta",
                new String[]{"v"},
                "k=?",
                new String[]{key},
                null, null, null
        );

        try {
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } finally {
            cursor.close();
        }

        setMeta(key, fallback);
        return fallback;
    }

    private synchronized void setMeta(String key, String value) {

        ContentValues values = new ContentValues();
        values.put("k", key);
        values.put("v", value);

        getWritableDatabase().insertWithOnConflict(
                "meta", null, values, SQLiteDatabase.CONFLICT_REPLACE
        );
    }


    /* =====================================================
     * IDENTITY
     * ===================================================== */

    /** Stable short id for this device, e.g. NODE-3344. */
    public String node() {
        return getMeta(
                "node",
                "NODE-" + UUID.randomUUID()
                        .toString()
                        .substring(0, 4)
                        .toUpperCase(Locale.US)
        );
    }

    /** Optional human readable name shown in chat. */
    public String displayName() {
        String name = getMeta("display_name", "");
        return name == null ? "" : name;
    }

    public void setDisplayName(String name) {
        setMeta("display_name", name == null ? "" : name.trim());
    }

    /** Name used on the wire: the chosen name, otherwise the node id. */
    public String label() {
        String name = displayName();
        return name.isEmpty() ? node() : name;
    }

    /**
     * Address of the main system.
     *
     * 10.0.2.2 is the host machine as seen from the Android emulator. On a
     * real phone this must be the LAN address of the machine running the
     * backend, which is why it is editable in Settings.
     */
    public String backendUrl() {
        return getMeta("backend_url", "http://10.0.2.2:8000/sync");
    }

    public void setBackendUrl(String url) {

        if (url == null || url.trim().isEmpty()) {
            return;
        }

        setMeta("backend_url", url.trim());
    }


    /* =====================================================
     * HUB
     * ===================================================== */

    public String hub() {
        return getMeta("hub", "");
    }

    public boolean hasHub() {
        String hub = hub();
        return hub != null && !hub.trim().isEmpty();
    }

    public String createHub() {

        String hubId = "DISASTER-HUB-" +
                UUID.randomUUID()
                        .toString()
                        .substring(0, 6)
                        .toUpperCase(Locale.US);

        setMeta("hub", hubId);
        touchMember(hubId, node());

        return hubId;
    }

    public boolean joinHub(String hubId) {

        if (hubId == null) {
            return false;
        }

        hubId = hubId.trim().toUpperCase(Locale.US);

        if (hubId.isEmpty() || !hubId.startsWith("DISASTER-HUB-")) {
            return false;
        }

        setMeta("hub", hubId);
        touchMember(hubId, node());

        return true;
    }

    public void leaveHub() {
        setMeta("hub", "");
    }


    /* =====================================================
     * MEMBERS
     * ===================================================== */

    public synchronized void touchMember(String hubId, String nodeId) {

        if (hubId == null || nodeId == null) {
            return;
        }

        if (hubId.trim().isEmpty() || nodeId.trim().isEmpty()) {
            return;
        }

        ContentValues values = new ContentValues();
        values.put("hub_id", hubId);
        values.put("node_id", nodeId);
        values.put("last_seen", System.currentTimeMillis());

        getWritableDatabase().insertWithOnConflict(
                "members", null, values, SQLiteDatabase.CONFLICT_REPLACE
        );
    }

    /** Members of a hub, most recently heard first. */
    public synchronized List<String> members(String hubId) {

        List<String> list = new ArrayList<>();

        if (hubId == null || hubId.trim().isEmpty()) {
            return list;
        }

        Cursor cursor = getReadableDatabase().query(
                "members",
                new String[]{"node_id"},
                "hub_id=?",
                new String[]{hubId},
                null, null,
                "last_seen DESC"
        );

        try {
            while (cursor.moveToNext()) {
                list.add(cursor.getString(0));
            }
        } finally {
            cursor.close();
        }

        return list;
    }

    public synchronized long memberLastSeen(String hubId, String nodeId) {

        Cursor cursor = getReadableDatabase().query(
                "members",
                new String[]{"last_seen"},
                "hub_id=? AND node_id=?",
                new String[]{hubId, nodeId},
                null, null, null
        );

        try {
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } finally {
            cursor.close();
        }

        return 0L;
    }

    /** Forget members of a hub (used when leaving). */
    public synchronized void clearMembers(String hubId) {
        getWritableDatabase().delete(
                "members", "hub_id=?", new String[]{hubId}
        );
    }


    /* =====================================================
     * MESSAGES
     * ===================================================== */

    /**
     * Store a message authored on this device.
     * Used for both chat messages and emergency reports.
     */
    public synchronized void saveOwn(
            String id,
            String type,
            String content,
            String syncStatus
    ) {

        ContentValues values = new ContentValues();

        values.put("message_id", id);
        values.put("hub_id", hub());
        values.put("sender_id", node());
        values.put("timestamp", System.currentTimeMillis());
        values.put("message_type", type);
        values.put("content", content);
        values.put("ttl", 8);
        values.put("forward_count", 0);
        values.put("sync_status", syncStatus);

        getWritableDatabase().insertWithOnConflict(
                "messages", null, values, SQLiteDatabase.CONFLICT_REPLACE
        );

        markSeen(id);
    }

    /** Store a message that arrived over the BLE mesh. */
    public synchronized void saveReceived(
            String id,
            String senderId,
            String hubId,
            String type,
            String content,
            long timestamp,
            int ttl
    ) {

        ContentValues values = new ContentValues();

        values.put("message_id", id);
        values.put("hub_id", hubId);
        values.put("sender_id", senderId);
        values.put("timestamp", timestamp);
        values.put("message_type", type);
        values.put("content", content);
        values.put("ttl", ttl);
        values.put("forward_count", 1);
        values.put("sync_status", "RECEIVED");

        getWritableDatabase().insertWithOnConflict(
                "messages", null, values, SQLiteDatabase.CONFLICT_IGNORE
        );

        markSeen(id);
        touchMember(hubId, senderId);
    }

    public synchronized void updateSyncStatus(String id, String status) {

        ContentValues values = new ContentValues();
        values.put("sync_status", status);

        getWritableDatabase().update(
                "messages", values, "message_id=?", new String[]{id}
        );
    }

    /**
     * Conversation for a hub: chat bubbles and system notices only.
     * Emergency reports are deliberately excluded, they belong to the
     * main system rather than to the local conversation.
     */
    public synchronized List<ChatMessage> conversation(String hubId) {

        List<ChatMessage> list = new ArrayList<>();

        if (hubId == null || hubId.trim().isEmpty()) {
            return list;
        }

        Cursor cursor = getReadableDatabase().query(
                "messages",
                new String[]{
                        "message_id",
                        "sender_id",
                        "message_type",
                        "content",
                        "timestamp",
                        "sync_status"
                },
                "hub_id=? AND (message_type=? OR message_type=?)",
                new String[]{hubId, TYPE_CHAT, TYPE_SYSTEM},
                null, null,
                "timestamp ASC"
        );

        try {
            while (cursor.moveToNext()) {

                ChatMessage message = new ChatMessage();

                message.id = cursor.getString(0);
                message.senderId = cursor.getString(1);
                message.type = cursor.getString(2);
                message.content = cursor.getString(3);
                message.timestamp = cursor.getLong(4);
                message.status = cursor.getString(5);

                list.add(message);
            }
        } finally {
            cursor.close();
        }

        return list;
    }

    /** Emergency reports still waiting to reach the main system. */
    public synchronized List<String[]> queuedReports() {

        List<String[]> list = new ArrayList<>();

        Cursor cursor = getReadableDatabase().query(
                "messages",
                new String[]{"message_id", "message_type", "content"},
                "sync_status=?",
                new String[]{"QUEUED"},
                null, null,
                "timestamp ASC",
                "20"
        );

        try {
            while (cursor.moveToNext()) {
                list.add(new String[]{
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2)
                });
            }
        } finally {
            cursor.close();
        }

        return list;
    }

    public synchronized int reportCount() {
        return countWhere(
                "message_type NOT IN (?,?,?)",
                new String[]{TYPE_CHAT, TYPE_SYSTEM, TYPE_PRESENCE}
        );
    }

    private int countWhere(String where, String[] args) {

        Cursor cursor = getReadableDatabase().query(
                "messages",
                new String[]{"COUNT(*)"},
                where, args,
                null, null, null
        );

        try {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } finally {
            cursor.close();
        }

        return 0;
    }


    /* =====================================================
     * DE-DUPLICATION
     * ===================================================== */

    public synchronized void markSeen(String id) {

        ContentValues values = new ContentValues();
        values.put("message_id", id);
        values.put("seen_at", System.currentTimeMillis());

        getWritableDatabase().insertWithOnConflict(
                "seen", null, values, SQLiteDatabase.CONFLICT_REPLACE
        );
    }

    public synchronized boolean alreadySeen(String id) {

        if (id == null || id.isEmpty()) {
            return false;
        }

        Cursor cursor = getReadableDatabase().query(
                "seen",
                new String[]{"message_id"},
                "message_id=?",
                new String[]{id},
                null, null, null
        );

        try {
            return cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    public boolean messageExists(String id) {
        return alreadySeen(id);
    }

    /**
     * Drop very old de-duplication records.
     *
     * Presence heartbeats mean this table grows steadily, and a message id
     * that has not been heard for a day is never going to arrive again.
     */
    public synchronized void pruneSeen(long maxAgeMs) {
        getWritableDatabase().delete(
                "seen",
                "seen_at < ?",
                new String[]{String.valueOf(System.currentTimeMillis() - maxAgeMs)}
        );
    }


    /* =====================================================
     * OUTBOX  (store and forward)
     * ===================================================== */

    public synchronized void enqueueOutbox(
            String messageId,
            String payload,
            String sourceAddr
    ) {

        ContentValues values = new ContentValues();

        values.put("message_id", messageId);
        values.put("payload", payload);
        values.put("source_addr", sourceAddr == null ? "" : sourceAddr);
        values.put("created_at", System.currentTimeMillis());
        values.put("rounds", 0);

        getWritableDatabase().insertWithOnConflict(
                "outbox", null, values, SQLiteDatabase.CONFLICT_IGNORE
        );
    }

    public synchronized List<OutboxItem> outbox() {

        List<OutboxItem> list = new ArrayList<>();

        Cursor cursor = getReadableDatabase().query(
                "outbox",
                new String[]{
                        "message_id", "payload", "source_addr", "created_at", "rounds"
                },
                null, null, null, null,
                "created_at ASC",
                "50"
        );

        try {
            while (cursor.moveToNext()) {

                OutboxItem item = new OutboxItem();

                item.messageId = cursor.getString(0);
                item.payload = cursor.getString(1);
                item.sourceAddr = cursor.getString(2);
                item.createdAt = cursor.getLong(3);
                item.rounds = cursor.getInt(4);

                list.add(item);
            }
        } finally {
            cursor.close();
        }

        return list;
    }

    public synchronized int outboxSize() {

        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM outbox", null
        );

        try {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } finally {
            cursor.close();
        }

        return 0;
    }

    public synchronized void bumpRounds(String messageId) {
        getWritableDatabase().execSQL(
                "UPDATE outbox SET rounds = rounds + 1 WHERE message_id=?",
                new Object[]{messageId}
        );
    }

    public synchronized void removeFromOutbox(String messageId) {
        getWritableDatabase().delete(
                "outbox", "message_id=?", new String[]{messageId}
        );
    }

    /** Drop queued messages older than the given age. */
    public synchronized void expireOutbox(long maxAgeMs) {
        getWritableDatabase().delete(
                "outbox",
                "created_at < ?",
                new String[]{String.valueOf(System.currentTimeMillis() - maxAgeMs)}
        );
    }


    /* =====================================================
     * DELIVERY TRACKING
     * ===================================================== */

    public synchronized void markDelivered(String messageId, String peerAddr) {

        ContentValues values = new ContentValues();
        values.put("message_id", messageId);
        values.put("peer_addr", peerAddr);

        getWritableDatabase().insertWithOnConflict(
                "delivered", null, values, SQLiteDatabase.CONFLICT_IGNORE
        );
    }

    public synchronized boolean wasDelivered(String messageId, String peerAddr) {

        Cursor cursor = getReadableDatabase().query(
                "delivered",
                new String[]{"message_id"},
                "message_id=? AND peer_addr=?",
                new String[]{messageId, peerAddr},
                null, null, null
        );

        try {
            return cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    public synchronized int deliveryCount(String messageId) {

        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM delivered WHERE message_id=?",
                new String[]{messageId}
        );

        try {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } finally {
            cursor.close();
        }

        return 0;
    }


    /* =====================================================
     * MAINTENANCE
     * ===================================================== */

    /** Wipe conversation and mesh state for a hub, keep reports. */
    public synchronized void clearConversation(String hubId) {

        getWritableDatabase().delete(
                "messages",
                "hub_id=? AND (message_type=? OR message_type=?)",
                new String[]{hubId, TYPE_CHAT, TYPE_SYSTEM}
        );
    }
}
