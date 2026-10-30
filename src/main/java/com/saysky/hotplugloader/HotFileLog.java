package com.saysky.hotplugloader;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 宿主文件日志(真机可见):追加写入 {@code hotplugins/hotloader.log}。
 * 安卓上 loader.log 不存在,这是最主要的排障通道(用 MT 打开附加包目录即可看)。
 */
public final class HotFileLog {

    private static final String FILE_NAME = "hotloader.log";
    private static final long MAX_SIZE = 512 * 1024;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    /** b30:会话是否已由启动器准备过(安卓端「启动时刷新」已在最早时机执行,这里的 init 不再清 ——
     *  修复「启动早期的启动器/冷注入日志被首帧 init 清掉」)。 */
    private static volatile boolean sessionPrepared = false;

    /** 错误去重(b54):同一条错误 5s 内只写一次堆栈。 */
    private static String lastErrMessage;
    private static long lastErrAt;
    private static int lastErrRepeat;

    private HotFileLog() {
    }

    /** 启动器(安卓,onCreate 最早处)已完成「启动时刷新」处理时调用。 */
    public static void markSessionPrepared() {
        sessionPrepared = true;
    }

    /** 会话开始时调用:按「启动时刷新日志」清空(启动器已准备过则跳过,只做过大清理);过大也清空。 */
    public static void init() {
        try {
            File f = file();
            if ((!sessionPrepared && LoaderConfig.refreshLogOnStart())
                    || (f.exists() && f.length() > MAX_SIZE)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    public static File file() {
        return new File(LoaderPaths.pluginsDir(), FILE_NAME);
    }

    public static synchronized void log(String message) {
        if (!LoaderConfig.logEnabled()) {
            return;
        }
        write(message);
    }

    /** 无条件写文件(不做开关判断,由调用方决定是否写)。 */
    private static synchronized void write(String message) {
        try {
            FileWriter writer = new FileWriter(file(), true);
            try {
                writer.write(TIME.format(new Date()) + " " + message + "\n");
            } finally {
                writer.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 错误日志:消息 + <b>完整</b> Java 堆栈(所有帧 / 所有 cause / suppressed,不截断)。
     *
     * <p>仍受「开启日志」总开关控制(与其它日志一致)。
     * 旧版截断成「自身 12 帧 + 2 层 cause 各 6 帧」,排查 D8/R8 这类嵌套异常等于没用。</p>
     */
    public static synchronized void logError(String message, Throwable t) {
        if (!LoaderConfig.logEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        // 同一条错误 5 秒内重复(例如每帧 tick 失败)只记一行,避免日志被刷爆
        if (message != null && message.equals(lastErrMessage) && now - lastErrAt < 5000L) {
            lastErrRepeat++;
            lastErrAt = now;
            write("E " + message + "  (重复第 " + lastErrRepeat + " 次,堆栈省略)");
            return;
        }
        lastErrMessage = message;
        lastErrAt = now;
        lastErrRepeat = 0;
        StringBuilder sb = new StringBuilder("E ").append(message);
        appendFull(sb, t, "    ", new java.util.HashSet<Throwable>());
        write(sb.toString());
    }

    private static void appendFull(StringBuilder sb, Throwable t, String indent, java.util.Set<Throwable> seen) {
        if (t == null || !seen.add(t)) {
            return;
        }
        sb.append("\n").append(indent).append(t);
        StackTraceElement[] st = t.getStackTrace();
        if (st != null) {
            for (StackTraceElement e : st) {
                sb.append("\n").append(indent).append("  at ").append(e);
            }
        }
        Throwable[] suppressed = t.getSuppressed();
        if (suppressed != null) {
            for (Throwable s : suppressed) {
                appendFull(sb, s, indent + "Suppressed: ", seen);
            }
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            appendFull(sb, cause, indent + "Caused by: ", seen);
        }
    }
}
