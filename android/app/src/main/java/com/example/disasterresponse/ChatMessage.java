package com.example.disasterresponse;

/**
 * One entry in the Local Emergency Chat.
 */
public class ChatMessage {

    public String id;
    public String senderId;
    public String type;
    public String content;
    public long timestamp;
    public String status;

    public boolean isSystem() {
        return EmergencyDb.TYPE_SYSTEM.equals(type);
    }

    public boolean isMine(String myNodeId) {
        return myNodeId != null && myNodeId.equals(senderId);
    }
}
