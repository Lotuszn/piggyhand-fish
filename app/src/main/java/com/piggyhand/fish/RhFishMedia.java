package com.piggyhand.fish;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 详情媒体数据（v1 支线 PoC）：从 mtop 响应里提取商品图片原始 URL + 视频直链。
 * - 数据源：详情接口响应 JSON 的 imageInfos 数组（无水印 CDN 原始 URL，闲鱼详情接口原生字段）
 *   + 视频对象的 sd480pUrl 字段（7.25.31 dex 验证存在）
 * - 识别策略：不依赖接口名（详情接口名会随版本变），凡响应体包含 "imageInfos" 键即解析，
 *   递归深度限 8 防爆栈；含 "sd480pUrl" 的对象视为视频。
 * - 持有：静态最新一份（进新详情页自动覆盖）；悬浮按钮点击时取用。
 * 全部静默容错：解析失败不影响主流程。
 */
public final class RhFishMedia {

    /** 原始图片 URL 列表（无水印） */
    private static volatile List<String> imageUrls = new ArrayList<String>();
    /** 视频直链（无视频为 null） */
    private static volatile String videoUrl = null;
    /** 数据更新时间戳 */
    private static volatile long updatedAt = 0L;

    private RhFishMedia() {}

    /** mtop 响应入口（FishMtopHook 全局响应监听处调用，含 imageInfos 才真正解析） */
    public static void parseResponse(String body) {
        if (body == null || body.length() == 0 || !body.contains("imageInfos")) return;
        try {
            JSONObject root = new JSONObject(body);
            List<String> urls = new ArrayList<String>();
            String[] video = new String[1];
            walk(root, 0, urls, video);
            if (!urls.isEmpty()) {
                imageUrls = urls;
                videoUrl = video[0]; // 可能为 null（无视频商品）
                updatedAt = System.currentTimeMillis();
                RhFileLog.write("[media] 详情媒体提取: 图片" + urls.size() + "张"
                        + (video[0] != null ? " + 视频" : ""));
            } else if (video[0] != null) {
                videoUrl = video[0];
                updatedAt = System.currentTimeMillis();
                RhFileLog.write("[media] 详情媒体提取: 仅视频");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 递归收集：imageInfos 数组取 url；含 sd480pUrl 键的对象取视频 */
    private static void walk(Object node, int depth, List<String> urls, String[] video) {
        if (node == null || depth > 8) return;
        try {
            if (node instanceof JSONObject) {
                JSONObject o = (JSONObject) node;
                // 视频：任意层级里含 sd480pUrl 键的对象
                String sd = o.optString("sd480pUrl", null);
                if (sd != null && sd.length() > 0 && video[0] == null) {
                    video[0] = normalize(sd);
                }
                // 图片：imageInfos 数组
                JSONArray arr = o.optJSONArray("imageInfos");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject it = arr.optJSONObject(i);
                        if (it == null) continue;
                        String u = it.optString("url", "");
                        if (u == null || u.length() == 0) u = it.optString("imagePath", "");
                        if (u != null && u.length() > 0) urls.add(normalize(u));
                    }
                }
                // 继续递归其余键（视频对象可能藏在 videoVO/videoInfo 等任意结构里）
                JSONArray names = o.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String k = names.optString(i);
                        if ("imageInfos".equals(k)) continue;
                        walk(o.opt(k), depth + 1, urls, video);
                    }
                }
            } else if (node instanceof JSONArray) {
                JSONArray arr = (JSONArray) node;
                for (int i = 0; i < arr.length() && i < 64; i++) {
                    walk(arr.opt(i), depth + 1, urls, video);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 协议补全：阿里 CDN 常见 "//gw.alicdn.com/..." 形态 */
    private static String normalize(String url) {
        if (url == null) return null;
        if (url.startsWith("//")) return "https:" + url;
        return url;
    }

    /** 画廊路由喂数据入口（RhFishFloat.dumpRoute 从 detail_photo_gallery 的 imageBeans 提取后调用；2026-10-19 实测 detail.unit 图片段加密，画廊路由是唯一明文源）。
     *  上下文互斥：喂图即清视频缓存——菜单永远只对应「当前打开的这个查看器」，防跨商品串数据。 */
    public static void setImageUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) return;
        imageUrls = urls;
        videoUrl = null;
        updatedAt = System.currentTimeMillis();
        RhFileLog.write("[media] 画廊路由提取: 图片" + urls.size() + "张");
    }

    /** 视频预览路由喂数入口（fleamarket://video_preview_page 的 videoURL 参数=明文视频直链，2026-10-19 实测）。同样互斥清图片缓存。 */
    public static void setVideoUrl(String url) {
        if (url == null || url.length() == 0) return;
        videoUrl = url;
        imageUrls = new ArrayList<String>();
        updatedAt = System.currentTimeMillis();
        RhFileLog.write("[media] 视频路由提取: " + (url.length() > 60 ? url.substring(0, 60) + "..." : url));
    }

    public static List<String> getImageUrls() {
        List<String> u = imageUrls;
        return u == null ? new ArrayList<String>() : u;
    }

    public static String getVideoUrl() {
        return videoUrl;
    }

    public static boolean hasData() {
        return !getImageUrls().isEmpty() || videoUrl != null;
    }
}
