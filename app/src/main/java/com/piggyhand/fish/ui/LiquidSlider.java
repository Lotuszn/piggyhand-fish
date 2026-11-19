package com.piggyhand.fish.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * 液态玻璃滑块：5dp 圆角轨道 + 橙金填充 + 20dp 白球拇指。
 * 默认范围 1..12（钓鱼间隔小时数）；v1.5.73 起用 setRange() 承接毫秒/百分比区间。
 */
public class LiquidSlider extends View {

    public interface OnValueChangeListener {
        /** fromUser=true 表示松手落定（可持久化），false 为拖动中（仅刷新显示） */
        void onChanged(int value, boolean fromUser);
    }

    private static final int GRAD_A = 0xFFD54D;
    private static final int GRAD_B = 0xFFFF9D00;

    private final Paint pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int min = 1, max = 12;
    private int value = 4;
    // 防误触：必须在水平方向真正拖动起来才改值。原实现 ACTION_DOWN 就 applyTouch，
    // 点一下轨道（甚至卡片刚展开时手指擦到）就会改值并落盘，节奏被意外改写。
    private float downX;
    private boolean dragging;
    private int downValue;
    private OnValueChangeListener listener;
    private final float den;

    public LiquidSlider(Context c) {
        this(c, null);
    }

    public LiquidSlider(Context c, android.util.AttributeSet attrs) {
        super(c, attrs);
        den = c.getResources().getDisplayMetrics().density;
        pBase.setColor(0x1F17181C);
        pThumb.setShader(new LinearGradient(0, 0, 0, 1, 0xFFFFFFFF, 0xFFE8EBF0, Shader.TileMode.CLAMP));
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(den);
        pRing.setColor(0x2EFFFFFF);
    }

    public int getValue() { return value; }

    /**
     * 设置取值区间（含端点）。绘制逻辑不需要改：frac() 只依赖 min/max。
     * 区间跨度 >= 1000 时触摸量化到 10 的整数倍，避免出现 1976ms 这类毛刺值。
     */
    public void setRange(int lo, int hi) {
        if (hi <= lo) hi = lo + 1;
        min = lo;
        max = hi;
        value = Math.max(min, Math.min(max, value));
        invalidate();
    }

    public void setValueSilent(int v) {
        value = Math.max(min, Math.min(max, v));
        invalidate();
    }

    public void setOnValueChangeListener(OnValueChangeListener l) { listener = l; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int h = (int) (26 * den);
        int w = View.MeasureSpec.getSize(wSpec);
        if (View.MeasureSpec.getMode(wSpec) == View.MeasureSpec.UNSPECIFIED) w = (int) (200 * den);
        setMeasuredDimension(w, h);
    }

    private float frac() { return (value - min) / (float) (max - min); }

    private void applyTouch(float x) {
        float pad = 12 * den; // 拇指半径 + 余量
        float w = getWidth();
        float f = Math.max(0f, Math.min(1f, (x - pad) / (w - 2 * pad)));
        int nv;
        // 大区间（毫秒档）量化到 10ms 刻度：300px 覆盖 7200ms 时每像素 24ms，
        // 不量化会拖出 1976 之类看着很脏的数，量化后落 1970/1980，精度损失可忽略
        if (max - min >= 1000) {
            nv = min + Math.round(f * (max - min) / 10f) * 10;
        } else {
            nv = min + Math.round(f * (max - min));
        }
        nv = Math.max(min, Math.min(max, nv));
        if (nv != value) {
            value = nv;
            invalidate();
            if (listener != null) listener.onChanged(value, false);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // 卡片塞在 ScrollView 里：按下就锁住父容器滚动，否则横拖会被当成竖滑抢走
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                downX = e.getX();
                downValue = value;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                // 超过 3dp 才算拖动：轻点/擦碰不改值，拖动正常跟手
                if (!dragging && Math.abs(e.getX() - downX) > 3 * den) dragging = true;
                if (dragging) applyTouch(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (dragging) {
                    applyTouch(e.getX());
                    if (listener != null) listener.onChanged(value, true);
                } else {
                    value = downValue; // 没有拖动：回退到按下前的值，不发回调、不落盘
                    invalidate();
                }
                dragging = false;
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cy = h / 2f;
        float trackH = 5 * den;
        float pad = 12 * den;
        float left = pad, right = w - pad;

        // 底轨
        rect.set(left, cy - trackH / 2f, right, cy + trackH / 2f);
        c.drawRoundRect(rect, trackH / 2f, trackH / 2f, pBase);

        // 填充段
        float fillR = left + (right - left) * frac();
        if (fillR > left) {
            pFill.setShader(new LinearGradient(left, 0, right, 0,
                    GRAD_A, GRAD_B, Shader.TileMode.CLAMP));
            rect.set(left, cy - trackH / 2f, fillR, cy + trackH / 2f);
            c.drawRoundRect(rect, trackH / 2f, trackH / 2f, pFill);
            pFill.setShader(null);
        }

        // 拇指
        float tr = 10 * den;
        c.drawCircle(fillR, cy, tr, pThumb);
        c.drawCircle(fillR, cy, tr, pRing);
    }
}
