package com.example.disasterresponse;

import android.content.Context;
import android.net.Network;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Finds the main system on the local network.
 *
 * <p>A phone cannot guess the address of the PC running the backend, and
 * typing it by hand is the usual reason reports never arrive. The backend
 * answers a UDP broadcast with its own address, so the app can configure
 * itself.</p>
 *
 * <p>This is a plain UDP broadcast rather than mDNS because it needs no
 * extra library and no multicast lock, which some Android builds refuse to
 * grant.</p>
 */
public class BackendLocator {

    private static final String TAG = "DISASTER_LOCATE";

    private static final int DISCOVERY_PORT = 8765;
    private static final String REQUEST = "DISASTER_DISCOVER";
    private static final String SERVICE = "AI-DISASTER-RESPONSE";

    /** Result of a successful discovery. */
    public static class Found {
        public String host;
        public int port;
        public String syncUrl;
        public String baseUrl;
    }

    /**
     * Broadcast on the local network and wait for the backend to answer.
     * Blocking: never call this from the main thread.
     *
     * @param context   used to bind the socket to Wi-Fi specifically, so the
     *                  broadcast still goes out over Wi-Fi even when the
     *                  phone also has mobile data turned on
     * @param timeoutMs how long to listen in total
     * @return the backend that answered, or null
     */
    public static Found discover(Context context, int timeoutMs) {

        DatagramSocket socket = null;

        try {
            socket = new DatagramSocket();

            NetworkUtils.bindSocket(NetworkUtils.wifiNetwork(context), socket);

            socket.setBroadcast(true);
            socket.setSoTimeout(600);

            byte[] request = REQUEST.getBytes(StandardCharsets.UTF_8);

            for (InetAddress target : broadcastAddresses()) {

                try {
                    socket.send(new DatagramPacket(
                            request, request.length, target, DISCOVERY_PORT
                    ));
                } catch (Exception ignored) {
                    /* Some interfaces refuse broadcast; try the rest. */
                }
            }

            long deadline = System.currentTimeMillis() + timeoutMs;

            while (System.currentTimeMillis() < deadline) {

                byte[] buffer = new byte[2048];
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);

                try {
                    socket.receive(reply);
                } catch (Exception timeout) {
                    continue;
                }

                String body = new String(
                        reply.getData(), 0, reply.getLength(),
                        StandardCharsets.UTF_8
                );

                Found found = parse(body, reply.getAddress().getHostAddress());

                if (found != null) {
                    Log.d(TAG, "Main system found at " + found.baseUrl);
                    return found;
                }
            }

        } catch (Exception error) {
            Log.d(TAG, "Discovery failed", error);

        } finally {
            if (socket != null) {
                socket.close();
            }
        }

        return null;
    }

    private static Found parse(String body, String senderAddress) {

        try {
            JSONObject object = new JSONObject(body);

            if (!SERVICE.equals(object.optString("service"))) {
                return null;
            }

            Found found = new Found();

            /*
             * Prefer the address the packet actually came from. The host the
             * server reports can be wrong when the PC has several adapters,
             * such as a VirtualBox or WSL interface.
             */
            found.host = senderAddress != null && !senderAddress.isEmpty()
                    ? senderAddress
                    : object.optString("host");

            found.port = object.optInt("port", 8000);

            String path = object.optString("sync", "/sync");

            found.baseUrl = "http://" + found.host + ":" + found.port;
            found.syncUrl = found.baseUrl + path;

            return found;

        } catch (Exception error) {
            return null;
        }
    }

    /** Every broadcast address this device can reach, plus the global one. */
    private static List<InetAddress> broadcastAddresses() {

        List<InetAddress> targets = new ArrayList<>();

        try {
            List<NetworkInterface> interfaces =
                    Collections.list(NetworkInterface.getNetworkInterfaces());

            for (NetworkInterface each : interfaces) {

                if (each.isLoopback() || !each.isUp()) {
                    continue;
                }

                for (InterfaceAddress address : each.getInterfaceAddresses()) {

                    InetAddress broadcast = address.getBroadcast();

                    if (broadcast != null) {
                        targets.add(broadcast);
                    }
                }
            }

        } catch (Exception ignored) {
        }

        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }

        return targets;
    }
}
