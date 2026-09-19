package com.example.disasterresponse;

/**
 * A message queued for delivery to nearby devices.
 *
 * sourceAddr is the BLE address the message arrived from, so a relayed
 * message is never sent straight back to the device that supplied it.
 */
public class OutboxItem {

    public String messageId;
    public String payload;
    public String sourceAddr;
    public long createdAt;
    public int rounds;
}
