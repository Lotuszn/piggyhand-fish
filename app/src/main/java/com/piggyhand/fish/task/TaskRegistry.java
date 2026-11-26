package com.piggyhand.fish.task;

import android.content.Context;

import com.piggyhand.fish.core.EngineHook;
import com.piggyhand.fish.core.RhFileLog;
import com.piggyhand.fish.core.VdKeepAlive;

import de.robv.android.xposed.XposedBridge;

/**
 * 任务模块注册中心 + 引擎事件分发（Phase 2b 依赖倒置 2026-11-15）。
 * 实现 core.EngineHook：FishMtopHook 只认识本接口，全部 task 类调用收拢在这里——
 * MainHook（root 装配层）执行 FishMtopHook.setEngineHook(TaskRegistry.get()) 完成装配。
 * 新增任务模块：在这里加一行注册/分支即可，hook 包零改动。
 */
public final class TaskRegistry implements EngineHook {

    private static final TaskRegistry INSTANCE = new TaskRegistry();

    public static TaskRegistry get() {
        return INSTANCE;
    }

    private TaskRegistry() {
    }

    private static void log(String msg) {
        XposedBridge.log("[RewardHook] " + msg);
        RhFileLog.write(msg);
    }

    // ---------------- EngineHook：引擎就绪 ----------------

    @Override
    public void onEngineReady(Context appCtx) {
        // 虚拟屏保活：闲鱼退后台 ~15s 即被 HyperOS 冻结（2026-11-16 真机基线），
        // 引擎就绪时按 cfg(vdKeepAliveOn) 初值立即挂窗抬 procstate，先于一切自启线
        try {
            VdKeepAlive.startIfEnabled(appCtx);
        } catch (Throwable t) {
            log("[vdka] fail: " + t);
        }
        // 原 FishMtopHook 418~439 就绪自启段原样迁入（时序零变化）
        try {
            FishTaskAuto.autoRunDaily(appCtx);
        } catch (Throwable t) {
            log("[autorun] tasks fail: " + t);
        }
        try {
            RhPondTask.autoRunDaily(appCtx);
        } catch (Throwable t) {
            log("[autorun] pond fail: " + t);
        }
        try {
            RhPondTask.autoRunFeedDaily(appCtx);
        } catch (Throwable t) {
            log("[autorun] pondfeed fail: " + t);
        }
        try {
            RhOlivertask.autoRunDaily(appCtx);
        } catch (Throwable t) {
            log("[autorun] oliver fail: " + t);
        }
        try {
            RhGameTask.autoRunDaily(appCtx);
        } catch (Throwable t) {
            log("[autorun] game fail: " + t);
        }
        try {
            // （避开 40~50s 那波引擎，防 center.query 共用响应文件串包；标识 0/1/2 停止条件见 RhCoinTask）
            RhCoinTask.autoRunDaily(appCtx);
        } catch (Throwable t) {
            log("[autorun] coin fail: " + t);
        }
        try {
            RhCashClaim.autoRunDaily(appCtx);
        } catch (Throwable t) {
            log("[autorun] cash fail: " + t);
        }
        try {
            RhCashJoin.autoRunDaily(appCtx);
        } catch (Throwable t) {
            log("[autorun] join fail: " + t);
        }
        try {
            // 2026-11-26 现金签到：scene.* 任务面板全量推进 + 红包开到 0
            RhCashSignTask.autoRunDaily(appCtx);
        } catch (Throwable t) {
            log("[autorun] cashsign fail: " + t);
        }
        try {
            RhOlivertask.autoRunDailyExp(appCtx);
        } catch (Throwable t) {
            log("[autorun] oliver-exp fail: " + t);
        }
        try {
            CoinLogger.startCoinBalanceLogger(appCtx);
        } catch (Throwable t) {
            log("[autorun] coinlogger fail: " + t);
        }
    }

    // ---------------- EngineHook：命令分发 ----------------

    @Override
    public String onCommand(String cmd, Context ctx, String arg) {
        if (cmd == null || ctx == null) return null;
        switch (cmd) {
            case "VD_ON": // v1.5.6 后台保活：UI 开关实时建屏（不回读 cfg，避免 override 异步写竞态）
                VdKeepAlive.applyEnabled(ctx, true);
                return "OK vd-on";
            case "VD_OFF": // v1.5.6 后台保活：UI 开关实时拆窗
                VdKeepAlive.applyEnabled(ctx, false);
                return "OK vd-off";
            case "LAUNCH_TASKS": // MTOP_CALL 广播 __RUN_TASKS__
                FishTaskAuto.launch(ctx, arg);
                log("[tasks] engine launched via broadcast");
                return null;
            case "JOIN": // v1.2.9 手动投币一轮（后台执行，结果 JOIN_REPORT 读）
                async("rh-cash-join-one", "[join] manual", () -> RhCashJoin.run(ctx));
                return "OK started";
            case "CASHSIGN": // 2026-11-26 手动推进一轮「更多红包」任务（结果 CASHSIGN_REPORT 读）
                async("rh-cashsign", "[cashsign] manual claim", () -> RhCashSignTask.runClaim(ctx));
                return "OK started";
            case "CASHOPEN": // 2026-11-26 手动开红包到归零（结果 CASHOPEN_REPORT 读）
                async("rh-cashopen", "[cashsign] manual open", () -> RhCashSignTask.runOpen(ctx));
                return "OK started";
            case "CASHSIGN_STATE": // 面板状态条：SIGN|... #OPEN|...
                try {
                    return "SIGN|" + RhCashSignTask.stateText(ctx, false)
                            + "#OPEN|" + RhCashSignTask.stateText(ctx, true);
                } catch (Throwable t) {
                    return "NONE";
                }
            case "CASH_STATE": // v1.2.9 两组状态合并回传：CLAIM|...#JOIN|...
                try {
                    return "CLAIM|" + RhCashClaim.stateText(ctx)
                            + "#JOIN|" + RhCashJoin.stateText(ctx);
                } catch (Throwable t) {
                    return "NONE";
                }
            case "CLAIM": // v1.2.9 手动开红包一轮（结果 CASH_REPORT 读）
                async("rh-cash", "[cash] claim", () -> RhCashClaim.run(ctx));
                return "OK started";
            case "CYCLE": // v1.2.10 手动跑一次圈数挑战查档+领取
                async("rh-cycle", "[cycle] manual run", () -> FishTaskAuto.runCycleClaimOnce(ctx));
                return "OK started";
            case "CLEAR_MARKS": // v1.5.3 清除全部当日任务标记（同步执行并回显，引擎运行中内部拒绝）
                return FishTaskAuto.clearDailyMarks();
            case "GAME": // v1.2.9 手动跑一轮游戏任务
                async("rh-game", "[game] engine", () -> RhGameTask.run(ctx));
                return "OK started";
            case "COINEX": // v1.4.9 手动执行一次兑换检查（manual=true）
                async("rh-coinex", "[exch] manual run", () -> RhCoinExchange.runCheck(ctx, true));
                return "OK started";
            case "COIN": // v1.4.6 手动跑一轮赚游戏币任务
                async("rh-coin", "[coin] engine", () -> RhCoinTask.run(ctx));
                return "OK started";
            case "PONDFEED": // v1.4.1 手动投喂
                async("rh-pondfeed", "[pondfeed] manual", () -> RhPondTask.runFeed(ctx));
                return "OK started";
            case "OLIVER_STATE": // v1.1.9 会员任务完成状态查询
                try {
                    return RhOlivertask.stateText(ctx);
                } catch (Throwable t) {
                    return "TODO";
                }
            case "OLIVER": // v1.1.9 会员任务引擎触发
                async("rh-oliver", "[oliver] engine", () -> RhOlivertask.run(ctx));
                return "OK started";
            case "POND": // v1.0.9 鱼塘任务引擎触发
                async("rh-pond", "[pond] engine", () -> RhPondTask.run(ctx));
                return "OK started";
            case "RUN": // 任务触发指令（绕过 MIUI 跨 App 广播拦截）：RUN / RUN {extras json}
                String extras = arg;
                if (extras != null && extras.isEmpty()) extras = null;
                FishTaskAuto.launch(ctx, extras);
                return "OK";
            default:
                return null;
        }
    }

    private interface VoidTask {
        void run();
    }

    /** 后台执行一轮手动命令（线程名与原 FishMtopHook 内保持一致，日志可读性不变） */
    private static void async(String threadName, String logTag, VoidTask task) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                    log(logTag + " done");
                } catch (Throwable t) {
                    log(logTag + " fail: " + t);
                }
            }
        }, threadName).start();
    }

    // ---------------- EngineHook：币值钩子 ----------------

    @Override
    public void onCoinLevel(String coinText) {
        try {
            long lv = JsonUtil.parseCoinNum(coinText);
            if (lv > 0 && com.piggyhand.fish.core.RhRuntime.appCtx != null) {
                CoinLogger.recordCoinBalance(com.piggyhand.fish.core.RhRuntime.appCtx, lv);
            }
        } catch (Throwable ignored) {
        }
    }
}
