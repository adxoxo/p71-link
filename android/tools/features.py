"""Generate FEATURES.json and FEATURES.md from one table. Run: python3 -I tools/features.py <project-root>."""
import json
import pathlib
import sys

# Column meanings:
#   advertised: what the P71J capability reply says (flag name), "always" for base protocol,
#               "not_advertised", or "watch_local" (on-watch utility with no companion command).
#   schema:     protocol evidence. static_sdk = decoded from the vendor app; captured = target frames recorded;
#               unresolved = fields not established; none = no companion command known.
#   target:     what the real watch has shown (before this app). none = untested on the target.
#   app:        implemented | partial | not_started | excluded (deliberately not offered) | n/a
#   automated:  offline test coverage in android/test.sh.
#   physical:   S23 Ultra + P71J acceptance with this app. Every row stays "open" until the coordinator records it.
F = []


def add(area, fid, name, advertised, schema, target, app, automated, physical="open", note=""):
    F.append(dict(area=area, id=fid, name=name, advertised=advertised, schema=schema, target=target, app=app,
                  automated=automated, physical=physical, note=note))


# ---- Connection and identity ----
add("Connection", "connect", "Direct BLE connect, bounded 15 s scan bound to exact name P71J_CEC9, explicit user start",
    "always", "captured", "nRF Connect and Pubu connected", "implemented", "queue/session tests, exact-name match",
    note="This build binds only P71J_CEC9; other P71 watches are ignored. A device picker is not implemented.", physical="live read via app: battery 50, firmware M71J_1.00.27_231223, 240x284 (coordinator)")
add("Connection", "stale-callbacks", "Epoch gate on posted main-thread session callbacks", "always", "n/a", "n/a",
    "implemented", "callback-order regression")
add("Connection", "session-invalidation", "Timeout or lost write closes session; new generation; no replay", "always",
    "n/a", "none", "implemented", "regressions: late reply, lost callback, fresh session")
add("Connection", "fragment-length", "2F fragment size, reset to 20 per session, capped by ATT payload", "always",
    "static_sdk", "2F frames present in capture, value not retained", "implemented", "packageLength, bulkChunk")
add("Connection", "stay-connected", "Optional connectedDevice foreground service; Connect restarts it, every Disconnect stops it",
    "always", "n/a", "none", "implemented", "none (Android runtime)")
add("Connection", "firmware", "Firmware revision via 180A/2A26", "always", "captured", "M71J_1.00.27_231223 read",
    "implemented", "none (GATT read)", physical="live read via app: battery 50, firmware M71J_1.00.27_231223, 240x284 (coordinator)")
add("Connection", "battery", "Battery percent (03)", "always", "captured", "38% reply captured", "implemented",
    "fixture, checksum, range", physical="live read via app: battery 50, firmware M71J_1.00.27_231223, 240x284 (coordinator)")
add("Connection", "capabilities", "Capability report from clock reply (01), 31 fields", "always", "captured",
    "two identical replies", "implemented", "31-field fixture", physical="live read via app: battery 50, firmware M71J_1.00.27_231223, 240x284 (coordinator)")
add("Connection", "phone-identity", "Phone model/platform frame (04)", "always", "captured", "reply 04 00 captured",
    "implemented", "capture fixture")
add("Connection", "disconnect-forget", "Disconnect, stop service, forget watch", "always", "n/a", "none", "implemented", "none")

# ---- Watch tab ----
add("Watch", "time-sync", "Set watch clock (01), explicit and optional on connect", "always", "captured",
    "capture reproduced byte-for-byte", "implemented", "two capture fixtures")
add("Watch", "dnd", "Scheduled DND switch and schedule (06), readback verified", "always", "captured",
    "enable/readback/restore passed", "implemented", "fixtures, invalid flag/hour, action mismatch",
    note="Notification suppression during quiet hours is untested.", physical="live via app: off-on-off, 22:00-08:00 kept, separate readback (coordinator); suppression untested")
add("Watch", "wrist-wake", "Raise to wake (05), options preserved", "always", "captured", "read reply captured",
    "implemented", "fixture, option preservation", physical="live via app: on-off-on with readback (coordinator); gesture untested")
add("Watch", "display-timeout", "Screen-on time (1F) within watch-reported min/max/step", "always", "captured",
    "read reply captured (5 s, 5-60 step 5)", "implemented", "fixture, range/step rejection, shared-field preservation", physical="live via app: 5-10-5 s with readback (coordinator); on-screen duration untested")
add("Watch", "time-format", "24-hour and metric (0A), profile fields preserved and compared", "always", "captured",
    "read and write captured", "implemented", "fixture, all-field readback comparison")
add("Watch", "temperature-unit", "Celsius/Fahrenheit (19)", "always", "static_sdk", "exchange present, payload not retained",
    "implemented", "codec")
add("Watch", "find-watch", "Find watch (50 55AA)", "always", "static_sdk", "none", "implemented", "frame",
    note="Vibration on the watch needs physical confirmation; no reply parser exists.")
add("Watch", "brightness", "Brightness (1B)", "always", "static_sdk", "read rejected 9B EE", "excluded",
    "rejection fixture", physical="n/a", note="Target rejects the read; no write is offered.")
add("Watch", "battery-saver", "Battery saver (76): read [0], write [1,on], then a later read reported as unconfirmed; YaWei-gated", "yawei",
    "static_sdk", "none", "implemented", "codec",
    note="A reading after a change cannot be tied to the change (no reply discriminator; the pause is not proof), so it is shown "
         "as reported, not stored. A standalone read on a new connection confirms the value. No battery-life gain is claimed.")
add("Watch", "display-style", "Display style / orientation / clock codecs (2A/29/12)", "always", "static_sdk",
    "none", "sdk_only_unproven", "none", physical="n/a",
    note="Pubu has no app caller for these; target use unproven. Not a missing Pubu feature. No speculative writes.")
add("Watch", "goals", "Daily step goal (21); other goal fields written back unchanged", "always", "static_sdk",
    "exchange present, payload not retained", "implemented", "round trip codec, all-field readback",
    note="Calorie/distance goal units unverified, so they are not editable. Write echo on this watch unverified.")
# ---- Faces ----
add("Faces", "face-list", "Installed face list (bulk 35) in watch order; record flag = delete eligible", "watch_faces", "static_sdk",
    "none before app", "implemented", "parser, delete-flag regression, fragment reassembly",
    physical="live via app (review6): list read with exactly one current row from the 75 index (coordinator)",
    note="The flag is delete eligibility, not current face. Delete itself is not offered. The list is read again after an upload or on Read again, not after a selection.")
add("Faces", "face-select", "Current face from one-based 75 index only; select row+1, then independent 75 readback", "watch_faces",
    "static_sdk", "none", "implemented", "index bounds, unknown when 75 absent/invalid",
    physical="live via app (review6): index 2 (b7df311af93311cf.di) to 1 and back to 2, each with separate readback; The tester saw it change and return",
    note="A failed readback after a write leaves the current face unknown. A new 35 list clears the old index until 75 answers. "
         "Apply re-resolves the pick against the list held at the click (same names, same order) and is refused otherwise.")
add("Faces", "face-picker", "Visual picker: Your faces and Discover, two-column cards in watch order, installed detail with Apply", "watch_faces",
    "n/a", "n/a", "implemented", "pick identity: reorder, add, remove, renamed row, cleared list, duplicate names",
    note="Installed titles are Watch face N by watch position; file names appear only in Details. The Current badge uses the 75 index only. "
         "List read, select and upload are manager-owned and exclude each other; a new session releases them. "
         "Any face, photo or Orbit transfer forgets the list, order and 75 index before it starts and reads them once after it ends.")
add("Faces", "face-previews", "Installed-face catalog previews by exact file name, opt-in, same hardware only", "watch_faces",
    "server API observed", "6 of 6 installed names had one exact catalog match (2026-11-02)", "implemented",
    "exact-name identity, no case/substring/position match, duplicates, other hardware, coalesced loads, sticky failure, bounded decode, "
    "bounded preview lane with download progress under saturation",
    note="The watch lists names, never images. No vendor preview is bundled. Without one exact image the card says Preview unavailable. "
         "Failed images retry only on Retry previews. Catalog artwork may show sample numbers and is labelled Catalog preview. "
         "Live reliability is open: the user saw Preview unavailable on 0.2 cards, cause unconfirmed, investigation deferred by the user.")
add("Faces", "orbit-face", "Bundled Orbit background with the aquryu logo, offline, applied through the photo background transfer", "custom_wallpaper",
    "static_sdk", "none", "implemented", "asset 240x284 fully opaque, RGB565 wire size and header",
    physical="user applied Orbit from the 0.2 test build and confirmed on the watch: the dragon face appears",
    note="Shown before any upload. Apply needs the replace-photo confirmation and never selects a face. The image holds no time, date, battery or health values. "
         "Banding and fine-line contrast were not separately assessed. Position markers show only after Show markers in detail.")
add("Faces", "photo-face", "Photo crop with [time]/[battery]/[data] position markers, native 240x284 RGB565 upload (31/32/33)", "custom_wallpaper", "static_sdk",
    "server parameters 240x284 r50", "implemented", "RGB565 length/prefix/pixel, transfer sequencing, link loss",
    note="No wallpaper readback exists; an overwritten photo cannot be restored. Markers use the watch's stored layout when read, otherwise "
         "they are labelled illustrative defaults. A local copy is kept after the watch accepts a photo and is never linked to an installed slot.")
add("Faces", "face-import", "Import opaque .di face file (36/37/38), required status byte", "watch_faces", "static_sdk",
    "catalog sample is .di, 159752 bytes", "implemented", "init refusal, finish error, empty status, stop",
    note="Automatic activation after upload is unverified.")
add("Faces", "face-layout", "Photo face item positions and colours (bulk 3A), write then exact re-read", "custom_wallpaper",
    "static_sdk", "none", "implemented", "write/read codec, missing types rejected, write echo filtered",
    note="Vendor reads only clock colour; battery/data colours are raw bytes at the same layout. Layout restore does not restore a photo.")
add("Faces", "face-catalog", "Optional public catalog for M71J_V1.0 (free items, HTTPS, guest token in memory)", "watch_faces",
    "server API observed", "222 faces for M71J_V1.0", "implemented", "none (network)",
    note="User-opened only. Files sent unchanged as vendor faces. Catalog order is not the watch face order. Two-column pages of 20 with bounded preview decoding. "
         "Previews use a bounded lane (2 running, 6 queued, dropped on page change); list and downloads use a separate bounded lane. "
         "A download that outlives its page showing or watch session never starts a transfer.")
add("Faces", "face-delete", "Delete installed face (39)", "watch_faces", "static_sdk", "none", "excluded", "frame only",
    physical="n/a", note="Not offered: irreversible, not needed to replace Pubu for selection or upload.")
add("Faces", "lzo-encoding", "LZO1X block encoding (literal-only)", "watch_faces", "static_sdk", "none", "implemented",
    "independent lzo1x decode of 3076 cases", note="No compression gain; transfer speed unmeasured.")

# ---- App ----
add("App", "native-redesign", "Native four-tab redesign: grouped surfaces, icon tabs, unread settings shown as unknown, Health empty state with Read history",
    "always", "n/a", "n/a", "implemented", "none (Android UI)",
    note="Press feedback scales to 0.97 in 140 ms; detail push fades and rises 8 dp in 200 ms, pop fades in 160 ms; tabs and data updates do not animate. "
         "No movement plays when the system disables animations. Health Read history and Activity share one app-owned sync run.")

# ---- Health ----
add("Health", "hr-auto", "Automatic heart rate switch (16), raw interval preserved", "always", "captured",
    "enable/readback/restore passed", "implemented", "fixtures", note="Interval units unresolved; never edited.")
add("Health", "hr-manual", "Manual heart rate (69 [1,0], stop 6A [1,0,0]), one at a time; unconfirmed stop blocks until reconnect", "manual_heart_rate", "static_sdk", "none",
    "implemented", "outcomes, stop frame, lost-callback watchdog, busy gate, duplicate replies", note="Sensor accuracy is not claimed.")
add("Health", "bp-manual", "Manual blood pressure (69 [2,0])", "blood_pressure", "static_sdk", "none", "excluded",
    "raw outcome", physical="n/a",
    note="Pubu can send 120/80 defaults or stale values when no result arrives. Field meaning unproven; not offered.")
add("Health", "today-activity", "Today's step counter (48) with raw counters and Pubu display convention", "always", "static_sdk",
    "exchange present, payload not retained", "implemented", "big-endian decode",
    physical="live via app (integrity4): zero counters received, saved as zero with date/source/unit notes, exported, kept after force-stop and restart (coordinator)",
    note="Date is the phone date at sync. km = distance/1000, kcal = calories/1000 (Pubu convention, not physically validated); raw kept. No accuracy claim.")
add("Health", "day-totals", "Daily totals codec (07)", "always", "static_sdk", "none", "sdk_only_unproven",
    "two-packet reader, date mismatch", physical="n/a",
    note="Unused alternative in Pubu; daily history comes from quarter-hour records (43), which the app implements.")
add("Health", "step-detail", "Past six days from quarter-hour steps (43), sums calculated on phone", "always", "static_sdk",
    "exchanges present", "implemented", "header, order, total, late row, raw calorie field and x10 flag",
    note="km = distance/1000 and kcal = calories x flag /1000 follow the Pubu display convention, applied once; raw kept.")
add("Health", "hr-history", "Heart-rate day history (15)", "always", "static_sdk", "exchanges present", "implemented",
    "terminator keeps samples, order",
    note="Stored under the requested day; the watch-returned time value is kept raw, not interpreted.")
add("Health", "manual-history", "Manual heart-rate history list (bulk 28)", "app_measurement", "static_sdk", "none",
    "implemented", "record layout, minute range", note="Time = phone date minus watch day offset plus watch minute; recorded in each entry.")
add("Health", "sleep", "Sleep history (bulk 27): light 2, deep 3, awake 5 as classified by the watch", "new_sleep_protocol",
    "static_sdk", "none", "implemented", "record bounds, summary with unlabelled time and interval mismatch",
    note="Asleep = light + deep. Awake, unlabelled stage time, the stage sum, and the recorded span are shown separately; "
         "a mismatch is marked partial or inconsistent. No score. Phone-calculated sums.")
add("Health", "bp-auto", "Automatic blood-pressure schedule switch (0C)", "blood_pressure", "static_sdk",
    "exchange present, payload not retained", "excluded", "codec", physical="n/a",
    note="Pubu's switch is real, but its BP results are generated with random terms and age formulas. "
         "The app shows no BP results, so the switch is not offered and no BP readings are promised.")
add("Health", "cycle", "Cycle reminders (2B): user dates, lengths, period/fertile lead days 1-3, reminder time", "menstruation",
    "static_sdk", "none", "implemented", "binary layout, lead-day range, no wrap",
    note="Send-only: the watch has no read-back, so the screen says sent, not stored. Day counts are phone calculations. No predictions.")
add("Health", "export", "Local JSON export and delete, with source and calculation notes", "always", "n/a", "n/a", "implemented", "none",
    note="Missing fields stay absent; raw-unit fields are named ...Raw; local write failures are reported, never shown as saved.", physical="live SAF export via app, no oxygen keys (coordinator)")

# ---- More ----
add("More", "notify-forward", "Notification forwarding (72), empty allowlist, dedupe, bounded", "always", "captured",
    "test message displayed (user confirmed, nRF path)", "implemented", "fixture, multi-chunk, Unicode, 256 limit, queue",
    physical="'Hello from P71 Link' sent from the app (connection3) and seen on the watch by the tester; app-to-watch forwarding not tested; notification access not granted",
    note="Non-SMS apps use SMS type 1 with the app name in text. Only type 1 is target-confirmed. "
         "The app picker sees launcher apps only (launcher intent in the manifest queries element, no QUERY_ALL_PACKAGES).")
add("More", "calls", "Calls: incoming CallStyle rings (type 0); answered, cleared, or ended sends type 4; optional reject (11 action 1)", "always",
    "static_sdk", "none", "implemented", "state transitions: incoming to ongoing same key, outgoing, unknown, screening, removal, no caller text, ringing update without call type",
    note="Uses notification access and the CallStyle call type; dialers without it are not forwarded (no phone-state or call-log permission). "
         "An update of the ringing call that drops its call type clears the ringing. "
         "No caller text means a padded control chunk, not invented text. Reject asks for answer-calls permission when turned on. No live call tested.")
add("More", "find-phone", "Find phone (22): 1 start/restart 10 s alert, 2 stop, others ignored", "always", "static_sdk",
    "none", "implemented", "none (phone audio)", note="No reply is sent, as in the vendor app. Stops on disconnect.")
add("Excluded by user", "camera", "Remote camera (02)", "always", "static_sdk", "none", "excluded_by_user",
    "none", physical="n/a",
    note="The owner asked to leave the camera feature out. No camera screen, permission, or camera command is in the app. "
         "Photo backgrounds for faces use the system photo picker and need no camera permission.")
add("More", "alarms", "Alarm list (bulk 2C), whole-list write and exact re-read", "always", "static_sdk", "none",
    "implemented", "parse/write round trip, byte-identical untouched records, write-echo isolation",
    note="Weekday bit order taken from the vendor editor (bit 0 Sunday). Native capacity and title limit unknown; app limits 10 and 24 bytes.")
add("More", "drink", "Drink reminders, slots 0-7 (read 28, write 27), slot re-read compare", "always", "static_sdk",
    "none", "implemented", "raw enable kept, BCD, slot bounds, readback compare",
    note="No capability bit; a slot that does not answer stays unavailable. Raw enable value 2 kept unless switched.")
add("More", "sedentary", "Move reminder (25 write, 26 read), re-read verified", "always", "static_sdk", "none",
    "implemented", "BCD codec, cycle", note="Interval values 30/60/90; unit not confirmed and labelled so.")
add("More", "contacts", "Contacts (bulk 29), full-list replacement from a phone-side list", "contacts", "static_sdk",
    "none", "implemented", "grouping, byte limits, status handling",
    note="No watch read-back exists; the phone list is the only copy. Uses the system picker, no contacts permission.")
add("More", "weather", "Weather (1A): user-typed place, vendor forecast service, fields forwarded unchanged", "weather",
    "static_sdk", "exchanges present", "implemented", "frame layout, exact-integer boundaries, fraction/overflow rejection",
    note="User-opened network use only. Server numbers must be exact integers in range before conversion; others are left out. "
         "Timestamp is an exact long sent as its low 32 bits (meaning unresolved); type codes unlabelled. Service reply not yet seen live.")
add("More", "music", "Music keys from watch (1D): play/pause, previous, next, volume up/down; opt-in", "always",
    "static_sdk", "none", "implemented", "none (Android media keys)",
    note="No track title, progress, or playing state is sent (1C not used).")
add("More", "music-files", "Local music file upload (Jieli SPP path)", "jieli_music (music=false)", "static_sdk", "none",
    "sdk_only_unproven", "none", physical="n/a",
    note="Pubu shows music upload only when music=true; this watch reports false. Not a missing Pubu feature here.")
add("Connection", "diagnostics", "Private connection diagnostics in export: transport counters, ATT/2F sizes, 75 index, face list", "always",
    "n/a", "n/a", "implemented", "counter test, single-session snapshot",
    physical="live export via app: ATT payload 244, passive 2F report 244, bulk fragment 244; after face tests 15/15 short writes, "
             "16 short notifications, 3/3 bulk fragments, 3 bulk notifications, 18 requests, 0 failed (coordinator). Idle comparison not done.",
    note="Counted at actual write/notification callbacks for the current session; unavailable when disconnected.")
# ---- Excluded by the user ----
for fid, name in [("spo2-manual", "Manual blood oxygen (69 type 3)"), ("spo2-auto", "Automatic blood oxygen switch (2C)"),
                  ("spo2-history", "Oxygen history (bulk 49, bulk 2A)"), ("spo2-export", "Oxygen values in export")]:
    add("Excluded by user", fid, name, "manual_blood_oxygen/blood_oxygen", "static_sdk", "see note", "excluded_by_user",
        "protocol fixtures kept offline", physical="n/a",
        note="Oxygen features are excluded by product scope. Private live sensor captures are omitted. "
             "Raw units and sensor accuracy remain unverified; sensor absence is not established.")

# ---- Not advertised by this watch ----
for fid, name, flag in [("temperature", "Body temperature", "temperature"), ("album", "Album", "album"),
                        ("ebook", "E-book", "ebook"), ("glucose", "Blood glucose", "blood_glucose"),
                        ("recording", "Recording", "recording"), ("gps", "Onboard GPS / phone location", "gps"),
                        ("ecard", "E-card", "ecard"), ("lyrics", "Lyrics", "lyrics"), ("one-key", "One-key check", "one_key_check")]:
    add("Not advertised", fid, name, "not_advertised (" + flag + "=false)", "static_sdk", "n/a", "excluded", "capability fixture",
        physical="n/a")

# ---- Watch-local, no companion command ----
for fid, name in [("calculator", "Calculator"), ("games", "Games"), ("stopwatch", "Stopwatch"), ("flashlight", "Flashlight"),
                  ("sound-vibration", "Sound and vibration menus")]:
    add("Watch-local", fid, name, "watch_local", "none", "n/a", "n/a", "none", physical="n/a",
        note="Used on the watch itself; no app control is needed or known.")

# ---- Maintenance, deliberately excluded ----
for fid, name in [("firmware-update", "Firmware update (DFU)"), ("reset", "Factory reset / restore"),
                  ("reboot", "Reboot"), ("test-mode", "Factory test commands")]:
    add("Maintenance", fid, name, "always", "static_sdk", "none", "excluded", "none", physical="n/a",
        note="Not offered. No firmware image or recovery path exists. Face files never reach a firmware handler.")


def main():
    root = pathlib.Path(sys.argv[1])
    doc = {
        "app": "P71 Link (dev.p71link.app)",
        "target": {"hardware": "M71J_V1.0", "firmware": "M71J_1.00.27_231223", "phone": "S23 Ultra, Android 16"},
        "legend": {
            "advertised": "capability flag, always, not_advertised, or watch_local",
            "schema": "captured | static_sdk | unresolved | none",
            "target": "behaviour seen on the real watch before this app",
            "app": "implemented | partial | not_started | excluded | excluded_by_user | sdk_only_unproven | n/a",
            "automated": "offline checks in android/test.sh",
            "physical": "open until the coordinator records a phone+watch result with this app",
        },
        "parity_claim": "None. Full Pubu parity is not claimed while not_started rows or open physical checks remain.",
        "features": F,
    }
    (root / "FEATURES.json").write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n")
    counts = {}
    for f in F:
        counts[f["app"]] = counts.get(f["app"], 0) + 1
    lines = ["# P71 Link feature tracker", "",
             "Generated from `android/tools/features.py`. Edit the table there, then regenerate both files.", "",
             "Target: M71J_V1.0, firmware M71J_1.00.27_231223, S23 Ultra on Android 16.",
             "A Bluetooth acknowledgment or a stored setting is not a physical result.",
             "Physical acceptance stays open until the coordinator records it with this app.", "",
             "App status counts: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())) + ".", "",
             "Full Pubu Wear parity is not claimed.", ""]
    area = None
    for f in F:
        if f["area"] != area:
            area = f["area"]
            lines += ["", f"## {area}", "",
                      "| Feature | Advertised | Schema | Target before app | App | Automated | Physical | Note |",
                      "| --- | --- | --- | --- | --- | --- | --- | --- |"]
        lines.append("| " + " | ".join([f["name"], f["advertised"], f["schema"], f["target"], f["app"], f["automated"],
                                        f["physical"], f["note"]]).replace("\n", " ") + " |")
    (root / "FEATURES.md").write_text("\n".join(lines) + "\n")
    print(f"{len(F)} features: {counts}")


main()
