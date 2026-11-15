package com.piggyhand.fish.core;

import android.content.Context;

/**
 * 引擎事件回调接口（Phase 2b 依赖倒置 2026-11-15）。
 * hook 层（FishMtopHook）持有一个实现（task.TaskRegistry，MainHook 装配时注入），
 * 就绪/命令/币值三类事件经此接口下发——hook 包从此不 import task 包，末环砍除。
 * cmd 常量取值见 TaskRegistry。
 */
public interface EngineHook {

    /** 引擎就绪（mtop 通道可用、非渲染进程）：触发各模块每日自启 + 探针装配 */
    void onEngineReady(Context appCtx);

    /**
     * 广播/socket 命令分发（task 调用从 FishMtopHook 迁入 TaskRegistry）。
     * @param cmd 命令字：LAUNCH_TASKS / JOIN / CASH_STATE / CLAIM / CYCLE / CLEAR_MARKS /
     *            GAME / COINEX / COIN / PONDFEED / OLIVER_STATE / OLIVER / POND / RUN
     * @param arg 附加参数（RUN 的 extras JSON 等），可 null
     * @return 响应文本；异步启动类命令返回 "OK started"，未知命令返回 null
     */
    String onCommand(String cmd, Context ctx, String arg);

    /** 响应金币值钩子：mtop 响应里检出金币字段时回调（原 FishTaskAuto.parseCoinNum+recordCoinBalance） */
    void onCoinLevel(String coinText);
}
