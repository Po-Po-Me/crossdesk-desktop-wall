package cn.crossdesk.mobile;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;

/**
 * The wall's single decode surface, shared by every preview peer.
 *
 * <p>Only one peer streams at a time, so one producer surface is enough; the native side
 * attaches that surface to the streaming peer and re-attaches it when the wall moves on.
 */
final class MirrorTexture {
    final SurfaceTexture texture = new SurfaceTexture(0);
    final Surface surface = new Surface(texture);

    private MirrorTexture() {
        // A wall tile never needs more than the 1440x810 it subscribes to.
        texture.setDefaultBufferSize(1440, 810);
    }

    static MirrorTexture create() { return new MirrorTexture(); }

    /** A TextureView is the sole consumer of this texture. */
    boolean bind(TextureView target) {
        if (target.getSurfaceTexture() == texture) return true;
        return target.setSurfaceTexture(texture);
    }

    void release() {
        try { surface.release(); } catch (RuntimeException ignored) { }
        texture.release();
    }
}
