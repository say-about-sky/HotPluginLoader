package com.saysky.hotplugloader;

import team.rainfall.finality.FinalityLogger;

/**
 * 永不抛出的日志包装。
 *
 * <p>注入到游戏逻辑里的代码必须绝对不抛异常,连日志本身失败也不行
 * (例如日志系统尚未初始化)。此类把一切异常/错误吞掉。</p>
 *
 * <p>从 v0.2.0 起,所有日志同时镜像写入附加包目录的 {@code hotloader.log}
 * (安卓上 loader.log 不存在,这是真机排障的主要通道)。</p>
 */
public final class SafeLog {

    private SafeLog() {
    }

    public static void e(String message, Throwable t) {
        if (!LoaderConfig.logEnabled()) {
            return;
        }
        try {
            FinalityLogger.error(message, t);
        } catch (Throwable ignored) {
        }
        // b19:热文件日志带堆栈(截断),安卓上这是唯一可靠通道
        HotFileLog.logError(message, t);
    }

    public static void i(String message) {
        if (!LoaderConfig.logEnabled()) {
            return;
        }
        try {
            FinalityLogger.info(message);
        } catch (Throwable ignored) {
        }
        HotFileLog.log("I " + message);
    }

    public static void d(String message) {
        if (!BuildInfo.DEBUG_OUTPUT || !LoaderConfig.logEnabled()) {
            return;
        }
        try {
            FinalityLogger.debug(message);
        } catch (Throwable ignored) {
        }
        HotFileLog.log("D " + message);
    }

    /** 详细日志(b37 起默认输出,开关已移除;仍受「开启日志」总开关与 DEBUG_OUTPUT 控制)。 */
    public static void v(String message) {
        d(message);
    }
}
