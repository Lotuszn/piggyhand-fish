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
 * - 主页：头卡 / 状态脉冲 / 开始钓鱼 / 四开关（LiquidSwitch）/ 间隔滑块（LiquidSlider）
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
    private TextView tvVersion, tvStatus, tvStatusMeta, tvReport, tvGhSub, tvGhVal, tvHoursNum, tvLspState, tvRootState, tvCoinToday;
    /** 头卡同步循环：前台每 10s 重读差分日志（引擎采集 60s 周期，显示滞后 ≤10s），onPause 停 */
    private Handler coinUiHandler;
    private Runnable coinUiLoop;
    private volatile boolean coinRefreshing = false;
    private View dotNew, dotOk, pulseHalo, scoutArgs, rowUpdate, rowCoolapk;
    private View linkPicker; // 下载渠道选择弹层（非空 = 正在显示）
    private LiquidSwitch swAutoRun, swMtop, swAds, swScout, swPolish, swToast;
    private LiquidSlider sliderHours;
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
        swPolish = findViewById(R.id.swPolish);
        swToast = findViewById(R.id.swToast);
        sliderHours = findViewById(R.id.sliderHours);
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
        int hours = cfg.optInt("autoRunIntervalHours", 4);
        tvStatusMeta.setText("自动间隔 " + hours + " 小时");

        // ===== 开关初始态（静默，不触发持久化） =====
        swAutoRun.setOnSilent(cfg.optBoolean("autoRun", true));
        swMtop.setOnSilent(cfg.optBoolean("mtopHook", true));
        swAds.setOnSilent(cfg.optBoolean("adSdkPresets", true));
        swScout.setOnSilent(cfg.optBoolean("scout", true));
        swPolish.setOnSilent(cfg.optBoolean("polish", true));
        swToast.setOnSilent(cfg.optBoolean("toastNotify", true));
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
        swPolish.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
            @Override public void onToggle(boolean on) { put("polish", on); }
        });
        swToast.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
            @Override public void onToggle(boolean on) { put("toastNotify", on); }
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
