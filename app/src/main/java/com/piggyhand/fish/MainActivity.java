package com.piggyhand.fish;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build.VERSION;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.FrameLayout.LayoutParams;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity {
   private static final String ACTION_MTOP_CALL = "com.piggyhand.fish.MTOP_CALL";
   private static final String ACTION_RESULT = "com.piggyhand.fish.RESULT";
   private static final String FISH_PKG = "com.taobao.idlefish";
   private static final String LAUNCHER_ALIAS = "com.piggyhand.fish.LauncherAlias";
   private static final String RELEASES_URL = "https://github.com/Lotuszn/piggyhand-fish/releases/latest";
   private static final String UC_PAN_URL = "https://drive.uc.cn/s/df475a5e0ad94";
   private static final String QUARK_PAN_URL = "https://pan.quark.cn/s/b0a477c5b50f";
   private static final String COOLAPK_URL = "https://www.coolapk.com/u/2206962";
   private ViewGroup track;
   private View pagesWrap;
   private View pill;
   private LinearLayout tabHome;
   private LinearLayout tabLog;
   private LinearLayout tabAbout;
   private ImageView[] tabIcons;
   private TextView[] tabLabels;
   private int cur = 0;
   private boolean pillReady = false;
   private TextView tvVersion;
   private TextView tvStatus;
   private TextView tvStatusMeta;
   private TextView tvReport;
   private TextView tvGhSub;
   private TextView tvGhVal;
   private TextView tvLspState;
   private TextView tvRootState;
   private TextView tvCoinToday;
   private Handler coinUiHandler;
   private Runnable coinUiLoop;
   private volatile boolean coinRefreshing = false;
   private View dotNew;
   private View dotOk;
   private View pulseHalo;
   private View scoutArgs;
   private View rowUpdate;
   private View rowCoolapk;
   private View rowQQGroup;
   // v1.5.3 清除今日任务标记（通用卡内，确认后 socket 清标记 + root 重启闲鱼）
   private View rowClearMarks;
   private View drawerHead;
   private View drawerContent;
   private TextView tvDrawerArrow;
   private boolean drawerOpen = false;
   private View drawerHead2;
   private View drawerContent2;
   private TextView tvDrawerArrow2;
   private boolean drawerOpen2 = false;
   private View drawerHead3;
   private View drawerContent3;
   private TextView tvDrawerArrow3;
   private boolean drawerOpen3 = false;
   private View drawerHead4;
   private View drawerContent4;
   private TextView tvDrawerArrow4;
   private boolean drawerOpen4 = false;
   // v1.4.1 「神奇鱼塘」一级菜单抽屉
   private View drawerHeadPond;
   private View drawerContentPond;
   private TextView tvDrawerArrowPond;
   private boolean drawerOpenPond = false;
   private final AtomicBoolean drawerBusyPond = new AtomicBoolean(false);
   // v1.4.6 「赚闲鱼币提现」一级菜单抽屉
   private View drawerHeadCoin;
   private View drawerContentCoin;
   private TextView tvDrawerArrowCoin;
   private boolean drawerOpenCoin = false;
   private final AtomicBoolean drawerBusyCoin = new AtomicBoolean(false);
   private LiquidSwitch swCoinGame;
   private LiquidSwitch swCoinExchange;
   private LiquidSwitch swPondFeed;
   private final AtomicBoolean drawerBusy = new AtomicBoolean(false);
   private final AtomicBoolean drawerBusy2 = new AtomicBoolean(false);
   private final AtomicBoolean drawerBusy3 = new AtomicBoolean(false);
   private final AtomicBoolean drawerBusy4 = new AtomicBoolean(false);
   private LiquidSwitch swCashJoin;
   private LiquidSwitch swCashClaim;
   private TextView tvCashJoinState;
   private TextView tvCashClaimState;
   private View linkPicker;
   private LiquidSwitch swAutoRun;
   private LiquidSwitch swMtop;
   private LiquidSwitch swAds;
   private LiquidSwitch swScout;
   private LiquidSwitch swPolish;
   private LiquidSwitch swToast;
   private LiquidSwitch swFishMedia;
   private LiquidSwitch swPondTask;
   private LiquidSwitch swNoChatSwipe;
   private LiquidSwitch swHideIcon;
   private LiquidSwitch swNoSplash;
   private LiquidSwitch swOlivertask;
   private LiquidSwitch swGameTask;
   private LiquidSwitch swCycleChallenge;
   private EditText etBagLuck;
   private EditText etBag8;
   private EditText etBag12;
   private EditText etBagRocket;
   private EditText etBagDouble;
   private EditText etBagGgk;
   private EditText etBagDzp;
   private LiquidSwitch swBagLuck;
   private LiquidSwitch swBag8;
   private LiquidSwitch swBag12;
   private LiquidSwitch swBagRocket;
   private LiquidSwitch swBagDouble;
   private LiquidSwitch swBagGgk;
   private LiquidSwitch swBagDzp;
   private EditText etFeedCols;
   private LiquidSwitch swFeedCols;
   private EditText etUiScale;
   private LiquidSwitch swUiScale;
   private EditText etPattern;
   private EditText etMax;
   private Button btnRun;
   private Button btnRefresh;
   private Button btnExportLog;
   private Button btnSaveBag;
   private boolean bagCommitQuiet = false;
   private boolean dirty = false;
   private boolean logLoaded = false;
   private boolean updateCheckFailed = false;
   private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
      public void onReceive(Context var1, Intent var2) {
         String var3 = var2.getStringExtra("report");
         if (MainActivity.this.tvReport != null) {
            MainActivity.this.tvReport.setText(var3 != null && !var3.trim().isEmpty() ? var3 : "暂无钓鱼记录（先跑一轮）");
         }
      }
   };
   private static final Object CFG_SYNC_LOCK = new Object();
   private static final String DIAG_FOOT = "\n\n解决步骤：\n1. root 授权：打开 Magisk / APatch → 超级用户 → 允许「咸鱼小猪手」，回本页点「刷新报告」\n2. MIUI 联网放行：安全中心 → 应用管理 → 咸鱼小猪手 → 联网控制 → 允许 WLAN 与数据\n3. 保持闲鱼在后台运行（报告服务随闲鱼进程启动，端口按用户自动分配）\n4. 最近一次钓鱼结果也会推送在通知栏";

   protected void onCreate(Bundle var1) {
      super.onCreate(var1);
      this.applyEdgeToEdge();
      this.setContentView(R.layout.activity_main);
      this.track = (ViewGroup)this.findViewById(R.id.track);
      this.pagesWrap = this.findViewById(R.id.pagesWrap);
      this.pill = this.findViewById(R.id.pill);
      this.tabHome = (LinearLayout)this.findViewById(R.id.tabHome);
      this.tabLog = (LinearLayout)this.findViewById(R.id.tabLog);
      this.tabAbout = (LinearLayout)this.findViewById(R.id.tabAbout);
      this.tabIcons = new ImageView[]{
         (ImageView)this.findViewById(R.id.ivTabHome), (ImageView)this.findViewById(R.id.ivTabLog), (ImageView)this.findViewById(R.id.ivTabAbout)
      };
      this.tabLabels = new TextView[]{
         (TextView)this.findViewById(R.id.tvTabHome), (TextView)this.findViewById(R.id.tvTabLog), (TextView)this.findViewById(R.id.tvTabAbout)
      };
      this.tvVersion = (TextView)this.findViewById(R.id.tvVersion);
      this.tvStatus = (TextView)this.findViewById(R.id.tvStatus);
      this.tvStatusMeta = (TextView)this.findViewById(R.id.tvStatusMeta);
      this.tvReport = (TextView)this.findViewById(R.id.tvReport);
      this.tvGhSub = (TextView)this.findViewById(R.id.tvGhSub);
      this.tvGhVal = (TextView)this.findViewById(R.id.tvGhVal);
      this.tvLspState = (TextView)this.findViewById(R.id.tvLspState);
      this.tvRootState = (TextView)this.findViewById(R.id.tvRootState);
      this.tvCoinToday = (TextView)this.findViewById(R.id.tvCoinToday);
      this.dotNew = this.findViewById(R.id.dotNew);
      this.dotOk = this.findViewById(R.id.dotOk);
      this.pulseHalo = this.findViewById(R.id.pulseHalo);
      this.scoutArgs = this.findViewById(R.id.scoutArgs);
      this.rowUpdate = this.findViewById(R.id.rowUpdate);
      this.rowCoolapk = this.findViewById(R.id.rowCoolapk);
      this.rowQQGroup = this.findViewById(R.id.rowQQGroup);
      this.rowClearMarks = this.findViewById(R.id.rowClearMarks);
      this.swAutoRun = (LiquidSwitch)this.findViewById(R.id.swAutoRun);
      this.swMtop = (LiquidSwitch)this.findViewById(R.id.swMtop);
      this.swAds = (LiquidSwitch)this.findViewById(R.id.swAds);
      this.swNoSplash = (LiquidSwitch)this.findViewById(R.id.swNoSplash);
      this.swScout = (LiquidSwitch)this.findViewById(R.id.swScout);
      this.swPolish = (LiquidSwitch)this.findViewById(R.id.swPolish);
      this.swToast = (LiquidSwitch)this.findViewById(R.id.swToast);
      this.drawerHead = this.findViewById(R.id.drawerHead);
      this.drawerContent = this.findViewById(R.id.drawerContent);
      this.tvDrawerArrow = (TextView)this.findViewById(R.id.tvDrawerArrow);
      this.drawerHead2 = this.findViewById(R.id.drawerHead2);
      this.drawerContent2 = this.findViewById(R.id.drawerContent2);
      this.tvDrawerArrow2 = (TextView)this.findViewById(R.id.tvDrawerArrow2);
      this.drawerHead3 = this.findViewById(R.id.drawerHead3);
      this.drawerContent3 = this.findViewById(R.id.drawerContent3);
      this.tvDrawerArrow3 = (TextView)this.findViewById(R.id.tvDrawerArrow3);
      this.drawerHead4 = this.findViewById(R.id.drawerHead4);
      this.drawerContent4 = this.findViewById(R.id.drawerContent4);
      this.tvDrawerArrow4 = (TextView)this.findViewById(R.id.tvDrawerArrow4);
      this.swCashJoin = (LiquidSwitch)this.findViewById(R.id.swCashJoin);
      this.swCashClaim = (LiquidSwitch)this.findViewById(R.id.swCashClaim);
      this.tvCashJoinState = (TextView)this.findViewById(R.id.tvCashJoinState);
      this.tvCashClaimState = (TextView)this.findViewById(R.id.tvCashClaimState);
      this.swFishMedia = (LiquidSwitch)this.findViewById(R.id.swFishMedia);
      this.swPondTask = (LiquidSwitch)this.findViewById(R.id.swPondTask);
      this.swOlivertask = (LiquidSwitch)this.findViewById(R.id.swOlivertask);
      this.swGameTask = (LiquidSwitch)this.findViewById(R.id.swGameTask);
      this.swCycleChallenge = (LiquidSwitch)this.findViewById(R.id.swCycleChallenge);
      this.swNoChatSwipe = (LiquidSwitch)this.findViewById(R.id.swNoChatSwipe);
      this.swHideIcon = (LiquidSwitch)this.findViewById(R.id.swHideIcon);
      this.etBagLuck = (EditText)this.findViewById(R.id.etBagLuck);
      this.swBagLuck = (LiquidSwitch)this.findViewById(R.id.swBagLuck);
      this.etBag8 = (EditText)this.findViewById(R.id.etBag8);
      this.swBag8 = (LiquidSwitch)this.findViewById(R.id.swBag8);
      this.etBag12 = (EditText)this.findViewById(R.id.etBag12);
      this.swBag12 = (LiquidSwitch)this.findViewById(R.id.swBag12);
      this.etBagRocket = (EditText)this.findViewById(R.id.etBagRocket);
      this.swBagRocket = (LiquidSwitch)this.findViewById(R.id.swBagRocket);
      this.etBagDouble = (EditText)this.findViewById(R.id.etBagDouble);
      this.swBagDouble = (LiquidSwitch)this.findViewById(R.id.swBagDouble);
      this.etBagGgk = (EditText)this.findViewById(R.id.etBagGgk);
      this.swBagGgk = (LiquidSwitch)this.findViewById(R.id.swBagGgk);
      this.etBagDzp = (EditText)this.findViewById(R.id.etBagDzp);
      this.swBagDzp = (LiquidSwitch)this.findViewById(R.id.swBagDzp);
      this.etFeedCols = (EditText)this.findViewById(R.id.etFeedCols);
      this.swFeedCols = (LiquidSwitch)this.findViewById(R.id.swFeedCols);
      this.etUiScale = (EditText)this.findViewById(R.id.etUiScale);
      this.swUiScale = (LiquidSwitch)this.findViewById(R.id.swUiScale);
      // v1.4.1 「神奇鱼塘」一级菜单：自动赚饲料沿用 rowPondTask/swPondTask（布局已迁入新卡片），
      // 自动投喂新增 rowPondFeed/swPondFeed；tvPondWarn 警示语随旧布局一并移除
      this.drawerHeadPond = this.findViewById(R.id.drawerHeadPond);
      this.drawerContentPond = this.findViewById(R.id.drawerContentPond);
      this.tvDrawerArrowPond = (TextView)this.findViewById(R.id.tvDrawerArrowPond);
      this.swPondFeed = (LiquidSwitch)this.findViewById(R.id.swPondFeed);
      // v1.4.6 「赚闲鱼币提现」一级菜单
      this.drawerHeadCoin = this.findViewById(R.id.drawerHeadCoin);
      this.drawerContentCoin = this.findViewById(R.id.drawerContentCoin);
      this.tvDrawerArrowCoin = (TextView)this.findViewById(R.id.tvDrawerArrowCoin);
      this.swCoinGame = (LiquidSwitch)this.findViewById(R.id.swCoinGame);
      this.swCoinExchange = (LiquidSwitch)this.findViewById(R.id.swCoinExchange);

      this.etPattern = (EditText)this.findViewById(R.id.etPattern);
      this.etMax = (EditText)this.findViewById(R.id.etMax);
      this.btnRun = (Button)this.findViewById(R.id.btnRun);
      this.btnRefresh = (Button)this.findViewById(R.id.btnRefresh);
      this.btnExportLog = (Button)this.findViewById(R.id.btnExportLog);
      this.btnSaveBag = (Button)this.findViewById(R.id.btnSaveBag);
      String var6 = UpdateChecker.currentVersion(this);
      this.tvVersion.setText("v" + var6 + " · LSPosed");
      this.tvGhVal.setText("v" + var6 + " ›");
      JSONObject var7 = this.currentConfig();
      boolean var8 = var7.optBoolean("enabled", true);
      this.tvStatus.setText(var8 ? "引擎运行中" : "模块未启用 · 请在 LSPosed 中启用");
      this.tvStatus.setTextColor(var8 ? -15263716 : -5021952);
      int var9 = var7.optInt("autoRunIntervalMin", -1);
      if (var9 < 0) {
         var9 = var7.optInt("autoRunIntervalHours", 4) * 60;
      }

      var9 = Math.min(1440, Math.max(0, var9));
      this.tvStatusMeta.setText("自动间隔 " + var9 + " 分钟 ▾");
      this.swAutoRun.setOnSilent(var7.optBoolean("autoRun", true));
      this.swMtop.setOnSilent(var7.optBoolean("mtopHook", true));
      this.swAds.setOnSilent(var7.optBoolean("adSdkPresets", true));
      this.swNoSplash.setOnSilent(var7.optBoolean("noSplashAd", true));
      this.swScout.setOnSilent(true);
      this.swPolish.setOnSilent(var7.optBoolean("polish", true));
      this.swToast.setOnSilent(var7.optBoolean("toastNotify", true));
      this.scoutArgs.setVisibility(8);
      this.etMax.setText("400");
      this.swAutoRun.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("autoRun", var1);
         }
      });
      this.swMtop.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("mtopHook", var1);
         }
      });
      this.swAds.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("adSdkPresets", var1);
         }
      });
      this.swNoSplash.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("noSplashAd", var1);
         }
      });
      this.swScout.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("scout", true);
            MainActivity.this.scoutArgs.setVisibility(8);
         }
      });
      this.swPolish.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("polish", var1);
         }
      });
      this.swToast.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("toastNotify", var1);
         }
      });
      this.swFishMedia.setOnSilent(var7.optBoolean("fishMedia", true));
      this.swFishMedia.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("fishMedia", var1);
         }
      });
      this.swPondTask.setOnSilent(var7.optBoolean("pond", true));
      this.swPondTask.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("pond", var1);
            Toast.makeText(MainActivity.this, var1
                    ? "\ud83c\udf3e 自动赚饲料已开启：每天打开闲鱼自动领取"
                    : "\ud83c\udf3e 自动赚饲料已关闭", 0).show();
         }
      });
      this.swOlivertask.setOnSilent(var7.optBoolean("oliverTask", true));
      this.swOlivertask.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("oliverTask", var1);
         }
      });
      this.swGameTask.setOnSilent(var7.optBoolean("gameTask", true));
      this.swGameTask.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("gameTask", var1);
         }
      });
      this.swCycleChallenge.setOnSilent(var7.optBoolean("cycleChallenge", true));
      this.swCycleChallenge.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("cycleChallenge", var1);
            Toast.makeText(MainActivity.this, var1 ? "\ud83c\udfaf 圈数挑战已开启：到档自动领取" : "\ud83c\udfaf 圈数挑战已关闭", 0).show();
         }
      });
      this.swCashJoin.setOnSilent(var7.optBoolean("cashJoin", true));
      this.swCashJoin.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("cashJoin", var1);
            Toast.makeText(MainActivity.this, var1 ? "\ud83e\ude99 自动投币已开启：打开闲鱼即投满，投满当日不再执行" : "\ud83e\ude99 自动投币已关闭", 0).show();
         }
      });
      this.swCashClaim.setOnSilent(var7.optBoolean("cashClaim", true));
      this.swCashClaim.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("cashClaim", var1);
            Toast.makeText(MainActivity.this, var1 ? "\ud83e\udde7 自动开红包已开启：开奖当天自动领取" : "\ud83e\udde7 自动开红包已关闭", 0).show();
         }
      });
      this.swCoinGame.setOnSilent(var7.optBoolean("coinGame", true));
      this.swCoinGame.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("coinGame", var1);
            Toast.makeText(MainActivity.this, var1
                    ? "\ud83e\ude99 赚游戏币任务已开启：每天打开闲鱼自动赚币"
                    : "\ud83e\ude99 赚游戏币任务已关闭", 0).show();
         }
      });
      // v1.4.9 自动兑换：标识 2 后每日一次（≥10:00 且攒满即兑），手动点行不受限
      this.swCoinExchange.setOnSilent(var7.optBoolean("coinExchange", true));
      this.swCoinExchange.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("coinExchange", var1);
            Toast.makeText(MainActivity.this, var1
                    ? "\ud83e\udde7 自动兑换已开启：赚币标识2后每日一次，≥10:00 且攒满 100000 自动兑支付宝红包"
                    : "\ud83e\udde7 自动兑换已关闭", 0).show();
         }
      });
      this.swNoChatSwipe.setOnSilent(var7.optBoolean("noChatSwipe", false));
      this.swNoChatSwipe.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("noChatSwipe", var1);
         }
      });
      boolean var10 = var7.optBoolean("hideIcon", false);
      this.swHideIcon.setOnSilent(var10);
      this.swHideIcon.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put("hideIcon", var1);
            MainActivity.this.applyLauncherIcon(var1);
            Toast.makeText(MainActivity.this, var1 ? "桌面图标已隐藏" : "桌面图标已恢复", 0).show();
         }
      });
      this.applyLauncherIcon(var10);
      this.installBag(var7, this.etBagLuck, this.swBagLuck, "bagLuckOn", "bagLuckNum", "好运骰");
      this.installBag(var7, this.etBag8, this.swBag8, "bag8On", "bag8Num", "八面骰");
      this.installBag(var7, this.etBag12, this.swBag12, "bag12On", "bag12Num", "十二面骰");
      this.installBag(var7, this.etBagRocket, this.swBagRocket, "bagRocketOn", "bagRocketNum", "火箭骰子");
      this.installBag(var7, this.etBagDouble, this.swBagDouble, "bagDoubleOn", "bagDoubleNum", "双倍骰子");
      this.installBag(var7, this.etBagGgk, this.swBagGgk, "bagGgkOn", "bagGgkNum", "刮刮卡");
      this.installBag(var7, this.etBagDzp, this.swBagDzp, "bagDzpOn", "bagDzpNum", "大转盘");
      this.btnSaveBag.setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.saveBagConfig(true);
         }
      });
      this.installFeedCols(var7);
      this.installUiScale(var7);
      this.installPondFeed(var7);
      this.findViewById(R.id.rowPondTask).setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            Toast.makeText(MainActivity.this, "\ud83d\udc1f 已触发鱼塘任务引擎…", 0).show();
            new Thread(new Runnable() {
               @Override
               public void run() {
                  String var1 = MainActivity.socketCmd("POND");
                  if (var1 != null && var1.length() != 0) {
                     String var2 = null;

                     for (int var3 = 0; var3 < 20; var3++) {
                        try {
                           Thread.sleep(2000L);
                        } catch (InterruptedException var5) {
                        }

                        String var4 = MainActivity.socketCmd("POND_REPORT");
                        if (var4 != null && var4.contains("鱼塘任务报告")) {
                           var2 = var4;
                           break;
                        }
                     }

                     final String var2f = var2;
                     MainActivity.this.runUi(new Runnable() {
                        @Override
                        public void run() {
                           MainActivity.this.showPondReport(var2f);
                        }
                     });
                  } else {
                     MainActivity.this.runUi(new Runnable() {
                        @Override
                        public void run() {
                           Toast.makeText(MainActivity.this, "触发失败：闲鱼未运行或 socket 不通", 1).show();
                        }
                     });
                  }
               }
            }, "rh-pond-ui").start();
         }
      });
      // v1.4.1 「神奇鱼塘 → 自动投喂」行点击：走 PONDFEED → PONDFEED_REPORT 轮询（投喂自动进行到饲料耗尽）
      this.findViewById(R.id.rowPondFeed).setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.clearInputFocusAndKeyboard();
            MainActivity.this.toastUi("\ud83c\udf5a 正在自动投喂至饲料耗尽…");
            new Thread(new Runnable() {
               @Override
               public void run() {
                  String var1 = MainActivity.socketCmd("PONDFEED");
                  if (var1 != null && var1.length() != 0) {
                     String var2 = null;

                     for (int var3 = 0; var3 < 150; var3++) {
                        try {
                           Thread.sleep(2000L);
                        } catch (InterruptedException var5) {
                        }

                        String var4 = MainActivity.socketCmd("PONDFEED_REPORT");
                        if (var4 != null && var4.contains("投喂报告")) {
                           var2 = var4;
                           break;
                        }
                     }

                     final String var2f = var2;
                     MainActivity.this.runUi(new Runnable() {
                        @Override
                        public void run() {
                           MainActivity.this.showPondFeedReport(var2f);
                        }
                     });
                  } else {
                     MainActivity.this.runUi(new Runnable() {
                        @Override
                        public void run() {
                           Toast.makeText(MainActivity.this, "触发失败：闲鱼未运行或 socket 不通", 1).show();
                        }
                     });
                  }
               }
            }, "rh-pondfeed-ui").start();
         }
      });
      // v1.4.6 「赚闲鱼币提现 → 赚游戏币任务」行点击：走 COIN → COIN_REPORT 轮询
      // （浏览类任务会自动拉起任务页，浏览 ≥20s 返回后引擎 100s 复扫自动领奖）
      this.findViewById(R.id.rowCoinGame).setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            Toast.makeText(MainActivity.this, "\ud83e\ude99 已触发赚游戏币任务引擎…", 0).show();
            new Thread(new Runnable() {
               @Override
               public void run() {
                  String var1 = MainActivity.socketCmd("COIN");
                  if (var1 != null && var1.length() != 0) {
                     String var2 = null;

                     for (int var3 = 0; var3 < 60; var3++) {
                        try {
                           Thread.sleep(2000L);
                        } catch (InterruptedException var5) {
                        }

                        String var4 = MainActivity.socketCmd("COIN_REPORT");
                        if (var4 != null && var4.contains("游戏币任务报告")) {
                           var2 = var4;
                           break;
                        }
                     }

                     final String var2f = var2;
                     MainActivity.this.runUi(new Runnable() {
                        @Override
                        public void run() {
                           MainActivity.this.showCoinReport(var2f);
                        }
                     });
                  } else {
                     MainActivity.this.runUi(new Runnable() {
                        @Override
                        public void run() {
                           Toast.makeText(MainActivity.this, "触发失败：闲鱼未运行或 socket 不通", 1).show();
                        }
                     });
                  }
               }
            }, "rh-coin-ui").start();
         }
      });
      // v1.4.9 「自动兑换」行点击：手动执行一次检查+兑换（manual，不受每日 1 次/时间窗限制），
      // 自动流程由 RhCoinTask 标识 2 挂点触发
      this.findViewById(R.id.rowCoinExchange).setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            Toast.makeText(MainActivity.this, "\ud83e\udde7 正在执行兑换检查（查余额→攒满判定→兑换）…", 0).show();
            new Thread(new Runnable() {
               @Override
               public void run() {
                  String var1 = MainActivity.socketCmd("COINEX");
                  if (var1 != null && var1.length() != 0) {
                     String var2 = null;

                     for (int var3 = 0; var3 < 45; var3++) {
                        try {
                           Thread.sleep(2000L);
                        } catch (InterruptedException var5) {
                        }

                        String var4 = MainActivity.socketCmd("COINEX_REPORT");
                        if (var4 != null && var4.contains("自动兑换报告")) {
                           var2 = var4;
                           break;
                        }
                     }

                     final String var2f = var2;
                     MainActivity.this.runUi(new Runnable() {
                        @Override
                        public void run() {
                           MainActivity.this.showCoinExchReport(var2f);
                        }
                     });
                  } else {
                     MainActivity.this.runUi(new Runnable() {
                        @Override
                        public void run() {
                           Toast.makeText(MainActivity.this, "触发失败：闲鱼未运行或 socket 不通", 1).show();
                        }
                     });
                  }
               }
            }, "rh-coinex-ui").start();
         }
      });
      this.findViewById(R.id.rowOlivertask).setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            final long var2 = System.currentTimeMillis() - 3000L;
            MainActivity.this.toastUi("\ud83c\udfc5 正在检查会员任务…");
            new Thread(new Runnable() {
               @Override
               public void run() {
                  String var1 = MainActivity.socketCmd("OLIVER_STATE");
                  Log.i("RHO", "pre-check: " + var1);
                  if (var1 != null && var1.contains("DONE")) {
                     MainActivity.this.toastUi("\ud83c\udfc5 今日会员任务已完成，跳过（明天 0 点重置）");
                  } else {
                     String var2x = MainActivity.socketCmd("OLIVER");
                     boolean var3 = var2x != null && var2x.length() > 0;
                     Log.i("RHO", "click: acked=" + var3);
                     String var4 = var3 ? MainActivity.pollOliverReport(var2, 6) : MainActivity.pollOliverReport(var2, 1);
                     Log.i("RHO", "phase1 rep=" + (var4 == null ? "null" : "ok"));
                     if (var4 == null) {
                        MainActivity.this.toastUi("闲鱼不在前台，正在打开闲鱼执行…");
                        Log.i("RHO", "phase1 dead -> open fish front");
                        MainActivity.this.openFishFront();

                        for (int var5 = 0; var5 < 5; var5++) {
                           MainActivity.sleepQuiet(2000L);
                           if (MainActivity.socketCmd("OLIVER_REPORT") != null) {
                              break;
                           }
                        }

                        MainActivity.socketCmd("OLIVER");
                        var4 = MainActivity.pollOliverReport(var2, 15);
                        Log.i("RHO", "phase2 rep=" + (var4 == null ? "null" : "ok"));
                     }

                     MainActivity.this.toastUi(MainActivity.summarizeOliver(var4));
                  }
               }
            }, "rh-oliver-ui").start();
         }
      });
      this.findViewById(R.id.rowGameTask).setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            final long var2 = System.currentTimeMillis() - 3000L;
            MainActivity.this.toastUi("\ud83c\udfae 正在跑游戏任务…");
            new Thread(new Runnable() {
               @Override
               public void run() {
                  String var1 = MainActivity.socketCmd("GAME");
                  boolean var2x = var1 != null && var1.contains("OK");
                  if (!var2x) {
                     MainActivity.this.toastUi("闲鱼未唤醒，正在打开闲鱼执行…");
                     MainActivity.this.openFishFront();

                     for (int var3 = 0; var3 < 8 && !var2x; var3++) {
                        MainActivity.sleepQuiet(1500L);
                        String var4 = MainActivity.socketCmd("GAME");
                        var2x = var4 != null && var4.contains("OK");
                     }
                  }

                  String var5 = var2x ? MainActivity.pollGameReport(var2, 20) : null;
                  if (var5 == null && var2x) {
                     MainActivity.this.toastUi("闲鱼不在前台，正在打开闲鱼执行…");
                     MainActivity.this.openFishFront();

                     for (int var6 = 0; var6 < 5; var6++) {
                        MainActivity.sleepQuiet(2000L);
                        if (MainActivity.socketCmd("GAME_REPORT") != null) {
                           break;
                        }
                     }

                     MainActivity.socketCmd("GAME");
                     var5 = MainActivity.pollGameReport(var2, 15);
                  }

                  if (var5 == null && !var2x) {
                     MainActivity.this.toastUi("\ud83c\udfae 未连通：先打开闲鱼（引擎随进程加载）再点");
                  } else {
                     MainActivity.this.toastUi(MainActivity.summarizeGame(var5));
                  }
               }
            }, "rh-game-ui").start();
         }
      });
      this.findViewById(R.id.rowCycleChallenge).setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            final long var2 = System.currentTimeMillis() - 3000L;
            MainActivity.this.toastUi("\ud83c\udfaf 正在查领圈数挑战…");
            new Thread(new Runnable() {
               @Override
               public void run() {
                  String var1 = MainActivity.socketCmd("CYCLE");
                  boolean var2x = var1 != null && var1.contains("OK");
                  if (!var2x) {
                     MainActivity.this.toastUi("闲鱼未唤醒，正在打开闲鱼执行…");
                     MainActivity.this.openFishFront();

                     for (int var3 = 0; var3 < 8 && !var2x; var3++) {
                        MainActivity.sleepQuiet(1500L);
                        String var4 = MainActivity.socketCmd("CYCLE");
                        var2x = var4 != null && var4.contains("OK");
                     }
                  }

                  // 圈数链路只有 info+领取两三发请求，轮询上限给短些
                  String var5 = var2x ? MainActivity.pollCycleReport(var2, 12) : null;
                  if (var5 == null && var2x) {
                     MainActivity.this.toastUi("闲鱼不在前台，正在打开闲鱼执行…");
                     MainActivity.this.openFishFront();

                     for (int var6 = 0; var6 < 5; var6++) {
                        MainActivity.sleepQuiet(2000L);
                        if (MainActivity.socketCmd("CYCLE_REPORT") != null) {
                           break;
                        }
                     }

                     MainActivity.socketCmd("CYCLE");
                     var5 = MainActivity.pollCycleReport(var2, 10);
                  }

                  if (var5 == null && !var2x) {
                     MainActivity.this.toastUi("\ud83c\udfaf 未连通：先打开闲鱼（引擎随进程加载）再点");
                  } else {
                     MainActivity.this.toastUi(MainActivity.summarizeCycle(var5));
                  }
               }
            }, "rh-cycle-ui").start();
         }
      });
      this.drawerHead
         .setOnClickListener(
            new OnClickListener() {
               public void onClick(View var1) {
                  if (!MainActivity.this.drawerBusy.get()) {
                     MainActivity.this.drawerOpen = !MainActivity.this.drawerOpen;
                     MainActivity.this.tvDrawerArrow
                        .animate()
                        .rotation(MainActivity.this.drawerOpen ? 0.0F : -90.0F)
                        .setDuration(MainActivity.this.drawerOpen ? 260L : 200L)
                        .start();
                     MainActivity.this.animateDrawer((ViewGroup)MainActivity.this.drawerContent, MainActivity.this.drawerOpen, MainActivity.this.drawerBusy);
                  }
               }
            }
         );
      this.drawerHead2
         .setOnClickListener(
            new OnClickListener() {
               public void onClick(View var1) {
                  if (!MainActivity.this.drawerBusy2.get()) {
                     MainActivity.this.drawerOpen2 = !MainActivity.this.drawerOpen2;
                     MainActivity.this.tvDrawerArrow2
                        .animate()
                        .rotation(MainActivity.this.drawerOpen2 ? 0.0F : -90.0F)
                        .setDuration(MainActivity.this.drawerOpen2 ? 260L : 200L)
                        .start();
                     MainActivity.this.animateDrawer((ViewGroup)MainActivity.this.drawerContent2, MainActivity.this.drawerOpen2, MainActivity.this.drawerBusy2);
                  }
               }
            }
         );
      this.drawerHead3
         .setOnClickListener(
            new OnClickListener() {
               public void onClick(View var1) {
                  if (!MainActivity.this.drawerBusy3.get()) {
                     MainActivity.this.drawerOpen3 = !MainActivity.this.drawerOpen3;
                     MainActivity.this.tvDrawerArrow3
                        .animate()
                        .rotation(MainActivity.this.drawerOpen3 ? 0.0F : -90.0F)
                        .setDuration(MainActivity.this.drawerOpen3 ? 260L : 200L)
                        .start();
                     MainActivity.this.animateDrawer((ViewGroup)MainActivity.this.drawerContent3, MainActivity.this.drawerOpen3, MainActivity.this.drawerBusy3);
                  }
               }
            }
         );
      this.drawerHead4
         .setOnClickListener(
            new OnClickListener() {
               public void onClick(View var1) {
                  if (!MainActivity.this.drawerBusy4.get()) {
                     MainActivity.this.drawerOpen4 = !MainActivity.this.drawerOpen4;
                     MainActivity.this.tvDrawerArrow4
                        .animate()
                        .rotation(MainActivity.this.drawerOpen4 ? 0.0F : -90.0F)
                        .setDuration(MainActivity.this.drawerOpen4 ? 260L : 200L)
                        .start();
                     MainActivity.this.animateDrawer((ViewGroup)MainActivity.this.drawerContent4, MainActivity.this.drawerOpen4, MainActivity.this.drawerBusy4);
                  }
               }
            }
         );
      // v1.4.1 「神奇鱼塘」抽屉展开/收起（与组①~④同一套动画）
      this.drawerHeadPond
         .setOnClickListener(
            new OnClickListener() {
               public void onClick(View var1) {
                  if (!MainActivity.this.drawerBusyPond.get()) {
                     MainActivity.this.drawerOpenPond = !MainActivity.this.drawerOpenPond;
                     MainActivity.this.tvDrawerArrowPond
                        .animate()
                        .rotation(MainActivity.this.drawerOpenPond ? 0.0F : -90.0F)
                        .setDuration(MainActivity.this.drawerOpenPond ? 260L : 200L)
                        .start();
                     MainActivity.this.animateDrawer((ViewGroup)MainActivity.this.drawerContentPond, MainActivity.this.drawerOpenPond, MainActivity.this.drawerBusyPond);
                  }
               }
            }
         );
      // v1.4.6 「赚闲鱼币提现」抽屉展开/收起（与鱼塘同一套动画）
      this.drawerHeadCoin
         .setOnClickListener(
            new OnClickListener() {
               public void onClick(View var1) {
                  if (!MainActivity.this.drawerBusyCoin.get()) {
                     MainActivity.this.drawerOpenCoin = !MainActivity.this.drawerOpenCoin;
                     MainActivity.this.tvDrawerArrowCoin
                        .animate()
                        .rotation(MainActivity.this.drawerOpenCoin ? 0.0F : -90.0F)
                        .setDuration(MainActivity.this.drawerOpenCoin ? 260L : 200L)
                        .start();
                     MainActivity.this.animateDrawer((ViewGroup)MainActivity.this.drawerContentCoin, MainActivity.this.drawerOpenCoin, MainActivity.this.drawerBusyCoin);
                  }
               }
            }
         );
      this.findViewById(R.id.rowCashJoin).setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            final long var2 = System.currentTimeMillis() - 3000L;
            MainActivity.this.toastUi("\ud83e\ude99 正在投币…");
            new Thread(new Runnable() {
               @Override
               public void run() {
                  boolean var1 = MainActivity.this.kickEngine("JOIN");
                  if (!var1) {
                     MainActivity.this.toastUi("闲鱼未唤醒，正在打开闲鱼执行…");
                     MainActivity.this.openFishFront();

                     for (int var2x = 0; var2x < 8 && !var1; var2x++) {
                        MainActivity.sleepQuiet(1500L);
                        var1 = MainActivity.this.kickEngine("JOIN");
                     }
                  }

                  String var4 = var1 ? MainActivity.pollCashReport("JOIN_REPORT", "现金瓜分自动投币", var2, 20) : null;
                  if (var4 == null && var1) {
                     MainActivity.this.toastUi("闲鱼不在前台，正在打开闲鱼执行…");
                     MainActivity.this.openFishFront();

                     for (int var3 = 0; var3 < 5; var3++) {
                        MainActivity.sleepQuiet(2000L);
                        if (MainActivity.socketCmd("JOIN_REPORT") != null) {
                           break;
                        }
                     }

                     MainActivity.socketCmd("JOIN");
                     var4 = MainActivity.pollCashReport("JOIN_REPORT", "现金瓜分自动投币", var2, 15);
                  }

                  if (var4 == null && !var1) {
                     MainActivity.this.toastUi("\ud83e\ude99 未连通：先打开闲鱼（引擎随进程加载）再点");
                  } else {
                     MainActivity.this.toastUi(MainActivity.summarizeJoin(var4));
                     MainActivity.this.refreshCashState();
                  }
               }
            }, "rh-join-ui").start();
         }
      });
      this.findViewById(R.id.rowCashClaim).setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            final long var2 = System.currentTimeMillis() - 3000L;
            MainActivity.this.toastUi("\ud83e\udde7 正在开红包…");
            new Thread(new Runnable() {
               @Override
               public void run() {
                  boolean var1 = MainActivity.this.kickEngine("CLAIM");
                  if (!var1) {
                     MainActivity.this.toastUi("闲鱼未唤醒，正在打开闲鱼执行…");
                     MainActivity.this.openFishFront();

                     for (int var2x = 0; var2x < 8 && !var1; var2x++) {
                        MainActivity.sleepQuiet(1500L);
                        var1 = MainActivity.this.kickEngine("CLAIM");
                     }
                  }

                  String var4 = var1 ? MainActivity.pollCashReport("CASH_REPORT", "现金夺宝开红包", var2, 20) : null;
                  if (var4 == null && var1) {
                     MainActivity.this.toastUi("闲鱼不在前台，正在打开闲鱼执行…");
                     MainActivity.this.openFishFront();

                     for (int var3 = 0; var3 < 5; var3++) {
                        MainActivity.sleepQuiet(2000L);
                        if (MainActivity.socketCmd("CASH_REPORT") != null) {
                           break;
                        }
                     }

                     MainActivity.socketCmd("CLAIM");
                     var4 = MainActivity.pollCashReport("CASH_REPORT", "现金夺宝开红包", var2, 15);
                  }

                  if (var4 == null && !var1) {
                     MainActivity.this.toastUi("\ud83e\udde7 未连通：先打开闲鱼（引擎随进程加载）再点");
                  } else {
                     MainActivity.this.toastUi(MainActivity.summarizeClaim(var4));
                     MainActivity.this.refreshCashState();
                  }
               }
            }, "rh-cash-ui").start();
         }
      });
      this.tvStatusMeta.setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.askInterval();
         }
      });
      this.btnRun.setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.btnRun.setText("触发中…");
            new Thread(new Runnable() {
               @Override
               public void run() {
                  final String var1 = MainActivity.socketCmd("RUN");
                  MainActivity.this.runOnUiThread(new Runnable() {
                     @Override
                     public void run() {
                        boolean var1x = var1 != null && var1.contains("OK");
                        MainActivity.this.btnRun.setText(var1x ? "已触发 · 闲鱼内执行中…" : "未连通 · 打开闲鱼后重试");
                        Toast.makeText(MainActivity.this, var1x ? "引擎已启动，钓鱼进行中，完成后通知栏推送" : "socket 未连通：请先打开闲鱼（引擎随进程加载），再点开始钓鱼", 1).show();
                        if (!var1x) {
                           try {
                              Intent var2 = MainActivity.this.getPackageManager().getLaunchIntentForPackage("com.taobao.idlefish");
                              if (var2 != null) {
                                 MainActivity.this.startActivity(var2);
                              }
                           } catch (Throwable var3) {
                           }
                        }

                        MainActivity.this.btnRun.postDelayed(new Runnable() {
                           @Override
                           public void run() {
                              MainActivity.this.btnRun.setText("开始钓鱼");
                           }
                        }, 5000L);
                     }
                  });
               }
            }, "rh-run-cmd").start();
         }
      });
      this.btnRefresh.setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.refreshLog();
         }
      });
      this.btnExportLog.setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.exportRunLog();
         }
      });
      this.rowUpdate.setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            if (MainActivity.this.updateCheckFailed) {
               MainActivity.this.checkUpdate();
            } else {
               MainActivity.this.showDownloadPicker();
            }
         }
      });
      this.rowCoolapk.setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            Intent var2 = new Intent("android.intent.action.VIEW", Uri.parse("https://www.coolapk.com/u/2206962"));
            var2.setPackage("com.coolapk.market");

            try {
               MainActivity.this.startActivity(var2);
            } catch (Throwable var5) {
               try {
                  MainActivity.this.startActivity(new Intent("android.intent.action.VIEW", Uri.parse("https://www.coolapk.com/u/2206962")));
               } catch (Throwable var4) {
                  Toast.makeText(MainActivity.this, "无可用浏览器", 0).show();
               }
            }
         }
      });
      // 加入我们🐧：三级回退——1) mqqapi 直拉 QQ 群资料卡（已装 QQ 时不经浏览器）
      // 2) qm.qq.com 链接系统分发（QQ 注册了 https 接管时接住）3) 都不行提示无浏览器
      // 注：Android 11+ 包可见性需 manifest <queries> 声明 com.tencent.mobileqq（已加）
      this.rowQQGroup.setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            // 群资料卡：uin=群号，直接拉起 QQ 的群卡片页（可申请加群）
            Intent var2 = new Intent("android.intent.action.VIEW",
               Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=250616876&card_type=group&source=qrcode"));
            var2.setPackage("com.tencent.mobileqq");

            try {
               MainActivity.this.startActivity(var2);
            } catch (Throwable var5) {
               try {
                  MainActivity.this.startActivity(new Intent("android.intent.action.VIEW", Uri.parse("https://qm.qq.com/q/UZNnW17Nqs")));
               } catch (Throwable var4) {
                  Toast.makeText(MainActivity.this, "未安装 QQ 且无可用浏览器", 0).show();
               }
            }
         }
      });
      // v1.5.3 清除今日任务标记：确认 → socket CLEAR_MARKS（闲鱼进程自己清，含 LVL 0/1/2 内存态）
      //       → root force-stop → monkey 拉起。拉起后引擎波次（40s/45s/50s/240s）自动重跑当日任务。
      //       root 不可用时降级：标记照清（socket 不依赖 root），提示手动重启。
      this.rowClearMarks.setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            new AlertDialog.Builder(MainActivity.this)
               .setTitle("清除今日任务标记")
               .setMessage("将清除全部 0/1/2 当日标识与 done/try/date 标记文件，"
                  + "并自动重启闲鱼，重启后各引擎将自动重跑今日任务。\n\n"
                  + "注意：\n· 自动投喂重跑会真实消耗饲料余额（投到耗尽为止）\n"
                  + "· 兑换类任务会再次尝试（服务端有每日限额兜底）\n\n确定执行？")
               .setPositiveButton("清除并重启", new DialogInterface.OnClickListener() {
                  public void onClick(DialogInterface d, int w) {
                     new Thread(new Runnable() {
                        public void run() {
                           MainActivity.this.execClearMarksAndRestart();
                        }
                     }, "rh-clearmarks").start();
                  }
               })
               .setNegativeButton("取消", null)
               .show();
         }
      });
      OnClickListener var11 = new OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.setIndex(0);
         }
      };
      OnClickListener var12 = new OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.setIndex(1);
         }
      };
      OnClickListener var13 = new OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.setIndex(2);
         }
      };
      this.tabHome.setOnClickListener(var11);
      this.tabLog.setOnClickListener(var12);
      this.tabAbout.setOnClickListener(var13);
      this.setTabVisual(0, true);
      this.setTabVisual(1, false);
      this.setTabVisual(2, false);
      this.startPulse();
      this.tabHome.post(new Runnable() {
         @Override
         public void run() {
            float var1 = (float)MainActivity.this.pagesWrap.getWidth();

            for (int var2 = 0; var2 < MainActivity.this.track.getChildCount(); var2++) {
               MainActivity.this.track.getChildAt(var2).setTranslationX((float)var2 * var1);
            }

            MainActivity.this.placePill(0, false);
            MainActivity.this.pillReady = true;
         }
      });
      IntentFilter var14 = new IntentFilter("com.piggyhand.fish.RESULT");
      if (VERSION.SDK_INT >= 33) {
         this.registerReceiver(this.resultReceiver, var14, 2);
      } else {
         this.registerReceiver(this.resultReceiver, var14);
      }

      this.startStateCheck();
      new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
         @Override
         public void run() {
            MainActivity.this.checkUpdate();
         }
      }, 700L);
   }

   private void applyEdgeToEdge() {
      try {
         Window var1 = this.getWindow();
         var1.setStatusBarColor(0);
         var1.setNavigationBarColor(0);
         if (VERSION.SDK_INT >= 29) {
            var1.setStatusBarContrastEnforced(false);
            var1.setNavigationBarContrastEnforced(false);
         }

         short var2 = 10000;
         var1.getDecorView().setSystemUiVisibility(var2);
      } catch (Throwable var3) {
      }
   }

   protected void onResume() {
      super.onResume();
      this.startStateCheck();
      this.startCoinUiLoop();
      this.refreshCashState();
   }

   private void startStateCheck() {
      new Thread(
            new Runnable() {
               @Override
               public void run() {
                  boolean var1 = MainActivity.probeEngineSameUser();
                  boolean var2 = false;
                  if (!var1) {
                     var2 = MainActivity.this.lspLogHasSameUserFish();
                  }

                  Log.i(
                     "RhState",
                     "[state] userId="
                        + ConfigManager.myUserId()
                        + " fishUid="
                        + MainActivity.this.fishPackageUid()
                        + " port="
                        + ConfigManager.reportPort()
                        + " socketSameUser="
                        + var1
                        + " logHit="
                        + var2
                  );
                  final boolean var4 = var1 || var2;
                  MainActivity.this.runOnUiThread(new Runnable() {
                     @Override
                     public void run() {
                        MainActivity.this.tvLspState.setText(var4 ? "LSPosed：已激活" : "LSPosed：未激活");
                        MainActivity.this.tvLspState.setTextColor(var4 ? -15878054 : -2937041);
                     }
                  });
               }
            },
            "rh-lsp-check"
         )
         .start();
      new Thread(new Runnable() {
         @Override
         public void run() {
            final boolean var1 = MainActivity.hasRootAccess();
            MainActivity.this.runOnUiThread(new Runnable() {
               @Override
               public void run() {
                  MainActivity.this.tvRootState.setText(var1 ? "Root权限：已激活" : "Root权限：未激活");
                  MainActivity.this.tvRootState.setTextColor(var1 ? -15878054 : -2937041);
               }
            });
         }
      }, "rh-root-check").start();
   }

   private static boolean probeEngineSameUser() {
      String var0 = socketCmd("PING");
      if (var0 == null) {
         return false;
      } else {
         String var1 = var0.trim();
         if (!var1.startsWith("RH|")) {
            return false;
         } else {
            String[] var2 = var1.split("\\|");
            if (var2.length < 3) {
               return false;
            } else {
               int var3;
               try {
                  var3 = Integer.parseInt(var2[1].trim());
               } catch (Throwable var5) {
                  return false;
               }

               return ConfigManager.userIdOf(var3) == ConfigManager.myUserId();
            }
         }
      }
   }

   private boolean lspLogHasSameUserFish() {
      int var1 = ConfigManager.myUserId();
      String var2 = var1 == 0 ? "[0-9]{1,5}" : var1 + "[0-9]{5}";
      String var3 = "for f in $(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -3); do grep -hE ' "
         + var2
         + ": .*\\(com\\.taobao\\.idlefish\\)\\[com\\.piggyhand\\.fish' \"$f\" 2>/dev/null; done | wc -l";
      String var4 = suExec(var3, 12);
      return parseIntSafe(var4) > 0;
   }

   private int fishPackageUid() {
      try {
         return this.getPackageManager().getPackageInfo("com.taobao.idlefish", 0).applicationInfo.uid;
      } catch (Throwable var2) {
         return -1;
      }
   }

   private void startCoinUiLoop() {
      if (this.coinUiHandler == null) {
         this.coinUiHandler = new Handler(Looper.getMainLooper());
      }

      if (this.coinUiLoop == null) {
         this.coinUiLoop = new Runnable() {
            @Override
            public void run() {
               MainActivity.this.refreshCoinToday();
               MainActivity.this.coinUiHandler.postDelayed(this, 10000L);
            }
         };
      }

      this.coinUiHandler.removeCallbacks(this.coinUiLoop);
      this.refreshCoinToday();
      this.coinUiHandler.postDelayed(this.coinUiLoop, 10000L);
   }

   private void stopCoinUiLoop() {
      if (this.coinUiHandler != null && this.coinUiLoop != null) {
         this.coinUiHandler.removeCallbacks(this.coinUiLoop);
      }
   }

   private void refreshCoinToday() {
      if (!this.coinRefreshing) {
         this.coinRefreshing = true;
         new Thread(
               new Runnable() {
                  @Override
                  public void run() {
                     long var1 = -1L;

                     try {
                        SimpleDateFormat var3 = new SimpleDateFormat("yyyy.MM.dd", Locale.US);
                        String var4 = var3.format(new Date());
                        String var5 = var3.format(new Date(System.currentTimeMillis() - 86400000L));
                        String var6 = ConfigManager.fishExternal("files");
                        String var7 = MainActivity.suExec(
                           "tail -1 '"
                              + var6
                              + "/zhushou_"
                              + var5
                              + ".log' 2>/dev/null; echo '---RHSEP---'; cat '"
                              + var6
                              + "/zhushou_"
                              + var4
                              + ".log' 2>/dev/null"
                        );
                        if (var7 != null && var7.contains("---RHSEP---")) {
                           String[] var8 = var7.split("---RHSEP---", 2);
                           SimpleDateFormat var9 = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.US);
                           ArrayList var10 = new ArrayList();
                           MainActivity.collectCoinRows(var8[0], var9, var10);
                           MainActivity.collectCoinRows(var8[1], var9, var10);
                           Collections.sort(var10, new Comparator<long[]>() {
                              public int compare(long[] var1, long[] var2) {
                                 return var1[0] < var2[0] ? -1 : (var1[0] > var2[0] ? 1 : 0);
                              }
                           });
                           int var11 = -1;

                           for (int var12 = 0; var12 < var10.size(); var12++) {
                              if (var4.equals(var3.format(new Date(((long[])var10.get(var12))[0])))) {
                                 var11 = var12;
                                 break;
                              }
                           }

                           if (var11 >= 0) {
                              var1 = 0L;
                              long var18 = var11 > 0 ? ((long[])var10.get(var11 - 1))[1] : ((long[])var10.get(var11))[1];

                              for (int var14 = var11; var14 < var10.size(); var14++) {
                                 long var15 = ((long[])var10.get(var14))[1];
                                 if (var15 > var18) {
                                    var1 += var15 - var18;
                                 }

                                 var18 = var15;
                              }
                           }
                        }
                     } catch (Throwable var17) {
                        var1 = -1L;
                     }

                     final long var1f = var1;
                     MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                           if (var1f >= 0L) {
                              MainActivity.this.tvCoinToday.setText("今日领取：" + var1f);
                              MainActivity.this.tvCoinToday.setTextColor(-15878054);
                           }
                        }
                     });
                     MainActivity.this.coinRefreshing = false;
                  }
               },
               "rh-coin-check"
            )
            .start();
      }
   }

   /**
    * 导出运行日志到内部存储根目录。
    *
    * 相比旧版的改动：
    *  1. 闲鱼版本走 PackageManager（manifest 已声明 <queries>），不再 su + dumpsys + grep。
    *     su 未授权/超时/输出被截时旧实现一律写 "?"，而这恰恰是导出日志最该有用的场景。
    *  2. 头部逐字段独立成行，带 versionCode，机器可读也人可读：
    *     # 小猪手=1.5.4(48) / # 闲鱼=7.29.10(529)
    *  3. 正文合并今天/昨天/前天三份（RhFileLog 本来就保留 3 天），一个文件覆盖 3 天现场。
    *  4. 末尾附 rh_caps.json 能力矩阵（符号命中/进程归属/engine_ok），省掉再来一轮问版本。
    *  5. 没有运行日志也照样出文件（只带环境头）；su 全挂则退回 App 私有外部目录，
    *     保证"闲鱼版本 + 设备"这两条信息一定能落到磁盘。
    */
   private void exportRunLog() {
      new Thread(
            new Runnable() {
               @Override
               public void run() {
                  SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy.MM.dd", Locale.US);
                  SimpleDateFormat tsFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                  long now = System.currentTimeMillis();
                  String today = dayFmt.format(new Date(now));
                  String fish = FishVer.label(MainActivity.this);
                  // 文件名不用括号：adb / 终端 / 部分文件管理器会把括号当 shell 元字符，_ 更省事
                  String outName = "xiaozhushou" + today + "_" + safeName(FishVer.name(MainActivity.this))
                     + "_" + FishVer.code(MainActivity.this) + ".log";
                  String outPath = ConfigManager.storageRoot() + "/" + outName;
                  String fishFiles = ConfigManager.fishExternal("files");
                  String[] src = new String[]{
                     fishFiles + "/zhushou_run_" + dayFmt.format(new Date(now)) + ".log",
                     fishFiles + "/zhushou_run_" + dayFmt.format(new Date(now - 86400000L)) + ".log",
                     fishFiles + "/zhushou_run_" + dayFmt.format(new Date(now - 172800000L)) + ".log"
                  };
                  String fishCache = ConfigManager.fishExternal("cache");
                  String caps = fishCache + "/rh_caps.json";

                  StringBuilder h = new StringBuilder();
                  h.append("# 导出时间=").append(tsFmt.format(new Date(now))).append('\n');
                  h.append("# 小猪手=").append(BuildInfo.VERSION).append('(').append(BuildInfo.CODE).append(")\n");
                  h.append("# 闲鱼=").append(fish).append('\n');
                  h.append("# 安卓=").append(VERSION.RELEASE).append('(').append(VERSION.SDK_INT)
                     .append(") 设备=").append(Build.BRAND).append(' ').append(Build.MODEL).append('\n');
                  h.append("# 导出端包名=").append(MainActivity.this.getPackageName()).append('\n');

                  // 一条 sh 里完成：写头 -> 拼 3 天日志 -> 追加 caps -> 追加网关拒绝原文档 -> 落盘 -> 回传统计
                  // 用 n/size 自报家门，不再靠 grep 输出里的 "No such" 猜成功与否（那套判断会被日志正文误伤）
                  // v1.5.5 改进 A：raw/.bad 是网关拒绝响应原文（各截 400B）——没有它 219B 拒绝页无法定性
                  String cmd = "n=0; { printf '%s' " + shq(h.toString())
                     + "; for f in " + shq(src[0]) + " " + shq(src[1]) + " " + shq(src[2])
                     + "; do if [ -f \"$f\" ]; then n=$((n+1)); printf '\\n===== %s =====\\n' \"$f\"; cat \"$f\"; fi; done"
                     + "; if [ -f " + shq(caps) + " ]; then printf '\\n===== caps =====\\n'; cat " + shq(caps) + "; fi"
                     + "; for f in " + shq(fishCache) + "/rh_resp_*.json.raw " + shq(fishCache) + "/rh_resp_*.json.bad"
                     + "; do if [ -f \"$f\" ]; then printf '\\n===== raw %s =====\\n' \"${f##*/}\"; head -c 400 \"$f\"; printf '\\n'; fi; done"
                     + "; } > " + shq(outPath) + " 2>&1; chmod 644 " + shq(outPath)
                     + "; printf 'RHSTAT n=%s size=%s\\n' \"$n\" \"$(stat -c %s " + shq(outPath) + ")\"";

                  String res = MainActivity.suExec(cmd, 25);
                  int cnt = -1;
                  long size = -1L;
                  int at = res == null ? -1 : res.indexOf("RHSTAT");
                  if (at >= 0) {
                     String[] toks = res.substring(at).trim().split("\\s+");
                     for (int i = 0; i < toks.length; i++) {
                        try {
                           if (toks[i].startsWith("n=")) cnt = Integer.parseInt(toks[i].substring(2));
                           else if (toks[i].startsWith("size=")) size = Long.parseLong(toks[i].substring(5));
                        } catch (Throwable ignored) {
                        }
                     }
                  }

                  final String msg;
                  if (size > 0) {
                     // 媒体库扫描：否则 MTP / 部分文件管理器要等重启才看得到新文件
                     MainActivity.suExec("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + outPath + " >/dev/null 2>&1", 8);
                     msg = cnt > 0
                        ? "已导出：" + outName + "\n" + cnt + " 天日志 · 闲鱼 " + fish
                        : "已导出环境信息（近期无运行日志）：" + outName + "\n闲鱼 " + fish;
                  } else {
                     // su 不可用（未授权/超时/Magisk 未放行）：退到 App 私有外部目录，至少把版本信息交出来
                     String fb = writeFallback(outName, h.toString());
                     msg = fb != null
                        ? "无 root，已导出环境信息到：\n" + fb + "\n闲鱼 " + fish
                        : "导出失败：" + (res == null ? "su 无响应" : res.trim());
                  }

                  MainActivity.this.runOnUiThread(new Runnable() {
                     @Override
                     public void run() {
                        Toast.makeText(MainActivity.this, msg, 1).show();
                     }
                  });
               }
            },
            "rh-export"
         )
         .start();
   }

   /** 无 root 兜底：写 App 自己的外部目录（不需要任何权限），返回绝对路径；失败返回 null */
   private String writeFallback(String name, String content) {
      try {
         File dir = getExternalFilesDir(null);
         if (dir == null) return null;
         if (!dir.exists() && !dir.mkdirs()) return null;
         File f = new File(dir, name);
         OutputStream os = new java.io.FileOutputStream(f, false);
         os.write(content.getBytes("UTF-8"));
         os.close();
         return f.getAbsolutePath();
      } catch (Throwable t) {
         return null;
      }
   }

   /** 文件名里不能出现的字符换成下划线（版本号本身是安全的，这里只防异常返回值） */
   private static String safeName(String s) {
      if (s == null || s.length() == 0) return "na";
      StringBuilder b = new StringBuilder(s.length());
      for (int i = 0; i < s.length(); i++) {
         char c = s.charAt(i);
         b.append(c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
            || c == '<' || c == '>' || c == '|' || c == ' ' || c == '\n' ? '_' : c);
      }
      return b.toString();
   }

   /** 单引号包裹给 sh 用；内容里的单引号直接剔除（路径与头部都不该带它） */
   private static String shq(String s) {
      return "'" + (s == null ? "" : s.replace("'", "")) + "'";
   }

   private static String suExec(String var0) {
      return suExec(var0, 5);
   }

   private static String suExec(String var0, int var1) {
      Process var2 = null;

      try {
         var2 = Runtime.getRuntime().exec(new String[]{"su", "-c", var0});
         boolean var3 = var2.waitFor((long)var1, TimeUnit.SECONDS);
         if (!var3) {
            var2.destroy();
            return null;
         } else {
            return readAll(var2.getInputStream());
         }
      } catch (Throwable var6) {
         try {
            if (var2 != null) {
               var2.destroy();
            }
         } catch (Throwable var5) {
         }

         return null;
      }
   }

   private static int parseIntSafe(String var0) {
      if (var0 == null) {
         return -1;
      } else {
         try {
            StringBuilder var1 = new StringBuilder();

            for (char var5 : var0.trim().toCharArray()) {
               if (var5 >= '0' && var5 <= '9') {
                  var1.append(var5);
               } else if (var1.length() > 0) {
                  break;
               }
            }

            return var1.length() == 0 ? -1 : Integer.parseInt(var1.toString());
         } catch (Throwable var6) {
            return -1;
         }
      }
   }

   private static void collectCoinRows(String var0, SimpleDateFormat var1, ArrayList<long[]> var2) {
      if (var0 != null) {
         String[] var3 = var0.split("\n");

         for (String var7 : var3) {
            String var8 = var7.trim();
            if (var8.length() != 0) {
               String[] var9 = var8.split("[ \\t]+");
               if (var9.length >= 3) {
                  try {
                     long var10 = var1.parse(var9[0] + " " + var9[1]).getTime();
                     long var12 = Long.parseLong(var9[2].trim());
                     if (var12 >= 0L) {
                        var2.add(new long[]{var10, var12});
                     }
                  } catch (Throwable var14) {
                  }
               }
            }
         }
      }
   }

   private View tab(int var1) {
      return var1 == 0 ? this.tabHome : (var1 == 1 ? this.tabLog : this.tabAbout);
   }

   private void setIndex(int var1) {
      if (var1 != this.cur) {
         int var2 = var1 > this.cur ? 1 : -1;
         if (this.pillReady) {
            this.animatePill(var1, var2);
         }

         this.animatePages(this.cur, var1, var2);
         this.setTabVisual(this.cur, false);
         this.setTabVisual(var1, true);
         this.cur = var1;
         if (var1 == 1 && !this.logLoaded) {
            this.logLoaded = true;
            this.refreshLog();
         }
      }
   }

   private void animatePages(final int var1, final int var2, final int var3) {
      final float var4 = (float)this.pagesWrap.getWidth();
      if (!(var4 <= 0.0F)) {
         ValueAnimator var5 = ValueAnimator.ofFloat(new float[]{0.0F, 1.0F});
         var5.setDuration(550L);
         var5.setInterpolator(BezierInterpolator.jelly());
         var5.addUpdateListener(new AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator var1x) {
               float var2x = (Float)var1x.getAnimatedValue();
               float var3x = ((float)var1 + (float)(var2 - var1) * var2x) * var4;
               int var4x = MainActivity.this.track.getChildCount();

               for (int var5 = 0; var5 < var4x; var5++) {
                  View var6 = MainActivity.this.track.getChildAt(var5);
                  float var7 = (float)var5 * var4 - var3x;
                  if (var5 == var2) {
                     var6.setTranslationX(var7 + (float)(var3 * -MainActivity.this.dp(26)) * (1.0F - var2x));
                     var6.setAlpha(0.4F + 0.6F * var2x);
                  } else {
                     var6.setTranslationX(var7);
                  }
               }
            }
         });
         var5.start();
      }
   }

   private void animatePill(int var1, int var2) {
      View var3 = this.tab(var1);
      final float var4 = this.pill.getTranslationX();
      final float var5 = var3.getX() + (float)this.dp(3) - (float)this.dp(7);
      final float var6 = Math.min(1.32F, 1.0F + Math.abs(var5 - var4) / (240.0F * this.getResources().getDisplayMetrics().density));
      this.pill.setPivotX(var2 > 0 ? 0.0F : (float)this.pill.getWidth());
      ValueAnimator var7 = ValueAnimator.ofFloat(new float[]{0.0F, 1.0F});
      var7.setDuration(560L);
      var7.setInterpolator(BezierInterpolator.jelly());
      var7.addUpdateListener(new AnimatorUpdateListener() {
         public void onAnimationUpdate(ValueAnimator var1) {
            float var2 = (Float)var1.getAnimatedValue();
            MainActivity.this.pill.setTranslationX(var4 + (var5 - var4) * var2);
            float var3 = 1.0F + (var6 - 1.0F) * (float)Math.sin(Math.PI * (double)var2);
            MainActivity.this.pill.setScaleX(var3);
         }
      });
      var7.start();
   }

   private void placePill(int var1, boolean var2) {
      View var3 = this.tab(var1);
      float var4 = var3.getX() + (float)this.dp(3) - (float)this.dp(7);
      int var5 = var3.getWidth() - this.dp(6);
      LayoutParams var6 = (LayoutParams)this.pill.getLayoutParams();
      if (var6.width != var5) {
         var6.width = var5;
         this.pill.setLayoutParams(var6);
      }

      this.pill.setPivotX(0.0F);
      if (var2) {
         this.pill.animate().translationX(var4).setDuration(300L).start();
      } else {
         this.pill.setTranslationX(var4);
      }
   }

   private void setTabVisual(int var1, boolean var2) {
      this.tabIcons[var1].setColorFilter(var2 ? -2066432 : 1712789532);
      this.tabLabels[var1].setTextColor(var2 ? -3708928 : -7697518);
      this.tabLabels[var1].setTypeface(Typeface.DEFAULT, var2 ? 1 : 0);
      this.tabIcons[var1]
         .animate()
         .scaleX(var2 ? 1.1F : 1.0F)
         .scaleY(var2 ? 1.1F : 1.0F)
         .translationY(var2 ? (float)(-this.dp(1)) : 0.0F)
         .setDuration(280L)
         .start();
   }

   private void startPulse() {
      ValueAnimator var1 = ValueAnimator.ofFloat(new float[]{0.0F, 1.0F});
      var1.setDuration(1800L);
      var1.setRepeatCount(-1);
      var1.addUpdateListener(new AnimatorUpdateListener() {
         public void onAnimationUpdate(ValueAnimator var1) {
            float var2 = (Float)var1.getAnimatedValue();
            MainActivity.this.pulseHalo.setScaleX(1.0F + var2 * 1.6F);
            MainActivity.this.pulseHalo.setScaleY(1.0F + var2 * 1.6F);
            MainActivity.this.pulseHalo.setAlpha(1.0F - var2);
         }
      });
      var1.start();
   }

   private void checkUpdate() {
      this.updateCheckFailed = false;
      this.tvGhSub.setVisibility(0);
      this.tvGhSub.setText("检查更新中…");
      this.tvGhSub.setTextColor(-7697518);
      UpdateChecker.check(this, new UpdateChecker.Callback() {
         @Override
         public void onResult(boolean var1, String var2, boolean var3) {
            if (var3) {
               MainActivity.this.updateCheckFailed = true;
               MainActivity.this.dotNew.setVisibility(8);
               MainActivity.this.dotOk.setVisibility(8);
               MainActivity.this.tvGhSub.setVisibility(0);
               MainActivity.this.tvGhSub.setText("检查失败 · 点按重试");
               MainActivity.this.tvGhSub.setTextColor(-7697518);
               MainActivity.this.tvVersion.setTextColor(-5021952);
               MainActivity.this.tvVersion.setText("v" + UpdateChecker.currentVersion(MainActivity.this) + " · LSPosed");
            } else if (var1) {
               MainActivity.this.dotNew.setVisibility(0);
               MainActivity.this.dotOk.setVisibility(8);
               MainActivity.this.tvGhSub.setVisibility(0);
               MainActivity.this.tvGhSub.setText("发现新版本 · 点击前往下载");
               MainActivity.this.tvGhSub.setTextColor(-3708928);
               MainActivity.this.tvGhVal.setText("v" + var2 + " ›");
               MainActivity.this.tvVersion.setTextColor(-2937041);
               MainActivity.this.tvVersion.setText("请及时更新插件 v" + var2);
            } else {
               MainActivity.this.dotNew.setVisibility(8);
               MainActivity.this.dotOk.setVisibility(0);
               MainActivity.this.tvGhSub.setVisibility(0);
               MainActivity.this.tvGhSub.setText("当前为最新版 v" + UpdateChecker.currentVersion(MainActivity.this));
               MainActivity.this.tvGhSub.setTextColor(-15878054);
               MainActivity.this.tvVersion.setTextColor(-15878054);
               MainActivity.this.tvVersion.setText("v" + UpdateChecker.currentVersion(MainActivity.this) + " · LSPosed");
            }
         }
      });
   }

   private void showDownloadPicker() {
      if (this.linkPicker == null) {
         ViewGroup var1 = (ViewGroup)this.findViewById(16908290);
         FrameLayout var2 = new FrameLayout(this);
         var2.setBackgroundColor(1493172224);
         var2.setClickable(true);
         var2.setOnClickListener(new OnClickListener() {
            public void onClick(View var1) {
               MainActivity.this.dismissPicker();
            }
         });
         LinearLayout var3 = new LinearLayout(this);
         var3.setOrientation(1);
         var3.setBackgroundResource(R.drawable.bg_glass);
         int var4 = this.dp(16);
         var3.setPadding(var4, var4, var4, this.dp(2));
         TextView var5 = new TextView(this);
         var5.setText("选择下载渠道");
         var5.setTextSize(13.5F);
         var5.setTypeface(Typeface.DEFAULT_BOLD);
         var5.setTextColor(-15263716);
         var3.addView(var5);
         var3.addView(this.pickRow("UC 网盘", "国内直连 · 推荐", "https://drive.uc.cn/s/df475a5e0ad94"));
         var3.addView(this.divider());
         var3.addView(this.pickRow("夸克网盘", "国内直连", "https://pan.quark.cn/s/b0a477c5b50f"));
         var3.addView(this.divider());
         var3.addView(this.pickRow("GitHub Releases", "国际源 · 需代理环境", "https://github.com/Lotuszn/piggyhand-fish/releases/latest"));
         TextView var6 = new TextView(this);
         var6.setText("取消");
         var6.setTextSize(11.5F);
         var6.setTextColor(-7697518);
         var6.setGravity(17);
         var6.setPadding(0, this.dp(10), 0, this.dp(10));
         var6.setClickable(true);
         var6.setOnClickListener(new OnClickListener() {
            public void onClick(View var1) {
               MainActivity.this.dismissPicker();
            }
         });
         var3.addView(var6);
         var2.addView(var3, new LayoutParams(this.dp(280), -2, 17));
         var1.addView(var2, new android.view.ViewGroup.LayoutParams(-1, -1));
         this.linkPicker = var2;
         var2.setAlpha(0.0F);
         var3.setTranslationY((float)this.dp(18));
         var2.animate().alpha(1.0F).setDuration(180L).start();
         var3.animate().translationY(0.0F).setDuration(180L).start();
      }
   }

   private View pickRow(String var1, String var2, final String var3) {
      LinearLayout var4 = new LinearLayout(this);
      var4.setOrientation(1);
      var4.setClickable(true);
      var4.setFocusable(true);
      TypedValue var5 = new TypedValue();
      this.getTheme().resolveAttribute(16843534, var5, true);
      var4.setForeground(this.getResources().getDrawable(var5.resourceId));
      int var6 = this.dp(11);
      var4.setPadding(0, var6, 0, var6);
      TextView var7 = new TextView(this);
      var7.setText(var1);
      var7.setTextSize(12.5F);
      var7.setTextColor(-15263716);
      var4.addView(var7);
      TextView var8 = new TextView(this);
      var8.setText(var2);
      var8.setTextSize(10.0F);
      var8.setTextColor(-7697518);
      var8.setPadding(0, this.dp(2), 0, 0);
      var4.addView(var8);
      var4.setOnClickListener(new OnClickListener() {
         public void onClick(View var1) {
            MainActivity.this.dismissPicker();

            try {
               MainActivity.this.startActivity(new Intent("android.intent.action.VIEW", Uri.parse(var3)));
            } catch (Throwable var3x) {
               Toast.makeText(MainActivity.this, "无可用浏览器", 0).show();
            }
         }
      });
      return var4;
   }

   private View divider() {
      View var1 = new View(this);
      var1.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1, this.dp(1)));
      var1.setBackgroundColor(253171740);
      return var1;
   }

   private void dismissPicker() {
      if (this.linkPicker != null) {
         final View var1 = this.linkPicker;
         this.linkPicker = null;
         var1.animate().alpha(0.0F).setDuration(140L).start();
         var1.postDelayed(new Runnable() {
            @Override
            public void run() {
               ViewGroup var1x = (ViewGroup)var1.getParent();
               if (var1x != null) {
                  var1x.removeView(var1);
               }
            }
         }, 160L);
      }
   }

   public void onBackPressed() {
      if (this.linkPicker != null) {
         this.dismissPicker();
      } else {
         super.onBackPressed();
      }
   }

   private JSONObject currentConfig() {
      try {
         SharedPreferences var1 = this.getSharedPreferences("config", 0);
         JSONObject var2 = new JSONObject(
            var1.getString(
               "config_json",
               "{\n  \"enabled\": true,\n  \"autoRun\": true,\n  \"autoRunIntervalMin\": 240,\n  \"targets\": [\"com.taobao.idlefish\"],\n  \"mtopHook\": true,\n  \"toastNotify\": true,\n  \"polish\": true,\n  \"scout\": true,\n  \"scoutPattern\": \"reward|bonus|coin\",\n  \"maxScoutMethods\": 400,\n  \"adSdkPresets\": true,\n  \"noSplashAd\": true,\n  \"rules\": [\n    {\n      \"comment\": \"侦察完成后按日志填规则。示例:\",\n      \"class\": \"com.example.target.app.AdTaskActivity\",\n      \"method\": \"onRewardVerify\",\n      \"action\": \"return_true\"\n    }\n  ]\n}\n"
            )
         );
         var2.put("scout", true);
         var2.put("scoutPattern", "reward|bonus|coin");
         var2.put("maxScoutMethods", 400);
         return var2;
      } catch (Throwable var4) {
         try {
            return new JSONObject(
               "{\n  \"enabled\": true,\n  \"autoRun\": true,\n  \"autoRunIntervalMin\": 240,\n  \"targets\": [\"com.taobao.idlefish\"],\n  \"mtopHook\": true,\n  \"toastNotify\": true,\n  \"polish\": true,\n  \"scout\": true,\n  \"scoutPattern\": \"reward|bonus|coin\",\n  \"maxScoutMethods\": 400,\n  \"adSdkPresets\": true,\n  \"noSplashAd\": true,\n  \"rules\": [\n    {\n      \"comment\": \"侦察完成后按日志填规则。示例:\",\n      \"class\": \"com.example.target.app.AdTaskActivity\",\n      \"method\": \"onRewardVerify\",\n      \"action\": \"return_true\"\n    }\n  ]\n}\n"
            );
         } catch (Throwable var3) {
            return new JSONObject();
         }
      }
   }

   private void animateDrawer(final ViewGroup var1, boolean var2, final AtomicBoolean var3) {
      this.drawerBusySet(var3, true);
      if (var2) {
         var1.setVisibility(0);
         var1.setAlpha(0.0F);
         var1.post(new Runnable() {
            @Override
            public void run() {
               final int var1x = var1.getHeight();
               if (var1x == 0) {
                  var1.setAlpha(1.0F);
                  MainActivity.this.drawerBusySet(var3, false);
               } else {
                  ValueAnimator var2 = ValueAnimator.ofFloat(new float[]{0.0F, 1.0F});
                  var2.setDuration(260L);
                  var2.setInterpolator(new DecelerateInterpolator(1.4F));
                  var2.addUpdateListener(new AnimatorUpdateListener() {
                     public void onAnimationUpdate(ValueAnimator var1xx) {
                        float var2 = (Float)var1xx.getAnimatedValue();
                        android.view.ViewGroup.LayoutParams var3x = var1.getLayoutParams();
                        var3x.height = (int)((float)var1x * var2);
                        var1.setLayoutParams(var3x);
                        var1.setAlpha(var2);
                     }
                  });
                  var2.addListener(new AnimatorListenerAdapter() {
                     public void onAnimationEnd(Animator var1x) {
                        android.view.ViewGroup.LayoutParams var2 = var1.getLayoutParams();
                        var2.height = -2;
                        var1.setLayoutParams(var2);
                        var1.setAlpha(1.0F);
                        MainActivity.this.drawerBusySet(var3, false);
                     }
                  });
                  var2.start();
               }
            }
         });
      } else {
         final int var4 = var1.getHeight();
         ValueAnimator var5 = ValueAnimator.ofFloat(new float[]{0.0F, 1.0F});
         var5.setDuration(200L);
         var5.setInterpolator(new AccelerateInterpolator(1.2F));
         var5.addUpdateListener(new AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator var1x) {
               float var2 = (Float)var1x.getAnimatedValue();
               android.view.ViewGroup.LayoutParams var3 = var1.getLayoutParams();
               var3.height = (int)((float)var4 * (1.0F - var2));
               var1.setLayoutParams(var3);
               var1.setAlpha(1.0F - var2);
            }
         });
         var5.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator var1x) {
               var1.setVisibility(8);
               var1.setAlpha(1.0F);
               android.view.ViewGroup.LayoutParams var2 = var1.getLayoutParams();
               var2.height = -2;
               var1.setLayoutParams(var2);
               MainActivity.this.drawerBusySet(var3, false);
            }
         });
         var5.start();
      }
   }

   private void drawerBusySet(AtomicBoolean var1, boolean var2) {
      var1.set(var2);
   }

   private void runUi(Runnable var1) {
      this.runOnUiThread(var1);
   }

   private void showPondReport(String var1) {
      this.showReportCard("\ud83d\udc1f 鱼塘任务报告", var1, "稍后重新点击「鱼塘任务」查看。");
   }

   private void showPondFeedReport(String var1) {
      this.showReportCard("\ud83c\udf5a 自动投喂报告", var1, "稍后重新点击「自动投喂」查看。");
   }

   private void showCoinReport(String var1) {
      this.showReportCard("\ud83e\ude99 游戏币任务报告", var1, "稍后重新点击「赚游戏币任务」查看。");
   }

   private void showCoinExchReport(String var1) {
      this.showReportCard("\ud83e\udde7 自动兑换报告", var1, "稍后重新点击「自动兑换」查看。");
   }

   /* ---------- v1.4.1 自动投喂开关 ---------- */

   /** 开关状态安装；投喂量固定语义：进行到饲料余额耗尽，无次数自定义 */
   private void installPondFeed(JSONObject var1) {
      this.swPondFeed.setOnSilent(var1.optBoolean("pondFeed", false));
      this.swPondFeed.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1x) {
            MainActivity.this.put("pondFeed", var1x);
            Toast.makeText(MainActivity.this, var1x
                    ? "\ud83c\udf5a 自动投喂已开启：每天打开闲鱼自动投喂至饲料耗尽"
                    : "\ud83c\udf5a 自动投喂已关闭", 0).show();
         }
      });
   }

   private static String summarizeOliver(String var0) {
      if (var0 == null) {
         return "\ud83c\udfc5 没拿到会员任务结果：先打开闲鱼再来点";
      } else if (var0.contains("任务列表拉取失败")) {
         return "\ud83c\udfc5 任务列表拉取失败，隔一分钟再点";
      } else if (var0.contains("没取到 asac")) {
         return "\ud83c\udfc5 活动参数缺失，本轮没执行";
      } else if (var0.contains("上一轮还在执行")) {
         return "\ud83c\udfc5 上一轮还在跑，本次跳过";
      } else if (!var0.contains("今日任务已完成") && !var0.contains("服务端显示全部完成")) {
         String var1 = "";
         Matcher var2 = Pattern.compile("共 \\+(\\d+) 经验").matcher(var0);
         if (var2.find() && !"0".equals(var2.group(1))) {
            var1 = " +" + var2.group(1) + " 经验";
         }

         Matcher var3 = Pattern.compile("复核: (\\d+) 个未完成").matcher(var0);
         if (var3.find()) {
            int var4 = Integer.parseInt(var3.group(1));
            return var4 == 0 ? "\ud83c\udfc5 会员任务全部完成" + var1 : "\ud83c\udfc5 已执行，仍有 " + var4 + " 个未完成" + var1;
         } else {
            return "\ud83c\udfc5 已执行，详情见日志" + var1;
         }
      } else {
         return "\ud83c\udfc5 今日会员任务已完成，跳过";
      }
   }

   private void toastUi(final String var1) {
      this.runUi(new Runnable() {
         @Override
         public void run() {
            Toast.makeText(MainActivity.this, var1, 1).show();
         }
      });
   }

   private static String pollOliverReport(long var0, int var2) {
      for (int var3 = 0; var3 < var2; var3++) {
         sleepQuiet(1500L);
         String var4 = socketCmd("OLIVER_REPORT");
         if (var4 == null) {
            return null;
         }

         if (var4.contains("会员任务报告")) {
            long var5 = parseReportTime(var4);
            if (var5 <= 0L || var5 >= var0) {
               return var4;
            }
         }
      }

      return null;
   }

   private static String pollGameReport(long var0, int var2) {
      for (int var3 = 0; var3 < var2; var3++) {
         sleepQuiet(1500L);
         String var4 = socketCmd("GAME_REPORT");
         if (var4 == null) {
            return null;
         }

         if (var4.contains("游戏任务报告")) {
            long var5 = parseGameRunId(var4);
            if (var5 <= 0L || var5 >= var0) {
               return var4;
            }
         }
      }

      return null;
   }

   private static long parseGameRunId(String var0) {
      try {
         Matcher var1 = Pattern.compile("@(\\d{13})").matcher(var0);
         return !var1.find() ? 0L : Long.parseLong(var1.group(1));
      } catch (Throwable var2) {
         return 0L;
      }
   }

   private static String summarizeGame(String var0) {
      if (var0 == null) {
         return "\ud83c\udfae 没拿到结果：先打开闲鱼再来点";
      } else if (var0.contains("任务清单拉取失败")) {
         return "\ud83c\udfae 任务清单拉取失败，隔一分钟再点";
      } else if (var0.contains("无可做任务")) {
         return "\ud83c\udfae 今日游戏任务已全部完成";
      } else {
         String var1 = "";
         Matcher var2 = Pattern.compile("共 \\+(\\d+) 闲鱼币").matcher(var0);
         if (var2.find() && !"0".equals(var2.group(1))) {
            var1 = " +" + var2.group(1) + " 币";
         }

         Matcher var3 = Pattern.compile("复核: (\\d+) 个仍待做").matcher(var0);
         if (var3.find()) {
            int var4 = Integer.parseInt(var3.group(1));
            return var4 == 0 ? "\ud83c\udfae 游戏任务全部完成" + var1 : "\ud83c\udfae 已执行，仍有 " + var4 + " 个待做" + var1;
         } else {
            return "\ud83c\udfae 已执行，详情见日志" + var1;
         }
      }
   }

   /** 圈数挑战手动触发结果轮询：按报告内 @13位runId 过滤，只认晚于点击时刻的新报告 */
   private static String pollCycleReport(long var0, int var2) {
      for (int var3 = 0; var3 < var2; var3++) {
         sleepQuiet(1500L);
         String var4 = socketCmd("CYCLE_REPORT");
         if (var4 == null) {
            return null;
         }

         if (var4.contains("圈数挑战报告")) {
            long var5 = parseCycleRunId(var4);
            if (var5 <= 0L || var5 >= var0) {
               return var4;
            }
         }
      }

      return null;
   }

   private static long parseCycleRunId(String var0) {
      try {
         Matcher var1 = Pattern.compile("@(\\d{13})").matcher(var0);
         return !var1.find() ? 0L : Long.parseLong(var1.group(1));
      } catch (Throwable var2) {
         return 0L;
      }
   }

   private static String summarizeCycle(String var0) {
      if (var0 == null) {
         return "\ud83c\udfaf 没拿到结果：先打开闲鱼再来点";
      } else if (var0.contains("开关已关闭")) {
         return "\ud83c\udfaf 圈数挑战开关未开，先打开开关";
      }

      String var1 = "";
      Matcher var2 = Pattern.compile("领取 第(\\d+)圈 (\\S+)").matcher(var0);
      while (var2.find()) {
         var1 = var1 + " 第" + var2.group(1) + "圈" + var2.group(2);
      }
      if (var1.length() > 0) {
         return "\ud83c\udfaf 已领取：" + var1.trim();
      }
      if (var0.contains("tierList/recordId 缺失") || var0.contains("info 无 data")) {
         return "\ud83c\udfaf 圈数挑战暂未开启或数据异常";
      }
      if (var0.contains("info 不可用")) {
         return "\ud83c\udfaf 接口暂不可用，隔一分钟再点";
      }
      if (var0.contains("今日已领") || var0.contains("已领（本地档）")) {
         return "\ud83c\udfaf 可领档位今日已领过，无新增";
      }
      return "\ud83c\udfaf 已执行：当前无可领档位";
   }

   private boolean kickEngine(String var1) {
      String var2 = socketCmd(var1);
      return var2 != null && var2.contains("OK");
   }

   private static String pollCashReport(String var0, String var1, long var2, int var4) {
      for (int var5 = 0; var5 < var4; var5++) {
         sleepQuiet(1500L);
         String var6 = socketCmd(var0);
         if (var6 == null) {
            return null;
         }

         if (var6.contains(var1)) {
            long var7 = parseGameRunId(var6);
            if (var7 <= 0L || var7 >= var2) {
               return var6;
            }
         }
      }

      return null;
   }

   private static String summarizeJoin(String var0) {
      if (var0 == null) {
         return "\ud83e\ude99 没拿到结果：先打开闲鱼再来点";
      } else if (var0.contains("上一轮仍在执行")) {
         return "\ud83e\ude99 上一轮还在跑，本次跳过";
      } else if (var0.contains("今日已终止")) {
         return "\ud83e\ude99 今日已投满，0 点后自动恢复";
      } else if (var0.contains("已投满")) {
         return "\ud83e\ude99 本期已投满，今日不再执行";
      } else if (var0.contains("余额不足")) {
         return "\ud83e\ude99 闲鱼币不足，今日不再尝试";
      } else if (var0.contains("未绑定支付宝")) {
         return "\ud83e\ude99 未绑定支付宝，无法投币";
      } else if (var0.contains("detail 失败")) {
         return "\ud83e\ude99 活动详情拉取失败，1 分钟后重试";
      } else if (var0.contains("投满成功")) {
         return "\ud83e\ude99 投币成功，已投满本期额度";
      } else {
         return var0.contains("业务拒绝") ? "\ud83e\ude99 服务端拒绝，详情见日志" : "\ud83e\ude99 已执行，详情见日志";
      }
   }

   private static String summarizeClaim(String var0) {
      if (var0 == null) {
         return "\ud83e\udde7 没拿到结果：先打开闲鱼再来点";
      } else if (var0.contains("今日已终止")) {
         return "\ud83e\udde7 今日已领完或配额用尽，0 点后自动恢复";
      } else if (var0.contains("本刻无可领")) {
         return "\ud83e\udde7 现在没有可领的期，到点会自动来领";
      } else {
         Matcher var1 = Pattern.compile("金额=(\\S+)").matcher(var0);
         if (var0.contains("✓ 成功") && var1.find()) {
            return "\ud83e\udde7 开红包成功，金额 " + var1.group(1);
         } else {
            return var0.contains("配额用尽") ? "\ud83e\udde7 今日两轮已用完，0 点后自动恢复" : "\ud83e\udde7 已执行，详情见日志";
         }
      }
   }

   private void refreshCashState() {
      new Thread(new Runnable() {
         @Override
         public void run() {
            final String var1 = MainActivity.socketCmd("CASH_STATE");
            MainActivity.this.runUi(new Runnable() {
               @Override
               public void run() {
                  MainActivity.this.applyCashState(var1);
               }
            });
         }
      }, "rh-cash-state").start();
   }

   private void applyCashState(String var1) {
      if (this.tvCashJoinState != null && this.tvCashClaimState != null) {
         String var2 = null;
         String var3 = null;
         if (var1 != null) {
            int var4 = var1.indexOf(35);
            if (var4 > 0) {
               var3 = var1.substring(0, var4);
               var2 = var1.substring(var4 + 1);
            }
         }

         this.tvCashClaimState.setText(describeCash(var3, true));
         this.tvCashJoinState.setText(describeCash(var2, false));
      }
   }

   private static String describeCash(String var0, boolean var1) {
      String var2 = var1 ? "\ud83e\udde7 " : "\ud83e\ude99 ";
      if (var0 == null) {
         return "今日未执行（引擎未在线）";
      } else {
         int var3 = var0.indexOf(124);
         String var4 = var3 >= 0 ? var0.substring(var3 + 1) : var0;
         String[] var5 = var4.split("\\|");
         if (var5.length >= 2 && !"NONE".equals(var5[0])) {
            String var6 = var5[0];
            String var7 = var5[1];
            String var8 = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            if (!var8.equals(var6)) {
               return "今日未执行（上次 " + var6 + "）";
            } else if (var7.startsWith("done_")) {
               return var1 ? "今日已开红包 ✓" : "今日已投满 ✓";
            } else if (var7.startsWith("abort_")) {
               if ("abort_quota".equals(var7)) {
                  return "今日两轮已用完";
               } else if ("abort_balance".equals(var7)) {
                  return "闲鱼币不足，今日已跳过";
               } else {
                  return "abort_retry".equals(var7) ? "今日重试用尽，明天再来" : "今日已中止（" + var7.substring(6) + "）";
               }
            } else if ("retry_pending".equals(var7)) {
               return var1 ? "今日已试 1 轮，等待兜底" : "等待重试中";
            } else {
               return var2 + "运行中…";
            }
         } else {
            return "今日未执行";
         }
      }
   }

   private static long parseReportTime(String var0) {
      try {
         Matcher var1 = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})").matcher(var0);
         if (!var1.find()) {
            return 0L;
         } else {
            Calendar var2 = Calendar.getInstance();
            var2.set(
               Integer.parseInt(var1.group(1)),
               Integer.parseInt(var1.group(2)) - 1,
               Integer.parseInt(var1.group(3)),
               Integer.parseInt(var1.group(4)),
               Integer.parseInt(var1.group(5)),
               Integer.parseInt(var1.group(6))
            );
            var2.set(14, 0);
            return var2.getTimeInMillis();
         }
      } catch (Throwable var3) {
         return 0L;
      }
   }

   private static void sleepQuiet(long var0) {
      try {
         Thread.sleep(var0);
      } catch (InterruptedException var3) {
      }
   }

   private void openFishFront() {
      try {
         Intent var1 = this.getPackageManager().getLaunchIntentForPackage("com.taobao.idlefish");
         if (var1 != null) {
            var1.addFlags(268435456);
            this.startActivity(var1);
         }
      } catch (Throwable var2) {
      }
   }

   private void showReportCard(String var1, String var2, String var3) {
      try {
         final Dialog var4 = new Dialog(this);
         LinearLayout var5 = new LinearLayout(this);
         var5.setOrientation(1);
         GradientDrawable var6 = new GradientDrawable();
         var6.setCornerRadius((float)this.dp(24));
         var6.setColor(-1);
         var5.setBackground(var6);
         TextView var7 = new TextView(this);
         var7.setText(var1);
         var7.setTextSize(15.0F);
         var7.setTypeface(Typeface.DEFAULT_BOLD);
         var7.setTextColor(-15263716);
         var7.setPadding(this.dp(24), this.dp(22), this.dp(24), this.dp(8));
         var5.addView(var7);
         TextView var8 = new TextView(this);
         var8.setText("关闭");
         var8.setTextSize(14.0F);
         var8.setTypeface(Typeface.DEFAULT_BOLD);
         var8.setTextColor(-16090369);
         var8.setGravity(17);
         var8.setPadding(0, this.dp(12), 0, this.dp(14));
         var8.setOnClickListener(new OnClickListener() {
            public void onClick(View var1) {
               var4.dismiss();
            }
         });
         ScrollView var9 = new ScrollView(this);
         TextView var10 = new TextView(this);
         var10.setText(var2 != null && var2.length() != 0 ? var2 : "报告尚未生成（闲鱼进程可能未运行，或引擎仍在执行）\n\n" + var3);
         var10.setTextSize(11.0F);
         var10.setTypeface(Typeface.MONOSPACE);
         var10.setTextColor(-14012875);
         var10.setPadding(this.dp(24), this.dp(4), this.dp(24), this.dp(16));
         var9.addView(var10);
         android.widget.LinearLayout.LayoutParams var11 = new android.widget.LinearLayout.LayoutParams(-1, this.dp(420));
         var9.setLayoutParams(var11);
         var5.addView(var9);
         var5.addView(var8);
         var4.setContentView(var5);
         Window var12 = var4.getWindow();
         if (var12 != null) {
            var12.setBackgroundDrawable(new ColorDrawable(0));
            android.view.WindowManager.LayoutParams var13 = var12.getAttributes();
            var13.width = (int)((float)this.getResources().getDisplayMetrics().widthPixels * 0.88F);
            var12.setAttributes(var13);
         }

         var4.show();
      } catch (Throwable var14) {
         Toast.makeText(this, "报告展示失败: " + var14, 0).show();
      }
   }

   private void askInterval() {
      try {
         JSONObject var1 = this.currentConfig();
         int var2 = var1.optInt("autoRunIntervalMin", -1);
         if (var2 < 0) {
            var2 = var1.optInt("autoRunIntervalHours", 4) * 60;
         }

         final Dialog var3 = new Dialog(this);
         LinearLayout var4 = new LinearLayout(this);
         var4.setOrientation(1);
         GradientDrawable var5 = new GradientDrawable();
         var5.setCornerRadius((float)this.dp(24));
         var5.setColor(-1);
         var4.setBackground(var5);
         TextView var6 = new TextView(this);
         var6.setText("自动间隔（分钟）");
         var6.setTextSize(15.0F);
         var6.setTypeface(Typeface.DEFAULT_BOLD);
         var6.setTextColor(-15263716);
         var6.setPadding(this.dp(24), this.dp(22), this.dp(24), this.dp(2));
         var4.addView(var6);
         TextView var7 = new TextView(this);
         var7.setText("0 = 打开闲鱼即跑 · 最大 1440（24 小时）");
         var7.setTextSize(10.5F);
         var7.setTextColor(-7696487);
         var7.setPadding(this.dp(24), 0, this.dp(24), this.dp(10));
         var4.addView(var7);
         final EditText var8 = new EditText(this);
         var8.setInputType(2);
         var8.setHint("0 - 1440");
         var8.setText(String.valueOf(var2));
         var8.setSelection(var8.getText().length());
         var8.setTextSize(15.0F);
         var8.setTextColor(-15263716);
         var8.setHintTextColor(-4604217);
         var8.setBackground(this.getDrawable(R.drawable.bg_input));
         var8.setPadding(this.dp(14), this.dp(11), this.dp(14), this.dp(11));
         android.widget.LinearLayout.LayoutParams var9 = new android.widget.LinearLayout.LayoutParams(-1, -2);
         var9.setMargins(this.dp(24), 0, this.dp(24), this.dp(6));
         var8.setLayoutParams(var9);
         var4.addView(var8);
         LinearLayout var10 = new LinearLayout(this);
         var10.setOrientation(0);
         var10.setPadding(0, this.dp(6), 0, 0);
         TextView var11 = new TextView(this);
         var11.setText("取消");
         var11.setTextSize(14.0F);
         var11.setTextColor(-7696487);
         var11.setGravity(17);
         var11.setPadding(0, this.dp(14), 0, this.dp(14));
         var11.setOnClickListener(new OnClickListener() {
            public void onClick(View var1) {
               var3.dismiss();
            }
         });
         TextView var12 = new TextView(this);
         var12.setText("确定");
         var12.setTextSize(14.0F);
         var12.setTypeface(Typeface.DEFAULT_BOLD);
         var12.setTextColor(-16090369);
         var12.setGravity(17);
         var12.setPadding(0, this.dp(14), 0, this.dp(14));
         var12.setOnClickListener(new OnClickListener() {
            public void onClick(View var1) {
               try {
                  int var2 = Integer.parseInt(var8.getText().toString().trim());
                  var2 = Math.min(1440, Math.max(0, var2));
                  MainActivity.this.put("autoRunIntervalMin", var2);
                  MainActivity.this.tvStatusMeta.setText("自动间隔 " + var2 + " 分钟 ▾");
                  Toast.makeText(MainActivity.this, "已保存：自动间隔 " + var2 + " 分钟（重启闲鱼生效）", 0).show();
                  var3.dismiss();
               } catch (Throwable var3x) {
                  Toast.makeText(MainActivity.this, "请输入 0-1440 的数字", 0).show();
               }
            }
         });
         android.widget.LinearLayout.LayoutParams var13 = new android.widget.LinearLayout.LayoutParams(0, -2, 1.0F);
         var11.setLayoutParams(var13);
         android.widget.LinearLayout.LayoutParams var14 = new android.widget.LinearLayout.LayoutParams(0, -2, 1.0F);
         var12.setLayoutParams(var14);
         var10.addView(var11);
         View var15 = new View(this);
         var15.setBackgroundColor(337057820);
         var10.addView(var15, new android.widget.LinearLayout.LayoutParams(this.dp(1), -1));
         var10.addView(var12);
         var4.addView(var10);
         var3.setContentView(var4);
         Window var16 = var3.getWindow();
         if (var16 != null) {
            var16.setBackgroundDrawable(new ColorDrawable(0));
            var16.setDimAmount(0.35F);
            android.view.WindowManager.LayoutParams var17 = var16.getAttributes();
            var17.width = (int)((double)this.getResources().getDisplayMetrics().widthPixels * 0.82);
            var17.gravity = 17;
            var16.setAttributes(var17);
         }

         var3.setCancelable(true);
         var3.setCanceledOnTouchOutside(true);
         var3.show();
      } catch (Throwable var18) {
      }
   }

   private int dp(float var1) {
      return (int)(TypedValue.applyDimension(1, var1, this.getResources().getDisplayMetrics()) + 0.5F);
   }

   private void installBag(JSONObject var1, final EditText var2, LiquidSwitch var3, final String var4, final String var5, final String var6) {
      int var7 = var1.optInt(var5, 0);
      var2.setText(String.valueOf(var7));
      var3.setOnSilent(var1.optBoolean(var4, false));
      var3.setOnToggleListener(new LiquidSwitch.OnToggleListener() {
         @Override
         public void onToggle(boolean var1) {
            MainActivity.this.put(var4, var1);
            MainActivity.this.put(var5, MainActivity.bagNumOf(var2));
            if (var1) {
               MainActivity.this.kickRun(var6);
            }
         }
      });
      var2.setOnFocusChangeListener(new OnFocusChangeListener() {
         public void onFocusChange(View var1, boolean var2x) {
            if (!var2x) {
               int var3 = MainActivity.bagNumOf(var2);
               var2.setText(String.valueOf(var3));
               if (!MainActivity.this.bagCommitQuiet) {
                  MainActivity.this.put(var5, var3);
               }
            }
         }
      });
   }

   private static int bagNumOf(EditText var0) {
      try {
         String var1 = var0.getText().toString().trim();
         if (var1.isEmpty()) {
            return 0;
         } else {
            int var2;
            try {
               var2 = Integer.parseInt(var1);
            } catch (Throwable var4) {
               return 0;
            }

            if (var2 < -1) {
               var2 = -1;
            }

            if (var2 > 999) {
               var2 = 999;
            }

            return var2;
         }
      } catch (Throwable var5) {
         return 0;
      }
   }

   private int saveBagConfig(boolean var1) {
      StringBuilder var2 = new StringBuilder();
      int var3 = 0;
      this.bagCommitQuiet = true;

      label65: {
         byte var5;
         try {
            this.clearInputFocusAndKeyboard();
            JSONObject var4 = this.currentConfig();
            var3 += this.bagCommitOne(var4, "bagLuckNum", this.etBagLuck, "好运骰", var2);
            var3 += this.bagCommitOne(var4, "bag8Num", this.etBag8, "八面骰", var2);
            var3 += this.bagCommitOne(var4, "bag12Num", this.etBag12, "十二面骰", var2);
            var3 += this.bagCommitOne(var4, "bagRocketNum", this.etBagRocket, "火箭骰", var2);
            var3 += this.bagCommitOne(var4, "bagDoubleNum", this.etBagDouble, "双倍骰", var2);
            var3 += this.bagCommitOne(var4, "bagGgkNum", this.etBagGgk, "刮刮卡", var2);
            var3 += this.bagCommitOne(var4, "bagDzpNum", this.etBagDzp, "大转盘", var2);
            if (var3 > 0) {
               this.persistSilent(var4);
            }
            break label65;
         } catch (Throwable var9) {
            if (var1) {
               Toast.makeText(this, "保存失败：" + var9.getClass().getSimpleName(), 1).show();
            }

            var5 = 0;
         } finally {
            this.bagCommitQuiet = false;
         }

         return var5;
      }

      if (var1) {
         String var17;
         if (var3 == 0) {
            var17 = "✅ 背包配置已保存";
         } else if (var2.length() > 34) {
            var17 = "✅ 已保存 " + var3 + " 项背包数量";
         } else {
            var17 = "✅ 已保存 " + var3 + " 项：" + var2;
         }

         Toast.makeText(this, var17, 1).show();
      }

      return var3;
   }

   private int bagCommitOne(JSONObject var1, String var2, EditText var3, String var4, StringBuilder var5) throws JSONException {
      if (var3 == null) {
         return 0;
      } else {
         int var6 = bagNumOf(var3);
         var3.setText(String.valueOf(var6));
         if (var1.optInt(var2, 0) == var6) {
            return 0;
         } else {
            var1.put(var2, var6);
            if (var5.length() > 0) {
               var5.append('、');
            }

            var5.append(var4).append(' ').append(var6);
            return 1;
         }
      }
   }

   private void clearInputFocusAndKeyboard() {
      try {
         InputMethodManager var1 = (InputMethodManager)this.getSystemService("input_method");
         View var2 = this.getCurrentFocus();
         if (var1 != null) {
            if (var2 != null) {
               var1.hideSoftInputFromWindow(var2.getWindowToken(), 0);
            }

            View var3 = this.getWindow() == null ? null : this.getWindow().getDecorView();
            if (var3 != null) {
               var1.hideSoftInputFromWindow(var3.getWindowToken(), 0);
            }
         }

         if (var2 != null) {
            var2.clearFocus();
         }
      } catch (Throwable var4) {
      }
   }

   private void installFeedCols(JSONObject var1) {
      this.etFeedCols.setText(String.valueOf(clampCols(var1.optInt("feedCols", 3))));
      this.swFeedCols.setOnSilent(var1.optBoolean("feedColsOn", false));
      this.swFeedCols
         .setOnToggleListener(
            new LiquidSwitch.OnToggleListener() {
               @Override
               public void onToggle(boolean var1) {
                  int var2 = MainActivity.clampCols(MainActivity.parseCols(MainActivity.this.etFeedCols));
                  MainActivity.this.etFeedCols.setText(String.valueOf(var2));
                  MainActivity.this.put("feedCols", var2);
                  MainActivity.this.put("feedColsOn", var1);
                  Toast.makeText(
                        MainActivity.this,
                        var1
                           ? "\ud83d\uddbc 已开启，商品 "
                              + var2
                              + " 列展示（重启闲鱼生效）"
                              + (var2 >= 3 && MainActivity.clampPct(MainActivity.parsePct(MainActivity.this.etUiScale)) < 80 ? "｜3 列建议 UI 比例 80~100" : "")
                           : "\ud83d\uddbc 已关闭，恢复双列",
                        0
                     )
                     .show();
               }
            }
         );
      this.etFeedCols.setOnFocusChangeListener(new OnFocusChangeListener() {
         public void onFocusChange(View var1, boolean var2) {
            if (!var2) {
               int var3 = MainActivity.clampCols(MainActivity.parseCols(MainActivity.this.etFeedCols));
               MainActivity.this.etFeedCols.setText(String.valueOf(var3));
               MainActivity.this.put("feedCols", var3);
            }
         }
      });
   }

   private static int parseCols(EditText var0) {
      try {
         String var1 = var0.getText().toString().trim();
         return var1.isEmpty() ? 3 : Integer.parseInt(var1);
      } catch (Throwable var2) {
         return 3;
      }
   }

   private static int clampCols(int var0) {
      if (var0 < 2) {
         var0 = 2;
      }

      if (var0 > 4) {
         var0 = 4;
      }

      return var0;
   }

   private void installUiScale(JSONObject var1) {
      this.etUiScale.setText(String.valueOf(clampPct(var1.optInt("uiScalePercent", 80))));
      this.swUiScale.setOnSilent(var1.optBoolean("uiScaleOn", false));
      this.swUiScale
         .setOnToggleListener(
            new LiquidSwitch.OnToggleListener() {
               @Override
               public void onToggle(boolean var1) {
                  int var2 = MainActivity.clampPct(MainActivity.parsePct(MainActivity.this.etUiScale));
                  MainActivity.this.etUiScale.setText(String.valueOf(var2));
                  MainActivity.this.put("uiScalePercent", var2);
                  MainActivity.this.put("uiScaleOn", var1);
                  Toast.makeText(
                        MainActivity.this,
                        var1 ? "\ud83d\uddbc 已开启，" + var2 + "% 缩放（重启闲鱼生效）" + (var2 < 80 ? "｜3 列建议 80~100" : "") : "\ud83d\uddbc 已关闭，恢复原始显示",
                        0
                     )
                     .show();
               }
            }
         );
      this.etUiScale.setOnFocusChangeListener(new OnFocusChangeListener() {
         public void onFocusChange(View var1, boolean var2) {
            if (!var2) {
               int var3 = MainActivity.clampPct(MainActivity.parsePct(MainActivity.this.etUiScale));
               MainActivity.this.etUiScale.setText(String.valueOf(var3));
               MainActivity.this.put("uiScalePercent", var3);
            }
         }
      });
   }

   private static int parsePct(EditText var0) {
      try {
         String var1 = var0.getText().toString().trim();
         return var1.isEmpty() ? 80 : Integer.parseInt(var1);
      } catch (Throwable var2) {
         return 80;
      }
   }

   private static int clampPct(int var0) {
      if (var0 < 40) {
         var0 = 40;
      }

      if (var0 > 100) {
         var0 = 100;
      }

      return var0;
   }

   private void commitNumberInputs() {
      try {
         if (this.etFeedCols != null) {
            int var1 = clampCols(parseCols(this.etFeedCols));
            this.etFeedCols.setText(String.valueOf(var1));
            if (this.currentConfig().optInt("feedCols", 3) != var1) {
               this.put("feedCols", var1);
            }
         }
      } catch (Throwable var4) {
      }

      try {
         if (this.etUiScale != null) {
            int var5 = clampPct(parsePct(this.etUiScale));
            this.etUiScale.setText(String.valueOf(var5));
            if (this.currentConfig().optInt("uiScalePercent", 80) != var5) {
               this.put("uiScalePercent", var5);
            }
         }
      } catch (Throwable var3) {
      }

      try {
         this.saveBagConfig(false);
      } catch (Throwable var2) {
      }
   }

   private void kickRun(final String var1) {
      new Thread(new Runnable() {
         @Override
         public void run() {
            final String var1x = MainActivity.socketCmd("RUN");
            MainActivity.this.runUi(new Runnable() {
               @Override
               public void run() {
                  boolean var1xx = var1x != null && var1x.contains("OK");
                  Toast.makeText(MainActivity.this, var1xx ? "\ud83c\udf92 已开始执行，" + var1 + " 将自动使用" : "暂未连通闲鱼（打开闲鱼后将自动执行）", 0).show();
               }
            });
         }
      }, "rh-bag-kick").start();
   }

   private void applyLauncherIcon(boolean var1) {
      try {
         this.getPackageManager().setComponentEnabledSetting(new ComponentName(this, "com.piggyhand.fish.LauncherAlias"), var1 ? 2 : 1, 1);
         Log.i("RhIcon", "[icon] launcher alias hidden=" + var1);
      } catch (Throwable var3) {
         Log.e("RhIcon", "[icon] setComponentEnabledSetting FAIL", var3);
      }
   }

   private void put(String var1, Object var2) {
      try {
         JSONObject var3 = this.currentConfig();
         var3.put(var1, var2);
         if (this.etPattern != null && this.etMax != null) {
            String var4 = this.etPattern.getText().toString().trim();
            var3.put("scoutPattern", var4.isEmpty() ? "reward|bonus|coin" : var4);

            try {
               var3.put("maxScoutMethods", Integer.parseInt(this.etMax.getText().toString().trim()));
            } catch (Throwable var6) {
               var3.put("maxScoutMethods", 400);
            }
         }

         this.persistSilent(var3);
      } catch (Throwable var7) {
      }
   }

   private void persistSilent(JSONObject var1) {
      SharedPreferences var2 = this.getSharedPreferences("config", 0);
      var2.edit().putString("config_json", var1.toString()).apply();
      this.openPrefsPerm();
      this.syncSdcardConfig(var1);
      this.dirty = true;
   }

   private void syncSdcardConfig(final JSONObject var1) {
      new Thread(new Runnable() {
         @Override
         public void run() {
            int var1x = -1;
            synchronized (MainActivity.CFG_SYNC_LOCK) {
               try {
                  String var3 = ConfigManager.fishExternal("files");
                  String var4 = ConfigManager.overridePath();
                  Process var5 = Runtime.getRuntime().exec(new String[]{"su", "-c", "mkdir -p '" + var3 + "' && cat > '" + var4 + "'"});
                  OutputStream var6 = var5.getOutputStream();
                  var6.write(var1.toString().getBytes("UTF-8"));
                  var6.flush();
                  var6.close();
                  var1x = var5.waitFor();
                  InputStream var7 = var5.getErrorStream();
                  byte[] var8 = new byte[var7.available()];
                  int var9 = var7.read(var8);
                  Log.i("RhSync", "override config written, exit=" + var1x + (var9 > 0 ? " err=" + new String(var8, 0, var9) : ""));
                  var5.destroy();
               } catch (Throwable var11) {
                  Log.e("RhSync", "su write fail", var11);
               }
            }
         }
      }, "rh-cfg-sync").start();
   }

   private void openPrefsPerm() {
      try {
         File var1 = new File(this.getApplicationInfo().dataDir, "shared_prefs");
         var1.setExecutable(true, false);
         var1.setReadable(true, false);
         File var2 = new File(var1, "config.xml");
         var2.setReadable(true, false);
      } catch (Throwable var3) {
      }
   }

   protected void onPause() {
      this.stopCoinUiLoop();
      this.commitNumberInputs();
      if (this.dirty) {
         this.dirty = false;
         Toast.makeText(this, "配置已保存 · 重启闲鱼生效", 0).show();
      }

      super.onPause();
   }

   private void refreshLog() {
      this.tvReport.setText("加载中…");
      this.pollReport(0);
   }

   private void pollReport(final int var1) {
      if (var1 < 3) {
         this.tvReport
            .postDelayed(
               new Runnable() {
                  @Override
                  public void run() {
                     new Thread(
                           new Runnable() {
                              @Override
                              public void run() {
                                 final MainActivity.ReadResult var1x = MainActivity.tryReadReport();
                                 MainActivity.this.runOnUiThread(
                                    new Runnable() {
                                       @Override
                                       public void run() {
                                          if (var1x.text != null) {
                                             MainActivity.this.tvReport
                                                .setText(
                                                   "· 读取通道: "
                                                      + var1x.via
                                                      + "\n· 闲鱼 "
                                                      + FishVer.label(MainActivity.this)
                                                      + "\n\n"
                                                      + var1x.text
                                                );
                                          } else if (var1 >= 2) {
                                             MainActivity.this.tvReport
                                                .setText(
                                                   "闲鱼版本：" + FishVer.label(MainActivity.this) + "\n\n"
                                                      + var1x.diag
                                                      + "\n\n解决步骤：\n1. root 授权：打开 Magisk / APatch → 超级用户 → 允许「咸鱼小猪手」，回本页点「刷新报告」\n2. MIUI 联网放行：安全中心 → 应用管理 → 咸鱼小猪手 → 联网控制 → 允许 WLAN 与数据\n3. 保持闲鱼在后台运行（报告服务随闲鱼进程启动，端口按用户自动分配）\n4. 最近一次钓鱼结果也会推送在通知栏"
                                                );
                                          } else {
                                             MainActivity.this.pollReport(var1 + 1);
                                          }
                                       }
                                    }
                                 );
                              }
                           },
                           "rh-read-report"
                        )
                        .start();
                  }
               },
               var1 == 0 ? 400L : 1500L
            );
      }
   }

   private static MainActivity.ReadResult tryReadReport() {
      // v1.2.9：报告迁到持久区 files/（宿主的 cache/ 冷启动会被清），cache 路径仅作旧版兜底
      String var4 = ConfigManager.fishExternal("files") + "/rh_tasks_result.txt";
      String var0 = ConfigManager.fishExternal("cache") + "/rh_tasks_result.txt";
      MainActivity.ReadResult var1 = new MainActivity.ReadResult();
      StringBuilder var2 = new StringBuilder("无法读取钓鱼报告\n\n逐通道诊断：\n");
      String[] var3 = readPlain(var4);
      if (var3[0] != null) {
         var1.text = var3[0];
         var1.via = "直读文件 files";
         return var1;
      }

      var2.append("· 直读 files：").append(var3[1]).append('\n');
      var3 = readPlain(var0);
      if (var3[0] != null) {
         var1.text = var3[0];
         var1.via = "直读文件 cache";
         return var1;
      }

      var2.append("· 直读 cache：").append(var3[1]).append('\n');
      var3 = readViaSocket();
      if (var3[0] != null) {
         var1.text = var3[0];
         var1.via = "socket (127.0.0.1:" + ConfigManager.reportPort() + ")";
         return var1;
      }

      var2.append("· socket：").append(var3[1]).append('\n');
      var3 = readViaSu(var4);
      if (var3[0] != null) {
         var1.text = var3[0];
         var1.via = "su (root) files";
         return var1;
      }

      var2.append("· su files：").append(var3[1]).append('\n');
      var3 = readViaSu(var0);
      if (var3[0] != null) {
         var1.text = var3[0];
         var1.via = "su (root) cache";
         return var1;
      }

      var2.append("· su cache：").append(var3[1]).append('\n');
      var1.diag = var2.toString();
      return var1;
   }

   private static String socketCmd(String var0) {
      Socket var1 = null;

      Object var3;
      try {
         var1 = new Socket();
         var1.connect(new InetSocketAddress("127.0.0.1", ConfigManager.reportPort()), 2500);
         var1.setSoTimeout(3000);
         OutputStream var2 = var1.getOutputStream();
         var2.write((var0 + "\n").getBytes("UTF-8"));
         var2.flush();
         return readAll(var1.getInputStream());
      } catch (Throwable var13) {
         var3 = null;
      } finally {
         try {
            if (var1 != null) {
               var1.close();
            }
         } catch (Throwable var12) {
         }
      }

      return (String)var3;
   }

   /**
    * v1.5.3 清标记 + 重启闲鱼执行链（后台线程调用）：
    * ① socket CLEAR_MARKS（闲鱼进程自己清 LVL 内存态 + 删标记文件，root 无关；同步等回显——
    *    force-stop 会杀掉 socket 服务端，必须先拿完整回显再杀，否则命令可能没执行完）；
    * ② su force-stop（杀主进程+全部子进程+定时任务，冷启动路径与覆盖装机验证一致）；
    * ③ sleep 1.5s 等 force-stop 落定；
    * ④ monkey 拉起（无 root 也可发）。拉起后引擎波次自动重跑，无需用户操作。
    * root 不可用时：标记已清，降级提示手动杀进程重启。
    */
   private void execClearMarksAndRestart() {
      // ① 清标记（同步回显）
      final String marks = socketCmd("CLEAR_MARKS");
      final boolean cleared = marks != null && marks.contains("DONE @");
      runOnUiThread(new Runnable() {
         public void run() {
            Toast.makeText(MainActivity.this,
               cleared ? "标记已清除，正在重启闲鱼…" : "标记清除失败：闲鱼未启动或通道不通（先打开一次闲鱼）",
               Toast.LENGTH_LONG).show();
         }
      });
      if (!cleared) return;
      // ② root 杀进程
      String[] stop = suExecDetail("am force-stop com.taobao.idlefish", 8);
      if (stop[0] == null) {
         toastMain("标记已清，但自动重启失败（" + stop[1] + "）——请手动杀掉闲鱼后重新打开");
         return;
      }
      // ③ 落定间隔
      try {
         Thread.sleep(1500);
      } catch (InterruptedException e) {
         return;
      }
      // ④ 重新拉起
      suExecDetail("monkey -p com.taobao.idlefish -c android.intent.category.LAUNCHER 1", 10);
      toastMain("闲鱼已重启，引擎将在 40s~240s 波次自动重跑今日任务");
   }

   private void toastMain(final String msg) {
      runOnUiThread(new Runnable() {
         public void run() {
            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
         }
      });
   }

   /**
    * v1.5.3 root 执行一条命令：返回 {stdout, error}；stdout 非空视为成功。
    * 与既有 suExec(String,int)（返回 stdout、null=失败）的区别：force-stop 等命令
    * 成功时 stdout 为空串，旧实现无法区分「成功无输出」与「失败」——本版带错误详情。
    * 超时按失败返回（root 管理器授权弹窗未确认时的典型态，与 readViaSu 同款处理）。
    */
   private static String[] suExecDetail(String cmd, int timeoutSec) {
      Process p = null;
      try {
         p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
         boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
         if (!done) {
            p.destroy();
            return new String[]{null, "授权超时（root 管理器未放行咸鱼小猪手——去 Magisk/APatch 超级用户里允许）"};
         }
         String out = readAll(p.getInputStream());
         String err = readAll(p.getErrorStream());
         int code;
         try {
            code = p.exitValue();
         } catch (Throwable t) {
            code = -1;
         }
         if (code != 0 && out.length() == 0) {
            String e = err == null ? "" : err.trim();
            return new String[]{null, e.length() > 0 ? e : "exit " + code};
         }
         return new String[]{out, null};
      } catch (Throwable t) {
         return new String[]{null, t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "")};
      } finally {
         try {
            if (p != null) p.destroy();
         } catch (Throwable ignored) {
         }
      }
   }

   private static String[] readViaSocket() {
      Socket var0 = null;

      String[] var2;
      try {
         var0 = new Socket();
         var0.connect(new InetSocketAddress("127.0.0.1", ConfigManager.reportPort()), 2500);
         var0.setSoTimeout(3000);
         OutputStream var1 = var0.getOutputStream();
         var1.write("REPORT\n".getBytes("UTF-8"));
         var1.flush();
         String var23 = readAll(var0.getInputStream());
         return var23.length() > 0 ? new String[]{var23, null} : new String[]{null, "服务返回空（钓鱼报告尚未生成）"};
      } catch (SocketTimeoutException var17) {
         return new String[]{null, "连接超时（闲鱼进程可能被杀，或 MIUI 联网管控拦截了本机回环）"};
      } catch (ConnectException var18) {
         return new String[]{null, "连接被拒（端口未监听，闲鱼未启动或引擎未加载）"};
      } catch (Throwable var19) {
         var2 = new String[]{null, "失败: " + var19.getClass().getSimpleName()};
      } finally {
         try {
            if (var0 != null) {
               var0.close();
            }
         } catch (Throwable var16) {
         }
      }

      return var2;
   }

   private static String[] readPlain(String var0) {
      try {
         File var1 = new File(var0);
         if (!var1.exists()) {
            return new String[]{null, "文件不存在（先在闲鱼里跑一轮任务）"};
         } else if (!var1.canRead()) {
            return new String[]{null, "无读取权限（Android 11+ 沙盒隔离，正常现象，走后续通道）"};
         } else {
            String var2 = readAll(new FileInputStream(var1));
            return var2.length() > 0 ? new String[]{var2, null} : new String[]{null, "文件为空"};
         }
      } catch (Throwable var3) {
         return new String[]{null, "读取异常: " + var3.getClass().getSimpleName()};
      }
   }

   private static String[] readViaSu(String var0) {
      Process var1 = null;

      try {
         var1 = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + var0});
         boolean var2 = var1.waitFor(10L, TimeUnit.SECONDS);
         if (!var2) {
            var1.destroy();
            return new String[]{null, "等待授权超时（root 管理器弹窗未确认——打开 Magisk / APatch → 超级用户 → 允许咸鱼小猪手）"};
         } else {
            String var3 = readAll(var1.getInputStream());
            String var4 = readAll(var1.getErrorStream());
            if (var3.length() > 0) {
               return new String[]{var3, null};
            } else {
               int var5;
               try {
                  var5 = var1.exitValue();
               } catch (Throwable var8) {
                  var5 = -1;
               }

               if (var5 != 0) {
                  String var6 = var4 == null ? "" : var4.trim();
                  if (var6.contains("No such file")) {
                     return new String[]{null, "命令失败：报告文件不存在（先在闲鱼里跑一轮任务）"};
                  } else {
                     return !var6.contains("not in sudoers") && !var6.contains("denied") && !var6.contains("denied by")
                        ? new String[]{null, "执行失败 (exit " + var5 + (var6.length() > 0 ? ": " + var6 : "") + ")"}
                        : new String[]{null, "授权被拒 (exit " + var5 + "): " + var6};
                  }
               } else {
                  return new String[]{null, var4 != null && var4.length() > 0 ? "失败: " + var4.trim() : "无输出（报告文件不存在？）"};
               }
            }
         }
      } catch (IOException var9) {
         return new String[]{null, "su 不存在（设备未 root，或 root 管理器未提供 su）"};
      } catch (Throwable var10) {
         try {
            if (var1 != null) {
               var1.destroy();
            }
         } catch (Throwable var7) {
         }

         return new String[]{null, "失败: " + var10.getClass().getSimpleName()};
      }
   }

   private static String readAll(InputStream var0) throws IOException {
      BufferedReader var1 = new BufferedReader(new InputStreamReader(var0));
      StringBuilder var2 = new StringBuilder();

      String var3;
      while ((var3 = var1.readLine()) != null) {
         var2.append(var3).append('\n');
      }

      var1.close();
      return var2.toString();
   }

   private static boolean hasRootAccess() {
      Process var0 = null;

      try {
         var0 = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
         boolean var1 = var0.waitFor(5L, TimeUnit.SECONDS);
         if (!var1) {
            var0.destroy();
            return false;
         } else {
            String var2 = readAll(var0.getInputStream());
            return var2.contains("uid=0");
         }
      } catch (Throwable var4) {
         try {
            if (var0 != null) {
               var0.destroy();
            }
         } catch (Throwable var3) {
         }

         return false;
      }
   }

   protected void onDestroy() {
      try {
         this.unregisterReceiver(this.resultReceiver);
      } catch (Throwable var2) {
      }

      super.onDestroy();
   }

   private int dp(int var1) {
      return (int)((float)var1 * this.getResources().getDisplayMetrics().density);
   }

   private static class ReadResult {
      String text;
      String via;
      String diag;
   }
}
