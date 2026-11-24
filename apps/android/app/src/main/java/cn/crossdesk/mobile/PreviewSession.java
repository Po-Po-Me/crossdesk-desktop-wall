package cn.crossdesk.mobile;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * One lightweight controller per desktop, used only to show a live picture on the wall.
 *
 * <p>The peer keeps its own mirror texture, so the wall controller can move the single live
 * stream from tile to tile without recreating surfaces. Hosts that support the DeskHub
 * subscription get a reduced cadence; other hosts still deliver their normal video stream so
 * the tile is not blank, only more expensive.
 */
final class PreviewSession implements NativeSession.Listener {
    interface Tile {
        void previewState(int state, String detail);
        /** The host's pointer, so the wall tile can draw it over the shared surface. */
        default void previewCursor(boolean visible, int shape, boolean positionUpdate,
                                   boolean positionValid, float x, float y,
                                   float offsetX, float offsetY, int display, long sequence) { }
    }

    static final int STATE_CONNECTING = 0, STATE_CONNECTED = 1, STATE_FAILED = 2;
    /**
     * The wall always streams at a normal interactive cadence and only uses the on-screen
     * flag to decide when to pause. Reduced preview tiers (1/8/15) are refused by hosts that
     * hold a shared-screen lease, and a refused request left a tile frozen forever.
     */
    static final int FPS_PAUSED = 0, FPS_WALL = 30;
    private static final int QUALITY = 1, FRAME_RATE = 30, PREFERENCE = 1;
    private static final int WALL_WIDTH = 1280, WALL_HEIGHT = 720;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final String host;
    private final int port;
    private final String id;
    private final String password;
    private volatile int display;
    private final Tile tile;
    private NativeSession session;
    private MirrorTexture mirror;
    private boolean closed, connected, handedOff;
    private int fps = FPS_PAUSED;
    private long request;
    private boolean accepted, pending, paused;
    private long lastKeyframe;
    volatile Runnable onFrame;
    /** The wall tells the session whether its picture is the one on screen. */
    volatile boolean isStreaming;

    PreviewSession(String host, int port, String id, String password, int display, Tile tile) {
        this.host = host; this.port = port; this.id = id; this.password = password;
        this.display = Math.max(0, display);
        this.tile = tile;
    }

    String id() { return id; }

    /** The wall owns the shared mirror; the peer renders straight into it. */
    void begin(MirrorTexture target) {
        main.post(() -> {
            if (closed) return;
            try {
                mirror = target;
                android.content.Context app = L10n.applicationContext();
                if (app == null) { fail(L10n.tr("预览暂不可用")); return; }
                session = new NativeSession(app, host, port, id, password, this);
                session.previewOnly();
                session.useMirror(target);
                session.start();
                session.connectAs("");
            } catch (RuntimeException error) {
                fail(L10n.tr("预览暂不可用"));
            }
        });
    }

    /** Show another display of this desktop; the host keeps the same peer. */
    void setDisplay(int index) {
        main.post(() -> {
            if (closed || !connected || handedOff || index < 0 || index == display) return;
            display = index;
            if (session != null) session.control(4, index);
            lastKeyframe = SystemClock.elapsedRealtime();
        });
    }

    void setFps(int value) {
        isStreaming = value != FPS_PAUSED;
        main.post(() -> { fps = value; refresh(); });
    }

    /** Hand the live peer to the interactive screen instead of reconnecting. */
    NativeSession take() {
        NativeSession current = session;
        session = null;
        handedOff = true;
        main.removeCallbacks(ticker);
        // Stop wall rendering first: the native side must release the shared surface
        // before the interactive SurfaceView is attached.
        if (current != null) current.leaveWall();
        mirror = null;
        return current;
    }

    void close() {
        main.post(() -> {
            if (closed) return;
            closed = true;
            main.removeCallbacks(ticker);
            if (session != null) session.close();
            session = null;
            mirror = null;
        });
    }

    private void fail(String detail) {
        if (closed) return;
        tile.previewState(STATE_FAILED, detail);
        close();
    }

    private void refresh() {
        if (session == null || !connected || handedOff) return;
        if (pending || paused == (fps == FPS_PAUSED)) return;
        subscribe(fps);
    }

    private void subscribe(int value) {
        if (session == null || !connected || handedOff) return;
        request++;
        pending = true;
        paused = value == FPS_PAUSED;
        // Same message the control screen sends; the peer simply never sends input.
        session.wallSubscription(value, WALL_WIDTH, WALL_HEIGHT, QUALITY, FRAME_RATE, PREFERENCE);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (closed || handedOff || !connected) return;
            refresh();
            main.postDelayed(this, 800);
        }
    };

    // Listener calls arrive on the main thread.
    @Override public void status(String message) { }

    @Override public void connected() {
        connected = true;
        session.attachMirror();
        session.setMirrorActive(true);
        tile.previewState(STATE_CONNECTED, "");
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 300);
    }

    @Override public void ended(String reason) {
        if (handedOff) return;
        connected = false;
        fail(reason == null || reason.isEmpty() ? L10n.tr("连接已结束") : reason);
    }

    @Override public void videoSize(int width, int height) { lastKeyframe = SystemClock.elapsedRealtime(); }

    @Override public void remoteCursor(boolean visible, int shape, boolean positionUpdate,
                                       boolean positionValid, float x, float y,
                                       float offsetX, float offsetY, int display, long sequence) {
        if (handedOff || !isStreaming) return;
        tile.previewCursor(visible, shape, positionUpdate, positionValid, x, y, offsetX, offsetY, display, sequence);
    }
    long lastFrame() { return lastKeyframe; }

    @Override public void previewFrame() {
        if (handedOff) return;
        lastKeyframe = SystemClock.elapsedRealtime();
        Runnable callback = onFrame;
        if (callback != null && isStreaming) main.post(callback);
    }

    @Override public void data(org.json.JSONObject message) { }

    @Override public void clipboard(String text) { }

    @Override public void statistics(RemoteNetworkStatistics.Snapshot value) { }

    @Override public void controlFeedback(String message) { }

    @Override public void privacyChanged() { }

    @Override public void signaling(int state, String deviceId) { }

    @Override public void signalingEnded(String reason) { ended(reason); }

    @Override public void presence(org.json.JSONObject message) { }

    @Override public void announcement(org.json.JSONObject message) { }

    @Override public void announcementFailed(String requestId) { }

    @Override public void passwordRejected() { ended(L10n.tr("远端密码错误")); }

    @Override public void previewStream(boolean value, long requestId) {
        if (requestId != request) return;
        pending = false;
        accepted = value;
        // A refused request must not leave the host paused; ask again at the same cadence.
        if (!value && !paused) main.postDelayed(this::refresh, 2500);
    }

    @Override public void previewStatus(boolean supported, boolean value) {
        accepted = value;
    }
}
