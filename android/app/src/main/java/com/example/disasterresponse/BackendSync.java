package com.example.disasterresponse;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Delivers Emergency Reports to the main system on the PC.
 *
 * <p>Reports are stored first and uploaded afterwards, so nothing is lost
 * while the network is down. Three things make delivery actually work on a
 * real phone rather than only on the emulator:</p>
 *
 * <ul>
 *   <li><b>Discovery.</b> If the configured address fails, the app asks the
 *       network who the main system is instead of giving up.</li>
 *   <li><b>Patience.</b> Long timeouts and several attempts with a growing
 *       gap, so a weak connection still gets the report through.</li>
 *   <li><b>Retry on reconnect.</b> The queue is flushed whenever a network
 *       becomes available again, not only when the screen is reopened.</li>
 * </ul>
 */
public class BackendSync {

    private static final String TAG = "DISASTER_SYNC";

    /** Generous on purpose: a weak connection is still a usable one. */
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    private static final int MAX_ATTEMPTS = 3;
    private static final int DISCOVERY_TIMEOUT_MS = 2500;

    /** Address that only ever works on the Android emulator. */
    private static final String EMULATOR_URL = "http://10.0.2.2:8000/sync";

    public interface Callback {
        void onResult(boolean success, String detail);
    }

    private final Context context;
    private final EmergencyDb db;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Stops overlapping flushes from uploading the same report twice. */
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    public BackendSync(Context context, EmergencyDb db) {
        this.context = context.getApplicationContext();
        this.db = db;
    }

    /* =====================================================
     * NETWORK STATE
     * ===================================================== */

    /**
     * True when this device has any usable network.
     *
     * <p>Deliberately not requiring a <em>validated</em> internet
     * connection: the main system usually sits on the same Wi-Fi, and a
     * router with no internet uplink must still be able to carry a
     * report across the local network.</p>
     */
    public boolean isOnline() {

        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);

            if (manager == null) {
                return false;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                Network network = manager.getActiveNetwork();

                if (network == null) {
                    return false;
                }

                NetworkCapabilities capabilities =
                        manager.getNetworkCapabilities(network);

                if (capabilities == null) {
                    return false;
                }

                return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            }

            return manager.getActiveNetworkInfo() != null
                    && manager.getActiveNetworkInfo().isConnected();

        } catch (Exception error) {
            return false;
        }
    }

    /* =====================================================
     * ADDRESS RESOLUTION
     * ===================================================== */

    /**
     * Ask the network where the main system is and remember the answer.
     * Blocking: call from a background thread.
     *
     * @return the discovered sync URL, or null
     */
    public String locateAndRemember() {

        BackendLocator.Found found =
                BackendLocator.discover(context, DISCOVERY_TIMEOUT_MS);

        if (found == null) {
            return null;
        }

        db.setBackendUrl(found.syncUrl);

        return found.syncUrl;
    }

    /** Run discovery on a background thread and report back on the UI thread. */
    public void locate(Callback callback) {

        new Thread(() -> {

            String url = locateAndRemember();

            boolean ok = url != null;

            String detail = ok
                    ? "Main system found at " + url
                    : "No main system answered on this network. Check that the "
                    + "PC and phone are on the same Wi-Fi and that the backend "
                    + "is started with run_server.py.";

            if (callback != null) {
                handler.post(() -> callback.onResult(ok, detail));
            }

        }).start();
    }

    /** Check the configured main system and say plainly what happened. */
    public void testConnection(Callback callback) {

        new Thread(() -> {

            String base = baseOf(db.backendUrl());

            String detail;
            boolean ok = false;

            String probe = probe(base + "/health");

            if (probe == null) {
                ok = true;
                detail = "Connected to the main system at " + base;

            } else if (NetworkUtils.isLocalAddress(base)) {

                /* Only a local address is worth LAN-discovering for. */
                String discovered = locateAndRemember();

                if (discovered != null) {

                    String retry = probe(baseOf(discovered) + "/health");

                    if (retry == null) {
                        ok = true;
                        detail = "Found and connected to the main system at "
                                + baseOf(discovered);
                    } else {
                        detail = "Found " + baseOf(discovered)
                                + " but could not reach it: " + retry;
                    }

                } else {
                    detail = "Could not reach " + base + "\n\n" + probe
                            + "\n\nCheck that the backend was started with "
                            + "run_server.py, that both devices are on the same "
                            + "Wi-Fi, and that the firewall allows port 8000.\n\n"
                            + "If the phone is on a different network (mobile "
                            + "data, a different Wi-Fi, or physically elsewhere), "
                            + "a LAN address will never work — see the README "
                            + "section on reaching this backend from anywhere.";
                }

            } else {

                /*
                 * A public address (a tunnel or cloud deployment) is either
                 * reachable or it isn't — LAN discovery cannot help here.
                 */
                detail = "Could not reach " + base + "\n\n" + probe
                        + "\n\nThis looks like a public address, so check that "
                        + "the tunnel or cloud service is still running, and "
                        + "that the URL in Settings is still correct — tunnel "
                        + "URLs often change when restarted.";
            }

            final boolean result = ok;
            final String message = detail;

            if (callback != null) {
                handler.post(() -> callback.onResult(result, message));
            }

        }).start();
    }

    /** @return null when reachable, otherwise a description of the failure. */
    private String probe(String url) {

        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) openFor(url);

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);

            int code = connection.getResponseCode();

            if (code >= 200 && code < 300) {
                return null;
            }

            return "Server replied with HTTP " + code + ".";

        } catch (Exception error) {
            return error.getClass().getSimpleName()
                    + ": " + error.getMessage();

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String baseOf(String url) {

        if (url == null) {
            return "";
        }

        int index = url.indexOf("/sync");

        return index > 0 ? url.substring(0, index) : url;
    }

    /**
     * Open a connection to a report URL.
     *
     * A local address (the PC's LAN IP) is only reachable over Wi-Fi, so
     * traffic is pinned there explicitly — see NetworkUtils for why that
     * matters even when the phone is already "on" that Wi-Fi. A public
     * address (a tunnel or a cloud host) is reachable over any network with
     * internet, so it is left to Android's normal routing, which lets a
     * victim on mobile data, a different Wi-Fi, or a hotspot get through.
     */
    private java.net.URLConnection openFor(String url) throws Exception {

        URL parsed = new URL(url);

        if (NetworkUtils.isLocalAddress(url)) {
            return NetworkUtils.openViaWifi(context, parsed);
        }

        return parsed.openConnection();
    }

    /* =====================================================
     * SENDING
     * ===================================================== */

    /** Upload one report, retrying and rediscovering as needed. */
    public void send(String id, String type, String content, Callback callback) {

        new Thread(() -> {

            Result result = sendBlocking(id, type, content);

            if (callback != null) {
                handler.post(() -> callback.onResult(result.ok, result.detail));
            }

        }).start();
    }

    private static class Result {
        boolean ok;
        String detail;

        Result(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    /**
     * The actual upload. Runs on the calling thread.
     *
     * <p>On the first failure the address is re-discovered once, which is
     * what makes a fresh install work without anybody typing an IP.</p>
     */
    private Result sendBlocking(String id, String type, String content) {

        String url = db.backendUrl();

        /*
         * 10.0.2.2 is the emulator's alias for the host PC and does not
         * exist on a real phone, so never waste an attempt on it. Only try
         * discovery here when the configured address is itself local —
         * discovery can only ever find another LAN address, so it is
         * pointless (and slow) to run it while heading for a public one.
         */
        if (EMULATOR_URL.equals(url) || NetworkUtils.isLocalAddress(url)) {

            String discovered = locateAndRemember();

            if (discovered != null) {
                url = discovered;
            }
        }

        String lastError = "unknown error";
        boolean rediscovered = false;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {

            String error = post(url, id, type, content);

            if (error == null) {

                db.updateSyncStatus(id, "SYNCED");

                return new Result(true,
                        "Report delivered to the main system.");
            }

            lastError = error;

            Log.d(TAG, "Attempt " + attempt + " failed: " + error);

            /*
             * Address may simply be stale; ask the network once — but only
             * when a local address could plausibly be the fix. Retrying a
             * public address just needs patience, not a broadcast.
             */
            if (!rediscovered && NetworkUtils.isLocalAddress(url)) {

                rediscovered = true;

                String discovered = locateAndRemember();

                if (discovered != null && !discovered.equals(url)) {
                    url = discovered;
                    continue;
                }
            }

            if (attempt < MAX_ATTEMPTS) {
                sleep(attempt * 2000L);
            }
        }

        db.updateSyncStatus(id, "QUEUED");

        return new Result(false,
                "Main system unreachable. The report is stored and will retry."
        );
    }

    /** @return null on success, otherwise a description of the failure. */
    private String post(String url, String id, String type, String content) {

        if (url == null || url.trim().isEmpty()) {
            return "No main system address configured.";
        }

        HttpURLConnection connection = null;

        try {
            JSONObject json = new JSONObject();

            json.put("messageId", id);
            json.put("hubId", db.hub());
            json.put("senderNodeId", db.node());
            json.put("timestamp", new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US
            ).format(new Date()));
            json.put("type", type);
            json.put("content", content);
            json.put("ttl", 10);

            connection = (HttpURLConnection) openFor(url);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");

            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

            connection.setFixedLengthStreamingMode(body.length);

            OutputStream output = connection.getOutputStream();
            output.write(body);
            output.flush();
            output.close();

            int code = connection.getResponseCode();

            if (code >= 200 && code < 300) {
                drain(connection.getInputStream());
                return null;
            }

            drain(connection.getErrorStream());

            return "HTTP " + code;

        } catch (Exception error) {
            return error.getClass().getSimpleName() + ": " + error.getMessage();

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void drain(InputStream stream) {

        if (stream == null) {
            return;
        }

        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)
            );

            while (reader.readLine() != null) {
                /* Reading the body lets the connection be reused cleanly. */
            }

            reader.close();

        } catch (Exception ignored) {
        }
    }

    private void sleep(long millis) {

        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /* =====================================================
     * QUEUE
     * ===================================================== */

    /**
     * Retry every report still waiting. Called when the app returns to the
     * foreground and whenever a network becomes available.
     */
    public void flushQueue(Callback callback) {

        if (!isOnline()) {
            return;
        }

        if (!flushing.compareAndSet(false, true)) {
            return;
        }

        new Thread(() -> {

            int sent = 0;
            int failed = 0;

            try {
                List<String[]> queued = db.queuedReports();

                for (String[] row : queued) {

                    Result result = sendBlocking(row[0], row[1], row[2]);

                    if (result.ok) {
                        sent++;
                    } else {
                        failed++;
                        /* The network is clearly down; stop hammering it. */
                        break;
                    }
                }

            } finally {
                flushing.set(false);
            }

            if (sent == 0) {
                return;
            }

            final String detail = sent
                    + (sent == 1 ? " stored report" : " stored reports")
                    + " delivered to the main system.";

            if (callback != null) {
                handler.post(() -> callback.onResult(true, detail));
            }

        }).start();
    }
}
