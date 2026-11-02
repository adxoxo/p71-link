package dev.p71link.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import dev.p71link.protocol.Contacts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Watch contacts. The list lives on this phone. Sending replaces the whole list on the watch;
 * the watch list cannot be read back, so this phone list is the only copy the app keeps.
 */
final class ContactsPage implements MainActivity.Page {
    static final int PICK_CONTACT = 20;
    private boolean sending;
    private int sent, total;
    private String message;
    private boolean error;

    static List<Contacts.Contact> load(Store s) {
        List<Contacts.Contact> out = new ArrayList<>();
        String raw = s.get("contacts");
        if (raw == null) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                try {
                    out.add(new Contacts.Contact(o.getString("name"), o.getString("number")));
                } catch (IllegalArgumentException ignored) {
                    // Skip an invalid stored entry.
                }
            }
        } catch (JSONException ignored) {
            // Damaged list: start empty, nothing is sent.
        }
        return out;
    }

    static void save(Store s, List<Contacts.Contact> list) {
        JSONArray a = new JSONArray();
        try {
            for (Contacts.Contact c : list) a.put(new JSONObject().put("name", c.name).put("number", c.number));
        } catch (JSONException ignored) {
            return;
        }
        s.put("contacts", a.toString());
    }

    @Override
    public View build(MainActivity a) {
        WatchManager w = a.watch;
        List<Contacts.Contact> list = load(a.store);
        int max = w.caps == null ? 20 : w.caps.maximumContacts;
        LinearLayout page = Ui.column(a);
        page.addView(Ui.title(a, "Contacts"));
        page.addView(Ui.subtitle(a, "Choose who appears on the watch. Up to " + max + " contacts."));
        Ui.add(page, Ui.buttonPair(a,
                Ui.button(a, "Add from phone", true, v -> {
                    Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
                    a.startActivityForResult(i, PICK_CONTACT);
                }),
                Ui.button(a, "Type one", false, v -> manual(a))), 0);
        page.addView(Ui.section(a, list.size() + " on this phone"));
        if (list.isEmpty()) page.addView(Ui.status(a, "No contacts chosen yet.", Ui.TEXT2));
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            Contacts.Contact c = list.get(i);
            String sent = Contacts.sentName(c);
            Ui.add(page, Ui.row(a, sent, c.number + (sent.equals(c.name) ? "" : " · shortened for the watch from \"" + c.name + "\""), null, sending ? null : v -> a.confirm("Remove " + c.name + "?",
                    "It is removed from this list. The watch changes the next time you send the list.", "Remove", () -> {
                        List<Contacts.Contact> next = new ArrayList<>(list);
                        next.remove(index);
                        save(a.store, next);
                        a.render();
                    })), i == 0 ? 0 : 8);
        }
        if (sending) {
            page.addView(Ui.status(a, "Sending… " + sent + " of " + total, Ui.TEXT2));
            ProgressBar p = Ui.progress(a);
            p.setMax(Math.max(total, 1));
            p.setProgress(sent);
            Ui.add(page, p, 4);
        } else {
            Ui.add(page, Ui.button(a, "Send to watch", true, v -> {
                if (!w.connected()) {
                    message = "Connect the watch first.";
                    error = true;
                    a.render();
                    return;
                }
                String text = list.isEmpty()
                        ? "The watch contact list is replaced with an empty list."
                        : "The watch contact list is replaced with these " + list.size() + " contacts.";
                a.confirm("Replace watch contacts?", text + " Contacts already on the watch cannot be read back.", "Replace",
                        () -> send(a, list));
            }), 16);
        }
        if (message != null) page.addView(Ui.status(a, message, error ? Ui.ERROR : Ui.TEXT2));
        page.addView(Ui.note(a, "Names are shown as the watch receives them. Numbers longer than the watch allows are not accepted. The list stays on this phone."));
        return page;
    }

    private void send(MainActivity a, List<Contacts.Contact> list) {
        sending = true;
        sent = 0;
        total = 0;
        message = null;
        a.render();
        a.watch.uploadContacts(list, (d, n) -> {
            sent = d;
            total = n;
            a.render();
        }, new WatchManager.Result<Void>() {
            @Override public void ok(Void v) { sending = false; message = "The watch accepted the contact list."; error = false; a.render(); }
            @Override public void fail(String m) { sending = false; message = m + " The watch list may be incomplete."; error = true; a.render(); }
        });
    }

    private void manual(MainActivity a) {
        EditText name = new EditText(a);
        name.setHint("Name");
        EditText number = new EditText(a);
        number.setHint("Phone number");
        number.setInputType(InputType.TYPE_CLASS_PHONE);
        LinearLayout box = Ui.column(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, p / 2, p, 0);
        box.addView(name);
        box.addView(number);
        a.dialog().setTitle("Add contact").setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (d, w) -> add(a, name.getText().toString(), number.getText().toString())).show();
    }

    private void add(MainActivity a, String name, String number) {
        List<Contacts.Contact> list = load(a.store);
        int max = a.watch.caps == null ? 20 : a.watch.caps.maximumContacts;
        if (list.size() >= max) {
            message = "The watch holds up to " + max + " contacts.";
            error = true;
            return;
        }
        try {
            list.add(new Contacts.Contact(name, number));
        } catch (IllegalArgumentException e) {
            message = e.getMessage() != null && e.getMessage().contains("longer") ? e.getMessage() : "Enter a name and a phone number.";
            error = true;
            return;
        }
        save(a.store, list);
        message = null;
    }

    @Override
    public void onActivityResult(MainActivity a, int request, int result, Intent data) {
        if (request != PICK_CONTACT || result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        String[] cols = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor c = a.getContentResolver().query(data.getData(), cols, null, null, null)) {
            if (c != null && c.moveToFirst()) add(a, c.getString(0), c.getString(1));
        } catch (RuntimeException e) {
            message = "That contact could not be read.";
            error = true;
        }
    }
}
