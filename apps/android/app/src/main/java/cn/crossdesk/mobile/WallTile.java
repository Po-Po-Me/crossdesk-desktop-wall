package cn.crossdesk.mobile;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One desktop on the wall: a still frame, a live overlay area and one line of state.
 *
 * <p>Preview never sends input. The live stream is a single shared surface moved from tile to
 * tile, so a tile only hosts the overlay while it owns the stream.
 */
@android.annotation.SuppressLint("ViewConstructor")
final class WallTile extends FrameLayout {
    static final int STATE_IDLE = 0, STATE_CONNECTING = 1, STATE_LOADING = 2, STATE_PREVIEW = 3, STATE_FAILED = 4;

    private final MobileUi ui;
    private final ImageView still;
    private final TextView placeholder;
    private final FrameLayout live;
    private final TextView badge;
    private final TextView stateLabel;
    private final TextView name;
    private final TextView subtitle;
    private TextureView host;
    private final FrameLayout picture;

    WallTile(MobileUi ui) {
        super(ui.context);
        this.ui = ui;
        setBackgroundColor(0xFF1A1C20);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ui.dp(16));
            }
        });
        picture = new FrameLayout(ui.context);   // picture window, above the caption strip
        addView(picture, new LayoutParams(-1, -2));
        still = new ImageView(ui.context);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.addView(still, new LayoutParams(-1, -1));
        placeholder = ui.text("", 15, true);
        placeholder.setTextColor(0x99FFFFFF);
        placeholder.setGravity(Gravity.CENTER);
        picture.addView(placeholder, new LayoutParams(-1, -1));
        // Height is resolved here because the picture window has no intrinsic size.
        addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int caption = 0;
            if (getChildCount() > 1) caption = getChildAt(1).getHeight();
            int height = Math.max(0, getHeight() - caption);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) picture.getLayoutParams();
            if (params.height != height) { params.height = height; picture.setLayoutParams(params); }
        });
        live = new FrameLayout(ui.context);
        live.setVisibility(GONE);
        picture.addView(live, new LayoutParams(-1, -1));
        LinearLayout strip = ui.column();
        strip.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(8));
        strip.setBackgroundColor(0xCC000000);
        LinearLayout top = ui.row();
        badge = ui.text("", 10, true);
        badge.setTextColor(Color.WHITE);
        badge.setPadding(ui.dp(6), ui.dp(1), ui.dp(6), ui.dp(1));
        badge.setBackground(ui.background(0x33FFFFFF, 6));
        top.addView(badge);
        top.addView(new View(ui.context), new LinearLayout.LayoutParams(0, 1, 1));
        stateLabel = ui.text("", 10, false);
        stateLabel.setTextColor(0xCCFFFFFF);
        top.addView(stateLabel);
        strip.addView(top);
        name = ui.text("", 13, true);
        name.setTextColor(Color.WHITE);
        strip.addView(name, ui.size(-1, 20));
        subtitle = ui.text("", 11, false);
        subtitle.setTextColor(0x99FFFFFF);
        strip.addView(subtitle, ui.size(-1, 16));
        addView(strip, new LayoutParams(-1, -2, Gravity.BOTTOM));
        setForeground(ui.selectable(0x22FFFFFF));
        setFocusable(true);
        setClickable(true);
        setContentDescription(L10n.tr("预览这个桌面，轻触开始控制"));
    }

    /** The shared stream surface is displayed here while this tile owns the stream. */
    void hostStream(TextureView surface) {
        host = surface;
        if (surface == null) return;
        FrameLayout parent = (FrameLayout) surface.getParent();
        if (parent != null) parent.removeView(surface);
        live.addView(surface, new LayoutParams(-1, -1));
    }

    /** Picture area inside the card, in this view's coordinates (above the caption strip). */
    void streamBounds(Rect out) {
        int[] tileLocation = new int[2], pictureLocation = new int[2];
        getLocationOnScreen(tileLocation);
        picture.getLocationOnScreen(pictureLocation);
        out.set(pictureLocation[0] - tileLocation[0], pictureLocation[1] - tileLocation[1],
                pictureLocation[0] - tileLocation[0] + picture.getWidth(),
                pictureLocation[1] - tileLocation[1] + picture.getHeight());
    }

    void showLive(boolean value) {
        boolean mine = value && host != null && live.getChildCount() > 0;
        live.setVisibility(mine ? VISIBLE : GONE);
        still.setAlpha(mine ? 0f : 1f);
        placeholder.setAlpha(mine ? 0f : 1f);
    }

    void showStill(Bitmap bitmap, String label) {
        if (bitmap == null) {
            still.setVisibility(GONE);
            placeholder.setVisibility(VISIBLE);
            placeholder.setText(label);
        } else {
            still.setImageBitmap(bitmap);
            still.setVisibility(VISIBLE);
            placeholder.setVisibility(GONE);
        }
    }

    void showLabels(String platform, String title, String detail) {
        badge.setText(platform == null || platform.isEmpty() ? L10n.tr("远程电脑") : platform);
        name.setText(title);
        subtitle.setText(detail);
    }

    void showState(int state, String text) {
        if (state == STATE_PREVIEW) subtitle.setText(L10n.tr("预览"));
        else if (state == STATE_IDLE) subtitle.setText(L10n.tr("轻触开始控制"));
        stateLabel.setText(text == null ? "" : text);
        stateLabel.setTextColor(state == STATE_FAILED ? 0xFFFF8A80 : 0xCCFFFFFF);
    }
}
