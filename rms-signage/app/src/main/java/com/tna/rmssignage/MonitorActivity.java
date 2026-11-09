package com.tna.rmssignage;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;

public final class MonitorActivity extends Activity {
    private boolean pattern;

    static String summary(Activity activity, SignageConfig config) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        Point size = new Point(); display.getRealSize(size);
        Display.Mode mode = display.getMode();
        String hdmi = read("/sys/class/display/mode");
        String edid = read("/sys/class/amhdmitx/amhdmitx0/edid");
        String product = field(edid, "Rx Product Name:");
        String physical = field(edid, "Physcial size(cm):");
        String target = config.targetResolution();
        StringBuilder result = new StringBuilder();
        if (!config.monitorLabel().isEmpty()) result.append("설치 메모: ").append(config.monitorLabel()).append('\n');
        result.append("앱 화면: ").append(size.x).append('×').append(size.y)
                .append(" / ").append(mode.getRefreshRate()).append("Hz\n");
        result.append("기기 합성 화면: ").append(mode.getPhysicalWidth()).append('×')
                .append(mode.getPhysicalHeight()).append(" (실제 렌더링 기준)\n");
        result.append("HDMI 출력: ").append(hdmi.isEmpty() ? "기기에서 읽을 수 없음" : hdmi).append('\n');
        result.append("HDMI 식별: ").append(product.isEmpty() ? "기기에서 읽을 수 없음" : product);
        if (!physical.isEmpty()) result.append(" / ").append(physical);
        result.append("\n목표 해상도: ").append(target.equals("4k") ? "3840×2160" :
                target.equals("1080p") ? "1920×1080" : target.equals("720p") ? "1280×720" : "자동");
        if (target.equals("4k") && !hdmi.contains("2160") && !hdmi.contains("4k"))
            result.append("\n주의: 목표는 4K지만 현재 HDMI는 4K가 아닙니다.");
        if (target.equals("1080p") && !hdmi.isEmpty() && !hdmi.contains("1080"))
            result.append("\n주의: 목표는 1080p지만 현재 HDMI가 다릅니다.");
        if (!target.equals("auto") && !target.equals("720p")) {
            int targetWidth = target.equals("4k") ? 3840 : 1920;
            boolean supported = false;
            for (Display.Mode available : display.getSupportedModes())
                if (available.getPhysicalWidth() == targetWidth) supported = true;
            if (!supported) result.append("\n주의: 이 기기는 앱에서 해당 출력 모드를 선택할 수 없습니다.");
        }
        if (size.x > mode.getPhysicalWidth() || size.y > mode.getPhysicalHeight())
            result.append("\n앱 화면 크기는 재정의되었지만 기기 합성 화면은 그대로입니다.");
        return result.toString();
    }
    private static String field(String source, String prefix) {
        for (String line : source.split("\n")) if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        return "";
    }
    private static String read(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            StringBuilder text = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null && text.length() < 6000) text.append(line).append('\n');
            return text.toString().trim();
        } catch (Exception ignored) { return ""; }
    }
    @Override public void onCreate(Bundle state) { super.onCreate(state); showInfo(); }
    private void showInfo() {
        pattern = false;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(19, 31, 53));
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(28), dp(24), dp(28), dp(24)); scroll.addView(column);
        TextView title = label("모니터 확인", 30); column.addView(title);
        TextView details = label(summary(this, new SignageConfig(this)), 21);
        details.setPadding(0, dp(22), 0, dp(24)); column.addView(details);
        Display.Mode[] modes = getWindowManager().getDefaultDisplay().getSupportedModes();
        StringBuilder supported = new StringBuilder("앱에서 선택 가능한 출력 모드\n");
        for (Display.Mode mode : modes) supported.append("• ").append(mode.getPhysicalWidth()).append('×')
                .append(mode.getPhysicalHeight()).append("  ").append(mode.getRefreshRate()).append("Hz\n");
        column.addView(label(supported.toString(), 19));
        String caps = read("/sys/class/amhdmitx/amhdmitx0/disp_cap");
        if (!caps.isEmpty()) column.addView(label("HDMI가 보고한 지원 모드\n" + caps, 17));
        Button test = button("전체 화면 시험 패턴 보기", () -> showPattern()); column.addView(test);
        Button back = button("관리자로 돌아가기", this::finish); column.addView(back);
        setContentView(scroll);
    }
    private void showPattern() {
        pattern = true;
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        FrameLayout root = new FrameLayout(this);
        root.addView(new PatternView(), new FrameLayout.LayoutParams(-1, -1));
        Button exit = button("패턴 닫기", this::showInfo);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(170), dp(58), Gravity.TOP | Gravity.RIGHT);
        params.setMargins(0, dp(18), dp(18), 0); root.addView(exit, params);
        setContentView(root);
    }
    @Override public void onBackPressed() { if (pattern) showInfo(); else super.onBackPressed(); }
    private TextView label(String value, int size) {
        TextView text = new TextView(this); text.setText(value); text.setTextColor(Color.WHITE);
        text.setTextSize(size); return text;
    }
    private Button button(String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(19);
        button.setOnClickListener(v -> action.run()); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class PatternView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        PatternView() { super(MonitorActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            canvas.drawColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.WHITE); paint.setStrokeWidth(4);
            canvas.drawRect(2, 2, w - 2, h - 2, paint);
            paint.setStrokeWidth(2); paint.setColor(Color.GRAY);
            for (int i = 1; i < 10; i++) {
                canvas.drawLine(w * i / 10f, 0, w * i / 10f, h, paint);
                canvas.drawLine(0, h * i / 10f, w, h * i / 10f, paint);
            }
            paint.setColor(Color.YELLOW); paint.setStrokeWidth(3);
            canvas.drawLine(0, 0, w, h, paint); canvas.drawLine(w, 0, 0, h, paint);
            canvas.drawCircle(w / 2f, h / 2f, Math.min(w, h) * 0.25f, paint);
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(Math.max(24, Math.min(w, h) / 25f));
            paint.setColor(Color.WHITE);
            canvas.drawText("TOP LEFT", 18, 55, paint);
            String info = w + " × " + h + "  |  16:9 기준 전체 화면 확인";
            canvas.drawText(info, w * 0.22f, h / 2f, paint);
            canvas.drawText("BOTTOM RIGHT", w - Math.min(320, w / 4f), h - 18, paint);
            paint.setColor(Color.RED); canvas.drawRect(0, h - 12, w / 3f, h, paint);
            paint.setColor(Color.GREEN); canvas.drawRect(w / 3f, h - 12, w * 2f / 3f, h, paint);
            paint.setColor(Color.BLUE); canvas.drawRect(w * 2f / 3f, h - 12, w, h, paint);
        }
    }
}
