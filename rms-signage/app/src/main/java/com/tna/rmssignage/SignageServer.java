package com.tna.rmssignage;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

final class SignageServer {
    interface Result<T> { void done(T value, String error); }
    static final class Location {
        final int id;
        final String name;
        final String code;
        Location(int id, String name, String code) { this.id = id; this.name = name; this.code = code; }
        @Override public String toString() { return name; }
    }
    static final class Media {
        static final String IMAGE = "image", VIDEO = "video", BGM = "bgm";
        final String type;
        final String url;
        final String file;
        final int order;
        Media(String type, String url, String file, int order) {
            this.type = type; this.url = url; this.file = file; this.order = order;
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("type", type).put("url", url).put("file", file).put("order", order);
        }
    }
    static final class Playlist {
        final List<Media> promotions;
        final List<Media> bgm;
        Playlist(List<Media> promotions, List<Media> bgm) { this.promotions = promotions; this.bgm = bgm; }
        String json() throws JSONException {
            JSONArray p = new JSONArray(), b = new JSONArray();
            for (Media item : promotions) p.put(item.json());
            for (Media item : bgm) b.put(item.json());
            return new JSONObject().put("promotions", p).put("bgm", b).toString();
        }
        static Playlist fromJson(String text) throws JSONException {
            JSONObject root = new JSONObject(text);
            List<Media> p = new ArrayList<>(), b = new ArrayList<>();
            readSaved(root.optJSONArray("promotions"), p);
            readSaved(root.optJSONArray("bgm"), b);
            return new Playlist(p, b);
        }
        private static void readSaved(JSONArray array, List<Media> output) {
            if (array == null) return;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String file = item.optString("file", "");
                if (!new File(file).isFile()) continue;
                output.add(new Media(item.optString("type"), item.optString("url"), file, item.optInt("order")));
            }
        }
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong generation = new AtomicLong();

    SignageServer(Context context) { this.context = context.getApplicationContext(); }
    void cancelPending() { generation.incrementAndGet(); }
    void close() { cancelPending(); io.shutdownNow(); }

    void companies(String server, Result<List<Location>> callback) {
        list(server, "/api/restroom/getCompanyList", new JSONObjectBody().put("userID", "rms"), "companyList", "companyID", "companyName", "", callback);
    }
    void buildings(String server, int companyId, Result<List<Location>> callback) {
        list(server, "/api/restroom/getbuildingInfo", new JSONObjectBody().put("userID", "rms").put("companyID", companyId), "buildingList", "", "buildingName", "buildingCode", callback);
    }
    void locations(String server, String buildingCode, Result<List<Location>> callback) {
        list(server, "/api/restroom/getRestroomInfo", new JSONObjectBody().put("userID", "rms").put("buildingCode", buildingCode), "restroomList", "restroomID", "restroomName", "", callback);
    }
    private void list(String server, String path, JSONObjectBody body, String listKey,
                      String idKey, String nameKey, String codeKey, Result<List<Location>> callback) {
        io.execute(() -> {
            try {
                JSONObject root = post(server, path, body.object);
                JSONArray array = root.getJSONObject("result").getJSONArray(listKey);
                List<Location> result = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    result.add(new Location(idKey.isEmpty() ? 0 : item.optInt(idKey),
                            item.optString(nameKey), codeKey.isEmpty() ? "" : item.optString(codeKey)));
                }
                main.post(() -> callback.done(result, null));
            } catch (Exception e) { main.post(() -> callback.done(null, e.getMessage())); }
        });
    }

    void refresh(String server, int locationId, Result<Playlist> callback) {
        long token = generation.incrementAndGet();
        io.execute(() -> {
            try {
                if (locationId <= 0) throw new IllegalStateException("설치 위치를 선택해 주세요.");
                JSONObject root = post(server, "/api/restroom/getIdleScreenDataV3",
                        new JSONObject().put("restroomID", locationId));
                JSONObject data = root.getJSONObject("result");
                List<Media> promotions = new ArrayList<>(), bgm = new ArrayList<>();
                addPromotion(server, data.optJSONArray("promotionImageInfo"), Media.IMAGE,
                        data.optString("promotionImageUpdateTime"), promotions);
                addPromotion(server, data.optJSONArray("promotionVideoInfo"), Media.VIDEO,
                        data.optString("promotionVideoUpdateTime"), promotions);
                addBgm(server, data.optJSONArray("bgmList"), data.optString("bgmUpdateTime"), bgm);
                SignageConfig current = new SignageConfig(context);
                if (token != generation.get() || locationId != current.locationId() || !server.equals(current.server())) return;
                Collections.sort(promotions, Comparator.comparingInt(m -> m.order));
                Playlist playlist = new Playlist(promotions, bgm);
                current.playlist(playlist.json());
                main.post(() -> { if (token == generation.get()) callback.done(playlist, null); });
            } catch (Exception e) {
                main.post(() -> { if (token == generation.get()) callback.done(null, e.getMessage()); });
            }
        });
    }
    private void addPromotion(String server, JSONArray list, String type, String version, List<Media> output) throws Exception {
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            String status = item.optString("onOff", "1");
            if ("0".equals(status) || "N".equalsIgnoreCase(status)) continue;
            String url = resourceUrl(server, item.optString("resourcePath"));
            if (url.isEmpty()) continue;
            File file = download(url, version);
            output.add(new Media(type, url, file.getAbsolutePath(), item.optInt("order", i)));
        }
    }
    private void addBgm(String server, JSONArray list, String version, List<Media> output) throws Exception {
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            String url = resourceUrl(server, item.optString("bgmUrl"));
            if (url.isEmpty()) continue;
            File file = download(url, version);
            output.add(new Media(Media.BGM, url, file.getAbsolutePath(), i));
        }
    }

    private File download(String url, String version) throws Exception {
        File dir = new File(context.getFilesDir(), "media");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("미디어 폴더를 만들 수 없습니다.");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest((url + "|" + version).getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder();
        for (byte one : hash) key.append(String.format("%02x", one & 0xff));
        String path = Uri.parse(url).getPath();
        String ext = path == null ? "" : path.replaceAll(".*(\\.[a-zA-Z0-9]{1,5})$", "$1");
        if (!ext.matches("\\.[a-zA-Z0-9]{1,5}")) ext = ".bin";
        File target = new File(dir, key + ext);
        if (target.isFile() && target.length() > 0) return target;
        File temp = new File(dir, key + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(30000);
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("미디어 HTTP " + connection.getResponseCode());
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(temp))) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            if (temp.length() == 0 || !temp.renameTo(target)) throw new IllegalStateException("미디어 저장에 실패했습니다.");
            return target;
        } finally {
            connection.disconnect();
            if (temp.exists()) temp.delete();
        }
    }
    private static String resourceUrl(String server, String source) {
        if (source == null || source.trim().isEmpty()) return "";
        String value = source.trim();
        if (value.startsWith("http://rmskorea.net/") || value.startsWith("http://rmstest.cloud/"))
            value = "https" + value.substring(4);
        if (value.startsWith("https://")) return encodeUrl(value);
        return server + encodePath(value.startsWith("/") ? value : "/" + value);
    }
    private static String encodeUrl(String value) {
        Uri uri = Uri.parse(value);
        String authority = uri.getAuthority();
        if (authority == null || !(authority.equals("rmstest.cloud") || authority.equals("rmskorea.net")))
            throw new IllegalArgumentException("허용되지 않은 미디어 서버");
        return "https://" + authority + encodePath(uri.getPath()) + (uri.getEncodedQuery() == null ? "" : "?" + uri.getEncodedQuery());
    }
    private static String encodePath(String value) { return Uri.encode(value, "/%:@"); }

    private static JSONObject post(String server, String path, JSONObject request) throws Exception {
        if (!SignageConfig.TEST_SERVER.equals(server) && !SignageConfig.PROD_SERVER.equals(server))
            throw new IllegalArgumentException("허용되지 않은 서버");
        HttpURLConnection connection = (HttpURLConnection) new URL(server + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setDoOutput(true);
        try {
            byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            if (status != 200) throw new IllegalStateException("서버 HTTP " + status);
            byte[] data;
            try (InputStream input = connection.getInputStream()) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                data = output.toByteArray();
            }
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            if (!"0".equals(root.optString("code")))
                throw new IllegalStateException(root.optString("message", "서버 응답 오류"));
            return root;
        } finally { connection.disconnect(); }
    }
    private static final class JSONObjectBody {
        final JSONObject object = new JSONObject();
        JSONObjectBody put(String name, Object value) {
            try { object.put(name, value); } catch (JSONException e) { throw new IllegalStateException(e); }
            return this;
        }
    }
}
