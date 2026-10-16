package com.piggyhand.fish;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;

/**
 * 模块主界面（液态玻璃亮色版）：
 * - 三页横滑（主页 / 日志 / 关于）+ 果冻 pill Tab + 贝塞尔缓动视差
 * - 主页：头卡 / 状态脉冲 / 立即跑批 / 四开关（LiquidSwitch）/ 间隔滑块（LiquidSlider）
 * - 日志：四通道读跑批报告（直读 / socket / su / 广播回传）
 * - 关于：GitHub 更新检查（有新版本红点 + 副标题，无则隐藏副标题自动居中）
 * - 配置即改即存（SharedPreferences），重启闲鱼生效
 */
public class MainActivity extends Activity {

    private static final String ACTION_MTOP_CALL = "com.piggyhand.fish.MTOP_CALL";
    private static final String ACTION_RESULT = "com.piggyhand.fish.RESULT";
    private static final String FISH_PKG = "com.taobao.idlefish";
    private static final String RELEASES_URL =
            "https://github.com/Lotuszn/piggyhand-fish/releases/latest";
    private static final String GITEE_RELEASES_URL =
            "https://gitee.com/lotuszn/piggyhand-fish/releases";
    private static final String PAN_URL =
            "https://1827807734.share.123pan.cn/123pan/5faujv-budcH?pwd=iHLf";
    private static final String COOLAPK_URL = "https://www.coolapk.com/u/2206962";

    /* 导航 */
    private ViewGroup track;
    private View pagesWrap;
    private View pill;
    private LinearLayout tabHome, tabLog, tabAbout;
    private ImageView[] tabIcons;
    private TextView[] tabLabels;
    private int cur = 0;
    private boolean pillReady = false;

    /* 控件 */
    private TextView tvVersion, tvStatus, tvStatusMeta, tvReport, tvGhSub, tvGhVal, tvHoursNum;
    private View dotNew, dotOk, pulseHalo, scoutArgs, rowUpdate, rowCoolapk;
    private View linkPicker; // 下载渠道选择弹层（非空 = 正在显示）
    private LiquidSwitch swAutoRun, swMtop, swAds, swScout;
    private LiquidSlider sliderHours;
    private EditText etPattern, etMax;
    private Button btnRun, btnRefresh;

    private boolean dirty = false;
    private boolean logLoaded = false;
    private boolean updateCheckFailed = false;

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String report = intent.getStringExtra("report");
            if (tvReport == null) return;
            tvReport.setText(report == null || report.trim().isEmpty()
                    ? "暂无跑批记录（先跑一轮）" : report);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 亮色状态栏 / 导航栏，与 blob 背景衔接
        getWindow().setStatusBarColor(0xFFFDF3E3);
        getWindow().setNavigationBarColor(0xFFF2F4F8);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        setContentView(R.layout.activity_main);

        track = findViewById(R.id.track);
        pagesWrap = findViewById(R.id.pagesWrap);
        pill = findViewById(R.id.pill);
        tabHome = findViewById(R.id.tabHome);
        tabLog = findViewById(R.id.tabLog);
        tabAbout = findViewById(R.id.tabAbout);
        tabIcons = new ImageView[]{findViewById(R.id.ivTabHome), findViewById(R.id.ivTabLog), findViewById(R.id.ivTabAbout)};
        tabLabels = new TextView[]{findViewById(R.id.tvTabHome), findViewById(R.id.tvTabLog), findViewById(R.id.tvTabAbout)};

        tvVersion = findViewById(R.id.tvVersion);
        tvStatus = findViewById(R.id.tvStatus);
        tvStatusMeta = findViewById(R.id.tvStatusMeta);
        tvReport = findViewById(R.id.tvReport);
        tvGhSub = findViewById(R.id.tvGhSub);
        tvGhVal = findViewById(R.id.tvGhVal);
        tvHoursNum = findViewById(R.id.tvHoursNum);
        dotNew = findViewById(R.id.dotNew);
        dotOk = findViewById(R.id.dotOk);
        pulseHalo = findViewById(R.id.pulseHalo);
        scoutArgs = findViewById(R.id.scoutArgs);
        rowUpdate = findViewById(R.id.rowUpdate);
        rowCoolapk = findViewById(R.id.rowCoolapk);
        swAutoRun = findViewById(R.id.swAutoRun);
        swMtop = findViewById(R.id.swMtop);
        swAds = findViewById(R.id.swAds);
        swScout = findViewById(R.id.swScout);
        sliderHours = findViewById(R.id.sliderHours);
        etPattern = findViewById(R.id.etPattern);
        etMax = findViewById(R.id.etMax);
        btnRun = findViewById(R.id.btnRun);
        btnRefresh = findViewById(R.id.btnRefresh);

        // 版本号
        String ver = UpdateChecker.currentVersion(this);
        tvVersion.setText("v" + ver + " · LSPosed");
        tvGhVal.setText("v" + ver + " ›");

        // 模块启用状态：读自身 SharedPreferences（不得调 ConfigManager.load——
        // 它内部用 XSharedPreferences/XposedBridge，模块自身进程无 Xposed 环境会闪退）
        JSONObject cfg = currentConfig();
        boolean enabled = cfg.optBoolean("enabled", true);
        tvStatus.setText(enabled ? "引擎运行中" : "模块未启用 · 请在 LSPosed 中启用");
        tvStatus.setTextColor(enabled ? 0xFF17181C : 0xFFB35F00);
        int hours = cfg.optInt("autoRunIntervalHours", 4);
        tvStatusMeta.setText("自动间隔 " + hours + " 小时");

        // ===== 开关初始态（静默，不触发持久化） =====
        swAutoRun.setOnSilent(cfg.optBoolean("autoRun", true));
        swMtop.setOnSilent(cfg.optBoolean("mtopHook", true));
        swAds.setOnSilent(cfg.optBoolean("adSdkPresets", true));
        swScout.setOnSilent(cfg.optBoolean("scout", true));
        scoutArgs.setVisibility(swScout.isOn() ? View.VISIBLE : View.GONE);
        etPattern.setText(cfg.optString("scoutPattern", "reward|bonus|coin"));
        etMax.setText(String.valueOf(cfg.optInt("maxScoutMethods", 400)));
        sliderHours.setValueSilent(hours);
        tvHoursNum.setText(String.valueOf(hours));

        // ===== 开关监听：即改即存 =====
        swAutoRun.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
            @Override public void onToggle(boolean on) { put("autoRun", on); }
        });
        swMtop.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
            @Override public void onToggle(boolean on) { put("mtopHook", on); }
        });
        swAds.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
            @Override public void onToggle(boolean on) { put("adSdkPresets", on); }
        });
        swScout.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
            @Override
            public void onToggle(boolean on) {
                put("scout", on);
                scoutArgs.setVisibility(on ? View.VISIBLE : View.GONE);
            }
        });

        // ===== 滑块监听：拖动刷新显示，松手落定持久化 =====
        sliderHours.setOnValueChangeListener(new LiquidSlider.OnValueChangeListener() {
            @Override
            public void onChanged(int value, boolean fromUser) {
                tvHoursNum.setText(String.valueOf(value));
                tvStatusMeta.setText("自动间隔 " + value + " 小时");
                if (fromUser) put("autoRunIntervalHours", value);
            }
        });

        // ===== 立即跑批 =====
        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(ACTION_MTOP_CALL);
                    i.setPackage(FISH_PKG);
                    i.putExtra("api", "__RUN_TASKS__");
                    sendBroadcast(i);
                    btnRun.setText("已触发 · 闲鱼内执行中…");
                    btnRun.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            btnRun.setText("立即跑批");
                        }
                    }, 5000);
                    Toast.makeText(MainActivity.this, "已触发引擎，闲鱼打开后自动执行", Toast.LENGTH_SHORT).show();
                    Intent fish = getPackageManager().getLaunchIntentForPackage(FISH_PKG);
                    if (fish != null) startActivity(fish);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "触发失败: " + t, Toast.LENGTH_LONG).show();
                }
            }
        });

        // ===== 刷新报告 =====
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshLog();
            }
        });

        // ===== 版本更新行点击：检查失败 → 重试检查；否则弹出下载渠道选择 =====
        rowUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (updateCheckFailed) {
                    checkUpdate();
                    return;
                }
                showDownloadPicker();
            }
        });

        // ===== 酷安行点击 → 作者主页：优先直拉酷安 App（免 MIUI 关联启动确认），未装则浏览器 =====
        rowCoolapk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent cool = new Intent(Intent.ACTION_VIEW, Uri.parse(COOLAPK_URL));
                cool.setPackage("com.coolapk.market");
                try {
                    startActivity(cool);
                    return;
                } catch (Throwable ignored) {
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(COOLAPK_URL)));
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "无可用浏览器", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // ===== Tab 切换 =====
        View.OnClickListener tl0 = new View.OnClickListener() {
            @Override public void onClick(View v) { setIndex(0); }
        };
        View.OnClickListener tl1 = new View.OnClickListener() {
            @Override public void onClick(View v) { setIndex(1); }
        };
        View.OnClickListener tl2 = new View.OnClickListener() {
            @Override public void onClick(View v) { setIndex(2); }
        };
        tabHome.setOnClickListener(tl0);
        tabLog.setOnClickListener(tl1);
        tabAbout.setOnClickListener(tl2);
        setTabVisual(0, true);
        setTabVisual(1, false);
        setTabVisual(2, false);

        // 状态脉冲光环
        startPulse();

        // pill 定位 + 页面初始偏移（等布局完成）
        tabHome.post(new Runnable() {
            @Override
            public void run() {
                final float w = pagesWrap.getWidth();
                for (int i = 0; i < track.getChildCount(); i++) {
                    track.getChildAt(i).setTranslationX(i * w);
                }
                placePill(0, false);
                pillReady = true;
            }
        });

        // 接收引擎回传的报告
        IntentFilter f = new IntentFilter(ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, f, RECEIVER_EXPORTED);
        } else {
            registerReceiver(resultReceiver, f);
        }

        // 延迟检查更新（避免抢首帧渲染）
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                checkUpdate();
            }
        }, 700);
    }

    /* ============================ 导航：三页横滑 + 果冻 pill ============================ */

    private View tab(int i) {
        return i == 0 ? tabHome : i == 1 ? tabLog : tabAbout;
    }

    private void setIndex(int i) {
        if (i == cur) return;
        final int dir = i > cur ? 1 : -1;

        if (pillReady) animatePill(i, dir);

        // 页面横滑（ViewPager 式：各页叠在本地 0 位，仅动 translationX，
        // 规避父容器把超出自身边界的子页裁剪掉的问题）+ 入页视差
        animatePages(cur, i, dir);

        setTabVisual(cur, false);
        setTabVisual(i, true);
        cur = i;

        // 首次进日志页自动拉一次报告
        if (i == 1 && !logLoaded) {
            logLoaded = true;
            refreshLog();
        }
    }

    /** 页面横滑动画：每帧更新各页 translationX，入页带视差偏移 + 透明度渐入 */
    private void animatePages(final int from, final int to, final int dir) {
        final float w = pagesWrap.getWidth();
        if (w <= 0) return;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(550);
        a.setInterpolator(BezierInterpolator.jelly());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                float t = (float) anim.getAnimatedValue();
                float off = (from + (to - from) * t) * w;
                int n = track.getChildCount();
                for (int idx = 0; idx < n; idx++) {
                    View page = track.getChildAt(idx);
                    float base = idx * w - off;
                    if (idx == to) {
                        page.setTranslationX(base + dir * -dp(26) * (1f - t));
                        page.setAlpha(0.4f + 0.6f * t);
                    } else {
                        page.setTranslationX(base);
                    }
                }
            }
        });
        a.start();
    }

    /** pill 滑动：中段拉伸 1.32 倍模拟果冻，两端回收 */
    private void animatePill(int i, int dir) {
        View tab = tab(i);
        final float fromTx = pill.getTranslationX();
        final float toTx = tab.getX() + dp(3) - dp(7);
        final float stretch = Math.min(1.32f, 1f + Math.abs(toTx - fromTx) / (240 * getResources().getDisplayMetrics().density));
        pill.setPivotX(dir > 0 ? 0 : pill.getWidth());
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(560);
        a.setInterpolator(BezierInterpolator.jelly());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                float t = (float) anim.getAnimatedValue();
                pill.setTranslationX(fromTx + (toTx - fromTx) * t);
                float s = 1f + (stretch - 1f) * (float) Math.sin(Math.PI * t);
                pill.setScaleX(s);
            }
        });
        a.start();
    }

    private void placePill(int i, boolean animate) {
        View tab = tab(i);
        float tx = tab.getX() + dp(3) - dp(7);
        int w = tab.getWidth() - dp(6);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pill.getLayoutParams();
        if (lp.width != w) {
            lp.width = w;
            pill.setLayoutParams(lp);
        }
        pill.setPivotX(0);
        if (animate) {
            pill.animate().translationX(tx).setDuration(300).start();
        } else {
            pill.setTranslationX(tx);
        }
    }

    private void setTabVisual(int i, boolean on) {
        tabIcons[i].setColorFilter(on ? 0xFFE07800 : 0x6617181C);
        tabLabels[i].setTextColor(on ? 0xFFC76800 : 0xFF8A8B92);
        tabLabels[i].setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
        tabIcons[i].animate().scaleX(on ? 1.1f : 1f).scaleY(on ? 1.1f : 1f)
                .translationY(on ? -dp(1) : 0).setDuration(280).start();
    }

    private void startPulse() {
        ValueAnimator p = ValueAnimator.ofFloat(0f, 1f);
        p.setDuration(1800);
        p.setRepeatCount(ValueAnimator.INFINITE);
        p.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (float) a.getAnimatedValue();
                pulseHalo.setScaleX(1f + t * 1.6f);
                pulseHalo.setScaleY(1f + t * 1.6f);
                pulseHalo.setAlpha(1f - t);
            }
        });
        p.start();
    }

    /* ============================ 更新检查 ============================ */

    private void checkUpdate() {
        updateCheckFailed = false;
        tvGhSub.setVisibility(View.VISIBLE);
        tvGhSub.setText("检查更新中…");
        tvGhSub.setTextColor(0xFF8A8B92);
        UpdateChecker.check(this, new UpdateChecker.Callback() {
            @Override
            public void onResult(boolean hasUpdate, String latest, boolean failed) {
                if (failed) {
                    // 网络失败（如国内直连 api.github.com 不通）：可见可重试，不再静默
                    updateCheckFailed = true;
                    dotNew.setVisibility(View.GONE);
                    dotOk.setVisibility(View.GONE);
                    tvGhSub.setVisibility(View.VISIBLE);
                    tvGhSub.setText("检查失败 · 点按重试");
                    tvGhSub.setTextColor(0xFF8A8B92);
                } else if (hasUpdate) {
                    dotNew.setVisibility(View.VISIBLE);
                    dotOk.setVisibility(View.GONE);
                    tvGhSub.setVisibility(View.VISIBLE);
                    tvGhSub.setText("发现新版本 · 点击前往下载");
                    tvGhSub.setTextColor(0xFFC76800);
                    tvGhVal.setText("v" + latest + " ›");
                } else {
                    // 已是最新：绿点 + 「当前为最新版 + 版本号」
                    dotNew.setVisibility(View.GONE);
                    dotOk.setVisibility(View.VISIBLE);
                    tvGhSub.setVisibility(View.VISIBLE);
                    tvGhSub.setText("当前为最新版 v" + UpdateChecker.currentVersion(MainActivity.this));
                    tvGhSub.setTextColor(0xFF0DB85A);
                }
            }
        });
    }

    /* ============================ 下载渠道选择弹层 ============================ */

    private void showDownloadPicker() {
        if (linkPicker != null) return;
        final ViewGroup root = (ViewGroup) findViewById(android.R.id.content);

        final FrameLayout mask = new FrameLayout(this);
        mask.setBackgroundColor(0x59000000);
        mask.setClickable(true);
        mask.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissPicker(); }
        });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_glass);
        int pad = dp(16);
        card.setPadding(pad, pad, pad, dp(2));

        TextView title = new TextView(this);
        title.setText("选择下载渠道");
        title.setTextSize(13.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF17181C);
        card.addView(title);

        card.addView(pickRow("GitHub Releases", "国际源 · 需代理环境", RELEASES_URL));
        card.addView(divider());
        card.addView(pickRow("Gitee 发行版", "国内直连 · 推荐", GITEE_RELEASES_URL));
        card.addView(divider());
        card.addView(pickRow("123 网盘", "备用通道 · 提取码 iHLf", PAN_URL));

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(11.5f);
        cancel.setTextColor(0xFF8A8B92);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(10), 0, dp(10));
        cancel.setClickable(true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissPicker(); }
        });
        card.addView(cancel);

        mask.addView(card, new FrameLayout.LayoutParams(
                dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(mask, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        linkPicker = mask;

        mask.setAlpha(0f);
        card.setTranslationY(dp(18));
        mask.animate().alpha(1f).setDuration(180).start();
        card.animate().translationY(0f).setDuration(180).start();
    }

    /** 弹层选项行：标题 + 副标题 + 点击打开链接后收起 */
    private View pickRow(String title, String sub, final String url) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setForeground(getResources().getDrawable(tv.resourceId));
        int vpad = dp(11);
        row.setPadding(0, vpad, 0, vpad);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(12.5f);
        t.setTextColor(0xFF17181C);
        row.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(10f);
        s.setTextColor(0xFF8A8B92);
        s.setPadding(0, dp(2), 0, 0);
        row.addView(s);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismissPicker();
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Throwable e) {
                    Toast.makeText(MainActivity.this, "无可用浏览器", Toast.LENGTH_SHORT).show();
                }
            }
        });
        return row;
    }

    private View divider() {
        View d = new View(this);
        d.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        d.setBackgroundColor(0x0F17181C);
        return d;
    }

    private void dismissPicker() {
        if (linkPicker == null) return;
        final View m = linkPicker;
        linkPicker = null;
        m.animate().alpha(0f).setDuration(140).start();
        // 兜底：Activity onStop 时 Choreographer 帧回调冻结，动画 withEndAction 不执行，
        // 必须用 Handler 定时强移，否则 clickable 的遮罩残留会挡住整个界面
        m.postDelayed(new Runnable() {
            @Override public void run() {
                ViewGroup p = (ViewGroup) m.getParent();
                if (p != null) p.removeView(m);
            }
        }, 160);
    }

    @Override
    public void onBackPressed() {
        if (linkPicker != null) {
            dismissPicker();
            return;
        }
        super.onBackPressed();
    }

    /* ============================ 配置读写 ============================ */

    private JSONObject currentConfig() {
        try {
            SharedPreferences sp = getSharedPreferences("config", Context.MODE_PRIVATE);
            return new JSONObject(sp.getString("config_json", ConfigManager.DEFAULT_JSON));
        } catch (Throwable t) {
            try {
                return new JSONObject(ConfigManager.DEFAULT_JSON);
            } catch (Throwable ignored) {
                return new JSONObject();
            }
        }
    }

    private void put(String key, Object val) {
        try {
            JSONObject o = currentConfig();
            o.put(key, val);
            // 关键词/上限输入框内容一并带上（若用户改过未失焦也兜底读一次）
            if (etPattern != null && etMax != null) {
                String pat = etPattern.getText().toString().trim();
                o.put("scoutPattern", pat.isEmpty() ? "reward|bonus|coin" : pat);
                try {
                    o.put("maxScoutMethods", Integer.parseInt(etMax.getText().toString().trim()));
                } catch (Throwable ignored) {
                    o.put("maxScoutMethods", 400);
                }
            }
            persistSilent(o);
        } catch (Throwable ignored) {
        }
    }

    private void persistSilent(JSONObject o) {
        SharedPreferences sp = getSharedPreferences("config", Context.MODE_PRIVATE);
        sp.edit().putString("config_json", o.toString()).apply();
        openPrefsPerm();
        dirty = true;
    }

    /** 放开文件权限，让 LSPosed 的 XSharedPreferences 能在目标进程读到 */
    private void openPrefsPerm() {
        try {
            File prefsDir = new File(getApplicationInfo().dataDir, "shared_prefs");
            prefsDir.setExecutable(true, false);
            prefsDir.setReadable(true, false);
            File xml = new File(prefsDir, "config.xml");
            xml.setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        if (dirty) {
            dirty = false;
            Toast.makeText(this, "配置已保存 · 重启闲鱼生效", Toast.LENGTH_SHORT).show();
        }
        super.onPause();
    }

    /* ============================ 报告四通道读取 ============================ */

    private void refreshLog() {
        tvReport.setText("加载中…");
        try {
            Intent i = new Intent(ACTION_MTOP_CALL);
            i.setPackage(FISH_PKG);
            i.putExtra("api", "__GET_RESULT__");
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
        pollReport(0);
    }

    /** 轮询三通道读报告（su 首次会等 root 管理器授权，必须后台线程防 ANR） */
    private void pollReport(final int attempt) {
        if (attempt >= 3) return;
        tvReport.postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String text = tryReadReport();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (text != null) {
                                    tvReport.setText(text);
                                } else if (attempt >= 2) {
                                    tvReport.setText("无法读取报告。\n\n"
                                            + "· root 设备：首次请在 APatch / root 管理器弹窗中允许咸鱼小猪手的 su 授权，然后重新点「刷新报告」\n"
                                            + "· 或下拉通知栏查看上次结果");
                                } else {
                                    pollReport(attempt + 1);
                                }
                            }
                        });
                    }
                }, "rh-read-report").start();
            }
        }, attempt == 0 ? 400 : 1500);
    }

    /** 四通道读报告：1) 直读 2) 本机 socket（闲鱼活着即通，免权限）3) su root 读 4) 广播回传 */
    private static String tryReadReport() {
        String path = "/storage/emulated/0/Android/data/com.taobao.idlefish/cache/rh_tasks_result.txt";
        String t = readPlain(path);
        if (t != null) return t;
        t = readViaSocket();
        if (t != null) return t;
        return readViaSu(path);
    }

    /** 本机 socket 通道：连闲鱼主进程的报告服务（127.0.0.1:27715，无任何权限依赖） */
    private static String readViaSocket() {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 27715), 2500);
            s.setSoTimeout(3000);
            java.io.OutputStream os = s.getOutputStream();
            os.write("REPORT\n".getBytes("UTF-8"));
            os.flush();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(s.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Throwable t) {
            try {
                if (s != null) s.close();
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    private static String readPlain(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.canRead()) return null;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** root 通道：LSPosed 经 APatch 刷入的设备必有 su——模块 App 首次请求 root 会在管理器弹授权 */
    private static String readViaSu(String path) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + path});
            boolean done = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroy();
                return null;
            }
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Throwable t) {
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(resultReceiver);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
