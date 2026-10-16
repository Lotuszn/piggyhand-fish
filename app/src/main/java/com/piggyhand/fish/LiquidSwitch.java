package com.piggyhand.fish;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * 液态玻璃开关：自绘胶囊 + 圆球滑块，Overshoot 弹性动画。
 * 尺寸固定 38×22dp，无任何外部依赖。
 */
public class LiquidSwitch extends View {

    public interface OnToggleListener { void onToggle(boolean on); }

    private static final int GRAD_ON_A = 0xFFD54D;
    private static final int GRAD_ON_B = 0xFFFF9D00;
    private static final int COLOR_OFF = 0x1F17181C;   // alpha 0x1F + 灰黑

    private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean on;
    private float progress;          // 0..1 圆球位置
    private float pressScale = 1f;   // 按压时球体缩放
    private OnToggleListener listener;
    private ValueAnimator anim;
    private float den;

    public LiquidSwitch(Context c) {
        this(c, null);
    }

    public LiquidSwitch(Context c, android.util.AttributeSet attrs) {
        super(c, attrs);
        den = c.getResources().getDisplayMetrics().density;
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) { setOn(!on); }
        });
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension((int) (38 * den), (int) (22 * den));
    }

    public boolean isOn() { return on; }

    public void setOnSilent(boolean v) {
        on = v;
        progress = v ? 1f : 0f;
        if (anim != null) anim.cancel();
        invalidate();
    }

    public void setOn(boolean v) {
        if (on == v) return;
        on = v;
        animateTo(on ? 1f : 0f);
        if (listener != null) listener.onToggle(on);
    }

    public void setOnToggleListener(OnToggleListener l) { listener = l; }

    private void animateTo(float target) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(progress, target);
        anim.setDuration(340);
        anim.setInterpolator(new OvershootInterpolator(1.6f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                progress = (float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        switch (e.getAction()) {
            case android.view.MotionEvent.ACTION_DOWN:
                pressScale = .86f; invalidate(); break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                pressScale = 1f; invalidate(); break;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        final float w = getWidth(), h = getHeight();
        final float ballR = (h - 4 * den) / 2f;

        // 轨道：底灰 -> 橙金渐变，alpha 随进度过渡
        rect.set(0, 0, w, h);
        pTrack.setShader(null);
        pTrack.setAlpha(255);
        pTrack.setColor(COLOR_OFF);
        c.drawRoundRect(rect, h / 2f, h / 2f, pTrack);
        if (progress > 0f) {
            pTrack.setShader(new LinearGradient(0, 0, w, h,
                    GRAD_ON_A, GRAD_ON_B, Shader.TileMode.CLAMP));
            pTrack.setAlpha((int) (255 * progress));
            c.drawRoundRect(rect, h / 2f, h / 2f, pTrack);
            pTrack.setAlpha(255);
            pTrack.setShader(null);
        }

        // 圆球：白渐变 + 内高光描边，按压回弹缩放
        float cx = ballR + 2 * den + progress * (w - h + 2 * den);
        float cy = h / 2f;
        float r = ballR * pressScale;
        pBall.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                0xFFFFFFFF, 0xFFE8EBF0, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, pBall);
        pBall.setShader(null);
        pBall.setStyle(Paint.Style.STROKE);
        pBall.setStrokeWidth(den);
        pBall.setColor(0x2EFFFFFF);
        c.drawCircle(cx, cy, r, pBall);
        pBall.setStyle(Paint.Style.FILL);
        pBall.setColor(Color.WHITE);
    }
}
