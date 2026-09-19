package com.example.disasterresponse;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

/**
 * Finds the phone's current Wi-Fi network so BackendLocator and BackendSync
 * can bind traffic to it explicitly.
 *
 * <p>The main system runs on a LAN address, but a phone with mobile data
 * also turned on does not always send app traffic over Wi-Fi by default —
 * Android's default network can be data, particularly when the Wi-Fi has no
 * validated internet access, which is exactly the case for a Wi-Fi that
 * only reaches a local PC. Binding to the Wi-Fi network removes that
 * ambiguity: the UDP discovery broadcast and the HTTP upload both go out
 * the interface that can actually reach the PC.</p>
 */
public class NetworkUtils {

    private static final String TAG = "DISASTER_NET";

    /** The phone's current Wi-Fi network, or null if not connected to one. */
    public static Network wifiNetwork(Context context) {

        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);

            if (manager == null) {
                return null;
            }

            for (Network network : manager.getAllNetworks()) {

                NetworkCapabilities capabilities =
                        manager.getNetworkCapabilities(network);

                if (capabilities != null && capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI)) {
                    return network;
                }
            }

        } catch (Exception error) {
            Log.d(TAG, "Could not enumerate networks", error);
        }

        return null;
    }

    /**
     * Bind this socket to the Wi-Fi network when one exists.
     * Safe to call with a null network — does nothing.
     */
    public static void bindSocket(Network wifi, java.net.DatagramSocket socket) {

        if (wifi == null || socket == null) {
            return;
        }

        try {
            wifi.bindSocket(socket);
        } catch (Exception error) {
            Log.d(TAG, "Could not bind UDP socket to Wi-Fi", error);
        }
    }

    /**
     * True when this URL points at a private/local address — the only kind
     * that LAN discovery could ever find or fix. A public host (a tunnel or
     * a cloud deployment) is either reachable or it isn't; broadcasting on
     * Wi-Fi never helps and only wastes several seconds during an emergency
     * report.
     */
    public static boolean isLocalAddress(String url) {

        if (url == null) {
            return true;
        }

        try {
            String host = new java.net.URL(url).getHost();

            if (host == null) {
                return true;
            }

            return host.equals("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("10.0.2.2")
                    || host.startsWith("192.168.")
                    || host.startsWith("10.")
                    || isPrivate172(host)
                    || host.endsWith(".local");

        } catch (Exception malformed) {
            return true;
        }
    }

    private static boolean isPrivate172(String host) {

        if (!host.startsWith("172.")) {
            return false;
        }

        String[] parts = host.split("\\.");

        if (parts.length < 2) {
            return false;
        }

        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException notNumeric) {
            return false;
        }
    }

    /** Open a URL connection routed over the Wi-Fi network when one exists,
     * falling back to the default network otherwise.
     */
    public static java.net.URLConnection openViaWifi(
            Context context, java.net.URL url) throws java.io.IOException {

        Network wifi = wifiNetwork(context);

        if (wifi != null) {
            try {
                return wifi.openConnection(url);
            } catch (Exception error) {
                Log.d(TAG, "Wi-Fi-bound connection failed, using default", error);
            }
        }

        return url.openConnection();
    }
}
