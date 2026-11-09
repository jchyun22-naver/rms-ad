package com.tna.rmssignage;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONException;

import java.io.File;
import java.util.List;

public final class SettingsActivity extends Activity {
    private SignageConfig config;
    private SignageServer server;
    private LinearLayout page;
    private TextView locationValue, contentValue, monitorValue;
    private int companyId;
    private String companyName = "", buildingCode = "", buildingName = "";
    private boolean waiting;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        config = new SignageConfig(this);
        server = new SignageServer(this);
        getWindow().setStatusBarColor(Color.rgb(18, 43, 80));
        buildPage();
        if (!config.hasPin()) page.post(this::askNewPin);
    }
    @Override protected void onDestroy() { server.close(); super.onDestroy(); }

    private void buildPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(242, 246, 251));
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(30), dp(20), dp(30), dp(40));
        scroll.addView(page);
        setContentView(scroll);
        title("RMS 사이니지 · 관리자");
        note("홍보 화면만 운영합니다. 이미지·동영상·배경음은 기존 RMS 서버에서 받아옵니다.");

        section("설치 위치");
        locationValue = note(config.locationId() == 0 ? "선택되지 않음" : config.locationName() + "  (ID " + config.locationId() + ")");
        button("RMS 서버 선택: " + (SignageConfig.TEST_SERVER.equals(config.server()) ? "시험" : "운영"), () ->
                choose("RMS 서버", new String[]{"시험 · rmstest.cloud", "운영 · rmskorea.net"}, index -> {
                    config.server(index == 0 ? SignageConfig.TEST_SERVER : SignageConfig.PROD_SERVER);
                    config.location(0, "");
                    companyId = 0; buildingCode = "";
                    buildPage();
                }));
        button("업체 선택", () -> loadLocations(0));
        button("건물 선택", () -> { if (companyId == 0) message("먼저 업체를 선택해 주세요."); else loadLocations(1); });
        button("설치 위치 선택", () -> { if (buildingCode.isEmpty()) message("먼저 건물을 선택해 주세요."); else loadLocations(2); });

        section("홍보 콘텐츠");
        contentValue = note(contentSummary());
        button("서버 콘텐츠 새로고침", this::refreshContent);
        note("서버에 등록된 이미지·동영상 순서와 사용 여부를 따릅니다. 파일을 완전히 받은 뒤 화면에 적용하고, 네트워크가 끊기면 마지막으로 받은 콘텐츠를 재생합니다.");

        section("화면과 모니터");
        monitorValue = note(MonitorActivity.summary(this, config));
        button("모니터 확인 · 시험 패턴", () -> startActivity(new Intent(this, MonitorActivity.class)));
        button("화면 방향: " + orientationName(config.orientation()), () -> choose("화면 방향",
                new String[]{"자동", "가로", "세로"}, index -> {
                    config.orientation(new String[]{"auto", "landscape", "portrait"}[index]); buildPage();
                }));
        button("목표 해상도: " + resolutionName(config.targetResolution()), () -> choose("목표 해상도",
                new String[]{"자동", "1280×720", "1920×1080", "3840×2160 · 4K"}, index -> {
                    config.targetResolution(new String[]{"auto", "720p", "1080p", "4k"}[index]); buildPage();
                }));
        button("영상 맞춤: " + ("fill".equals(config.scaleMode()) ? "화면 채우기 · 가장자리 잘림" : "전체 보기 · 여백 허용"),
                () -> choose("이미지·동영상 맞춤", new String[]{"전체 보기 (비율 유지)", "화면 채우기 (비율 유지, 일부 잘림)"},
                        index -> { config.scaleMode(index == 0 ? "fit" : "fill"); buildPage(); }));
        button("모니터 이름 / 설치 메모", this::editMonitorLabel);
        slider("이미지 재생 시간", 3, 60, config.imageSeconds(), value -> config.imageSeconds(value), "초");

        section("소리");
        AudioManager audio = (AudioManager)getSystemService(AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = config.volume() < 0 ? audio.getStreamVolume(AudioManager.STREAM_MUSIC) : config.volume();
        slider("음악·영상 볼륨", 0, max, Math.min(current, max), value -> {
            config.volume(value);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
        }, " / " + max);

        section("관리 및 초기화");
        button("관리자 PIN 변경", this::askNewPin);
        button("기기 리셋 · 사이니지 설정과 저장 콘텐츠 삭제", this::confirmReset);
        button("홍보 화면으로 돌아가기", () -> {
            if (!config.hasPin()) message("관리자 PIN을 먼저 설정해 주세요.");
            else { startActivity(new Intent(this, MainActivity.class)); finish(); }
        });
    }

    private void loadLocations(int level) {
        if (waiting) return;
        waiting = true;
        if (level == 0) server.companies(config.server(), (items, error) -> onLocations(level, items, error));
        else if (level == 1) server.buildings(config.server(), companyId, (items, error) -> onLocations(level, items, error));
        else server.locations(config.server(), buildingCode, (items, error) -> onLocations(level, items, error));
    }
    private void onLocations(int level, List<SignageServer.Location> items, String error) {
        waiting = false;
        if (isFinishing()) return;
        if (error != null) { message("서버 목록을 받지 못했습니다.\n" + error); return; }
        if (items == null || items.isEmpty()) { message("등록된 항목이 없습니다."); return; }
        String[] names = new String[items.size()];
        for (int i = 0; i < items.size(); i++) names[i] = items.get(i).name;
        new AlertDialog.Builder(this).setTitle(new String[]{"업체 선택", "건물 선택", "설치 위치 선택"}[level])
                .setItems(names, (dialog, index) -> {
                    SignageServer.Location item = items.get(index);
                    if (level == 0) { companyId = item.id; companyName = item.name; buildingCode = ""; }
                    else if (level == 1) { buildingCode = item.code; buildingName = item.name; }
                    else {
                        config.location(item.id, companyName + " / " + buildingName + " / " + item.name);
                        contentValue.setText("위치를 변경했습니다. 홍보 화면에서 서버 콘텐츠를 받습니다.");
                    }
                    locationValue.setText(level == 2 ? config.locationName() : companyName + (buildingName.isEmpty() ? "" : " / " + buildingName));
                }).show();
    }
    private void refreshContent() {
        if (config.locationId() == 0) { message("설치 위치를 먼저 선택해 주세요."); return; }
        contentValue.setText("서버 콘텐츠를 확인하는 중…");
        server.refresh(config.server(), config.locationId(), (playlist, error) -> {
            if (isFinishing()) return;
            if (error != null) { contentValue.setText("새로고침 실패: " + error + "\n기존 저장 콘텐츠는 유지됩니다."); return; }
            contentValue.setText("이미지·동영상 " + playlist.promotions.size() + "개, 배경음 " + playlist.bgm.size() + "개 저장 완료");
        });
    }
    private void editMonitorLabel() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(config.monitorLabel());
        new AlertDialog.Builder(this).setTitle("모니터 이름 / 설치 메모").setView(input)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", (d, w) -> { config.monitorLabel(input.getText().toString()); monitorValue.setText(MonitorActivity.summary(this, config)); })
                .show();
    }
    private void askNewPin() {
        LinearLayout fields = new LinearLayout(this);
        fields.setPadding(dp(24), dp(8), dp(24), dp(8));
        fields.setOrientation(LinearLayout.VERTICAL);
        EditText first = new EditText(this), second = new EditText(this);
        first.setHint("새 PIN (숫자 4~8자리)"); second.setHint("PIN 다시 입력");
        first.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        second.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        fields.addView(first); fields.addView(second);
        new AlertDialog.Builder(this).setTitle("관리자 PIN 설정").setView(fields)
                .setNegativeButton("나중에", null)
                .setPositiveButton("저장", (dialog, which) -> {
                    String pin = first.getText().toString();
                    if (!pin.matches("[0-9]{4,8}") || !pin.equals(second.getText().toString())) {
                        message("PIN은 숫자 4~8자리로 두 번 같게 입력해 주세요."); return;
                    }
                    config.setPin(pin); message("관리자 PIN을 저장했습니다.");
                }).show();
    }
    private void confirmReset() {
        new AlertDialog.Builder(this).setTitle("기기 리셋")
                .setMessage("이 사이니지 앱의 설치 위치, PIN, 화면·음량 설정과 저장된 홍보 파일을 지웁니다. RMS 서버 콘텐츠와 기존 RMS 앱은 지우지 않습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("초기화", (dialog, which) -> {
                    server.cancelPending();
                    config.reset();
                    deleteMedia(new File(getFilesDir(), "media"));
                    companyId = 0; buildingCode = ""; companyName = ""; buildingName = "";
                    buildPage();
                    askNewPin();
                }).show();
    }
    private void deleteMedia(File dir) {
        if (!dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children != null) for (File file : children) if (file.isFile()) file.delete();
        dir.delete();
    }
    private String contentSummary() {
        try {
            SignageServer.Playlist list = SignageServer.Playlist.fromJson(config.playlist());
            return "저장된 홍보 이미지·동영상 " + list.promotions.size() + "개, 배경음 " + list.bgm.size() + "개";
        } catch (JSONException e) { return "저장된 콘텐츠 없음"; }
    }
    @Override public void onBackPressed() {
        if (!config.hasPin()) { message("관리자 PIN을 먼저 설정해 주세요."); return; }
        super.onBackPressed();
    }
    private interface Choice { void selected(int index); }
    private void choose(String title, String[] options, Choice selected) {
        new AlertDialog.Builder(this).setTitle(title).setItems(options, (dialog, index) -> selected.selected(index)).show();
    }
    private interface ValueChanged { void set(int value); }
    private void slider(String caption, int min, int max, int start, ValueChanged changed, String suffix) {
        TextView text = note(caption + ": " + start + suffix);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, start - min)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
                if (fromUser) { int value = min + progress; changed.set(value); text.setText(caption + ": " + value + suffix); }
            }
            @Override public void onStartTrackingTouch(SeekBar seek) { }
            @Override public void onStopTrackingTouch(SeekBar seek) { }
        });
        page.addView(bar);
    }
    private void title(String value) {
        TextView text = new TextView(this); text.setText(value); text.setTextColor(Color.rgb(18, 43, 80));
        text.setTextSize(30); text.setPadding(0, 0, 0, dp(12)); page.addView(text);
    }
    private void section(String value) {
        TextView text = new TextView(this); text.setText(value); text.setTextColor(Color.rgb(0, 91, 190));
        text.setTextSize(24); text.setPadding(0, dp(22), 0, dp(5)); page.addView(text);
    }
    private TextView note(String value) {
        TextView text = new TextView(this); text.setText(value); text.setTextColor(Color.rgb(38, 54, 75));
        text.setTextSize(18); text.setPadding(0, dp(4), 0, dp(8)); page.addView(text); return text;
    }
    private void button(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setTextSize(20);
        button.setAllCaps(false); button.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        button.setPadding(dp(18), 0, dp(18), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(58));
        params.setMargins(0, dp(3), 0, dp(3)); page.addView(button, params);
        button.setOnClickListener(v -> action.run());
    }
    private void message(String value) { new AlertDialog.Builder(this).setMessage(value).setPositiveButton("확인", null).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String orientationName(String value) {
        return "portrait".equals(value) ? "세로" : "landscape".equals(value) ? "가로" : "자동";
    }
    private String resolutionName(String value) {
        return "4k".equals(value) ? "3840×2160" : "1080p".equals(value) ? "1920×1080" : "720p".equals(value) ? "1280×720" : "자동";
    }
}
