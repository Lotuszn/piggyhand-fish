package com.piggyhand.fish;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * 闲鱼自有高层协议栈直调通道：不走 mtopsdk 借壳，直接调闲鱼自己的高层协议栈
 *   XModuleCenter.moduleForProtocol(PApiContext) -> sender
 *   ApiProtocol.newInstance().apiNameAndVersion(api, ver).paramMap(map)
 *   sender.send(request, callback)
 * 登录态/签名/wua 全部由闲鱼自己的协议栈处理。
 * 响应捕获双通道：
 *   ① 全局 MtopFinishEvent 监听落盘（rh_resp_<api>.json）——主通道；
 *   ② v1.5.1 补：send 挂动态 Proxy 万能 callback（原实现 callback=null，
 *      部分 ROM/时序下 MtopLauncher 依赖 callback 才派发响应——2026-11-11 日志实证
 *      13:10/13:20/15:49 三轮直调请求进构建层但响应零到达）。callback 参数对象
 *      统一喂 FishMtopHook.feedRespCandidate，内部有 MtopResponse 校验，
 *      匹配即落盘，不匹配丢弃，安全。
 *
 * 调用链定位自闲鱼 7.25.31 dex 静态分析。
 */
public final class ApiDirect {

    private ApiDirect() {}

    /** 尝试直调；成功返回 true（响应由全局监听/Proxy callback 落盘），失败返回 false（调用方回落借壳） */
    public static boolean send(ClassLoader hostCl, String api, String version, String jsonParams) {
        try {
            /* 1. sender = XModuleCenter.moduleForProtocol(PApiContext.class) */
            Class<?> xmc = Reflect.findClass("com.taobao.idlefish.xmc.XModuleCenter", hostCl);
            Class<?> papi = Reflect.findClass("com.taobao.idlefish.protocol.net.PApiContext", hostCl);
            Object sender = Reflect.callStaticMethod(xmc, "moduleForProtocol", papi);
            if (sender == null) {
                log("[direct] moduleForProtocol -> null");
                return false;
            }

            /* 2. req = new ApiProtocol().apiNameAndVersion(api, version) */
            Class<?> apiCls = Reflect.findClass("com.taobao.idlefish.protocol.net.api.ApiProtocol", hostCl);
            Object apiObj = Reflect.newInstance(apiCls);
            Object req = null;
            for (Method m : apiCls.getMethods()) {
                if ("apiNameAndVersion".equals(m.getName()) && m.getParameterTypes().length == 2) {
                    req = m.invoke(apiObj, api, version);
                    break;
                }
            }
            if (req == null) {
                log("[direct] apiNameAndVersion not found on " + apiCls.getName());
                return false;
            }

            /* 3. req.paramMap(params)（链式，返回值可能是新对象）
             *    实测 coin.fishing.sold 带 data={} 会 FAIL_SYS_API_NOT_FOUNDED，
             *    无参则正常——因此参数为空时整段跳过不调 paramMap（请求不带 data）。 */
            Map<String, Object> params = toMap(jsonParams);
            if (!params.isEmpty()) {
                try {
                    for (Method m : req.getClass().getMethods()) {
                        if ("paramMap".equals(m.getName()) && m.getParameterTypes().length == 1
                                && Map.class.isAssignableFrom(m.getParameterTypes()[0])) {
                            Object r2 = m.invoke(req, params);
                            if (r2 != null) req = r2;
                            break;
                        }
                    }
                } catch (Throwable t) {
                    log("[direct] paramMap fail: " + t);
                }
            }

            /* 4. sender.send(req, callback)
             *    callback 不再传 null：第二参数是接口时用 Proxy 造万能监听，
             *    所有方法的所有参数都喂 feedRespCandidate（响应对象）。
             *    feedRespCandidate 有类名校验兜底，喂进非响应对象会静默丢弃。 */
            for (Method m : sender.getClass().getMethods()) {
                if ("send".equals(m.getName()) && m.getParameterTypes().length == 2) {
                    // 兼容版自检：先标记再发送——setApiName 在 send 内部同步触发，
                    // 若 send 之后才标记，清除逻辑永远追不上导致 WARN 恒误报（一加用户日志实证）
                    FishMtopHook.noteDirectSent(api);
                    Object cb = buildProxyCallback(hostCl, m.getParameterTypes()[1], api);
                    m.invoke(sender, req, cb);
                    log("[direct] sent: " + api + " v" + version + " via "
                            + sender.getClass().getName() + (cb != null ? " +proxy-cb" : ""));
                    return true;
                }
            }
            log("[direct] send method not found on " + sender.getClass().getName());
            return false;
        } catch (Throwable t) {
            log("[direct] FAIL: " + t);
            return false;
        }
    }

    /** ApiCallBack 类级 hook 武装标记（进程内一次性） */
    private static volatile boolean cbHookArmed = false;

    /**
     * v1.5.1 直调响应旁路：
     *  - callback 类型是接口 → Proxy 万能监听（当前 ROM 未命中，保留作兼容路径）；
     *  - callback 类型是具体类（MI9 实证 com.taobao.idlefish.protocol.net.ApiCallBack）
     *    → 对该类做一次性类级 hook（构造器 + 全部方法 after），所有参数喂
     *    FishMtopHook.feedRespCandidate（isMtopResp 校验兜底），send 仍传 null。
     *    这是响应旁路捕获的真正主路径：页面请求与直调请求的回调都经过 ApiCallBack。
     */
    private static Object buildProxyCallback(final ClassLoader hostCl, Class<?> cbCls, final String api) {
        try {
            if (cbCls == null) return null;
            if (cbCls.isInterface()) {
                return buildInterfaceProxy(hostCl, cbCls);
            }
            armCallbackClassHook(cbCls);
            return null;
        } catch (Throwable t) {
            log("[direct] callback capture setup fail: " + t);
            return null;
        }
    }

    /** 接口路径：Proxy 万能监听（所有方法所有参数喂 feedRespCandidate） */
    private static Object buildInterfaceProxy(final ClassLoader hostCl, Class<?> cbCls) {
        return Proxy.newProxyInstance(hostCl, new Class<?>[]{cbCls}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                try {
                    if (args != null) {
                        for (Object a : args) {
                            if (a != null) FishMtopHook.feedRespCandidate(a, "direct-proxy");
                        }
                    }
                } catch (Throwable ignored) {
                }
                // 回调方法若有返回值：boolean→false / int→0，其余 null（unboxing 安全）
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class) return Boolean.FALSE;
                if (rt == int.class) return Integer.valueOf(0);
                if (rt == long.class) return Long.valueOf(0L);
                return null;
            }
        });
    }

    /** 具体类路径：ApiCallBack 构造器 + 全部实例方法挂观察 hook（一次性，只读不改流程） */
    private static void armCallbackClassHook(final Class<?> cbCls) {
        if (cbHookArmed) return;
        synchronized (ApiDirect.class) {
            if (cbHookArmed) return;
            try {
                de.robv.android.xposed.XC_MethodHook feeder =
                        new de.robv.android.xposed.XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(
                                    de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
                                try {
                                    if (param.args == null) return;
                                    for (Object a : param.args) {
                                        if (a != null) FishMtopHook.feedRespCandidate(a, "direct-callback");
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        };
                Reflect.hookAllConstructors(cbCls, feeder);
                int mArmed = 0;
                for (Method m : cbCls.getDeclaredMethods()) {
                    try {
                        Reflect.hookAllMethods(cbCls, m.getName(), feeder);
                        mArmed++;
                    } catch (Throwable ignored) {
                    }
                }
                cbHookArmed = true;
                log("[direct] ApiCallBack class hook armed on " + cbCls.getName()
                        + " (" + mArmed + " methods)");
            } catch (Throwable t) {
                cbHookArmed = true; // 失败也置位防反复重挂刷屏
                log("[direct] ApiCallBack hook fail: " + t);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(String json) {
        Map<String, Object> out = new HashMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            org.json.JSONObject j = new org.json.JSONObject(json);
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = j.get(k);
                // org.json 的 Boolean/Integer 与 fastjson 参数兼容；JSONArray 等复杂值直接透传
                out.put(k, v);
            }
        } catch (Throwable t) {
            log("[direct] params json parse fail: " + t);
        }
        return out;
    }

    private static void log(String msg) {
        XposedBridge.log("[RewardHook] " + msg);
        RhFileLog.write(msg);
    }
}
