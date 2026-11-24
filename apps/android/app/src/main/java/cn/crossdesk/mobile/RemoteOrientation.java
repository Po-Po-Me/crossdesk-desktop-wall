package cn.crossdesk.mobile;

import android.app.Activity;
import android.content.pm.ActivityInfo;

/** Applies the selected orientation policy for the whole remote session. */
final class RemoteOrientation {
    private final Activity activity;
    private boolean autoLandscape;

    RemoteOrientation(Activity activity) {
        this.activity = activity;
    }

    void begin(boolean autoLandscape, boolean fullScreenPhone) {
        this.autoLandscape = autoLandscape;
        update(fullScreenPhone);
    }

    boolean autoLandscape() { return autoLandscape; }

    void end() { autoLandscape = false; }

    void update(boolean fullScreenPhone) {
        int orientation = autoLandscape && fullScreenPhone
            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        if (activity.getRequestedOrientation() != orientation) activity.setRequestedOrientation(orientation);
    }

    /**
     * Explicit rotate button: land in one orientation and stay there (the system will not flip
     * back until the user rotates again). Tablets and multi-window keep their own policy.
     */
    void lockLandscape(boolean fullScreenPhone) {
        if (!fullScreenPhone) return;
        autoLandscape = true;
        if (activity.getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    void lockPortrait(boolean fullScreenPhone) {
        if (!fullScreenPhone) return;
        autoLandscape = false;
        if (activity.getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    }

    /** Whether the device is currently held sideways, for the rotate button's label. */
    static boolean isLandscape(Activity activity) {
        return activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }
}
