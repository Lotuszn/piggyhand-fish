package com.piggyhand.fish;

import android.view.animation.Interpolator;

/**
 * 三次贝塞尔缓动（css cubic-bezier 等价实现），
 * 用于页面横滑 / 果冻 pill 的「液态」手感。
 * 控制点 y 允许 >1（回弹）。
 */
public class BezierInterpolator implements Interpolator {

    private final float x1, y1, x2, y2;

    public BezierInterpolator(float x1, float y1, float x2, float y2) {
        this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
    }

    /** 设计稿缓动 cubic-bezier(.3,1.15,.42,1) */
    public static BezierInterpolator jelly() {
        return new BezierInterpolator(0.30f, 1.15f, 0.42f, 1.00f);
    }

    @Override
    public float getInterpolation(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        // 牛顿迭代解 x(t)=t 的参数 u
        float u = t;
        for (int i = 0; i < 8; i++) {
            float x = bez(u, x1, x2) - t;
            if (Math.abs(x) < 1e-5f) break;
            float d = 3f * (1f - u) * (1f - u) * x1 + 6f * (1f - u) * u * (x2 - x1) + 3f * u * u * (1f - x2);
            if (Math.abs(d) < 1e-6f) break;
            u -= x / d;
        }
        return bez(u, y1, y2);
    }

    private static float bez(float u, float a, float b) {
        float v = 1f - u;
        return 3f * v * v * u * a + 3f * v * u * u * b + u * u * u;
    }
}
