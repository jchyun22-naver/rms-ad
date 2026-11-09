package com.tna.rmssignage;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final String TAG = "RmsSignage";
    private static final long REFRESH_MS = 5 * 60 * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<SignageServer.Media> promotions = new ArrayList<>();
    private final List<SignageServer.Media> music = new ArrayList<>();
    private SignageConfig config;
    private SignageServer server;
    private FrameLayout root, stage;
    private ImageView image;
    private TextureView video;
    private TextView status;
    private MediaPlayer videoPlayer, bgmPlayer;
    private Runnable videoTimeout;
    private int musicPosition;
    private int position;
    private long playbackToken;
    private boolean active;
    private final Runnable nextImage = this::playNext;
    private final Runnable hidePromoPointer = () -> setPromoPointerVisible(false);
    private final Runnable refresh = new Runnable() {
        @Override public void run() { if (active) { refreshMedia(); handler.postDelayed(this, REFRESH_MS); } }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        config = new SignageConfig(this);
        server = new SignageServer(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        image = new ImageView(this);
        image.setBackgroundColor(Color.BLACK);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        video = new TextureView(this);
        video.setVisibility(View.GONE);
        stage.addView(image, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(video, new FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(24);
        status.setGravity(Gravity.CENTER);
        status.setPadding(30, 30, 30, 30);
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.setOnLongClickListener(v -> { openAdmin(); return true; });
        stage.setOnLongClickListener(v -> { openAdmin(); return true; });
        status.setOnLongClickListener(v -> { openAdmin(); return true; });
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyOrientation());
        if (!config.hasPin() || config.locationId() == 0) {
            status.setText("RMS 사이니지\n관리자 설정에서 설치 위치를 선택해 주세요.\n리모컨 확인(OK) 버튼: 관리자 설정");
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        showPromoPointer();
        BoxPointer.center(this);
        active = true;
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent().getBooleanExtra("test_landscape", false))
            config.orientation("landscape");
        applyDisplayMode();
        applyOrientation();
        applyVolume();
        handler.removeCallbacks(nextImage);
        stopVideo();
        stopBgm();
        promotions.clear();
        music.clear();
        musicPosition = 0;
        position = 0;
        image.setImageDrawable(null);
        video.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        handler.removeCallbacks(refresh);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent().hasExtra("test_extra_video")) {
            String extra = getIntent().getStringExtra("test_extra_video");
            String portrait = getIntent().getStringExtra("test_portrait_video");
            try {
                SignageServer.Playlist saved = SignageServer.Playlist.fromJson(config.playlist());
                if (extra == null || portrait == null || !new File(extra).isFile()
                        || !new File(portrait).isFile() || saved.promotions.size() != 3) {
                    status.setText("시험 콘텐츠 4개를 찾을 수 없습니다.");
                    return;
                }
                int images = 0, videos = 0;
                for (SignageServer.Media item : saved.promotions) {
                    if (SignageServer.Media.IMAGE.equals(item.type)) images++;
                    if (SignageServer.Media.VIDEO.equals(item.type)) videos++;
                }
                if (images != 1 || videos != 2) {
                    status.setText("시험 콘텐츠 구성이 이미지 1개·영상 2개가 아닙니다.");
                    return;
                }
                List<SignageServer.Media> local = new ArrayList<>();
                int videoIndex = 0;
                for (SignageServer.Media item : saved.promotions) {
                    if (SignageServer.Media.VIDEO.equals(item.type) && ++videoIndex == 2)
                        local.add(new SignageServer.Media(item.type, item.url, portrait, item.order));
                    else local.add(item);
                }
                local.add(new SignageServer.Media(SignageServer.Media.VIDEO, "usb-local-test", extra, 3));
                Log.i(TAG, "offline HD playback test: 1 image + 3 videos");
                usePlaylist(new SignageServer.Playlist(local, saved.bgm));
            } catch (JSONException e) {
                status.setText("저장된 시험 콘텐츠를 읽을 수 없습니다.");
            }
            return;
        }
        try {
            SignageServer.Playlist saved = SignageServer.Playlist.fromJson(config.playlist());
            if (!saved.promotions.isEmpty()) usePlaylist(saved);
        } catch (JSONException ignored) { }
        refresh.run();
    }
    @Override protected void onPause() {
        active = false;
        handler.removeCallbacks(hidePromoPointer);
        setPromoPointerVisible(true);
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(nextImage);
        stopVideo();
        stopBgm();
        super.onPause();
    }
    @Override protected void onDestroy() {
        server.close();
        super.onDestroy();
    }

    private void refreshMedia() {
        int location = config.locationId();
        if (location == 0) {
            status.setVisibility(View.VISIBLE);
            status.setText("설치 위치를 선택해 주세요.\n리모컨 메뉴 키 또는 화면 길게 누르기");
            return;
        }
        if (promotions.isEmpty()) {
            status.setVisibility(View.VISIBLE);
            status.setText("홍보 콘텐츠 확인 중…\n" + config.locationName());
        }
        server.refresh(config.server(), location, (playlist, error) -> {
            if (!active || location != config.locationId()) return;
            if (error != null) {
                if (promotions.isEmpty()) {
                    status.setVisibility(View.VISIBLE);
                    status.setText("콘텐츠를 불러오지 못했습니다.\n" + error + "\n리모컨 메뉴 키: 관리자 설정");
                }
                return;
            }
            usePlaylist(playlist);
        });
    }
    private void usePlaylist(SignageServer.Playlist playlist) {
        boolean same = promotions.size() == playlist.promotions.size();
        for (int i = 0; same && i < promotions.size(); i++)
            same = promotions.get(i).file.equals(playlist.promotions.get(i).file)
                    && promotions.get(i).type.equals(playlist.promotions.get(i).type)
                    && promotions.get(i).order == playlist.promotions.get(i).order;
        promotions.clear();
        promotions.addAll(playlist.promotions);
        boolean sameMusic = music.size() == playlist.bgm.size();
        for (int i = 0; sameMusic && i < music.size(); i++)
            sameMusic = music.get(i).file.equals(playlist.bgm.get(i).file);
        if (!sameMusic) {
            stopBgm();
            musicPosition = 0;
        }
        music.clear();
        music.addAll(playlist.bgm);
        if (promotions.isEmpty()) {
            stopVideo(); stopBgm();
            image.setImageDrawable(null);
            status.setVisibility(View.VISIBLE);
            status.setText("등록된 홍보 이미지·동영상이 없습니다.\n" + config.locationName() + "\n리모컨 메뉴 키: 관리자 설정");
            return;
        }
        status.setText("");
        status.setVisibility(View.GONE);
        if (!same) {
            position = 0;
            playNext();
        } else if (videoPlayer == null && video.getVisibility() != View.VISIBLE) startBgm();
    }
    private void playNext() {
        if (!active || promotions.isEmpty()) return;
        handler.removeCallbacks(nextImage);
        stopVideo();
        long token = ++playbackToken;
        int index = position++ % promotions.size();
        SignageServer.Media item = promotions.get(index);
        Log.i(TAG, "play index=" + index + " order=" + item.order + " type=" + item.type);
        if (SignageServer.Media.VIDEO.equals(item.type)) {
            stopBgm();
            image.setVisibility(View.GONE);
            video.setVisibility(View.VISIBLE);
            playVideo(item.file, token);
        } else {
            video.setVisibility(View.GONE);
            image.setVisibility(View.VISIBLE);
            image.setScaleType("fill".equals(config.scaleMode()) ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            showImage(item.file, token);
            startBgm();
            handler.postDelayed(nextImage, config.imageSeconds() * 1000L);
        }
    }
    private void showImage(String file, long token) {
        int width = Math.max(1280, root.getWidth()), height = Math.max(720, root.getHeight());
        new Thread(() -> {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file, bounds);
            int sample = 1;
            while (bounds.outWidth / sample > width * 2 || bounds.outHeight / sample > height * 2) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(file, options);
            handler.post(() -> {
                if (token != playbackToken || !active) { if (bitmap != null) bitmap.recycle(); return; }
                if (bitmap != null) image.setImageBitmap(bitmap);
            });
        }, "signage-image").start();
    }
    private void playVideo(String file, long token) {
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                if (token != playbackToken || !active || videoPlayer != null) return;
                try {
                    MediaPlayer player = new MediaPlayer();
                    videoPlayer = player;
                    player.setDataSource(file);
                    Surface surface = new Surface(texture);
                    player.setSurface(surface);
                    surface.release();
                    player.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    player.setOnPreparedListener(mp -> {
                        if (token != playbackToken || !active) return;
                        Log.i(TAG, "video prepared " + mp.getVideoWidth() + "x" + mp.getVideoHeight());
                        fitVideo(mp.getVideoWidth(), mp.getVideoHeight());
                        mp.start();
                        long duration = mp.getDuration();
                        long timeoutMs = duration > 0 ? duration + 5000L : 30000L;
                        videoTimeout = () -> {
                            if (token != playbackToken || !active || videoPlayer != mp) return;
                            Log.w(TAG, "video timed out after " + timeoutMs + " ms; skipping");
                            playNext();
                        };
                        handler.postDelayed(videoTimeout, timeoutMs);
                    });
                    player.setOnVideoSizeChangedListener((mp, w, h) -> fitVideo(w, h));
                    player.setOnCompletionListener(mp -> {
                        Log.i(TAG, "video completed");
                        handler.post(() -> { if (token == playbackToken && active) playNext(); });
                    });
                    player.setOnErrorListener((mp, what, extra) -> {
                        Log.e(TAG, "video error what=" + what + " extra=" + extra);
                        handler.postDelayed(() -> { if (token == playbackToken && active) playNext(); }, 1000);
                        return true;
                    });
                    player.prepareAsync();
                } catch (Exception e) {
                    Log.e(TAG, "video setup failed", e);
                    stopVideo();
                    handler.postDelayed(() -> { if (token == playbackToken && active) playNext(); }, 1000);
                }
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
                if (videoPlayer != null) fitVideo(videoPlayer.getVideoWidth(), videoPlayer.getVideoHeight());
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) { stopVideo(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
        });
        if (video.isAvailable()) video.getSurfaceTextureListener().onSurfaceTextureAvailable(video.getSurfaceTexture(), video.getWidth(), video.getHeight());
    }
    private void fitVideo(int contentW, int contentH) {
        int viewW = video.getWidth(), viewH = video.getHeight();
        if (contentW <= 0 || contentH <= 0 || viewW <= 0 || viewH <= 0) return;
        float factor = "fill".equals(config.scaleMode())
                ? Math.max((float)viewW / contentW, (float)viewH / contentH)
                : Math.min((float)viewW / contentW, (float)viewH / contentH);
        float sx = contentW * factor / viewW, sy = contentH * factor / viewH;
        Matrix transform = new Matrix();
        transform.setScale(sx, sy, viewW / 2f, viewH / 2f);
        video.setTransform(transform);
    }
    private void stopVideo() {
        if (videoTimeout != null) {
            handler.removeCallbacks(videoTimeout);
            videoTimeout = null;
        }
        if (videoPlayer != null) {
            try { videoPlayer.setOnCompletionListener(null); videoPlayer.stop(); } catch (Exception ignored) { }
            videoPlayer.release(); videoPlayer = null;
        }
    }
    private void startBgm() {
        if (!active || music.isEmpty() || bgmPlayer != null) return;
        try {
            bgmPlayer = new MediaPlayer();
            bgmPlayer.setDataSource(music.get(musicPosition % music.size()).file);
            bgmPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            bgmPlayer.setOnPreparedListener(MediaPlayer::start);
            bgmPlayer.setOnCompletionListener(mp -> {
                stopBgm();
                if (!music.isEmpty()) {
                    musicPosition = (musicPosition + 1) % music.size();
                    startBgm();
                }
            });
            bgmPlayer.setOnErrorListener((mp, what, extra) -> { stopBgm(); return true; });
            bgmPlayer.prepareAsync();
        } catch (Exception e) { stopBgm(); }
    }
    private void stopBgm() {
        if (bgmPlayer != null) {
            try { bgmPlayer.stop(); } catch (Exception ignored) { }
            bgmPlayer.release(); bgmPlayer = null;
        }
    }
    private void applyVolume() {
        int value = config.volume();
        if (value < 0) return;
        AudioManager audio = (AudioManager)getSystemService(AUDIO_SERVICE);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                Math.min(value, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
    }
    private void applyDisplayMode() {
        Display display = getWindowManager().getDefaultDisplay();
        String target = config.targetResolution();
        int wantedW = "4k".equals(target) ? 3840 : "1080p".equals(target) ? 1920 : "720p".equals(target) ? 1280 : 0;
        int wantedH = "4k".equals(target) ? 2160 : "1080p".equals(target) ? 1080 : "720p".equals(target) ? 720 : 0;
        int matchingMode = 0;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == wantedW && mode.getPhysicalHeight() == wantedH) { matchingMode = mode.getModeId(); break; }
        }
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.preferredDisplayModeId = matchingMode;
        getWindow().setAttributes(params);
    }
    private void applyOrientation() {
        if (root == null || root.getWidth() == 0 || root.getHeight() == 0) return;
        int w = root.getWidth(), h = root.getHeight();
        boolean physicalLandscape = w >= h;
        String orientation = config.orientation();
        boolean rotate = ("portrait".equals(orientation) && physicalLandscape)
                || ("landscape".equals(orientation) && !physicalLandscape);
        int stageW = rotate ? h : w, stageH = rotate ? w : h;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) stage.getLayoutParams();
        if (params.width != stageW || params.height != stageH || params.gravity != Gravity.CENTER)
            stage.setLayoutParams(new FrameLayout.LayoutParams(stageW, stageH, Gravity.CENTER));
        if (stage.getPivotX() != stageW / 2f) stage.setPivotX(stageW / 2f);
        if (stage.getPivotY() != stageH / 2f) stage.setPivotY(stageH / 2f);
        float rotation = rotate ? 90f : 0f;
        if (stage.getRotation() != rotation) stage.setRotation(rotation);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void setPromoPointerVisible(boolean visible) {
        PointerIcon icon = PointerIcon.getSystemIcon(this, visible ? PointerIcon.TYPE_DEFAULT : PointerIcon.TYPE_NULL);
        root.setPointerIcon(icon);
        stage.setPointerIcon(icon);
        image.setPointerIcon(icon);
        video.setPointerIcon(icon);
        status.setPointerIcon(icon);
    }
    private void showPromoPointer() {
        setPromoPointerVisible(true);
        handler.removeCallbacks(hidePromoPointer);
        handler.postDelayed(hidePromoPointer, 5000);
    }
    private void openAdmin() {
        handler.removeCallbacks(hidePromoPointer);
        setPromoPointerVisible(true);
        if (!config.hasPin()) {
            startActivity(new Intent(this, SettingsActivity.class)); return;
        }
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setSingleLine(true);
        pin.setHint("PIN 입력");
        pin.setShowSoftInputOnFocus(false);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), 0, dp(20), dp(8));
        panel.addView(pin, new LinearLayout.LayoutParams(-1, dp(56)));
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"", "0", "지우기"}};
        for (String[] keyRow : keys) {
            LinearLayout row = new LinearLayout(this);
            for (String key : keyRow) {
                View keyView;
                if (key.isEmpty()) keyView = new View(this);
                else {
                    Button button = new Button(this);
                    button.setText(key);
                    button.setTextSize(24);
                    button.setFocusable(false);
                    button.setOnClickListener(v -> {
                        String value = pin.getText().toString();
                        if ("지우기".equals(key)) {
                            if (!value.isEmpty()) pin.setText(value.substring(0, value.length() - 1));
                        } else if (value.length() < 12) pin.setText(value + key);
                        pin.setSelection(pin.length());
                    });
                    keyView = button;
                }
                LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(0, dp(62), 1);
                keyParams.setMargins(dp(2), dp(2), dp(2), dp(2));
                row.addView(keyView, keyParams);
            }
            panel.addView(row);
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("관리자 PIN")
                .setMessage("취소하려면 리모컨 뒤로가기를 누르세요.").setView(panel)
                .setPositiveButton("열기", (clickedDialog, which) -> {
                    if (config.verifyPin(pin.getText().toString())) startActivity(new Intent(this, SettingsActivity.class));
                    else new AlertDialog.Builder(this).setMessage("PIN이 맞지 않습니다.").setPositiveButton("확인", null).show();
                }).create();
        pin.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) {
                if (dialog.isShowing() && config.verifyPin(value.toString())) {
                    dialog.dismiss();
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                }
            }
        });
        dialog.show();
        dialog.setOnDismissListener(closed -> { if (active) showPromoPointer(); });
        pin.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (RemoteMouseDialog.fromBoxRemote(event)) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) openAdmin();
            return true;
        }
        return super.dispatchTouchEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (RemoteMouseDialog.fromBoxRemote(event)
                && event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) showPromoPointer();
        return super.dispatchGenericMotionEvent(event);
    }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getRepeatCount() == 0) openAdmin();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
