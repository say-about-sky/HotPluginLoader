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
     * 错误日志(b19):消息 + 堆栈(截断:自身 12 帧 + 2 层 cause 各 6 帧)。
     * 安卓上 hotloader.log 是唯一可靠通道,只写 t.toString() 定位不了问题。
     */
    public static synchronized void logError(String message, Throwable t) {
        if (!LoaderConfig.logEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder("E ").append(message);
        if (t != null) {
            sb.append("\n    ").append(t);
            appendFrames(sb, t.getStackTrace(), 12);
            Throwable cause = t.getCause();
            int guard = 0;
            while (cause != null && cause != t && guard++ < 2) {
                sb.append("\n    caused by: ").append(cause);
                appendFrames(sb, cause.getStackTrace(), 6);
                Throwable next = cause.getCause();
                cause = next == cause ? null : next;
            }
        }
        log(sb.toString());
    }

    private static void appendFrames(StringBuilder sb, StackTraceElement[] st, int max) {
        if (st == null) {
            return;
        }
        int n = Math.min(st.length, max);
        for (int i = 0; i < n; i++) {
            sb.append("\n      at ").append(st[i]);
        }
        if (st.length > n) {
            sb.append("\n      ... (").append(st.length - n).append(" more)");
        }
    }
}
