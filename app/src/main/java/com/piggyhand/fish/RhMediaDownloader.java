package com.piggyhand.fish;

import android.app.Activity;

/**
 * 媒体下载写相册（v1 支线 PoC）：
 * - HttpURLConnection 直下 CDN 原始 URL（闲鱼进程内，自带网络权限，无新增请求特征）
 * - Android 10+ 走 MediaStore（RELATIVE_PATH=Pictures/小猪手），8/9 走公有目录+媒体扫描
 * - 全子线程执行，完成/失败 Toast 回主线程
 * - URL 协议补全（// → https:）、jpg/webp 按响应落盘
 */
public final class RhMediaDownloader {

    private static final String DIR_NAME = "Pictures/小猪手";
    private static volatile boolean busy = false;

    private RhMediaDownloader() {}

    /** 批量下载图片 */
    public static void downloadImages(final Activity act, final java.util.List<String> urls) {
        if (busy) {
            toast(act, "下载进行中，稍等");
            return;
        }
        if (urls == null || urls.isEmpty()) {
            toast(act, "还没有图片数据（先浏览商品详情）");
            return;
        }
        busy = true;
        toast(act, "开始下载 " + urls.size() + " 张原图…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0, fail = 0;
                long ts = System.currentTimeMillis();
                for (int i = 0; i < urls.size(); i++) {
                    String name = "xz_" + ts + "_" + (i + 1);
                    try {
                        byte[] data = httpGet(urls.get(i));
                        if (data != null && data.length > 0) {
                            saveImage(act, data, name);
                            ok++;
                        } else {
                            fail++;
                        }
                    } catch (Throwable t) {
                        fail++;
                        RhFileLog.write("[media] 下载失败 " + i + ": " + t);
                    }
                }
                busy = false;
                final String msg = "原图下载完成：" + ok + " 张成功" + (fail > 0 ? "，" + fail + " 张失败" : "") + "（相册 Pictures/小猪手）";
                toast(act, msg);
                RhFileLog.write("[media] " + msg);
            }
        }, "rh-media-img").start();
    }

    /** 下载视频 */
    public static void downloadVideo(final Activity act, final String url) {
        if (busy) {
            toast(act, "下载进行中，稍等");
            return;
        }
        if (url == null || url.length() == 0) {
            toast(act, "该商品没有视频");
            return;
        }
        busy = true;
        toast(act, "开始下载视频…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    byte[] data = httpGet(url);
                    if (data != null && data.length > 0) {
                        saveVideo(act, data, "xz_video_" + System.currentTimeMillis());
                        msg = "视频下载完成：" + (data.length / 1024 / 1024) + "MB（相册 Movies/小猪手）";
                    } else {
                        msg = "视频下载失败（空响应）";
                    }
                } catch (Throwable t) {
                    msg = "视频下载失败：" + t;
                    RhFileLog.write("[media] 视频下载失败: " + t);
                }
                busy = false;
                toast(act, msg);
                RhFileLog.write("[media] " + msg);
            }
        }, "rh-media-vid").start();
    }

    /** 保存指定序号的单张（1-based） */
    public static void downloadOneImage(final Activity act, final int index) {
        final java.util.List<String> urls = RhFishMedia.getImageUrls();
        if (index < 1 || index > urls.size()) {
            toast(act, "序号超出范围（1-" + urls.size() + "）");
            return;
        }
        busy = true;
        final String url = urls.get(index - 1);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    byte[] data = httpGet(url);
                    if (data != null && data.length > 0) {
                        saveImage(act, data, "xz_" + System.currentTimeMillis() + "_" + index);
                        msg = "第 " + index + " 张原图已保存（相册 Pictures/小猪手）";
                    } else {
                        msg = "下载失败（空响应）";
                    }
                } catch (Throwable t) {
                    msg = "下载失败：" + t;
                }
                busy = false;
                toast(act, msg);
            }
        }, "rh-media-one").start();
    }

    /* ---------------- 底层 ---------------- */

    private static byte[] httpGet(String url) throws Exception {
        if (url.startsWith("//")) url = "https:" + url;
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0"); // CDN 直取无需淘系签名
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        if (code != 200) {
            RhFileLog.write("[media] HTTP " + code + " " + url);
            return null;
        }
        java.io.InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        c.disconnect();
        return bos.toByteArray();
    }

    private static void saveImage(Activity act, byte[] data, String name) throws Exception {
        android.net.Uri uri;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name + ".jpg");
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, DIR_NAME);
            uri = act.getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        } else {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES), "小猪手");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, name + ".jpg");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(data);
            fo.close();
            android.media.MediaScannerConnection.scanFile(act,
                    new String[]{f.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            return;
        }
        if (uri == null) throw new IllegalStateException("MediaStore insert null");
        java.io.OutputStream os = act.getContentResolver().openOutputStream(uri);
        os.write(data);
        os.close();
    }

    private static void saveVideo(Activity act, byte[] data, String name) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name + ".mp4");
            cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/小猪手");
            android.net.Uri uri = act.getContentResolver().insert(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("MediaStore insert null");
            java.io.OutputStream os = act.getContentResolver().openOutputStream(uri);
            os.write(data);
            os.close();
        } else {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_MOVIES), "小猪手");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, name + ".mp4");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(data);
            fo.close();
            android.media.MediaScannerConnection.scanFile(act,
                    new String[]{f.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        }
    }

    private static void toast(final Activity act, final String msg) {
        if (act == null || act.isFinishing()) return;
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
