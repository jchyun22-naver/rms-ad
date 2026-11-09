package com.tna.rmssignage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

final class SignageConfig {
    static final String TEST_SERVER = "https://rmstest.cloud";
    static final String PROD_SERVER = "https://rmskorea.net";
    private final SharedPreferences prefs;

    SignageConfig(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("signage", Context.MODE_PRIVATE);
    }

    String server() { return prefs.getString("server", TEST_SERVER); }
    void server(String value) {
        if (!TEST_SERVER.equals(value) && !PROD_SERVER.equals(value)) throw new IllegalArgumentException("Unsupported server");
        prefs.edit().putString("server", value).apply();
    }
    int locationId() { return prefs.getInt("location_id", 0); }
    String locationName() { return prefs.getString("location_name", ""); }
    void location(int id, String name) {
        prefs.edit().putInt("location_id", id).putString("location_name", name).remove("playlist").apply();
    }
    String orientation() { return prefs.getString("orientation", "auto"); }
    void orientation(String value) { prefs.edit().putString("orientation", value).apply(); }
    String scaleMode() { return prefs.getString("scale", "fit"); }
    void scaleMode(String value) { prefs.edit().putString("scale", value).apply(); }
    int displayModeId() { return prefs.getInt("display_mode_id", 0); }
    void displayModeId(int value) { prefs.edit().putInt("display_mode_id", value).apply(); }
    String targetResolution() { return prefs.getString("target_resolution", "auto"); }
    void targetResolution(String value) { prefs.edit().putString("target_resolution", value).apply(); }
    int imageSeconds() { return prefs.getInt("image_seconds", 10); }
    void imageSeconds(int value) { prefs.edit().putInt("image_seconds", Math.max(3, Math.min(value, 120))).apply(); }
    int volume() { return prefs.getInt("volume", -1); }
    void volume(int value) { prefs.edit().putInt("volume", value).apply(); }
    String monitorLabel() { return prefs.getString("monitor_label", ""); }
    void monitorLabel(String value) { prefs.edit().putString("monitor_label", value.trim()).apply(); }
    String playlist() { return prefs.getString("playlist", ""); }
    void playlist(String value) { prefs.edit().putString("playlist", value).apply(); }

    boolean hasPin() { return prefs.contains("pin_hash"); }
    void setPin(String pin) {
        if (!pin.matches("[0-9]{4,8}")) throw new IllegalArgumentException("PIN must be 4 to 8 digits");
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        prefs.edit().putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("pin_hash", hash(salt, pin)).apply();
    }
    boolean verifyPin(String pin) {
        try {
            String stored = prefs.getString("pin_hash", "");
            byte[] salt = Base64.decode(prefs.getString("pin_salt", ""), Base64.DEFAULT);
            return MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
                    hash(salt, pin).getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ignored) { return false; }
    }
    private static String hash(byte[] salt, String pin) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(pin.getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(digest.digest(), Base64.NO_WRAP);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    void reset() { prefs.edit().clear().commit(); }
}
