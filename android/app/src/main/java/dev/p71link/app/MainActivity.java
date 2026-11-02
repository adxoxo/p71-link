package dev.p71link.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayDeque;

/** Single activity: four tabs plus a stack of detail pages. */
public final class MainActivity extends Activity {
    interface Page {
        View build(MainActivity a);

        default void onShown(MainActivity a) { }

        default void onActivityResult(MainActivity a, int request, int result, Intent data) { }

        /** The page left the screen (popped, replaced by a tab, or the app stopped). */
        default void onHidden(MainActivity a) { }
    }

    static final int REQ_BT = 1, REQ_NOTIFY = 2, REQ_CALLS = 4;
    static final int PICK_PHOTO = 10, PICK_FACE_FILE = 11, EXPORT = 12;

    private final Page[] tabs = {new WatchPage(), new FacesPage(), new HealthPage(), new MorePage()};
    private final String[] tabNames = {"Watch", "Faces", "Health", "More"};
    private final ArrayDeque<Page> stack = new ArrayDeque<>();
    private int tab;
    private FrameLayout content;
    private LinearLayout nav;
    private ScrollView scroll;
    private Runnable afterPermission;
    private final Runnable listener = this::render;
    private final OnBackInvokedCallback back = this::popPage;
    private boolean backRegistered;
    WatchManager watch;
    Store store;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        P71App app = (P71App) getApplication();
        watch = app.watch;
        store = app.store;
        if (saved != null) tab = saved.getInt("tab", 0);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        View divider = new View(this);
        divider.setBackgroundColor(Ui.DIVIDER);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            content.setPadding(bars.left, bars.top, bars.right, 0);
            nav.setPadding(bars.left, 0, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        buildNav();
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    @Override
    protected void onStart() {
        super.onStart();
        watch.addListener(listener);
        current().onShown(this);
        render();
    }

    @Override
    protected void onStop() {
        current().onHidden(this);
        watch.removeListener(listener);
        super.onStop();
    }

    private Page current() { return stack.isEmpty() ? tabs[tab] : stack.peek(); }

    private void buildNav() {
        nav.removeAllViews();
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            TextView t = Ui.text(this, tabNames[i], 15, i == tab && stack.isEmpty() ? Ui.ACCENT : Ui.TEXT2);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(Ui.dp(this, 64));
            t.setContentDescription(tabNames[i] + (i == tab ? ", selected" : ""));
            t.setOnClickListener(v -> selectTab(index));
            nav.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
    }

    void selectTab(int index) {
        current().onHidden(this);
        stack.clear();
        updateBack();
        tab = index;
        buildNav();
        scrollTo(0);
        tabs[tab].onShown(this);
        render();
    }

    void push(Page p) {
        current().onHidden(this);
        stack.push(p);
        updateBack();
        buildNav();
        scrollTo(0);
        p.onShown(this);
        render();
    }

    void popPage() {
        if (stack.isEmpty()) return;
        stack.pop().onHidden(this);
        updateBack();
        buildNav();
        scrollTo(0);
        current().onShown(this);
        render();
    }

    private void updateBack() {
        OnBackInvokedDispatcher d = getOnBackInvokedDispatcher();
        if (!stack.isEmpty() && !backRegistered) {
            d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back);
            backRegistered = true;
        } else if (stack.isEmpty() && backRegistered) {
            d.unregisterOnBackInvokedCallback(back);
            backRegistered = false;
        }
    }

    private int pendingScroll = -1;

    private void scrollTo(int y) { pendingScroll = y; }

    /** Rebuild the visible page from current state. Scroll position is kept. */
    void render() {
        int y = pendingScroll >= 0 ? pendingScroll : scroll == null ? 0 : scroll.getScrollY();
        pendingScroll = -1;
        content.removeAllViews();
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = Ui.column(this);
        int p = Ui.dp(this, 20);
        page.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 28));
        if (!stack.isEmpty()) {
            TextView backRow = Ui.text(this, "‹  Back", 16, Ui.ACCENT);
            backRow.setMinHeight(Ui.dp(this, 48));
            backRow.setGravity(Gravity.CENTER_VERTICAL);
            backRow.setContentDescription("Back");
            backRow.setOnClickListener(v -> popPage());
            page.addView(backRow);
        }
        page.addView(current().build(this));
        scroll.addView(page);
        content.addView(scroll);
        final int target = y;
        scroll.post(() -> scroll.scrollTo(0, target));
    }

    // ---- Permissions only when a feature needs them ----

    boolean hasBluetoothPermission() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    void withBluetooth(Runnable r) {
        if (hasBluetoothPermission()) {
            r.run();
            return;
        }
        afterPermission = r;
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
    }

    void withNotificationPermission(Runnable r) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            r.run();
            return;
        }
        afterPermission = r;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    void withCallPermission(Runnable r) {
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            r.run();
            return;
        }
        afterPermission = r;
        requestPermissions(new String[]{Manifest.permission.ANSWER_PHONE_CALLS}, REQ_CALLS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        boolean all = results.length > 0;
        for (int r : results) all &= r == PackageManager.PERMISSION_GRANTED;
        Runnable next = afterPermission;
        afterPermission = null;
        if (all && next != null) next.run();
        else if (code == REQ_BT) toast("Nearby devices permission is needed to reach the watch.");
        else if (code == REQ_CALLS) toast("Call permission is needed to end calls from the watch.");
        else if (code == REQ_NOTIFY && next != null) next.run(); // The link works without its own notification.
        render();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        current().onActivityResult(this, request, result, data);
        render();
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    AlertDialog.Builder dialog() { return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert); }

    void confirm(String title, String message, String action, Runnable yes) {
        dialog().setTitle(title).setMessage(message).setNegativeButton("Cancel", null)
                .setPositiveButton(action, (d, w) -> yes.run()).show();
    }
}
