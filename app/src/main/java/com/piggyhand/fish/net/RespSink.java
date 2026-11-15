package com.piggyhand.fish.net;

/**
 * 响应/直调事件下沉接口（Phase 2a-2 依赖倒置 2026-11-15）。
 * hook 层（FishMtopHook）在启动时经 RespChannel.install 注册实现；
 * net 层（ApiDirect）只依赖本接口，不再 import hook 包——net↔hook 环砍除。
 */
public interface RespSink {

    /** ApiDirect 万能 callback / 类级 hook 捕获到的候选响应统一喂这里（内部有 MtopResponse 类名校验） */
    void feedRespCandidate(Object resp, String api);

    /** 直调 send 前登记：供 hook 层做兼容版自检（4s 内未出现在构建层则告警） */
    void noteDirectSent(String api);
}
