package com.example.disasterresponse;

import java.util.List;

/**
 * Callbacks from {@link MeshEngine}. Always delivered on the UI thread.
 */
public interface MeshListener {

    /** Human readable mesh status for the status panel. */
    void onMeshStatus(String status);

    /** A chat message was stored, either sent locally or received. */
    void onChatChanged();

    /** Nearby peer list or hub membership changed. */
    void onPeersChanged(List<String> peerNames);

    /** Delivery queue size changed. */
    void onOutboxChanged(int pending);
}
