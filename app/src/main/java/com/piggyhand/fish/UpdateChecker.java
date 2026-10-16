package com.piggyhand.fish;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 更新检查：读 GitHub releases/latest 的 tag_name，与当前 versionName 逐段比较。
 * 结果回调 UI 线程；任何异常一律按「无更新」处理，不影响界面。
 */
public class UpdateChecker {

    public interface Callback {
        /** hasUpdate=true 时 latest 为远端版本号 */
        void onResult(boolean hasUpdate, String latest);
    }

    private static final String LATEST_URL =
            "https://api.github.com/repos/Lotuszn/piggyhand-fish/releases/latest";

    public static String currentVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0.0.0";
        }
    }

    public static void check(final Context ctx, final Callback cb) {
        final String cur = currentVersion(ctx);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String latest = null;
                boolean has = false;
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(LATEST_URL + "?t=" + System.currentTimeMillis())
                            .openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "piggyhand-fish");
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();
                        String tag = new JSONObject(sb.toString()).optString("tag_name", "");
                        if (!tag.isEmpty()) {
                            latest = tag.startsWith("v") ? tag.substring(1) : tag;
                            has = isNewer(latest, cur);
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                }
                final boolean fh = has;
                final String fl = latest;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(fh, fl);
                    }
                });
            }
        }, "rh-update-check").start();
    }

    /** 逐段数值比较：1.0.10 > 1.0.9 */
    static boolean isNewer(String latest, String cur) {
        try {
            String[] a = latest.split("\\.");
            String[] b = cur.split("\\.");
            int n = Math.max(a.length, b.length);
            for (int i = 0; i < n; i++) {
                int va = i < a.length ? Integer.parseInt(a[i].trim()) : 0;
                int vb = i < b.length ? Integer.parseInt(b[i].trim()) : 0;
                if (va != vb) return va > vb;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
