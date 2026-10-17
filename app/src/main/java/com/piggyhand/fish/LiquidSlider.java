package com.piggyhand.fish;

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
 * 取值范围 1..12（钓鱼间隔小时数）。
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

    private final int min = 1, max = 12;
    private int value = 4;
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
        int nv = min + Math.round(f * (max - min));
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
            case MotionEvent.ACTION_MOVE:
                applyTouch(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                applyTouch(e.getX());
                if (listener != null) listener.onChanged(value, true);
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
