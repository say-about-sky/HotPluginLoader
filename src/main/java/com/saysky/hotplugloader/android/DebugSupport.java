package com.saysky.hotplugloader.android;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Debug;

/**
 * 【JDWP 远程调试支持】(b44)
 *
 * <p>把游戏进程变成"可调状态"的两件事:</p>
 * <ol>
 *   <li>启动最早期把 {@code debuggable / connected / waitMarker} 状态写日志 ——
 *       attach 不上时第一眼看它(不 debuggable 的进程根本无法 attach);</li>
 *   <li>存在等待标记 {@code hotplugins/.wait_debugger} 时,在<b>一切加载器逻辑之前</b>轮询等待
 *       调试器接入(默认上限 180 秒;文件内容可写秒数,如 {@code 300};删除该文件 = 取消等待)——
 *       这样冷加载预载、资源树 attach、附加包 init 这些"启动最早期的代码"也能断到。</li>
 * </ol>
 *
 * <p>使用前提:测试 APK 的 manifest 加 {@code android:debuggable="true"} 并重签名(发布包勿带)。
 * 连接 = {@code adb forward tcp:8700 jdwp:<pid>} 后 IDE(Remote JVM Debug)attach 或
 * {@code jdb -attach localhost:8700};断点按「类名+行号」,附加包源码工程直接可断
 * (D8 转换保留行号/源文件名;建议 javac 带 {@code -g} 让局部变量可见)。详见「调试指南_JDWP.md」。</p>
 *
 * <p>本类只被替换版启动类调用;桌面端永不被加载。所有方法不抛异常。</p>
 */
public final class DebugSupport {

    /** 等待标记文件(hotplugins 目录下;删除即取消等待)。 */
    public static final String WAIT_MARKER = ".wait_debugger";
    /** 默认等待上限(毫秒)= 180 秒。 */
    public static final long DEFAULT_WAIT_MS = 180_000L;

    private DebugSupport() {
    }

    /**
     * 检查调试器状态;存在等待标记时轮询等待接入(不抛异常)。
     *
     * @return 一行日志文本(内容随接入结果:debuggable/connected/waitMarker/等待耗时)
     */
    public static String checkAndWait(Activity activity) {
        try {
            boolean debuggable = false;
            File dir = null;
            if (activity != null) {
                ApplicationInfo info = activity.getApplicationInfo();
                debuggable = info != null && (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                dir = activity.getExternalFilesDir("hotplugins");
            }
            File marker = dir == null ? null : new File(dir, WAIT_MARKER);
            boolean wait = marker != null && marker.exists();
            boolean connected = Debug.isDebuggerConnected();
            StringBuilder sb = new StringBuilder();
            sb.append("debuggable=").append(debuggable)
                    .append(" connected=").append(connected)
                    .append(" waitMarker=").append(wait);
            if (!wait || connected) {
                return sb.toString();
            }
            long budgetMs = DEFAULT_WAIT_MS;
            int sec = readSeconds(marker);
            if (sec > 0) {
                budgetMs = Math.min(sec, 3600) * 1000L;
            }
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < budgetMs) {
                if (Debug.isDebuggerConnected()) {
                    break;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    break;
                }
            }
            sb.append(" -> waited ").append(System.currentTimeMillis() - t0)
                    .append("ms, connected=").append(Debug.isDebuggerConnected())
                    .append(" (budget ").append(budgetMs / 1000).append("s)");
            return sb.toString();
        } catch (Throwable t) {
            return "debug check failed: " + t;
        }
    }

    /** 读取等待秒数(标记文件首行整数;无/非法返回 -1)。 */
    private static int readSeconds(File marker) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(marker), "UTF-8"));
            try {
                String line = r.readLine();
                if (line != null) {
                    return Integer.parseInt(line.trim());
                }
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
