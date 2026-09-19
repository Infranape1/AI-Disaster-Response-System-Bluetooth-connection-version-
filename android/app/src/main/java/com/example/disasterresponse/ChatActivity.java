package com.example.disasterresponse;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;

/**
 * The Local Emergency Chat.
 *
 * This is a group conversation shared by every device in the hub. There is no
 * pairing step and no device limit: whatever a node receives it also relays,
 * so a message spreads across the whole hub.
 */
public class ChatActivity extends AppCompatActivity implements MeshListener {

    private MeshEngine mesh;
    private EmergencyDb db;

    private ChatAdapter adapter;

    private ListView messageList;
    private LinearLayout emptyState;
    private LinearLayout messageComposer;

    private EditText input;
    private ImageView btnSend;
    private TextView chatHubId;
    private TextView chatSubtitle;
    private TextView meshBanner;

    private int pendingCount = 0;

    /** False when onCreate bailed out, since onResume still runs after finish(). */
    private boolean ready = false;

    /* =====================================================
     * LIFECYCLE
     * ===================================================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        mesh = MeshEngine.get(this);
        db = mesh.db();

        if (!db.hasHub()) {
            Toast.makeText(
                    this,
                    "Join or create an emergency hub first.",
                    Toast.LENGTH_LONG
            ).show();

            finish();
            return;
        }

        setContentView(R.layout.activity_chat);

        /* =================================================
         * FIND VIEWS
         * ================================================= */

        messageList = findViewById(R.id.messageList);
        emptyState = findViewById(R.id.emptyState);

        input = findViewById(R.id.input);
        btnSend = findViewById(R.id.btnSend);

        chatHubId = findViewById(R.id.chatHubId);
        chatSubtitle = findViewById(R.id.chatSubtitle);
        meshBanner = findViewById(R.id.meshBanner);

        /*
         * This ID is added to the composer LinearLayout in
         * activity_chat.xml.
         */
        messageComposer = findViewById(R.id.messageComposer);

        /* =================================================
         * SYSTEM BAR / NAVIGATION BAR HANDLING
         * ================================================= */

        setupWindowInsets();

        /* =================================================
         * CHAT ADAPTER
         * ================================================= */

        adapter = new ChatAdapter(this, db.node());
        messageList.setAdapter(adapter);

        chatHubId.setText(db.hub());

        /* =================================================
         * BUTTONS
         * ================================================= */

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        findViewById(R.id.btnMenu).setOnClickListener(v -> showMenu());

        btnSend.setOnClickListener(v -> send());

        btnSend.setEnabled(false);
        btnSend.setAlpha(0.45f);

        /* =================================================
         * TEXT INPUT
         * ================================================= */

        input.addTextChangedListener(new TextWatcher() {

            @Override
            public void beforeTextChanged(
                    CharSequence s,
                    int start,
                    int count,
                    int after
            ) {
                // Nothing required.
            }

            @Override
            public void onTextChanged(
                    CharSequence s,
                    int start,
                    int before,
                    int count
            ) {
                boolean hasText = s.toString().trim().length() > 0;

                btnSend.setEnabled(hasText);
                btnSend.setAlpha(hasText ? 1f : 0.45f);
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Nothing required.
            }
        });

        /* =================================================
         * LOAD INITIAL MESSAGES
         * ================================================= */

        loadMessages();

        ready = true;
    }

    /**
     * Keeps the composer above the Android navigation/gesture area.
     *
     * This is particularly important on newer Android versions where
     * applications can draw behind the system navigation bar.
     */
    private void setupWindowInsets() {

        if (messageComposer == null) {
            return;
        }

        /*
         * Remember the original padding so that we don't destroy
         * the visual spacing defined in activity_chat.xml.
         */
        final int originalLeft = messageComposer.getPaddingLeft();
        final int originalTop = messageComposer.getPaddingTop();
        final int originalRight = messageComposer.getPaddingRight();
        final int originalBottom = messageComposer.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(
                messageComposer,
                (view, windowInsets) -> {

                    /*
                     * Navigation/gesture bar inset.
                     *
                     * Example:
                     * navigation bar = 24dp
                     *
                     * The composer gets that extra bottom space so
                     * the input and send button aren't hidden behind
                     * Android's navigation controls.
                     */
                    Insets navigationInsets =
                            windowInsets.getInsets(
                                    WindowInsetsCompat.Type.navigationBars()
                            );

                    int bottomInset = navigationInsets.bottom;

                    view.setPadding(
                            originalLeft,
                            originalTop,
                            originalRight,
                            originalBottom + bottomInset
                    );

                    return windowInsets;
                }
        );

        /*
         * Ask Android to dispatch the current insets immediately.
         */
        ViewCompat.requestApplyInsets(messageComposer);
    }

    @Override
    protected void onResume() {

        super.onResume();

        if (!ready) {
            return;
        }

        mesh.addListener(this);

        /*
         * Announce ourselves so the other devices list this node
         * as a member as soon as the chat is opened.
         */
        mesh.sendPresence();

        loadMessages();
        updateHeader();
    }

    @Override
    protected void onPause() {

        super.onPause();

        if (ready) {
            mesh.removeListener(this);
        }
    }

    /* =====================================================
     * SENDING
     * ===================================================== */

    private void send() {

        String text = input.getText().toString().trim();

        if (text.isEmpty()) {
            return;
        }

        if (!db.hasHub()) {
            Toast.makeText(
                    this,
                    "No hub selected.",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        mesh.sendChat(text);

        /*
         * Clear the input after sending.
         */
        input.setText("");

        /*
         * If the mesh is currently inactive, the message is
         * still saved locally and will be delivered later.
         */
        if (!mesh.isActive()) {

            Toast.makeText(
                    this,
                    "Saved. It will be delivered once the Alert System is on.",
                    Toast.LENGTH_SHORT
            ).show();
        }

        loadMessages();
        scrollToBottom();
    }

    /* =====================================================
     * DATA
     * ===================================================== */

    private void loadMessages() {

        List<ChatMessage> messages =
                db.conversation(db.hub());

        adapter.replaceAll(messages);

        emptyState.setVisibility(
                messages.isEmpty()
                        ? View.VISIBLE
                        : View.GONE
        );
    }

    private void scrollToBottom() {

        if (adapter == null || adapter.getCount() == 0) {
            return;
        }

        messageList.post(() ->
                messageList.setSelection(
                        adapter.getCount() - 1
                )
        );
    }

    private void updateHeader() {

        int peers = mesh.onlinePeerCount();
        int members = db.members(db.hub()).size();

        StringBuilder subtitle = new StringBuilder();

        subtitle.append(
                members <= 1
                        ? "You only"
                        : members + " nodes"
        );

        if (peers > 0) {
            subtitle
                    .append(" · ")
                    .append(peers)
                    .append(" in range");
        }

        if (pendingCount > 0) {
            subtitle
                    .append(" · ")
                    .append(pendingCount)
                    .append(" queued");
        }

        chatSubtitle.setText(subtitle.toString());

        if (mesh.isActive()) {

            meshBanner.setVisibility(View.GONE);

        } else {

            meshBanner.setVisibility(View.VISIBLE);

            meshBanner.setText(
                    "Alert System is off. Messages are saved and sent when it is on."
            );
        }
    }

    /* =====================================================
     * MENU
     * ===================================================== */

    private void showMenu() {

        String[] options = {
                "Hub members",
                "Hub information",
                "Leave this hub",
                "Clear this conversation"
        };

        new AlertDialog.Builder(this)
                .setItems(options, (dialog, which) -> {

                    switch (which) {

                        case 0:
                            showMembers();
                            break;

                        case 1:
                            showHubInfo();
                            break;

                        case 2:
                            confirmLeave();
                            break;

                        case 3:
                            confirmClear();
                            break;
                    }
                })
                .show();
    }

    private void showMembers() {

        List<String> members = db.members(db.hub());
        List<String> inRange = mesh.peerNames();

        StringBuilder builder = new StringBuilder();

        builder.append("This device\n")
                .append(db.node())
                .append(
                        db.displayName().isEmpty()
                                ? ""
                                : " (" + db.displayName() + ")"
                )
                .append("\n\n");

        builder.append("Known nodes in this hub\n");

        boolean any = false;

        for (String member : members) {

            if (member.equals(db.node())) {
                continue;
            }

            builder
                    .append("• ")
                    .append(member)
                    .append("\n");

            any = true;
        }

        if (!any) {
            builder.append(
                    "No other node has been heard yet.\n"
            );
        }

        builder
                .append("\nRadios in range right now: ")
                .append(inRange.size());

        new AlertDialog.Builder(this)
                .setTitle("Hub Members")
                .setMessage(builder.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void showHubInfo() {

        new AlertDialog.Builder(this)
                .setTitle("Hub Information")
                .setMessage(
                        "Hub ID\n" +
                                db.hub() +
                                "\n\n" +

                                "Your node\n" +
                                db.node() +
                                "\n\n" +

                                "Every device that joins this hub receives the same " +
                                "messages. A device that is out of range is reached " +
                                "through the devices in between, so the group grows " +
                                "as more people join."
                )
                .setPositiveButton("OK", null)
                .show();
    }

    private void confirmLeave() {

        new AlertDialog.Builder(this)
                .setTitle("Leave this hub?")
                .setMessage(
                        "This device will stop receiving messages from " +
                                db.hub() +
                                ".\n\n" +
                                "You can scan and join another hub straight after."
                )
                .setPositiveButton("LEAVE", (dialog, which) -> {

                    mesh.leaveHub();

                    Toast.makeText(
                            this,
                            "Left the hub.",
                            Toast.LENGTH_SHORT
                    ).show();

                    startActivity(
                            new Intent(
                                    this,
                                    MainActivity.class
                            ).addFlags(
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            )
                    );

                    finish();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void confirmClear() {

        new AlertDialog.Builder(this)
                .setTitle("Clear conversation?")
                .setMessage(
                        "Messages stored on this device will be removed. " +
                                "Other devices keep their own copies."
                )
                .setPositiveButton("CLEAR", (dialog, which) -> {

                    db.clearConversation(db.hub());

                    loadMessages();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    /* =====================================================
     * MESH CALLBACKS
     * ===================================================== */

    @Override
    public void onMeshStatus(String status) {

        updateHeader();
    }

    @Override
    public void onChatChanged() {

        int before = adapter.getCount();

        loadMessages();

        if (adapter.getCount() > before) {
            scrollToBottom();
        }
    }

    @Override
    public void onPeersChanged(List<String> peerNames) {

        updateHeader();
    }

    @Override
    public void onOutboxChanged(int pending) {

        pendingCount = pending;

        updateHeader();
    }
}