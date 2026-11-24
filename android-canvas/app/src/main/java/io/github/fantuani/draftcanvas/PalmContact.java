package io.github.fantuani.draftcanvas;

import android.os.Build;
import android.view.InputDevice;
import android.view.MotionEvent;

final class PalmContact {
    private static final String PAD_6_PRO = "liuqin";
    static final String TOUCHSCREEN = "NVTCapacitiveTouchScreen";

    // On the Pad 6 Pro this axis is the touchscreen's raw contact size divided by 255.
    // Measured swipes reached 6. Resting palms first reported 13–18.
    private static final float WIDE_CONTACT = 12.5f / 255;

    private PalmContact() {}

    static boolean isWide(MotionEvent event) {
        if (!PAD_6_PRO.equals(Build.DEVICE) || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return false;
        return hasWideFinger(event);
    }

    private static boolean hasWideFinger(MotionEvent event) {
        for (int pointer = 0; pointer < event.getPointerCount(); pointer++) {
            if (event.getToolType(pointer) != MotionEvent.TOOL_TYPE_FINGER) continue;
            if (event.getSize(pointer) >= WIDE_CONTACT) return isBuiltInTouchscreen(event);
            for (int sample = 0; sample < event.getHistorySize(); sample++) {
                if (event.getHistoricalSize(pointer, sample) >= WIDE_CONTACT) return isBuiltInTouchscreen(event);
            }
        }
        return false;
    }

    private static boolean isBuiltInTouchscreen(MotionEvent event) {
        InputDevice device = event.getDevice();
        return device != null && TOUCHSCREEN.equals(device.getName());
    }
}
