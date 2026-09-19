package com.example.disasterresponse;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Renders the Local Emergency Chat.
 *
 * Own messages sit on the right, everyone else on the left with the sender
 * name above, and mesh notices are centred.
 */
public class ChatAdapter extends BaseAdapter {

    private final Context context;
    private final List<ChatMessage> items = new ArrayList<>();
    private final String myNodeId;

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    public ChatAdapter(Context context, String myNodeId) {
        this.context = context;
        this.myNodeId = myNodeId;
    }

    public void replaceAll(List<ChatMessage> messages) {
        items.clear();
        items.addAll(messages);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public ChatMessage getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {

        View view = convertView;

        if (view == null) {
            view = LayoutInflater.from(context)
                    .inflate(R.layout.item_message, parent, false);
        }

        ChatMessage message = getItem(position);

        TextView systemText = view.findViewById(R.id.systemText);
        LinearLayout bubbleRow = view.findViewById(R.id.bubbleRow);
        LinearLayout bubble = view.findViewById(R.id.bubble);
        TextView sender = view.findViewById(R.id.sender);
        TextView body = view.findViewById(R.id.body);
        TextView meta = view.findViewById(R.id.meta);

        if (message.isSystem()) {

            systemText.setVisibility(View.VISIBLE);
            bubbleRow.setVisibility(View.GONE);
            systemText.setText(message.content);

            return view;
        }

        systemText.setVisibility(View.GONE);
        bubbleRow.setVisibility(View.VISIBLE);

        boolean mine = message.isMine(myNodeId);

        bubbleRow.setGravity(mine ? Gravity.END : Gravity.START);

        bubble.setBackgroundResource(
                mine ? R.drawable.bubble_me : R.drawable.bubble_other
        );

        /* Cap bubble width so long messages stay readable. */
        int maxWidth = (int) (parent.getWidth() * 0.78f);

        if (maxWidth > 0) {
            body.setMaxWidth(maxWidth);
        }

        if (mine) {
            sender.setVisibility(View.GONE);
        } else {
            sender.setVisibility(View.VISIBLE);
            sender.setText(message.senderId);
        }

        body.setText(message.content);

        meta.setText(buildMeta(message, mine));
        meta.setTextColor(
                ContextCompat.getColor(
                        context,
                        mine ? R.color.text_muted : R.color.text_dim
                )
        );

        return view;
    }

    private String buildMeta(ChatMessage message, boolean mine) {

        String time = timeFormat.format(new Date(message.timestamp));

        if (!mine) {
            return time;
        }

        if ("DELIVERED".equals(message.status)) {
            return time + "  ✓ delivered";
        }

        return time + "  · queued";
    }
}
