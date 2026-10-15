package com.piggyhand.fish;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;

/**
 * 模块主界面：
 * - 状态卡 / 立即跑一轮 / 上次报告（广播+文件双通道）/ 功能开关（autoRun/mtopHook/adSdkPresets/scout）
 * - 开关式配置：每项带中文说明，保存后重启闲鱼生效；原始 JSON 已不再暴露给用户
 */
public class MainActivity extends Activity {

    private static final String ACTION_MTOP_CALL = "com.piggyhand.fish.MTOP_CALL";
    private static final String ACTION_RESULT = "com.piggyhand.fish.RESULT";
    private static final String FISH_PKG = "com.taobao.idlefish";

    private TextView tvReport;
    private TextView tvConfigTitle;
    private LinearLayout layoutToggles;
    private Button btnSave;
    private Button btnRun;
    private boolean reportShown = false;
    private boolean configShown = false;

    private Switch swAutoRun, swMtop, swAds, swScout;
    private EditText etPattern, etMax, etInterval;
    private LinearLayout layoutScoutArgs, layoutAutoRunArgs;

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
        getWindow().setStatusBarColor(0xFFE08600); // 状态栏与品牌渐变衔接
        setContentView(R.layout.activity_main);

        TextView tvVersion = findViewById(R.id.tvVersion);
        TextView tvStatus = findViewById(R.id.tvStatus);
        tvReport = findViewById(R.id.tvReport);
        tvConfigTitle = findViewById(R.id.tvConfigTitle);
        layoutToggles = findViewById(R.id.layoutToggles);
        btnSave = findViewById(R.id.btnSave);
        btnRun = findViewById(R.id.btnRun);
        Button btnReport = findViewById(R.id.btnReport);

        // 版本号
        try {
            tvVersion.setText("v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName + " · LSPosed 模块");
        } catch (Throwable ignored) {
        }

        // 模块启用状态：读自身 SharedPreferences（不得调 ConfigManager.load——
        // 它内部用 XSharedPreferences/XposedBridge，模块自身进程无 Xposed 环境会闪退）
        boolean on = true;
        try {
            SharedPreferences sp0 = getSharedPreferences("config", Context.MODE_PRIVATE);
            on = new org.json.JSONObject(sp0.getString("config_json", ConfigManager.DEFAULT_JSON))
                    .optBoolean("enabled", true);
        } catch (Throwable ignored) {
        }
        tvStatus.setText(on ? "模块运行中 · 闲鱼启动自动接管" : "模块未启用 · 请在 LSPosed 中启用本模块");
        tvStatus.setTextColor(getColor(on ? R.color.text_main : R.color.brand_deep));

        // 立即跑
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
                            btnRun.setText("立即跑一轮任务");
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

        // 报告：展开时发 __GET_RESULT__（部分 ROM 会拦跨 App 广播），同时轮询直读引擎报告文件双保险
        btnReport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reportShown = !reportShown;
                tvReport.setVisibility(reportShown ? View.VISIBLE : View.GONE);
                if (reportShown) {
                    tvReport.setText("加载中…");
                    Intent i = new Intent(ACTION_MTOP_CALL);
                    i.setPackage(FISH_PKG);
                    i.putExtra("api", "__GET_RESULT__");
                    sendBroadcast(i);
                    pollReport(0);
                }
            }
        });

        // 功能开关区展开
        tvConfigTitle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                configShown = !configShown;
                layoutToggles.setVisibility(configShown ? View.VISIBLE : View.GONE);
                btnSave.setVisibility(configShown ? View.VISIBLE : View.GONE);
                tvConfigTitle.setText(configShown ? "功能开关 ▾" : "功能开关 ▸");
                if (configShown) buildToggles();
            }
        });

        // 保存：开关状态组装进当前 JSON
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveToggles();
            }
        });

        // 接收引擎回传的报告
        IntentFilter f = new IntentFilter(ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, f, RECEIVER_EXPORTED);
        } else {
            registerReceiver(resultReceiver, f);
        }
    }

    /* ============================ 功能开关区 ============================ */

    private void buildToggles() {
        layoutToggles.removeAllViews();
        JSONObject cfg = currentConfig();

        swAutoRun = addToggle("自动跑任务", "打开闲鱼时自动执行一轮任务（间隔可调）", cfg.optBoolean("autoRun", true));
        swMtop = addToggle("网络请求侦察", "记录闲鱼全部网络请求日志（排障用，日常可关）", cfg.optBoolean("mtopHook", true));
        swAds = addToggle("广告 SDK 监控", "监控穿山甲 / 优量汇 / 快手广告 SDK 调用", cfg.optBoolean("adSdkPresets", true));
        swScout = addToggle("类扫描侦察", "按关键词批量扫描 App 内方法调用（深度排障，会略降性能）", cfg.optBoolean("scout", false));

        // 自动跑子参数：间隔小时数
        layoutAutoRunArgs = new LinearLayout(this);
        layoutAutoRunArgs.setOrientation(LinearLayout.VERTICAL);
        layoutAutoRunArgs.setPadding(dp(28), 0, 0, 0);
        layoutAutoRunArgs.setVisibility(swAutoRun.isChecked() ? View.VISIBLE : View.GONE);
        TextView lb0 = smallLabel("自动跑间隔（小时，默认 4：距上次跑完 4 小时后，再次打开闲鱼自动执行）");
        etInterval = new EditText(this);
        etInterval.setText(String.valueOf(cfg.optInt("autoRunIntervalHours", 4)));
        etInterval.setTextSize(11);
        styleBox(etInterval);
        layoutAutoRunArgs.addView(lb0);
        layoutAutoRunArgs.addView(etInterval);
        layoutToggles.addView(layoutAutoRunArgs);

        // scout 子参数：关键词 + 扫描上限
        layoutScoutArgs = new LinearLayout(this);
        layoutScoutArgs.setOrientation(LinearLayout.VERTICAL);
        layoutScoutArgs.setPadding(dp(28), 0, 0, 0);
        layoutScoutArgs.setVisibility(swScout.isChecked() ? View.VISIBLE : View.GONE);

        TextView lb1 = smallLabel("扫描关键词（| 分隔）");
        etPattern = new EditText(this);
        etPattern.setText(cfg.optString("scoutPattern", "reward|bonus|coin"));
        etPattern.setTextSize(11);
        styleBox(etPattern);
        TextView lb2 = smallLabel("最大扫描方法数");
        etMax = new EditText(this);
        etMax.setText(String.valueOf(cfg.optInt("maxScoutMethods", 400)));
        etMax.setTextSize(11);
        styleBox(etMax);

        layoutScoutArgs.addView(lb1);
        layoutScoutArgs.addView(etPattern);
        layoutScoutArgs.addView(lb2);
        layoutScoutArgs.addView(etMax);
        layoutToggles.addView(layoutScoutArgs);

        swAutoRun.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                layoutAutoRunArgs.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        });

        swScout.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                layoutScoutArgs.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        });

        // 说明
        TextView note = smallLabel("以上开关保存后需重启闲鱼生效 · 任务结果通过通知栏推送");
        note.setPadding(0, dp(10), 0, 0);
        layoutToggles.addView(note);
    }

    /** 一行开关：左侧标题+说明，右侧 Switch */
    private Switch addToggle(String title, String desc, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(getColor(R.color.text_main));
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(11);
        d.setTextColor(getColor(R.color.text_sub));
        d.setPadding(0, dp(2), dp(8), 0);
        left.addView(t);
        left.addView(d);

        Switch sw = new Switch(this);
        sw.setChecked(checked);

        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sw, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        layoutToggles.addView(row);
        return sw;
    }

    private TextView smallLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(getColor(R.color.text_sub));
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private void styleBox(EditText et) {
        et.setBackground(getDrawable(R.drawable.bg_code));
        et.setTextColor(getColor(R.color.code_text));
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

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

    private void saveToggles() {
        try {
            JSONObject o = currentConfig();
            o.put("enabled", o.optBoolean("enabled", true));
            o.put("autoRun", swAutoRun.isChecked());
            try {
                int h = Integer.parseInt(etInterval.getText().toString().trim());
                o.put("autoRunIntervalHours", Math.max(1, h));
            } catch (Throwable ignored) {
                o.put("autoRunIntervalHours", 4);
            }
            o.put("mtopHook", swMtop.isChecked());
            o.put("adSdkPresets", swAds.isChecked());
            o.put("scout", swScout.isChecked());
            o.put("scoutPattern", etPattern.getText().toString().trim().isEmpty()
                    ? "reward|bonus|coin" : etPattern.getText().toString().trim());
            try {
                o.put("maxScoutMethods", Integer.parseInt(etMax.getText().toString().trim()));
            } catch (Throwable ignored) {
                o.put("maxScoutMethods", 400);
            }
            persist(o.toString());
        } catch (Throwable t) {
            Toast.makeText(this, "保存失败: " + t, Toast.LENGTH_LONG).show();
        }
    }

    /* ============================ 报告轮询 ============================ */

    /** 轮询三通道读报告（su 首次会等 root 管理器授权，必须后台线程防 ANR） */
    private void pollReport(final int attempt) {
        if (attempt >= 3 || !reportShown) return;
        tvReport.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!reportShown) return;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String text = tryReadReport();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (!reportShown) return;
                                if (text != null) {
                                    tvReport.setText(text);
                                } else if (attempt >= 2) {
                                    tvReport.setText("无法读取报告。\n\n"
                                            + "· root 设备：首次请在 APatch / root 管理器弹窗中允许 RewardHook 的 su 授权，然后重新点「查看上次跑批报告」\n"
                                            + "· 或下拉通知栏查看「RewardHook」上次结果");
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

    private String loadConfig() {
        SharedPreferences sp = getSharedPreferences("config", Context.MODE_PRIVATE);
        return sp.getString("config_json", ConfigManager.DEFAULT_JSON);
    }

    private void persist(String json) {
        SharedPreferences sp = getSharedPreferences("config", Context.MODE_PRIVATE);
        sp.edit().putString("config_json", json).apply();

        // 放开文件权限，让 LSPosed 的 XSharedPreferences 能在目标进程读到
        try {
            File prefsDir = new File(getApplicationInfo().dataDir, "shared_prefs");
            prefsDir.setExecutable(true, false);
            prefsDir.setReadable(true, false);
            File xml = new File(prefsDir, "config.xml");
            xml.setReadable(true, false);
        } catch (Throwable ignored) {
        }

        Toast.makeText(this, "已保存，重启闲鱼生效", Toast.LENGTH_SHORT).show();
    }
}
