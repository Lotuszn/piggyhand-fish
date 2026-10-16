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
 * 更新检查（多源，任一通即出结果，按国内可达性排序）：
 *  1. Gitee raw  version.json（国内直连最快；需存在同名公开仓库）
 *  2. jsDelivr   version.json（CDN 读 GitHub 仓库 main 分支，零维护自动同步）
 *  3. GitHub API releases/latest（tag_name）
 * 全部失败 → failed=true（UI 显示「检查失败 · 点按重试」，不再静默当无更新）。
 * 版本比较逐段数值：1.0.10 > 1.0.9。
 */
public class UpdateChecker {

    public interface Callback {
        /**
         * failed=true：三源全部失败（网络不通），latest 无意义；
         * 否则 hasUpdate=true 表示远端有新版本，latest 为远端版本号
         */
        void onResult(boolean hasUpdate, String latest, boolean failed);
    }

    /** 版本清单（Gitee 仓库建好后自动生效；同文件已随 GitHub 仓库分发） */
    private static final String VERSION_URL_GITEE =
            "https://gitee.com/lotuszn/piggyhand-fish/raw/master/version.json";
    private static final String VERSION_URL_JSD =
            "https://cdn.jsdelivr.net/gh/Lotuszn/piggyhand-fish@main/version.json";
    private static final String GH_API =
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
                boolean failed = true;

                // 源 1/2：version.json（Gitee raw → jsDelivr）
                String[] jsonUrls = {VERSION_URL_GITEE, VERSION_URL_JSD};
                for (String u : jsonUrls) {
                    latest = fetchVersionJson(u, 4000);
                    if (latest != null) {
                        failed = false;
                        break;
                    }
                }

                // 源 3：GitHub releases API
                if (latest == null) {
                    latest = fetchGithubLatest(6000);
                    if (latest != null) failed = false;
                }

                final boolean has = latest != null && isNewer(latest, cur);
                final String fl = latest;
                final boolean ff = failed;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(has, fl, ff);
                    }
                });
            }
        }, "rh-update-check").start();
    }

    /** 拉取 version.json，返回 latest 版本号；失败返回 null */
    private static String fetchVersionJson(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url + "?t=" + System.currentTimeMillis())
                    .openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "piggyhand-fish");
            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                String v = new JSONObject(sb.toString()).optString("latest", "").trim();
                return v.isEmpty() ? null : v;
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
        return null;
    }

    /** GitHub releases/latest，返回去掉 v 前缀的版本号；失败返回 null */
    private static String fetchGithubLatest(int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(GH_API + "?t=" + System.currentTimeMillis())
                    .openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "piggyhand-fish");
            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                String tag = new JSONObject(sb.toString()).optString("tag_name", "");
                if (!tag.isEmpty()) {
                    return tag.startsWith("v") ? tag.substring(1) : tag;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
        return null;
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
