package com.piggyhand.fish.core;

import android.app.Activity;

import java.lang.ref.WeakReference;

/**
 * 当前前台 Activity 持有者（2026-11-26）。
 *
 * 为什么需要：闲鱼的广告位路由入口
 *   AdsUtil.interceptRouterShowAds(Context, String, ILoadingDialog, InterceptShowAdsCallback)
 * 开头就做 instance-of Activity 判定，传 Application 直接返回、什么都不做；
 * 而模块里的任务线程只有 Application。这里由 FishMtopHook 在 Activity.onResume 时
 * 存一份弱引用，任务线程要拉起商城页时取用。
 *
 * 弱引用：不延长 Activity 生命周期，宿主回收后自然为 null，调用方按“需前台”处理。
 */
public final class RhTopActivity {

    private static volatile WeakReference<Activity> top = new WeakReference<>(null);

    private RhTopActivity() {
    }

    public static void set(Activity a) {
        top = new WeakReference<>(a);
    }

    public static Activity get() {
        return top.get();
    }

    /** 生命周期结束时清理，避免拿到已 destroy 的实例去 startActivity */
    public static void clearIf(Activity a) {
        Activity cur = top.get();
        if (cur == null || cur == a) top = new WeakReference<>(null);
    }
}
