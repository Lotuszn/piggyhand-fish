package com.piggyhand.fish.ui;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.piggyhand.fish.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 赞赏码弹窗（关于页「支持一下」）：玻璃卡片 + 赞赏码图 + 「保存图片」/「取消」。
 * 形态与松鼠小猪手 DonateDialog 一致：decor 遮罩 0x59000000 + 180ms 淡入上浮，
 * 140ms 淡出摘除；返回键拦截由 MainActivity.onBackPressed → handleBack() 完成。
 *
 * 保存路径：
 *   Android 10+（API 29）→ MediaStore Pictures/XianyuZhushou，无需存储权限；
 *   API 26~28 → 应用私有 Pictures 目录（不为此功能申请公共存储权限）。
 */
final class DonateDialog {

    /** 相册里的展示名（不带扩展名，保存时补 .png） */
    private static final String SAVE_NAME = "xianyu_donate";

    /** 色板与松鼠小猪手 UiKit 同值：C_OK 绿（ok_green）/ C_DIM 中性灰（text_sub） */
    private static final int C_OK = 0xFF0DB85A;
    private static final int C_DIM = 0xFF8A8B92;

    private static android.view.View current;

    private DonateDialog() {}

    static int dp(Activity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    /** 弹窗开着则关闭并吃掉这次返回；没开则放行 */
    static boolean handleBack() {
        if (current == null) return false;
        dismiss();
        return true;
    }

    static void show(final Activity act) {
        if (current != null) return; // 单例：连点不叠层

        ViewGroup decor = (ViewGroup) act.findViewById(android.R.id.content);
        final FrameLayout mask = new FrameLayout(act);
        mask.setBackgroundColor(0x59000000);
        mask.setClickable(true);
        mask.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) { dismiss(); }
        });

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_glass);
        int pad = dp(act, 16);
        card.setPadding(pad, pad, pad, dp(act, 2));

        TextView title = new TextView(act);
        title.setText("请我喝杯咖啡");
        title.setTextSize(13.5f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(act.getResources().getColor(R.color.text_main));
        card.addView(title);

        // 赞赏码：白底承载，nodpi 原图直出，保扫码清晰度
        ImageView code = new ImageView(act);
        code.setImageBitmap(BitmapFactory.decodeResource(act.getResources(), R.drawable.donate_code));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                dp(act, 236), dp(act, 236));
        cl.topMargin = dp(act, 10);
        cl.gravity = Gravity.CENTER_HORIZONTAL;
        code.setScaleType(ImageView.ScaleType.FIT_CENTER);
        code.setBackgroundColor(0xFFFFFFFF);
        card.addView(code, cl);

        // 保存按钮：绿字居中，与 URL 行同色系（可点击暗示）
        TextView save = new TextView(act);
        save.setText("保存图片");
        save.setTextSize(12.5f);
        save.setTextColor(C_OK);
        save.setGravity(Gravity.CENTER);
        save.setPadding(0, dp(act, 12), 0, dp(act, 4));
        save.setClickable(true);
        save.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                saveImage(act);
            }
        });
        card.addView(save);

        TextView cancel = new TextView(act);
        cancel.setText("取消");
        cancel.setTextSize(11.5f);
        cancel.setTextColor(C_DIM);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(act, 8), 0, dp(act, 10));
        cancel.setClickable(true);
        cancel.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) { dismiss(); }
        });
        card.addView(cancel);

        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(
                dp(act, 280), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        mask.addView(card, ml);
        decor.addView(mask, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        current = mask;
        mask.setAlpha(0f);
        card.setTranslationY(dp(act, 18));
        mask.animate().alpha(1f).setDuration(180).start();
        card.animate().translationY(0f).setDuration(180).start();
    }

    /** 保存到相册 Pictures/XianyuZhushou（API 29+，免权限）；低版本落应用私有目录并提示实际路径 */
    private static void saveImage(Activity act) {
        try {
            Bitmap bm = BitmapFactory.decodeResource(act.getResources(), R.drawable.donate_code);
            if (bm == null) {
                android.widget.Toast.makeText(act, "图片加载失败", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            String name = SAVE_NAME + "_" + System.currentTimeMillis() + ".png";
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/XianyuZhushou");
                Uri uri = act.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) {
                    android.widget.Toast.makeText(act, "保存失败", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                OutputStream os = act.getContentResolver().openOutputStream(uri);
                bm.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
                android.widget.Toast.makeText(act, "已保存到相册 · Pictures/XianyuZhushou", android.widget.Toast.LENGTH_SHORT).show();
            } else {
                File dir = new File(act.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "XianyuZhushou");
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, name);
                FileOutputStream fo = new FileOutputStream(out);
                bm.compress(Bitmap.CompressFormat.PNG, 100, fo);
                fo.close();
                android.widget.Toast.makeText(act, "已保存 " + out.getPath(), android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            android.widget.Toast.makeText(act, "保存失败：" + t.getClass().getSimpleName(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 关闭：淡出 140ms 后从 decor 摘除；current 先清，防重复动画 */
    private static void dismiss() {
        final android.view.View mask = current;
        if (mask == null) return;
        current = null;
        mask.animate().alpha(0f).setDuration(140).start();
        mask.postDelayed(new Runnable() {
            @Override
            public void run() {
                ViewGroup p = (ViewGroup) mask.getParent();
                if (p != null) p.removeView(mask);
            }
        }, 160);
    }
}
