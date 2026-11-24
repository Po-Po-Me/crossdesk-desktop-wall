package cn.crossdesk.mobile;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.TextureView;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A wall of desktops that shows live pictures before any control is taken.
 *
 * <p>Media is one stream per desktop, so the wall gives a desktop the stream only while its
 * tile is on screen and the user is looking at it; a single shared preview peer is reused in a
 * round-robin so N desktops do not open N full-quality sessions. Tapping a tile stops the
 * preview loop and hands that same peer to the interactive session, so control starts without
 * a second connection and without re-entering a password.
 */
final class DesktopWallController {
    interface Host {
        /** Start interactive control for one desktop, reusing the live preview peer when possible. */
        void openControl(String id, NativeSession live, boolean liveUsable);
        void wallMessage(String message);
    }

    static final long DWELL_MS = 4200, SETTLE_MS = 900, VISIBLE_MS = 1200, CONTROL_GRACE_MS = 8000,
            FIRST_FRAME_TIMEOUT_MS = 7000;
    private static final int MAX_DESKTOPS = 16;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Host host;
    private final String server;
    private final int port;
    private final int passwordHash;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final List<WallTile> tiles = new ArrayList<>();
    private final Map<String, Bitmap> stills = new HashMap<>();
    private volatile TextureView streamHost;
    private long lastBindCheck;
    private MirrorTexture mirror;
    private Entry streaming;
    private boolean visible, paused;
    private long dwell = DWELL_MS;

    static final class Entry {
        final String id, platform, name;
        final String password;
        final int displays, rememberedDisplay;
        PreviewSession session;
        MirrorTexture mirror;
        WallTile tile;
        int state = WallTile.STATE_IDLE;
        String detail = "";
        long visibleSince, lastActive, lastKeyframe;
        int failures, retries, index;
        void tileState(int value, String text) {
            state = value;
            detail = text == null ? "" : text;
            if (tile != null) tile.showState(value, detail);
        }

        Entry(String id, String platform, String name, String password, int displays, int rememberedDisplay) {
            this.id = id; this.platform = platform; this.name = name; this.password = password;
            this.displays = Math.max(1, displays); this.rememberedDisplay = Math.max(0, rememberedDisplay);
        }
    }

    DesktopWallController(Host host, String server, int port, int passwordHash) {
        this.host = host; this.server = server; this.port = port; this.passwordHash = passwordHash;
    }

    int size() { return entries.size(); }

    List<Entry> entries() { return new ArrayList<>(entries.values()); }

    /** Replace the wall membership; surviving desks keep their live session. */
    void setDevices(List<Entry> next) {
        List<Entry> incoming = new ArrayList<>(next.subList(0, Math.min(MAX_DESKTOPS, next.size())));
        for (int i = incoming.size() - 1; i >= 0; i--) {
            Entry candidate = incoming.get(i);
            // A desktop appears once, even when several groups list it.
            for (int j = i - 1; j >= 0; j--)
                if (incoming.get(j).id.equals(candidate.id)) incoming.remove(i);
        }
        LinkedHashMap<String, Entry> merged = new LinkedHashMap<>();
        for (int i = 0; i < incoming.size(); i++) {
            Entry candidate = incoming.get(i);
            Entry existing = entries.get(candidate.id);
            if (existing != null) {
                existing.index = i;
                merged.put(existing.id, existing);
            } else {
                candidate.index = i;
                merged.put(candidate.id, candidate);
            }
        }
        for (Entry gone : entries.values())
            if (!merged.containsKey(gone.id)) release(gone);
        entries.clear();
        entries.putAll(merged);
        schedule(0);
    }

    /** Wire the tile views to the current membership; one tile per desktop, in list order. */
    void attachTiles(List<WallTile> list) {
        tiles.clear();
        tiles.addAll(list);
        int index = 0;
        for (Entry entry : entries.values()) {
            entry.tile = index < tiles.size() ? tiles.get(index++) : null;
            if (entry.tile != null) {
                WallTile tile = entry.tile;
                tile.setOnClickListener(view -> openControl(entry));
                tile.showLabels(entry.platform, entry.name, entry.mirror == null ? L10n.tr("轻触开始控制") : L10n.tr("预览中"));
                tile.showStill(stills.get(entry.id), platformLabel(entry.platform));
                tile.showLive(entry == streaming && entry.mirror != null);
            }
        }
        for (; index < tiles.size(); index++) tiles.get(index).showLive(false);
        rebindStream();
        schedule(0);
    }

    /**
     * The wall has one picture surface; each tile draws a still and the shared surface is
     * overlaid on the tile that currently receives frames.
     */
    void setStreamHost(TextureView host) {
        streamHost = host;
        rebindStream();
    }

    private void rebindStream() {
        TextureView host = streamHost;
        Entry current = streaming;
        if (host == null) return;
        if (current != null && current.mirror != null && current.tile != null && current.mirror.bind(host)) {
            current.tile.hostStream(host);
            current.tile.showLive(true);
            // Follow the tile across scrolls so the picture never slips under a sibling card.
            host.post(this::trackTile);
        }
    }

    /** Keep the shared surface exactly on the picture area of the tile that owns the stream. */
    private void trackTile() {
        TextureView host = streamHost;
        Entry current = streaming;
        if (host == null || current == null || current.tile == null) return;
        WallTile tile = current.tile;
        if (tile.getWidth() <= 0 || tile.getHeight() <= 0) { host.postDelayed(this::trackTile, 200); return; }
        if (tile.getWindowToken() == null || !tile.isAttachedToWindow()) { host.postDelayed(this::trackTile, 200); return; }
        Rect picture = new Rect();
        tile.pictureRect(picture);
        int[] hostLocation = new int[2];
        host.getLocationOnScreen(hostLocation);
        host.setTranslationX(picture.left - hostLocation[0]);
        host.setTranslationY(picture.top - hostLocation[1]);
        android.view.ViewGroup.LayoutParams params = host.getLayoutParams();
        if (params.width != picture.width() || params.height != picture.height()) {
            params.width = picture.width();
            params.height = picture.height();
            host.setLayoutParams(params);
        }
        host.setVisibility(picture.width() > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        host.postDelayed(this::trackTile, 120);
    }

    void setStill(String id, Bitmap bitmap) {
        if (bitmap == null) stills.remove(id); else stills.put(id, bitmap);
    }

    private String platformLabel(String platform) {
        return "windows".equals(platform) ? "Windows"
                : "macos".equals(platform) ? "macOS"
                : "linux".equals(platform) ? "Linux"
                : L10n.tr("远程电脑");
    }

    /** The wall page became visible or hidden; hidden walls stop all decode. */
    /** The shared wall surface; the owner hands it to control when a tile is opened. */
    MirrorTexture mirror() { return mirror; }

    /**
     * Control takes the whole wall down. The surface must stay alive until the interactive
     * screen has attached its own SurfaceView, so only membership and timers are dropped here.
     */
    void detachStream() {
        main.removeCallbacks(advance);
        visible = false; paused = true; streaming = null;
        for (Entry entry : entries.values()) {
            entry.session = null; entry.mirror = null; entry.tile = null;
        }
        entries.clear(); tiles.clear();
    }

    /** Release the wall surface after the interactive screen owns the peer. */
    void disposeStream() {
        if (mirror != null) { mirror.release(); mirror = null; }
    }

    void setVisible(boolean value) {
        visible = value;
        for (Entry entry : entries.values()) {
            if (!value) {
                if (entry.session != null) entry.session.setFps(PreviewSession.FPS_PAUSED);
            } else {
                entry.lastActive = SystemClock.elapsedRealtime();
            }
        }
        schedule(value ? 0 : -1);
    }

    void setPaused(boolean value) {
        paused = value;
        if (value) {
            for (Entry entry : entries.values()) if (entry.session != null) entry.session.setFps(PreviewSession.FPS_PAUSED);
        }
        schedule(0);
    }

    void setDwell(long millis) {
        dwell = Math.max(1500, Math.min(20000, millis));
        schedule(dwell);
    }

    /** Display index the streaming peer is showing, so control opens on the same screen. */
    int displayHint() {
        Entry current = streaming;
        return current == null ? 0 : current.rememberedDisplay;
    }

    /** A tile was tapped: pause the preview loop and hand the stream to interactive control. */
    void openControl(Entry entry) {
        if (entry == null) return;
        if (streaming == entry) streaming = null;
        for (Entry other : entries.values()) if (other != entry && other.session != null) other.session.setFps(PreviewSession.FPS_PAUSED);
        schedule(-1);
        NativeSession live = entry.session == null ? null : entry.session.take();
        entry.session = null;
        // The stream owner is gone, so the wall has no picture to show any more.
        if (entry.tile != null) entry.tile.showLive(false);
        host.openControl(entry.id, live, live != null);
    }

    /** Everything the wall owns for this desktop, released when the page is left. */
    void close() {
        main.removeCallbacksAndMessages(null);
        visible = false; streaming = null;
        for (Entry entry : entries.values()) release(entry);
        entries.clear();
        tiles.clear();
        if (mirror != null) { mirror.release(); mirror = null; }
    }

    private void release(Entry entry) {
        if (entry.tile != null) entry.tile.showLive(false);
        if (streaming == entry) streaming = null;
        if (entry.session != null) entry.session.close();
        entry.session = null;
        entry.mirror = null;
        entry.state = WallTile.STATE_IDLE;
        entry.detail = "";
    }

    private void schedule(long delayMs) {
        main.removeCallbacks(advance);
        if (!visible || paused || delayMs < 0) return;
        if (entries.isEmpty()) return;
        main.postDelayed(advance, Math.max(0, delayMs));
    }

    private final Runnable advance = () -> {
        if (!visible || paused || entries.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        Entry current = streaming;
        // Give the current stream a chance to prove itself before moving on.
        if (current != null) {
            int state = current.session == null ? WallTile.STATE_IDLE : current.state;
            if (state != WallTile.STATE_FAILED) {
                long held = now - (current.session == null ? now : current.lastActive);
                if (held < dwell) {
                    schedule(dwell - held);
                    return;
                }
            }
        }
        Entry next = current == null ? first() : after(current);
        if (next == null) return;
        switchTo(next, now);
    };

    private Entry first() {
        for (Entry entry : entries.values()) if (entry.state != WallTile.STATE_FAILED || entry.failures < 3) return entry;
        return entries.values().iterator().next();
    }

    private Entry after(Entry current) {
        boolean seen = false;
        for (Entry entry : entries.values()) {
            if (seen && (entry.state != WallTile.STATE_FAILED || entry.failures < 3)) return entry;
            if (entry == current) seen = true;
        }
        for (Entry entry : entries.values())
            if (entry != current && (entry.state != WallTile.STATE_FAILED || entry.failures < 3)) return entry;
        return null;
    }

    private void switchTo(Entry entry, long now) {
        if (streaming != null && streaming.session != null) streaming.session.setFps(PreviewSession.FPS_PAUSED);
        streaming = entry;
        entry.lastActive = now;
        entry.visibleSince = now;
        if (entry.session == null) start(entry, now);
        else entry.session.setFps(PreviewSession.FPS_WALL);
        for (Entry other : entries.values()) {
            if (other == entry) continue;
            other.tileState(WallTile.STATE_IDLE, "");
            if (other.tile != null) other.tile.showLive(false);
        }
        entry.lastKeyframe = 0;
        rebindStream();
        entry.tileState(WallTile.STATE_CONNECTING, entry.failures > 0 ? L10n.tr("正在重新连接…") : L10n.tr("正在建立预览连接…"));
        // Give a fresh peer a bounded window for its first picture, then restart it once.
        final Entry scheduled = entry;
        main.postDelayed(() -> {
            if (streaming != scheduled || scheduled.lastKeyframe != 0) return;
            if (scheduled.retries++ >= 1) {
                scheduled.tileState(WallTile.STATE_FAILED, L10n.tr("没有收到画面"));
                return;
            }
            if (scheduled.session != null) { scheduled.session.close(); scheduled.session = null; }
            scheduled.tileState(WallTile.STATE_CONNECTING, L10n.tr("正在重新连接…"));
            start(scheduled, SystemClock.elapsedRealtime());
        }, FIRST_FRAME_TIMEOUT_MS);
        schedule(dwell);
    }

    private void start(final Entry entry, long now) {
        if (mirror == null) mirror = MirrorTexture.create();
        entry.mirror = mirror;
        PreviewSession session = new PreviewSession(server, port, entry.id, entry.password, entry.rememberedDisplay,
                (state, detail) -> onPreviewState(entry, state, detail));
        session.onFrame = () -> onFrame(entry);
        entry.session = session;
        entry.state = WallTile.STATE_CONNECTING;
        entry.lastActive = now;
        session.begin(entry.mirror);
    }

    private void onPreviewState(Entry entry, int state, String detail) {
        if (entry.session == null) return;
        boolean active = entry == streaming;
        if (state == PreviewSession.STATE_CONNECTED) {
            entry.failures = 0;
            if (active) {
                entry.lastKeyframe = SystemClock.elapsedRealtime();
                entry.tileState(WallTile.STATE_LOADING, L10n.tr("正在获取画面"));
                rebindStream();
            }
            entry.session.setFps(active ? PreviewSession.FPS_WALL : PreviewSession.FPS_PAUSED);
        } else if (state == PreviewSession.STATE_FAILED) {
            entry.failures++;
            entry.session.close();
            entry.session = null;
            entry.tileState(WallTile.STATE_FAILED, detail);
            if (active) {
                streaming = null;
                schedule(1200);
            }
        } else {
            entry.tileState(WallTile.STATE_CONNECTING, detail);
        }
    }

    boolean streamingNow(){return streaming!=null;}

    /** Start the rotation now (the surface became available after the page was built). */
    void kick(){schedule(0);}

    /** True when the streaming tile has produced no picture for the given time. */
    boolean stalled(long millis) {
        Entry current = streaming;
        if (current == null || current.session == null) return false;
        long last = current.session.lastFrame();
        return last != 0 && SystemClock.elapsedRealtime() - last > millis;
    }

    /** Route a decoded frame notification of the given peer id to the tile that owns it. */
    void onPeerFrame(String peerId) {
        Entry entry = entries.get(peerId);
        if (entry != null) onFrame(entry);
    }

    /** Called when the streaming peer renders a frame: the picture is live, extend the dwell. */
    void onFrame(Entry entry) {
        if (entry == null || entry != streaming) return;
        entry.lastKeyframe = SystemClock.elapsedRealtime();
        boolean first = entry.state != WallTile.STATE_PREVIEW;
        if (first) {
            entry.tileState(WallTile.STATE_PREVIEW, L10n.tr("预览"));
            rebindStream();
            entry.tile.showStill(null, platformLabel(entry.platform));
            entry.tile.showLive(true);
        }
        // The shared surface can be re-bound by layout changes; keep it on the live tile.
        if (first || SystemClock.elapsedRealtime() - lastBindCheck > 1000) {
            lastBindCheck = SystemClock.elapsedRealtime();
            rebindStream();
        }
    }


}
