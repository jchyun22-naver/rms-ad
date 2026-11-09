package com.tna.rmssignage;

import android.app.AlertDialog;
import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;

/** Scrolls a choice list with the V_BOX14 remote while clicks follow its pointer. */
final class RemoteMouseDialog extends AlertDialog {
    interface Scroller { void scroll(int direction); }

    private final Scroller scroller;
    private float lastY;
    private boolean hasPosition;
    private long lastMoveAt;

    RemoteMouseDialog(Context context, Scroller scroller) {
        super(context);
        this.scroller = scroller;
    }

    static boolean fromBoxRemote(MotionEvent event) {
        InputDevice device = InputDevice.getDevice(event.getDeviceId());
        return device != null && "aml_keypad".equals(device.getName())
                && (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (fromBoxRemote(event) && event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
            float dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
            if (dy == 0 && hasPosition) dy = event.getY() - lastY;
            lastY = event.getY();
            hasPosition = true;
            long now = event.getEventTime();
            if (Math.abs(dy) >= 0.5f && now - lastMoveAt >= 140) {
                lastMoveAt = now;
                scroller.scroll(dy > 0 ? 1 : -1);
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }
}
