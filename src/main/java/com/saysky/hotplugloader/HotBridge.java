package com.saysky.hotplugloader;

/**
 * 跨平台功能桥:所有「安卓专属能力」都经反射调用,保证桌面端对宿主类做
 * 校验/加载时不会碰到 android.* 引用(HotImporter/AndroidLauncher 只在安卓端被加载)。
 */
public final class HotBridge {

    private static final String TAG = "HotLoader/bridge";

    private HotBridge() {
    }

    /** 「导入附加包文件」按钮入口(桌面 = 提示手动放置)。 */
    public static void startImport() {
        if (!HotPluginHost.isAndroid()) {
            try {
                aoc.kingdoms.lukasz.jakowski.Game.menuManager.addToast(
                        "桌面端请直接把文件放入 hotplugins 目录");
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            Class.forName("com.saysky.hotplugloader.android.HotImporter")
                    .getMethod("startImportFromGame")
                    .invoke(null);
        } catch (Throwable t) {
            SafeLog.e(TAG + ": startImport failed", t);
        }
    }

    /**
     * 整进程重启入口(安卓;兜底用):重新进入游戏加载界面。
     * 「全部重载」正常走原位整层重载,仅当重载出现致命异常时才调用这里兜底;
     * 桌面 / 失败返回 false。
     */
    public static boolean restartGame() {
        if (!HotPluginHost.isAndroid()) {
            return false;
        }
        try {
            Object r = Class.forName("com.saysky.hotplugloader.android.HotRestart")
                    .getMethod("restartGameFromGame")
                    .invoke(null);
            return r instanceof Boolean && ((Boolean) r).booleanValue();
        } catch (Throwable t) {
            SafeLog.e(TAG + ": restartGame failed", t);
            return false;
        }
    }
}
