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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

/**
 * 模块主界面（液态玻璃亮色版）：
 * - 三页横滑（主页 / 日志 / 关于）+ 果冻 pill Tab + 贝塞尔缓动视差
 * - 主页：头卡 / 状态脉冲 / 开始钓鱼 / 功能开关抽屉（LiquidSwitch）/ 间隔分钟制点击填写
 * - 日志：三通道读钓鱼报告（直读 / socket / su，全失败时逐通道诊断），钓鱼触发走 socket RUN
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
    private TextView tvVersion, tvStatus, tvStatusMeta, tvReport, tvGhSub, tvGhVal, tvLspState, tvRootState, tvCoinToday;
    /** 头卡同步循环：前台每 10s 重读差分日志（引擎采集 60s 周期，显示滞后 ≤10s），onPause 停 */
    private Handler coinUiHandler;
    private Runnable coinUiLoop;
    private volatile boolean coinRefreshing = false;
    private View dotNew, dotOk, pulseHalo, scoutArgs, rowUpdate, rowCoolapk;
    private View drawerHead, drawerContent;
    private TextView tvDrawerArrow;
    private boolean drawerOpen = false;
    private View drawerHead2, drawerContent2;
    private TextView tvDrawerArrow2;
    private boolean drawerOpen2 = false;
    private View drawerHead3, drawerContent3;
    private TextView tvDrawerArrow3;
    /** 🎒 背包功能第三个折叠面板状态 */
    private boolean drawerOpen3 = false;
    /** 抽屉动画互斥标记（true=动画进行中忽略点击） */
    private final java.util.concurrent.atomic.AtomicBoolean drawerBusy = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean drawerBusy2 = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean drawerBusy3 = new java.util.concurrent.atomic.AtomicBoolean(false);
    private View linkPicker; // 下载渠道选择弹层（非空 = 正在显示）
    private LiquidSwitch swAutoRun, swMtop, swAds, swScout, swPolish, swToast, swFishMedia, swPondTask, swNoChatSwipe;
    // 🎒 背包自动使用：6 道具行（输入框+开关）
    private android.widget.EditText etBagLuck, etBag8, etBag12, etBagRocket, etBagDouble, etBagGgk;
    private LiquidSwitch swBagLuck, swBag8, swBag12, swBagRocket, swBagDouble, swBagGgk;
    private EditText etPattern, etMax;
    private Button btnRun, btnRefresh, btnExportLog;

    private boolean dirty = false;
    private boolean logLoaded = false;
    private boolean updateCheckFailed = false;

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String report = intent.getStringExtra("report");
            if (tvReport == null) return;
            tvReport.setText(report == null || report.trim().isEmpty()
                    ? "暂无钓鱼记录（先跑一轮）" : report);
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
        tvLspState = findViewById(R.id.tvLspState);
        tvRootState = findViewById(R.id.tvRootState);
        tvCoinToday = findViewById(R.id.tvCoinToday);
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
        swPolish = findViewById(R.id.swPolish);
        swToast = findViewById(R.id.swToast);
        drawerHead = findViewById(R.id.drawerHead);
        drawerContent = findViewById(R.id.drawerContent);
        tvDrawerArrow = findViewById(R.id.tvDrawerArrow);
        drawerHead2 = findViewById(R.id.drawerHead2);
        drawerContent2 = findViewById(R.id.drawerContent2);
        tvDrawerArrow2 = findViewById(R.id.tvDrawerArrow2);
        drawerHead3 = findViewById(R.id.drawerHead3);
        drawerContent3 = findViewById(R.id.drawerContent3);
        tvDrawerArrow3 = findViewById(R.id.tvDrawerArrow3);
        swFishMedia = findViewById(R.id.swFishMedia);
        swPondTask = findViewById(R.id.swPondTask);
        swNoChatSwipe = findViewById(R.id.swNoChatSwipe);
        // 🎒 背包功能控件
        etBagLuck = findViewById(R.id.etBagLuck);       swBagLuck = findViewById(R.id.swBagLuck);
        etBag8 = findViewById(R.id.etBag8);             swBag8 = findViewById(R.id.swBag8);
        etBag12 = findViewById(R.id.etBag12);           swBag12 = findViewById(R.id.swBag12);
        etBagRocket = findViewById(R.id.etBagRocket);   swBagRocket = findViewById(R.id.swBagRocket);
        etBagDouble = findViewById(R.id.etBagDouble);   swBagDouble = findViewById(R.id.swBagDouble);
        etBagGgk = findViewById(R.id.etBagGgk);         swBagGgk = findViewById(R.id.swBagGgk);
        // 鱼塘任务副标题：「未完善，谨慎开启」染红（8 字符，span 前后含逗号）
        android.widget.TextView tvPondWarn = findViewById(R.id.tvPondWarn);
        String pw = tvPondWarn.getText().toString();
        android.text.SpannableString pwSs = new android.text.SpannableString(pw);
        int pwIdx = pw.indexOf("未完善，谨慎开启");
        if (pwIdx >= 0) {
            pwSs.setSpan(new android.text.style.ForegroundColorSpan(0xFFE53935),
                    pwIdx, pwIdx + 8, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tvPondWarn.setText(pwSs);
        }
        etPattern = findViewById(R.id.etPattern);
        etMax = findViewById(R.id.etMax);
        btnRun = findViewById(R.id.btnRun);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnExportLog = findViewById(R.id.btnExportLog);

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
        // 间隔分钟制（2026-10-19 UI 改版）：新键 autoRunIntervalMin，旧键 autoRunIntervalHours 兼容换算
        int mins = cfg.optInt("autoRunIntervalMin", -1);
        if (mins < 0) mins = cfg.optInt("autoRunIntervalHours", 4) * 60;
        mins = Math.min(1440, Math.max(0, mins));
        tvStatusMeta.setText("自动间隔 " + mins + " 分钟 ▾");

        // ===== 开关初始态（静默，不触发持久化） =====
        swAutoRun.setOnSilent(cfg.optBoolean("autoRun", true));
        swMtop.setOnSilent(cfg.optBoolean("mtopHook", true));
        swAds.setOnSilent(cfg.optBoolean("adSdkPresets", true));
        swScout.setOnSilent(true); // 侦察模式 UI 已隐藏：功能保持默认开启
        swPolish.setOnSilent(cfg.optBoolean("polish", true));
        swToast.setOnSilent(cfg.optBoolean("toastNotify", true));
        scoutArgs.setVisibility(View.GONE); // 恒隐藏
        etMax.setText("400");

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
                // 侦察模式 UI 已隐藏：开关不再可操作，保持恒开（此监听为视图隐藏后的死代码，兜底恒写 true）
                put("scout", true);
                scoutArgs.setVisibility(View.GONE);
            }
        });
        swPolish.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
            @Override public void onToggle(boolean on) { put("polish", on); }
        });
        swToast.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
            @Override public void onToggle(boolean on) { put("toastNotify", on); }
        });
        // 🎣 无水印保存总开关（强化功能抽屉；RhFishFloat.hook 时读 fishMedia）
        swFishMedia.setOnSilent(cfg.optBoolean("fishMedia", true));
        swFishMedia.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
            @Override public void onToggle(boolean on) { put("fishMedia", on); }
        });
        // 🐟 鱼塘任务开关：开启=打开闲鱼自动跑一次（RhPondTask.autoRunDaily 读 pond）
        swPondTask.setOnSilent(cfg.optBoolean("pond", true));
        swPondTask.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
            @Override public void onToggle(boolean on) { put("pond", on); }
        });
        // 💬 禁止聊天左滑：禁用聊天页左滑进对方主页手势（RhChatHook 60s 热读 noChatSwipe）
        swNoChatSwipe.setOnSilent(cfg.optBoolean("noChatSwipe", false));
        swNoChatSwipe.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
            @Override public void onToggle(boolean on) { put("noChatSwipe", on); }
        });
        // 🎒 背包自动使用：初始态 + 绑定（数量 -1=用到0，留空/0=不用；开关打开立即执行一轮）
        installBag(cfg, etBagLuck, swBagLuck, "bagLuckOn", "bagLuckNum", "好运骰");
        installBag(cfg, etBag8, swBag8, "bag8On", "bag8Num", "八面骰");
        installBag(cfg, etBag12, swBag12, "bag12On", "bag12Num", "十二面骰");
        installBag(cfg, etBagRocket, swBagRocket, "bagRocketOn", "bagRocketNum", "火箭骰子");
        installBag(cfg, etBagDouble, swBagDouble, "bagDoubleOn", "bagDoubleNum", "双倍骰子");
        installBag(cfg, etBagGgk, swBagGgk, "bagGgkOn", "bagGgkNum", "刮刮卡");
        // 🐟 鱼塘任务：触发闲鱼进程内 RhPondTask（AWARD 批量领+签到气泡），报告弹窗
        findViewById(R.id.rowPondTask).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "🐟 已触发鱼塘任务引擎…", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String kick = socketCmd("POND");
                        if (kick == null || kick.length() == 0) {
                            runUi(new Runnable() {
                                @Override public void run() {
                                    Toast.makeText(MainActivity.this,
                                            "触发失败：闲鱼未运行或 socket 不通", Toast.LENGTH_LONG).show();
                                }
                            });
                            return;
                        }
                        // 引擎后台跑（列表+逐个领奖），轮询等报告生成（最多 40s）
                        String report = null;
                        for (int i = 0; i < 20; i++) {
                            try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                            String r = socketCmd("POND_REPORT");
                            if (r != null && r.contains("鱼塘任务报告")) { report = r; break; }
                        }
                        final String rep = report;
                        runUi(new Runnable() {
                            @Override public void run() {
                                showPondReport(rep);
                            }
                        });
                    }
                }, "rh-pond-ui").start();
            }
        });

        // ===== 功能开关抽屉：点击展开/收起（高度+透明度动画） =====
        drawerHead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (drawerBusy.get()) return; // 动画中忽略连点
                drawerOpen = !drawerOpen;
                // 实心三角 ▼：收起 -90°（指向右），展开 0°（指向下），与抽屉动画同步
                tvDrawerArrow.animate().rotation(drawerOpen ? 0f : -90f)
                        .setDuration(drawerOpen ? 260 : 200).start();
                animateDrawer((ViewGroup) drawerContent, drawerOpen, drawerBusy);
            }
        });

        // ===== 强化功能抽屉：同款连体折叠 =====
        drawerHead2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (drawerBusy2.get()) return;
                drawerOpen2 = !drawerOpen2;
                tvDrawerArrow2.animate().rotation(drawerOpen2 ? 0f : -90f)
                        .setDuration(drawerOpen2 ? 260 : 200).start();
                animateDrawer((ViewGroup) drawerContent2, drawerOpen2, drawerBusy2);
            }
        });

        // ===== 🎒 背包功能抽屉：第三个连体折叠 =====
        drawerHead3.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (drawerBusy3.get()) return;
                drawerOpen3 = !drawerOpen3;
                tvDrawerArrow3.animate().rotation(drawerOpen3 ? 0f : -90f)
                        .setDuration(drawerOpen3 ? 260 : 200).start();
                animateDrawer((ViewGroup) drawerContent3, drawerOpen3, drawerBusy3);
            }
        });

        // ===== 自动间隔：点击弹输入卡（分钟制 0-1440） =====
        tvStatusMeta.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askInterval();
            }
        });

        // ===== 开始钓鱼：socket 直触发引擎（MIUI 拦跨 App 广播，旧广播通道已废弃）=====
        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnRun.setText("触发中…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String resp = socketCmd("RUN");
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                boolean ok = resp != null && resp.contains("OK");
                                btnRun.setText(ok ? "已触发 · 闲鱼内执行中…" : "未连通 · 打开闲鱼后重试");
                                Toast.makeText(MainActivity.this,
                                        ok ? "引擎已启动，钓鱼进行中，完成后通知栏推送"
                                                : "socket 未连通：请先打开闲鱼（引擎随进程加载），再点开始钓鱼",
                                        Toast.LENGTH_LONG).show();
                                if (!ok) {
                                    // 兜底：拉起闲鱼，进程起来引擎自动加载，稍后再点一次即可
                                    try {
                                        Intent fish = getPackageManager().getLaunchIntentForPackage(FISH_PKG);
                                        if (fish != null) startActivity(fish);
                                    } catch (Throwable ignored) {
                                    }
                                }
                                btnRun.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        btnRun.setText("开始钓鱼");
                                    }
                                }, 5000);
                            }
                        });
                    }
                }, "rh-run-cmd").start();
            }
        });

        // ===== 刷新报告 =====
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshLog();
            }
        });
        // 导出小猪手运行日志（B 类）：su cp 当天 files 运行日志到 sd 卡根目录 xiaozhushou日期.log
        btnExportLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportRunLog();
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

        // 模块状态检测（onResume 统一触发，此处不再重复启动）
        startStateCheck();

        // 延迟检查更新（避免抢首帧渲染）
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                checkUpdate();
            }
        }, 700);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到前台重检模块状态（闲鱼可能在后台被启停，一次检测会过期误导）
        startStateCheck();
        startCoinUiLoop(); // 前台同步：每 10s 重算今日领取，引擎采集写入后最迟 10s 上屏
    }

    /**
     * 模块状态检测（异步防 ANR，两线程并行；onCreate / onResume 都会触发）：
     * - LSPosed 三级判定：① PING 引擎报告服务（最真实：引擎此刻在闲鱼里跑）；
     *   ② 失败时 su 查 LSPosed 日志留痕（本开机周期模块被加载过 = 已启用，
     *   覆盖"闲鱼没跑导致引擎不在"的误报——10-17 用户实锤）；③ 均无 = 未激活
     * - Root = su 试跑（未授权设备首次调用可能触发管理器弹窗，5s 超时判失败）
     */
    private void startStateCheck() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = socketCmd("PING") != null;
                if (!ok) {
                    // 闲鱼未运行时引擎必然不在——回查 LSPosed 模块日志留痕
                    String out = suExec("grep -c piggyhand $(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1) 2>/dev/null");
                    ok = parseIntSafe(out) > 0;
                }
                final boolean okF = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvLspState.setText(okF ? "LSPosed：已激活" : "LSPosed：未激活");
                        tvLspState.setTextColor(okF ? 0xFF0DB85A : 0xFFD32F2F);
                    }
                });
            }
        }, "rh-lsp-check").start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = hasRootAccess();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvRootState.setText(ok ? "Root权限：已激活" : "Root权限：未激活");
                        tvRootState.setTextColor(ok ? 0xFF0DB85A : 0xFFD32F2F);
                    }
                });
            }
        }, "rh-root-check").start();
    }

    /** 前台同步循环：进前台立即刷一轮，之后每 10s 一轮；与引擎 60s 采集周期错频，读到新行即上屏 */
    private void startCoinUiLoop() {
        if (coinUiHandler == null) coinUiHandler = new Handler(Looper.getMainLooper());
        if (coinUiLoop == null) {
            coinUiLoop = new Runnable() {
                @Override
                public void run() {
                    refreshCoinToday();
                    coinUiHandler.postDelayed(this, 10000L);
                }
            };
        }
        coinUiHandler.removeCallbacks(coinUiLoop);
        refreshCoinToday();
        coinUiHandler.postDelayed(coinUiLoop, 10000L);
    }

    private void stopCoinUiLoop() {
        if (coinUiHandler != null && coinUiLoop != null) coinUiHandler.removeCallbacks(coinUiLoop);
    }

    /**
     * 读差分日志重算今日领取并上屏（重入保护：su cat 约 1s，10s 周期不会堆积）。
     * 一次 su 读「昨日最后一行 + 今日全文」，相邻行差分：涨了累加、跌了（兑换支出）跳过。
     * 拿不到值不改 UI（保留上次显示），不存在假 0。
     */
    private void refreshCoinToday() {
        if (coinRefreshing) return;
        coinRefreshing = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                long gain = -1;
                try {
                    java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy.MM.dd", Locale.US);
                    String today = df.format(new java.util.Date());
                    String yest = df.format(new java.util.Date(System.currentTimeMillis() - 86400000L));
                    String dir = "/storage/emulated/0/Android/data/com.taobao.idlefish/files";
                    String out = suExec("tail -1 '" + dir + "/zhushou_" + yest
                            + ".log' 2>/dev/null; echo '---RHSEP---'; cat '"
                            + dir + "/zhushou_" + today + ".log' 2>/dev/null");
                    if (out != null && out.contains("---RHSEP---")) {
                        String[] parts = out.split("---RHSEP---", 2);
                        java.text.SimpleDateFormat tf =
                                new java.text.SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.US);
                        ArrayList<long[]> rows = new ArrayList<long[]>();
                        collectCoinRows(parts[0], tf, rows);
                        collectCoinRows(parts[1], tf, rows);
                        java.util.Collections.sort(rows, new Comparator<long[]>() {
                            @Override
                            public int compare(long[] a, long[] b) {
                                return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : 0);
                            }
                        });
                        int startIdx = -1;
                        for (int i = 0; i < rows.size(); i++) {
                            if (today.equals(df.format(new java.util.Date(rows.get(i)[0])))) {
                                startIdx = i;
                                break;
                            }
                        }
                        if (startIdx >= 0) {
                            gain = 0;
                            // 基准=今日首行的上一行（即昨日最后一行，跨天衔接）；首日无昨日文件则基准=首行自身
                            long prev = startIdx > 0 ? rows.get(startIdx - 1)[1] : rows.get(startIdx)[1];
                            for (int i = startIdx; i < rows.size(); i++) {
                                long v = rows.get(i)[1];
                                if (v > prev) gain += v - prev; // 只记增加的，不记支出
                                prev = v;
                            }
                        }
                    }
                } catch (Throwable t) {
                    gain = -1;
                }
                final long g = gain;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (g >= 0) {
                            tvCoinToday.setText("今日领取：" + g);
                            tvCoinToday.setTextColor(0xFF0DB85A);
                        }
                        // g < 0：不改 UI——保留上次成功值或初始「--」占位，10s 后下轮重试
                    }
                });
                coinRefreshing = false;
            }
        }, "rh-coin-check").start();
    }

    /**
     * 导出小猪手运行日志（B 类，10-17）：su cp 当天 files 运行日志到 sd 卡根目录。
     * 文件名 xiaozhushouYYYY.MM.DD.log；cp 一步到位不经 App 内存（日志可达数百 KB）。
     */
    private void exportRunLog() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy.MM.dd", Locale.US);
                String day = df.format(new java.util.Date());
                String src = "/storage/emulated/0/Android/data/com.taobao.idlefish/files/zhushou_run_" + day + ".log";
                String dst = "/storage/emulated/0/xiaozhushou" + day + ".log";
                String probe = suExec("ls -la '" + src + "' 2>/dev/null");
                final String msg;
                if (probe == null || probe.trim().isEmpty()) {
                    msg = "今天还没有运行日志（引擎运行后自动产生）";
                } else {
                    String r = suExec("cp -f '" + src + "' '" + dst + "' 2>&1; chmod 644 '" + dst + "' 2>/dev/null");
                    boolean ok = r != null && !r.contains("No such") && !r.contains("denied") && !r.contains("Permission");
                    msg = ok ? "已导出到 sd 卡根目录：xiaozhushou" + day + ".log" : "导出失败：" + r;
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "rh-export").start();
    }

    /** su 执行任意命令（5s 超时）。返回 stdout（可能为空串），失败/超时返回 null */
    private static String suExec(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroy();
                return null;
            }
            return readAll(p.getInputStream());
        } catch (Throwable t) {
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /** 宽松解析首个整数（"12\n" → 12），失败返回 -1 */
    private static int parseIntSafe(String s) {
        if (s == null) return -1;
        try {
            StringBuilder sb = new StringBuilder();
            for (char ch : s.trim().toCharArray()) {
                if (ch >= '0' && ch <= '9') sb.append(ch);
                else if (sb.length() > 0) break;
            }
            return sb.length() == 0 ? -1 : Integer.parseInt(sb.toString());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 解析余额日志行「yyyy.MM.dd HH:mm:ss val」进 rows（容错跳过残行/空行/CRLF） */
    private static void collectCoinRows(String text, java.text.SimpleDateFormat tf, ArrayList<long[]> rows) {
        if (text == null) return;
        String[] lines = text.split("\n");
        for (String ln : lines) {
            String s = ln.trim();
            if (s.length() == 0) continue;
            String[] tk = s.split("[ \\t]+");
            if (tk.length < 3) continue;
            try {
                long ts = tf.parse(tk[0] + " " + tk[1]).getTime();
                long v = Long.parseLong(tk[2].trim());
                if (v >= 0) rows.add(new long[]{ts, v});
            } catch (Throwable ignored) {
            }
        }
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
                    // 主页 banner 版本号恢复默认
                    tvVersion.setTextColor(0xFFB35F00);
                    tvVersion.setText("v" + UpdateChecker.currentVersion(MainActivity.this) + " · LSPosed");
                } else if (hasUpdate) {
                    dotNew.setVisibility(View.VISIBLE);
                    dotOk.setVisibility(View.GONE);
                    tvGhSub.setVisibility(View.VISIBLE);
                    tvGhSub.setText("发现新版本 · 点击前往下载");
                    tvGhSub.setTextColor(0xFFC76800);
                    tvGhVal.setText("v" + latest + " ›");
                    // 主页 banner 版本号：红色提醒 + 前缀文案
                    tvVersion.setTextColor(0xFFD32F2F);
                    tvVersion.setText("请及时更新插件 v" + latest);
                } else {
                    // 已是最新：绿点 + 「当前为最新版 + 版本号」
                    dotNew.setVisibility(View.GONE);
                    dotOk.setVisibility(View.VISIBLE);
                    tvGhSub.setVisibility(View.VISIBLE);
                    tvGhSub.setText("当前为最新版 v" + UpdateChecker.currentVersion(MainActivity.this));
                    tvGhSub.setTextColor(0xFF0DB85A);
                    // 主页 banner 版本号：绿色
                    tvVersion.setTextColor(0xFF0DB85A);
                    tvVersion.setText("v" + UpdateChecker.currentVersion(MainActivity.this) + " · LSPosed");
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
            JSONObject o = new JSONObject(sp.getString("config_json", ConfigManager.DEFAULT_JSON));
            // 侦察模式已固化为默认（UI 隐藏）：历史配置里的自定义值一律校正回默认
            o.put("scout", true);
            o.put("scoutPattern", "reward|bonus|coin");
            o.put("maxScoutMethods", 400);
            return o;
        } catch (Throwable t) {
            try {
                return new JSONObject(ConfigManager.DEFAULT_JSON);
            } catch (Throwable ignored) {
                return new JSONObject();
            }
        }
    }

    /** 抽屉展开/收起动画：高度 0↔满 + alpha 同步（展开 260ms 减速 / 收起 200ms 加速）；busy 为所属抽屉的动画互斥标记 */
    private void animateDrawer(final ViewGroup v, final boolean expand, final java.util.concurrent.atomic.AtomicBoolean busy) {
        drawerBusySet(busy, true);
        if (expand) {
            v.setVisibility(View.VISIBLE);
            v.setAlpha(0f);
            // post 等 layout 完成，拿到真实满高
            v.post(new Runnable() {
                @Override
                public void run() {
                    final int full = v.getHeight();
                    if (full == 0) { // 兜底：量不到直接显示
                        v.setAlpha(1f);
                        drawerBusySet(busy, false);
                        return;
                    }
                    ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
                    va.setDuration(260);
                    va.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
                    va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator a) {
                            float t = (Float) a.getAnimatedValue();
                            ViewGroup.LayoutParams lp = v.getLayoutParams();
                            lp.height = (int) (full * t);
                            v.setLayoutParams(lp);
                            v.setAlpha(t);
                        }
                    });
                    va.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator a) {
                            ViewGroup.LayoutParams lp = v.getLayoutParams();
                            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT; // 归还自适应高
                            v.setLayoutParams(lp);
                            v.setAlpha(1f);
                            drawerBusySet(busy, false);
                        }
                    });
                    va.start();
                }
            });
        } else {
            final int full = v.getHeight();
            ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(200);
            va.setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f));
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    float t = (Float) a.getAnimatedValue();
                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                    lp.height = (int) (full * (1 - t));
                    v.setLayoutParams(lp);
                    v.setAlpha(1 - t);
                }
            });
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    v.setVisibility(View.GONE);
                    v.setAlpha(1f);
                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    v.setLayoutParams(lp);
                    drawerBusySet(busy, false);
                }
            });
            va.start();
        }
    }

    private void drawerBusySet(java.util.concurrent.atomic.AtomicBoolean busy, boolean val) {
        busy.set(val);
    }

    /** 自动间隔填写卡：分钟制 0-1440（默认 240），确定即存（自绘圆角卡，风格对齐主界面） */
    /** 主线程执行 */
    private void runUi(Runnable r) {
        runOnUiThread(r);
    }

    /** 🐟 鱼塘任务报告弹窗（等宽字体滚动查看） */
    private void showPondReport(String rep) {
        try {
            final android.app.Dialog dlg = new android.app.Dialog(this);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(24));
            bg.setColor(0xFFFFFFFF);
            card.setBackground(bg);

            TextView title = new TextView(this);
            title.setText("🐟 鱼塘任务报告");
            title.setTextSize(15);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(0xFF17181C);
            title.setPadding(dp(24), dp(22), dp(24), dp(8));
            card.addView(title);

            TextView close = new TextView(this);
            close.setText("关闭");
            close.setTextSize(14);
            close.setTypeface(Typeface.DEFAULT_BOLD);
            close.setTextColor(0xFF0A7AFF);
            close.setGravity(Gravity.CENTER);
            close.setPadding(0, dp(12), 0, dp(14));
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dlg.dismiss(); }
            });

            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            TextView body = new TextView(this);
            body.setText(rep == null || rep.length() == 0
                    ? "报告尚未生成（闲鱼进程可能未运行，或引擎仍在执行）\n\n稍后重新点击「鱼塘任务」查看。" : rep);
            body.setTextSize(11);
            body.setTypeface(Typeface.MONOSPACE);
            body.setTextColor(0xFF2A2E35);
            body.setPadding(dp(24), dp(4), dp(24), dp(16));
            sv.addView(body);
            LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(420));
            sv.setLayoutParams(svlp);
            card.addView(sv);
            card.addView(close);

            dlg.setContentView(card);
            android.view.Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                        android.graphics.Color.TRANSPARENT));
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
                w.setAttributes(lp);
            }
            dlg.show();
        } catch (Throwable t) {
            Toast.makeText(this, "报告展示失败: " + t, Toast.LENGTH_SHORT).show();
        }
    }

    private void askInterval() {
        try {
            JSONObject cfg = currentConfig();
            int cur = cfg.optInt("autoRunIntervalMin", -1);
            if (cur < 0) cur = cfg.optInt("autoRunIntervalHours", 4) * 60;

            final android.app.Dialog dlg = new android.app.Dialog(this);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(24));
            bg.setColor(0xFFFFFFFF);
            card.setBackground(bg);

            TextView title = new TextView(this);
            title.setText("自动间隔（分钟）");
            title.setTextSize(15);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(0xFF17181C);
            title.setPadding(dp(24), dp(22), dp(24), dp(2));
            card.addView(title);

            TextView sub = new TextView(this);
            sub.setText("0 = 打开闲鱼即跑 · 最大 1440（24 小时）");
            sub.setTextSize(10.5f);
            sub.setTextColor(0xFF8A8F99);
            sub.setPadding(dp(24), 0, dp(24), dp(10));
            card.addView(sub);

            final EditText et = new EditText(this);
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            et.setHint("0 - 1440");
            et.setText(String.valueOf(cur));
            et.setSelection(et.getText().length());
            et.setTextSize(15);
            et.setTextColor(0xFF17181C);
            et.setHintTextColor(0xFFB9BEC7);
            et.setBackground(getDrawable(R.drawable.bg_input));
            et.setPadding(dp(14), dp(11), dp(14), dp(11));
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.setMargins(dp(24), 0, dp(24), dp(6));
            et.setLayoutParams(elp);
            card.addView(et);

            // 按钮行
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(6), 0, 0);
            TextView cancel = new TextView(this);
            cancel.setText("取消");
            cancel.setTextSize(14);
            cancel.setTextColor(0xFF8A8F99);
            cancel.setGravity(Gravity.CENTER);
            cancel.setPadding(0, dp(14), 0, dp(14));
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dlg.dismiss(); }
            });
            TextView ok = new TextView(this);
            ok.setText("确定");
            ok.setTextSize(14);
            ok.setTypeface(Typeface.DEFAULT_BOLD);
            ok.setTextColor(0xFF0A7AFF);
            ok.setGravity(Gravity.CENTER);
            ok.setPadding(0, dp(14), 0, dp(14));
            ok.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        int v2 = Integer.parseInt(et.getText().toString().trim());
                        v2 = Math.min(1440, Math.max(0, v2));
                        put("autoRunIntervalMin", v2);
                        tvStatusMeta.setText("自动间隔 " + v2 + " 分钟 ▾");
                        Toast.makeText(MainActivity.this, "已保存：自动间隔 " + v2 + " 分钟（重启闲鱼生效）", Toast.LENGTH_SHORT).show();
                        dlg.dismiss();
                    } catch (Throwable t) {
                        Toast.makeText(MainActivity.this, "请输入 0-1440 的数字", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cancel.setLayoutParams(clp);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ok.setLayoutParams(olp);
            row.addView(cancel);
            View divider = new View(this);
            divider.setBackgroundColor(0x1417181C);
            row.addView(divider, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
            row.addView(ok);
            card.addView(row);

            dlg.setContentView(card);
            android.view.Window w = dlg.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
                w.setDimAmount(0.35f);
                android.view.WindowManager.LayoutParams lp = w.getAttributes();
                lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.82);
                lp.gravity = Gravity.CENTER;
                w.setAttributes(lp);
            }
            dlg.setCancelable(true);
            dlg.setCanceledOnTouchOutside(true);
            dlg.show();
        } catch (Throwable ignored) {
        }
    }

    private int dp(float v) {
        return (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()) + 0.5f);
    }

    /* ============================ 🎒 背包自动使用 ============================ */

    /** 背包行绑定：初始态回填、开关切换保存并立即执行一轮、输入框失焦保存 */
    private void installBag(JSONObject cfg, final android.widget.EditText et, final LiquidSwitch sw,
                            final String onKey, final String numKey, final String label) {
        int num = cfg.optInt(numKey, 0);
        et.setText(String.valueOf(num)); // 默认显示 0（0=不使用，-1=用到0）
        sw.setOnSilent(cfg.optBoolean(onKey, false));
        sw.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
            @Override public void onToggle(boolean on) {
                put(onKey, on);
                put(numKey, bagNumOf(et));
                if (on) kickRun(label);
            }
        });
        et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    int nv = bagNumOf(et);
                    et.setText(String.valueOf(nv)); // 失焦校正显示（-2→-1、1000→999、空→0）
                    put(numKey, nv);
                }
            }
        });
    }

    /** 背包数量框解析：空=0（不使用）；合法区间 [-1, 999]，越界自动收敛（-2→-1、1000→999）；非法输入按 0 处理 */
    private static int bagNumOf(android.widget.EditText et) {
        try {
            String s = et.getText().toString().trim();
            if (s.isEmpty()) return 0;
            int v;
            try {
                v = Integer.parseInt(s);
            } catch (Throwable neg) {
                return 0; // 纯"-"或残缺输入
            }
            if (v < -1) v = -1;   // 最小 -1（用到 0）
            if (v > 999) v = 999; // 最大 999
            return v;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 背包开关打开后立即执行一轮（socket RUN；闲鱼未运行则提示，下次打开闲鱼自动执行） */
    private void kickRun(final String label) {
        new Thread(new Runnable() {
            @Override public void run() {
                final String resp = socketCmd("RUN");
                runUi(new Runnable() {
                    @Override public void run() {
                        boolean ok = resp != null && resp.contains("OK");
                        Toast.makeText(MainActivity.this,
                                ok ? "🎒 已开始执行，" + label + " 将自动使用"
                                        : "暂未连通闲鱼（打开闲鱼后将自动执行）",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "rh-bag-kick").start();
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
        syncSdcardConfig(o); // su 双写 /sdcard/RewardHook/config.json（引擎 sdcard 通道优先读，绕开 LSPosed 快照滞后）
        dirty = true;
    }

    /** 异步 su 双写配置到闲鱼外部私有 files/（目标进程必可读；失败静默，仍走 XSharedPreferences 兜底） */
    private void syncSdcardConfig(final JSONObject o) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int exit = -1;
                try {
                    java.lang.Process p = Runtime.getRuntime().exec(new String[]{
                            "su", "-c", "mkdir -p /sdcard/Android/data/com.taobao.idlefish/files"
                                    + " && cat > " + ConfigManager.OVERRIDE_PATH});
                    java.io.OutputStream os = p.getOutputStream();
                    os.write(o.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    exit = p.waitFor();
                    java.io.InputStream es = p.getErrorStream();
                    byte[] err = new byte[es.available()];
                    int n = es.read(err);
                    android.util.Log.i("RhSync", "override config written, exit=" + exit
                            + (n > 0 ? " err=" + new String(err, 0, n) : ""));
                    p.destroy();
                } catch (Throwable t) {
                    android.util.Log.e("RhSync", "su write fail", t);
                }
            }
        }, "rh-cfg-sync").start();
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
        stopCoinUiLoop(); // 退后台停轮询，零后台开销
        if (dirty) {
            dirty = false;
            Toast.makeText(this, "配置已保存 · 重启闲鱼生效", Toast.LENGTH_SHORT).show();
        }
        super.onPause();
    }

    /* ============================ 报告四通道读取 ============================ */

    private void refreshLog() {
        tvReport.setText("加载中…");
        pollReport(0);
    }

    /** 轮询读报告（su 首次会等 root 管理器授权，必须后台线程防 ANR） */
    private void pollReport(final int attempt) {
        if (attempt >= 3) return;
        tvReport.postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final ReadResult r = tryReadReport();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (r.text != null) {
                                    tvReport.setText("· 读取通道: " + r.via + "\n\n" + r.text);
                                } else if (attempt >= 2) {
                                    tvReport.setText(r.diag + DIAG_FOOT);
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

    private static final String DIAG_FOOT =
            "\n\n解决步骤：\n"
                    + "1. root 授权：打开 Magisk / APatch → 超级用户 → 允许「咸鱼小猪手」，回本页点「刷新报告」\n"
                    + "2. MIUI 联网放行：安全中心 → 应用管理 → 咸鱼小猪手 → 联网控制 → 允许 WLAN 与数据\n"
                    + "3. 保持闲鱼在后台运行（报告服务随闲鱼进程启动，端口 27715）\n"
                    + "4. 最近一次钓鱼结果也会推送在通知栏";

    /** 四通道读报告结果：text=报告全文 / via=成功通道 / diag=全失败诊断 */
    private static class ReadResult {
        String text;
        String via;
        String diag;
    }

    /**
     * 通道优先级：1) 直读文件 2) 本机 socket（闲鱼活着即通，免权限）3) su root 读。
     * 广播回传（ACTION_RESULT）作为独立即时通道由 resultReceiver 承担，不在此列。
     */
    private static ReadResult tryReadReport() {
        String path = "/storage/emulated/0/Android/data/com.taobao.idlefish/cache/rh_tasks_result.txt";
        ReadResult r = new ReadResult();
        StringBuilder diag = new StringBuilder("无法读取钓鱼报告\n\n逐通道诊断：\n");
        String[] p = readPlain(path);
        if (p[0] != null) { r.text = p[0]; r.via = "直读文件"; return r; }
        diag.append("· 直读：").append(p[1]).append('\n');
        p = readViaSocket();
        if (p[0] != null) { r.text = p[0]; r.via = "socket (127.0.0.1:27715)"; return r; }
        diag.append("· socket：").append(p[1]).append('\n');
        p = readViaSu(path);
        if (p[0] != null) { r.text = p[0]; r.via = "su (root)"; return r; }
        diag.append("· su (root)：").append(p[1]).append('\n');
        r.diag = diag.toString();
        return r;
    }

    /** 向报告服务发指令（RUN / REPORT），返回响应全文，失败返回 null */
    private static String socketCmd(String cmd) {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 27715), 2500);
            s.setSoTimeout(3000);
            java.io.OutputStream os = s.getOutputStream();
            os.write((cmd + "\n").getBytes("UTF-8"));
            os.flush();
            return readAll(s.getInputStream());
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (s != null) s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 本机 socket 通道：连闲鱼主进程的报告服务（127.0.0.1:27715，无任何权限依赖）。返回 {内容, 失败原因} */
    private static String[] readViaSocket() {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 27715), 2500);
            s.setSoTimeout(3000);
            java.io.OutputStream os = s.getOutputStream();
            os.write("REPORT\n".getBytes("UTF-8"));
            os.flush();
            String body = readAll(s.getInputStream());
            return body.length() > 0 ? new String[]{body, null}
                    : new String[]{null, "服务返回空（钓鱼报告尚未生成）"};
        } catch (java.net.SocketTimeoutException e) {
            return new String[]{null, "连接超时（闲鱼进程可能被杀，或 MIUI 联网管控拦截了本机回环）"};
        } catch (java.net.ConnectException e) {
            return new String[]{null, "连接被拒（端口未监听，闲鱼未启动或引擎未加载）"};
        } catch (Throwable t) {
            return new String[]{null, "失败: " + t.getClass().getSimpleName()};
        } finally {
            try {
                if (s != null) s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String[] readPlain(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return new String[]{null, "文件不存在（先在闲鱼里跑一轮任务）"};
            if (!f.canRead()) return new String[]{null, "无读取权限（Android 11+ 沙盒隔离，正常现象，走后续通道）"};
            String t = readAll(new java.io.FileInputStream(f));
            return t.length() > 0 ? new String[]{t, null} : new String[]{null, "文件为空"};
        } catch (Throwable t) {
            return new String[]{null, "读取异常: " + t.getClass().getSimpleName()};
        }
    }

    /** root 通道：su 读沙盒内报告。授权弹窗首次出现需用户确认，waitFor 放宽到 10s。返回 {内容, 失败原因} */
    private static String[] readViaSu(String path) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + path});
            boolean done = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroy();
                return new String[]{null, "等待授权超时（root 管理器弹窗未确认——打开 Magisk / APatch → 超级用户 → 允许咸鱼小猪手）"};
            }
            String out = readAll(p.getInputStream());
            String err = readAll(p.getErrorStream());
            if (out.length() > 0) return new String[]{out, null};
            int code;
            try {
                code = p.exitValue();
            } catch (Throwable t) {
                code = -1;
            }
            if (code != 0) {
                String e = err == null ? "" : err.trim();
                if (e.contains("No such file")) {
                    return new String[]{null, "命令失败：报告文件不存在（先在闲鱼里跑一轮任务）"};
                }
                if (e.contains("not in sudoers") || e.contains("denied") || e.contains("denied by")) {
                    return new String[]{null, "授权被拒 (exit " + code + "): " + e};
                }
                return new String[]{null, "执行失败 (exit " + code + (e.length() > 0 ? ": " + e : "") + ")"};
            }
            return new String[]{null, err != null && err.length() > 0 ? "失败: " + err.trim() : "无输出（报告文件不存在？）"};
        } catch (java.io.IOException e) {
            return new String[]{null, "su 不存在（设备未 root，或 root 管理器未提供 su）"};
        } catch (Throwable t) {
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
            return new String[]{null, "失败: " + t.getClass().getSimpleName()};
        }
    }

    /** 流读取公共方法：按行读到 EOF */
    private static String readAll(java.io.InputStream is) throws java.io.IOException {
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    /** root 可用性检测：su -c id 输出含 uid=0 即已授权（未授权被拒 / 无 su 二进制 / 超时均判否） */
    private static boolean hasRootAccess() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroy();
                return false;
            }
            String out = readAll(p.getInputStream());
            return out.contains("uid=0");
        } catch (Throwable t) {
            try {
                if (p != null) p.destroy();
            } catch (Throwable ignored) {
            }
            return false;
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
