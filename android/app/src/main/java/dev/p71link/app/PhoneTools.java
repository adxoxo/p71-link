package dev.p71link.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.KeyEvent;

import dev.p71link.protocol.ShortProtocol;

/**
 * Phone-side reactions to watch-initiated events. Runs on the main thread.
 * Find phone (22): 1 starts or restarts a 10 s alert, 2 stops it, other values are ignored.
 * Music (1D): 1 play/pause, 2 previous, 3 next, 4 volume up, 5 volume down; opt-in. Nothing is sent back.
 */
final class PhoneTools {
    static final long FIND_PHONE_MS = 10000;

    private final Context context;
    private final WatchManager watch;
    private final Store store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ringtone ringtone;
    private boolean ringing;
    private final Runnable autoStop = this::stopFindPhone;
    int forwarded, forwardFailed;
    long lastFindPhone;
    String lastMusicCommand;
    /** Outcome of the last reject request from the watch, as Android reported it. */
    String lastReject;
    long lastRejectAt;
    long lastMusicAt;

    PhoneTools(Context c, WatchManager watch, Store store) {
        this.context = c.getApplicationContext();
        this.watch = watch;
        this.store = store;
        // Local effects never outlive the connection.
        watch.addListener(() -> {
            if (!watch.connected() && ringing) stopFindPhone();
        });
    }

    void handle(int command, byte[] frame) {
        int action = frame[1] & 0xff;
        if (command == ShortProtocol.FIND_PHONE) {
            if (action == 1) {
                lastFindPhone = System.currentTimeMillis();
                startFindPhone();
            } else if (action == 2) {
                stopFindPhone();
            }
        } else if (command == ShortProtocol.MUSIC_KEY) {
            if (store.musicControl()) music(action);
        } else if (command == ShortProtocol.PHONE_EVENT) {
            if (action == 1) rejectCall(); // Other values have no established meaning and are ignored.
        }
    }

    boolean ringing() { return ringing; }

    /** Start, or restart from the beginning, a bounded alert. */
    void startFindPhone() {
        stopSound();
        ringing = true;
        try {
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (ringtone != null) {
                ringtone.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                ringtone.setLooping(true);
                ringtone.play();
            }
        } catch (RuntimeException ignored) {
            ringtone = null;
        }
        VibratorManager vm = context.getSystemService(VibratorManager.class);
        if (vm != null) vm.getDefaultVibrator().vibrate(VibrationEffect.createWaveform(new long[]{0, 600, 400}, 0));
        main.removeCallbacks(autoStop);
        main.postDelayed(autoStop, FIND_PHONE_MS);
        watch.notifyChanged();
    }

    void stopFindPhone() {
        main.removeCallbacks(autoStop);
        if (!ringing) return;
        stopSound();
        ringing = false;
        watch.notifyChanged();
    }

    private void stopSound() {
        if (ringtone != null) {
            try {
                ringtone.stop();
            } catch (RuntimeException ignored) {
                // Already stopped.
            }
            ringtone = null;
        }
        VibratorManager vm = context.getSystemService(VibratorManager.class);
        if (vm != null) {
            Vibrator v = vm.getDefaultVibrator();
            v.cancel();
        }
    }

    /** Forward a watch music key to whichever player Android routes media keys to. */
    private void music(int action) {
        AudioManager am = context.getSystemService(AudioManager.class);
        if (am == null) return;
        String name;
        switch (action) {
            case 1: key(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); name = "play or pause"; break;
            case 2: key(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS); name = "previous"; break;
            case 3: key(am, KeyEvent.KEYCODE_MEDIA_NEXT); name = "next"; break;
            case 4: am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); name = "volume up"; break;
            case 5: am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); name = "volume down"; break;
            default: return; // Unknown values do nothing.
        }
        lastMusicCommand = name;
        lastMusicAt = System.currentTimeMillis();
        watch.notifyChanged();
    }

    private static void key(AudioManager am, int code) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    /** Watch asked to reject or end the current call. Runs only when the user enabled it and granted permission. */
    @SuppressWarnings("deprecation")
    private void rejectCall() {
        lastRejectAt = System.currentTimeMillis();
        if (!store.rejectCalls()) {
            lastReject = "ignored: reject from the watch is off";
        } else if (context.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            lastReject = "not done: permission missing";
        } else {
            android.telecom.TelecomManager tm = context.getSystemService(android.telecom.TelecomManager.class);
            boolean ended;
            try {
                ended = tm != null && tm.endCall();
            } catch (RuntimeException e) {
                ended = false;
            }
            lastReject = ended ? "Android ended the call" : "Android did not end a call";
        }
        watch.notifyChanged();
    }

    void recordForward(boolean ok) {
        if (ok) forwarded++;
        else forwardFailed++;
    }
}
