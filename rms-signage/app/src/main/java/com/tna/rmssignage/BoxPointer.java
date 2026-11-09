package com.tna.rmssignage;

import android.app.Activity;
import android.graphics.Point;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Centers the V_BOX14 system pointer through its rooted remote input device. */
final class BoxPointer {
    private static final String TAG = "RmsSignagePointer";
    private static final Pattern EVENT = Pattern.compile("\\bevent[0-9]+\\b");

    private BoxPointer() { }

    static void center(Activity activity) {
        if (!"V_BOX14".equals(Build.MODEL)) return;
        Point size = new Point();
        activity.getWindowManager().getDefaultDisplay().getRealSize(size);
        if (size.x <= 0 || size.y <= 0) return;
        new Thread(() -> moveToCenter(size.x / 2, size.y / 2), "rms-pointer-center").start();
    }

    private static void moveToCenter(int x, int y) {
        String device = remoteDevice();
        if (device == null) return;
        String command = "sendevent " + device + " 2 0 -20000; "
                + "sendevent " + device + " 2 1 -20000; "
                + "sendevent " + device + " 0 0 0; "
                + "sleep 0.2; "
                + "sendevent " + device + " 2 0 " + x + "; "
                + "sendevent " + device + " 2 1 " + y + "; "
                + "sendevent " + device + " 0 0 0";
        try {
            Process process = new ProcessBuilder("/system/xbin/su", "-c", command).start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            else if (process.exitValue() != 0) Log.w(TAG, "pointer centering failed");
        } catch (Exception error) {
            Log.w(TAG, "pointer centering unavailable", error);
        }
    }

    private static String remoteDevice() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/bus/input/devices"))) {
            boolean remote = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("N: Name=")) remote = line.contains("\"aml_keypad\"");
                if (remote && line.startsWith("H: Handlers=")) {
                    Matcher event = EVENT.matcher(line);
                    if (event.find()) return "/dev/input/" + event.group();
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "remote input device unavailable", error);
        }
        return null;
    }
}
