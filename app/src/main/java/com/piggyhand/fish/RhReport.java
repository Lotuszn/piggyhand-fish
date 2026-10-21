package com.piggyhand.fish;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 带时间戳的报告拼装器（1.1.3）：替换引擎报告里的 StringBuilder。
 *
 * 设计：
 *  - 每行行首自动插入「HH:mm:ss 」前缀；同一行多次 append 只插一次（行首判断）
 *  - 纯换行（空行）不加前缀，避免出现只有时间戳的空行
 *  - append 返回 this，与 StringBuilder 的链式写法完全兼容；只用 append/toString 两个 API
 *  - 用途：让日志界面/跑批报告能看出每步执行时刻与间隔（排查节奏、卡顿、误判「停了」）
 */
public final class RhReport {

    private final StringBuilder sb = new StringBuilder();
    private final SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss", Locale.US);
    /** true = 当前位置处于行首（下一个非换行字符前要先插时间戳） */
    private boolean atLineStart = true;

    public RhReport append(Object o) {
        String s = o == null ? "null" : String.valueOf(o);
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (atLineStart && ch != '\n' && ch != '\r') {
                sb.append(df.format(new Date())).append(' ');
                atLineStart = false;
            }
            sb.append(ch);
            if (ch == '\n') atLineStart = true;
        }
        return this;
    }

    public int length() {
        return sb.length();
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
