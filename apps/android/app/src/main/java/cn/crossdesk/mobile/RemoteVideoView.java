package cn.crossdesk.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

@android.annotation.SuppressLint("ViewConstructor") // Constructed only with a live session, never from XML.
final class RemoteVideoView extends FrameLayout implements SurfaceHolder.Callback {
    private final SurfaceView video;
    private final Cursor cursor;
    private final NativeSession session;
    final RemoteMouseInput mouseInput;
    Runnable remoteFocusChanged;
    private final GestureDetector gestures;
    private final float pinchSlop;
    private final RemoteViewport viewport = new RemoteViewport();
    private final RemoteViewport pictureInPictureViewport = new RemoteViewport();
    private boolean pictureInPicture;
    private int frameWidth = 16, frameHeight = 9;
    private float x = .5f, y = .5f, scrollY;
    private boolean dragging, twoFinger, relative, pinching, viewportPanActive;
    private float lastX,lastY,twoFingerX,twoFingerY;
    private float pinchSpan;
    private long touchRevision;
    private boolean suppressPointerGesture;
    private boolean viewOnly;
    // Remote cursor presentation, reported by the host on the mouse stream. The captured
    // frame usually excludes the pointer, so the phone draws it at the host's coordinates.
    private boolean remoteCursorReported, remoteCursorVisible, remoteCursorPositionValid;
    private float remoteCursorX = .5f, remoteCursorY = .5f;
    private float remoteCursorOffsetX, remoteCursorOffsetY;
    private int remoteCursorShape, remoteCursorDisplay = -1;
    private long remoteCursorSequence = Long.MIN_VALUE;
    private boolean cursorTracked;
    private long viewOnlyDownAt, viewOnlyLastTap;
    private float viewOnlyDownX, viewOnlyDownY;
    /** Notified when the local zoom changes so the screen can show the current factor. */
    Runnable viewOnlyFeedback = () -> { };
    /** Current local zoom, for the screen to show a factor such as "2.0×". */
    float zoomScale() { return viewport.scale(); }
    void resetZoom() { viewport.reset(); applyViewport(); }
    /** Zoom around the centre of the picture; used by the on-screen +/- buttons. */
    void zoomBy(float factor) {
        if (!viewOnly || factor <= 0 || !Float.isFinite(factor)) return;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        viewport.zoomBy(factor, cx, cy);
        applyViewport();
    }
    void setRelative(boolean value) { releaseMouse(); relative=value; }
    void setPictureInPicture(boolean value) {
        if(pictureInPicture==value)return;
        releaseMouse();pictureInPicture=value;cursor.setVisibility(value?GONE:VISIBLE);fit();
    }
    Rect pictureInPictureBounds() {
        Rect bounds=new Rect();video.getGlobalVisibleRect(bounds);return bounds;
    }
    void releaseMouse() { mouseInput.releaseAll(); dragging=false; suppressPointerGesture=true; }
    /** Watch-only: touches still pan/zoom locally, but no pointer or tap reaches the remote. */
    void setViewOnly(boolean value) {
        viewOnly=value;
        if(value)releaseMouse();
        cursor.setVisibility(value?GONE:VISIBLE);
    }
    boolean viewOnly() { return viewOnly; }

    /**
     * Apply one cursor_state sample. Only newer sequences win; a position sample for another
     * display is ignored so a mirror of monitor 2 does not paint a pointer onto monitor 1.
     */
    void remoteCursor(boolean visible, int shape, boolean positionUpdate, boolean positionValid,
                      float x, float y, float offsetX, float offsetY, int display, long sequence) {
        if (sequence != 0 && sequence <= remoteCursorSequence) return;
        remoteCursorSequence = sequence;
        remoteCursorReported = true;
        remoteCursorVisible = visible;
        remoteCursorShape = Math.max(0, Math.min(28, shape));
        remoteCursorOffsetX = offsetX;
        remoteCursorOffsetY = offsetY;
        if (positionUpdate) {
            remoteCursorDisplay = display;
            if (positionValid) {
                remoteCursorPositionValid = true;
                remoteCursorX = Math.max(0f, Math.min(1f, x));
                remoteCursorY = Math.max(0f, Math.min(1f, y));
            } else {
                remoteCursorPositionValid = false;
            }
        }
        cursor.invalidate();
    }
    void remoteCursorDisplay(int display) {
        if (remoteCursorDisplay == display) return;
        remoteCursorDisplay = display;
        cursor.invalidate();
    }
    private boolean suppressPointerGesture() {
        return suppressPointerGesture || mouseInput.hasVirtualButton() || touchRevision != mouseInput.revision();
    }
    RemoteVideoView(Context context, NativeSession session) {
        super(context); this.session = session;
        setFocusable(true);
        setFocusableInTouchMode(true);
        // Own every gesture here: the SurfaceView child must not take the multi-touch stream.
        setOnTouchListener(null);
        mouseInput = new RemoteMouseInput(this::sendMouse);
        mouseInput.relativeMotion = this::moveRelative;
        setBackgroundColor(Color.BLACK);
        video = new SurfaceView(context); video.getHolder().addCallback(this);
        addView(video, new LayoutParams(1, 1, android.view.Gravity.CENTER));
        cursor = new Cursor(context);
        // Keep input in container coordinates so transforms never move its hit area.
        addView(cursor, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return point(e); }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (!twoFinger && !suppressPointerGesture() && point(e)) cursor.performClick(); return true;
            }
            @Override public void onLongPress(MotionEvent e) { if (!twoFinger && !dragging && !suppressPointerGesture() && point(e)) click(3, 4); }
            @Override public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
                if (twoFinger) return true;
                if (!usesRelativeMotion(current) && !dragging && !suppressPointerGesture()) {
                    if (!point(first)) return true;
                    mouseInput.gestureLeft(true); dragging = true;
                }
                point(current); return true;
            }
        });
        // Let the remote desktop detect double-clicks; the default listener consumes the second tap.
        gestures.setOnDoubleTapListener(null);
        // Detect a change in finger spacing even when the fingers start close together.
        pinchSlop = ViewConfiguration.get(context).getScaledTouchSlop() * .5f;
        cursor.setOnGenericMotionListener((v, event) -> {
            if (!point(event)) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                float delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (delta != 0) session.pointer(x, y, 7, delta > 0 ? 1 : -1);
            }
            return true;
        });
        setContentDescription(L10n.tr("远程桌面：轻触点击，拖动移动，长按右键，双指捏合缩放，放大后双指拖动画面，原始比例下双指上下滑动滚动"));
    }
    void videoSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        frameWidth = width; frameHeight = height; fit();
    }
    void resetViewport() { releaseMouse(); viewport.reset(); applyViewport(); }
    void setKeyboardInset(int inset) {
        if (viewport.setKeyboardInset(inset)) applyViewport();
    }
    static Rect previewCrop(int width,int height){
        int w=width,h=height;
        if((long)width*RemotePreviewStore.HEIGHT>(long)height*RemotePreviewStore.WIDTH)w=Math.max(1,height*RemotePreviewStore.WIDTH/RemotePreviewStore.HEIGHT);
        else h=Math.max(1,width*RemotePreviewStore.HEIGHT/RemotePreviewStore.WIDTH);
        return new Rect((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2);
    }
    void capturePreview(java.util.function.Consumer<Bitmap> completion){
        // Copy only the decoded-video Surface, excluding cursor, controls, keyboard and black margins.
        if(!isAttachedToWindow()||!video.getHolder().getSurface().isValid()){completion.accept(null);return;}
        Bitmap image=Bitmap.createBitmap(RemotePreviewStore.WIDTH,RemotePreviewStore.HEIGHT,Bitmap.Config.ARGB_8888);
        try{
            PixelCopy.request(video.getHolder().getSurface(),previewCrop(frameWidth,frameHeight),image,result->{
                if(result==PixelCopy.SUCCESS)completion.accept(image);else{image.recycle();completion.accept(null);}
            },new Handler(Looper.getMainLooper()));
        }catch(IllegalArgumentException error){image.recycle();completion.accept(null);}
    }
    private void fit() {
        if (getWidth() == 0 || getHeight() == 0) return;
        RemoteViewport active=pictureInPicture?pictureInPictureViewport:viewport;
        active.fit(getWidth(), getHeight(), frameWidth, frameHeight);
        video.setLayoutParams(new LayoutParams(active.baseWidth(),active.baseHeight(),android.view.Gravity.CENTER));
        applyViewport();
    }
    private void applyViewport() {
        // Transform the existing Surface without enlarging its decoder buffers.
        RemoteViewport active=pictureInPicture?pictureInPictureViewport:viewport;
        video.setScaleX(active.scale()); video.setScaleY(active.scale());
        video.setTranslationX(active.offsetX()); video.setTranslationY(active.offsetY());
        mouseInput.viewportWidth = active.width(); mouseInput.viewportHeight = active.height();
        cursor.invalidate();
    }
    private void sendMouse(float x, float y, int flag, int wheel) {
        if (viewOnly) return;
        this.x = x; this.y = y;
        if((flag==1||flag==3||flag==5)&&remoteFocusChanged!=null)remoteFocusChanged.run();
        session.pointer(x,y,flag,wheel);
        cursor.invalidate();
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { post(this::fit); }
    private void moveRelative(float dx, float dy) {
        if (pictureInPicture || viewport.width() <= 0 || viewport.height() <= 0) return;
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || (dx == 0 && dy == 0)) return;
        float nextX = Math.max(0, Math.min(1, x + dx / viewport.width()));
        float nextY = Math.max(0, Math.min(1, y + dy / viewport.height()));
        viewport.panForPointer(nextX, nextY, y, dy, 18 * getResources().getDisplayMetrics().density);
        applyViewport();
        mouseInput.move(nextX, nextY);
    }
    private boolean usesRelativeMotion(MotionEvent event) {
        return relative && event.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE;
    }
    private boolean point(MotionEvent e) {
        boolean touchpad = usesRelativeMotion(e);
        float px=e.getX(),py=e.getY();
        if (!touchpad && !viewport.contains(px,py)) return false;
        if(touchpad){
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                moveRelative(px-lastX,py-lastY);
                lastX=px;lastY=py;
                return true;
            }
        }else{
            int action = e.getActionMasked();
            if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) &&
                    (px != lastX || py != lastY) &&
                    viewport.panForAbsolutePointer(px, py, 18 * getResources().getDisplayMetrics().density)) {
                applyViewport();
            }
            x=viewport.normalizedX(px);
            y=viewport.normalizedY(py);
        }
        lastX=px;lastY=py;
        mouseInput.move(x,y); cursor.invalidate();
        return true;
    }
    private void click(int down, int up) { mouseInput.click(down,up); }
    private boolean touch(MotionEvent e) {
        int action = e.getActionMasked();
        // View only: the image can still be zoomed and panned locally, but nothing is sent.
        if (viewOnly) return touchViewOnly(e, action);
        if (action == MotionEvent.ACTION_DOWN) {
            twoFinger = pinching = viewportPanActive = false;
            touchRevision = mouseInput.revision();
            suppressPointerGesture = mouseInput.hasVirtualButton();
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (dragging) { mouseInput.gestureLeft(false); dragging = false; }
            twoFinger = true;
            MotionEvent cancel = MotionEvent.obtain(e); cancel.setAction(MotionEvent.ACTION_CANCEL);
            gestures.onTouchEvent(cancel); cancel.recycle();
        }
        if (twoFinger && e.getPointerCount() >= 2 && (action == MotionEvent.ACTION_MOVE ||
                action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)) {
            updateTwoFingers(e);
        } else if (!twoFinger) {
            if ((e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0 && action == MotionEvent.ACTION_DOWN) {
                if (point(e)) click(3,4); return true;
            }
            gestures.onTouchEvent(e);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (dragging) mouseInput.gestureLeft(false);
            dragging = false;
            viewport.finishGesture(); applyViewport();
            viewportPanActive = false;
        }
        return true;
    }

    /**
     * Watch-only touch: pinch to zoom, one finger to pan while zoomed, double tap to zoom in
     * and out. No pointer, wheel or click message leaves this view.
     */
    private boolean touchViewOnly(MotionEvent e, int action) {
        if (action == MotionEvent.ACTION_DOWN) {
            twoFinger = false;
            pinching = false;
            viewportPanActive = viewport.isZoomed();
            lastX = e.getX();
            lastY = e.getY();
            viewOnlyDownAt = SystemClock.elapsedRealtime();
            viewOnlyDownX = lastX;
            viewOnlyDownY = lastY;
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            twoFinger = true;
            viewportPanActive = false;
            startPinch(e);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (e.getPointerCount() >= 2) { viewOnlyPinchSamples++; movePinch(e); }
            else if (viewport.isZoomed()) panViewOnly(e);
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            // Keep the remaining finger as the new anchor instead of jumping.
            twoFinger = false;
            pinching = false;
            startPinch(e, e.getActionIndex());
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            boolean tap = !pinching && SystemClock.elapsedRealtime() - viewOnlyDownAt < 260
                    && Math.hypot(e.getX() - viewOnlyDownX, e.getY() - viewOnlyDownY) < pinchSlop * 3;
            if (tap) { viewOnlyTaps++; handleViewOnlyTap(e.getX(), e.getY()); }
            twoFinger = false;
            pinching = false;
            viewport.finishGesture();
            applyViewport();
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            twoFinger = false;
            pinching = false;
            viewport.finishGesture();
            applyViewport();
            return true;
        }
        return true;
    }

    private float pinchStartSpan;
    /** Watch-only gesture counters, also used by logcat to confirm fingers reach this view. */
    int viewOnlyPinchSamples, viewOnlyTaps;
    private void startPinch(MotionEvent e) { startPinch(e, -1); }
    private void startPinch(MotionEvent e, int skipIndex) {
        float[] focus = focus(e, skipIndex);
        pinchStartSpan = Math.max(pinchSlop, span(e, focus[0], focus[1], skipIndex));
        twoFingerX = focus[0];
        twoFingerY = focus[1];
    }
    private void movePinch(MotionEvent e) {
        if (pinchStartSpan <= 0) startPinch(e);
        float[] focus = focus(e, -1);
        // No slop gate: any finger-spread change scales the picture immediately, so a slow or
        // short pinch still zooms. The ratio is clamped to keep one bad sample from jumping.
        float span = Math.max(1f, span(e, focus[0], focus[1], -1));
        float ratio = Math.max(0.5f, Math.min(2f, span / pinchStartSpan));
        pinching = true;
        viewport.zoomBy(ratio, twoFingerX, twoFingerY);
        pinchStartSpan = span;
        if (viewport.isZoomed()) viewport.panBy(focus[0] - twoFingerX, focus[1] - twoFingerY);
        twoFingerX = focus[0];
        twoFingerY = focus[1];
        applyViewport();
        viewOnlyFeedback.run();
    }
    private void panViewOnly(MotionEvent e) {
        float dx = e.getX() - lastX, dy = e.getY() - lastY;
        if (dx == 0 && dy == 0) return;
        lastX = e.getX();
        lastY = e.getY();
        viewport.panBy(dx, dy);
        applyViewport();
    }
    private void handleViewOnlyTap(float x, float y) {
        long now = SystemClock.elapsedRealtime();
        if (now - viewOnlyLastTap < 300) {
            viewOnlyLastTap = 0;
            // Double tap: toggle between fit and 2x around the tapped point.
            if (viewport.isZoomed()) viewport.reset(); else viewport.zoomBy(2f, x, y);
            applyViewport();
            viewOnlyFeedback.run();
        } else {
            viewOnlyLastTap = now;
        }
    }
    private static float[] focus(MotionEvent e, int skip) {
        float x = 0, y = 0; int count = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            x += e.getX(i); y += e.getY(i); count++;
        }
        return count == 0 ? new float[]{0, 0} : new float[]{x / count, y / count};
    }
    private static float span(MotionEvent e, float focusX, float focusY, int skip) {
        float sum = 0; int count = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            sum += (float) Math.hypot(e.getX(i) - focusX, e.getY(i) - focusY);
            count++;
        }
        return count == 0 ? 0 : 2 * sum / count;
    }

    private void updateTwoFingers(MotionEvent event) {
        int action = event.getActionMasked();
        int lifted = action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
        int count = event.getPointerCount() - (lifted >= 0 ? 1 : 0);
        float focusX = 0, focusY = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == lifted) continue;
            focusX += event.getX(i);
            focusY += event.getY(i);
        }
        focusX /= count;
        focusY /= count;
        float radiusSum = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == lifted) continue;
            radiusSum += (float) Math.hypot(event.getX(i) - focusX, event.getY(i) - focusY);
        }
        float span = Math.max(pinchSlop, 2 * radiusSum / count);
        boolean canMoveViewport = !mouseInput.hasVirtualButton() && viewport.width() > 0;
        if (action == MotionEvent.ACTION_MOVE && canMoveViewport) {
            moveTwoFingers(focusX, focusY, span);
        } else {
            // A change of contacts establishes a new baseline without moving the image.
            pinchSpan = span;
            pinching = pinching && count >= 2 && canMoveViewport;
            viewportPanActive = viewport.isZoomed() && viewport.contains(focusX, focusY);
            scrollY = focusY;
        }
        twoFingerX = focusX;
        twoFingerY = focusY;
    }
    private void moveTwoFingers(float focusX, float focusY, float span) {
        // Accumulate small span changes until the pinch threshold is crossed.
        if (Math.abs(span - pinchSpan) >= pinchSlop) pinching = true;
        if (pinching) {
            viewport.zoomBy(span / pinchSpan, twoFingerX, twoFingerY);
            pinchSpan = span;
        }
        if (pinching || viewport.isZoomed()) {
            // Pinches can start in the letterbox. A pan alone starts over the image.
            if (pinching || viewportPanActive || viewport.contains(focusX, focusY)) {
                viewportPanActive = true;
                viewport.panBy(focusX - twoFingerX, focusY - twoFingerY);
                applyViewport();
            }
        } else if (viewport.contains(focusX, focusY)) {
            float delta = focusY - scrollY;
            if (Math.abs(delta) > 16 * getResources().getDisplayMetrics().density) {
                session.pointer(x, y, 7, delta > 0 ? 1 : -1);
                scrollY = focusY;
            }
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public void surfaceCreated(SurfaceHolder holder) { session.surface(holder.getSurface()); }
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { releaseMouse(); session.surface(null); }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }
    /** Watch-only gestures arrive here; the SurfaceView child never receives them. */
    @Override public boolean onTouchEvent(MotionEvent event) { return touch(event); }
    @Override protected void onDetachedFromWindow() { releaseMouse(); super.onDetachedFromWindow(); }
    private final class Cursor extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final float arrowWidth = 12, arrowHeight = 15;
        Cursor(Context context) {
            super(context); setClickable(true); setContentDescription(L10n.tr("远程桌面触控区域"));
            // Match the iOS RemoteCursorArrowShape in density-independent units.
            arrow.moveTo(arrowWidth*.08f,arrowHeight*.04f);
            arrow.lineTo(arrowWidth*.08f,arrowHeight*.78f);
            arrow.lineTo(arrowWidth*.34f,arrowHeight*.60f);
            arrow.lineTo(arrowWidth*.55f,arrowHeight*.94f);
            arrow.lineTo(arrowWidth*.73f,arrowHeight*.84f);
            arrow.lineTo(arrowWidth*.52f,arrowHeight*.51f);
            arrow.lineTo(arrowWidth*.88f,arrowHeight*.49f);
            arrow.close();
        }
        // GestureDetector calls performClick only after resolving tap vs drag/long press.
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent event) { return touch(event); }
        @Override public boolean performClick() { super.performClick(); click(1, 2); return true; }
        @Override protected void onDraw(Canvas canvas) {
            drawRemoteCursor(canvas);
            int saved=canvas.save();
            canvas.translate(viewport.left()+x*viewport.width(),viewport.top()+y*viewport.height());
            float density=getResources().getDisplayMetrics().density;canvas.scale(density,density);
            // Anchor the arrow tip to the same normalized coordinate sent to the desktop.
            canvas.translate(-arrowWidth*.08f,-arrowHeight*.04f);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.75f);paint.setColor(Color.BLACK);canvas.drawPath(arrow,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);canvas.drawPath(arrow,paint);
            canvas.restoreToCount(saved);
        }
    }

    /**
     * The desktop's own pointer. Drawn before the local touch cursor, in the same transformed
     * picture space, so zoom and pan move it with the image.
     */
    private void drawRemoteCursor(Canvas canvas) {
        if (!remoteCursorReported || !remoteCursorVisible || !remoteCursorPositionValid) return;
        if (remoteCursorShape == 1) return; // shape "none": the host asked for no pointer
        float px = viewport.left() + remoteCursorX * viewport.width();
        float py = viewport.top() + remoteCursorY * viewport.height();
        float density = getResources().getDisplayMetrics().density;
        // Visual offset moves the drawn arrow so its hotspot matches the host position.
        px += remoteCursorOffsetX * viewport.width();
        py += remoteCursorOffsetY * viewport.height();
        canvas.save();
        canvas.translate(px, py);
        canvas.scale(density, density);
        remoteCursorPaint.setStyle(Paint.Style.STROKE);
        remoteCursorPaint.setStrokeWidth(1.75f);
        remoteCursorPaint.setColor(Color.BLACK);
        canvas.drawPath(cursorPathFor(remoteCursorShape), remoteCursorPaint);
        remoteCursorPaint.setStyle(Paint.Style.FILL);
        remoteCursorPaint.setColor(Color.WHITE);
        canvas.drawPath(cursorPathFor(remoteCursorShape), remoteCursorPaint);
        canvas.restore();
    }

    private final Paint remoteCursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path remoteCursorPath = new Path();
    private int remoteCursorPathShape = -1;
    /** Arrow for the common shapes, crosshair/insertion for text, resize arrows otherwise. */
    private Path cursorPathFor(int shape) {
        if (remoteCursorPathShape == shape) return remoteCursorPath;
        remoteCursorPath.rewind();
        remoteCursorPathShape = shape;
        switch (shape) {
            case 7:  // text
                remoteCursorPath.moveTo(6, 2); remoteCursorPath.lineTo(6, 20);
                remoteCursorPath.moveTo(2, 3); remoteCursorPath.lineTo(10, 3);
                remoteCursorPath.moveTo(2, 19); remoteCursorPath.lineTo(10, 19);
                break;
            case 6:  // crosshair
                remoteCursorPath.moveTo(11, 1); remoteCursorPath.lineTo(11, 21);
                remoteCursorPath.moveTo(1, 11); remoteCursorPath.lineTo(21, 11);
                break;
            case 15: case 16: case 26: // col/row/ew resize
                remoteCursorPath.moveTo(2, 11); remoteCursorPath.lineTo(20, 11);
                remoteCursorPath.moveTo(6, 7); remoteCursorPath.lineTo(2, 11); remoteCursorPath.lineTo(6, 15);
                remoteCursorPath.moveTo(16, 7); remoteCursorPath.lineTo(20, 11); remoteCursorPath.lineTo(16, 15);
                break;
            case 17: case 19: // ns / vertical resize
                remoteCursorPath.moveTo(11, 2); remoteCursorPath.lineTo(11, 20);
                remoteCursorPath.moveTo(7, 6); remoteCursorPath.lineTo(11, 2); remoteCursorPath.lineTo(15, 6);
                remoteCursorPath.moveTo(7, 16); remoteCursorPath.lineTo(11, 20); remoteCursorPath.lineTo(15, 16);
                break;
            case 5:  // wait
                remoteCursorPath.addCircle(11, 11, 8, Path.Direction.CW);
                remoteCursorPath.moveTo(11, 11); remoteCursorPath.lineTo(11, 5);
                remoteCursorPath.moveTo(11, 11); remoteCursorPath.lineTo(15, 13);
                break;
            default: // arrow, pointer, and every other shape
                remoteCursorPath.moveTo(1, 1); remoteCursorPath.lineTo(1, 18);
                remoteCursorPath.lineTo(5, 14); remoteCursorPath.lineTo(8, 21);
                remoteCursorPath.lineTo(11, 19.6f); remoteCursorPath.lineTo(8, 12.6f);
                remoteCursorPath.lineTo(13, 12.2f);
                remoteCursorPath.close();
                break;
        }
        return remoteCursorPath;
    }
}
