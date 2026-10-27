package com.saysky.hotplugloader;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;

/**
 * 加载器设置项(存 Gdx Preferences,随应用数据持久化)。
 */
public final class LoaderConfig {

    private static final String PREFS = "saysky_hotloader";

    private LoaderConfig() {
    }

    private static Preferences prefs() {
        return Gdx.app.getPreferences(PREFS);
    }

    public static boolean hotloadEnabled() {
        try {
            return prefs().getBoolean("hotload_enabled", true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setHotloadEnabled(boolean value) {
        try {
            prefs().putBoolean("hotload_enabled", value).flush();
        } catch (Throwable ignored) {
        }
    }

    public static boolean allowUntrusted() {
        try {
            return prefs().getBoolean("allow_untrusted", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setAllowUntrusted(boolean value) {
        try {
            prefs().putBoolean("allow_untrusted", value).flush();
        } catch (Throwable ignored) {
        }
    }

    /** 全局日志总开关(b20;b42 起默认关闭):关闭后宿主不再写任何日志(hotloader.log 与框架日志镜像)。 */
    public static boolean logEnabled() {
        try {
            return prefs().getBoolean("log_enabled", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setLogEnabled(boolean value) {
        try {
            prefs().putBoolean("log_enabled", value).flush();
        } catch (Throwable ignored) {
        }
    }

    /** 启动时刷新日志(b20;b42 起默认关闭):开启后每次启动清空 hotloader.log(否则追写、超过 512KB 自动清理)。 */
    public static boolean refreshLogOnStart() {
        try {
            return prefs().getBoolean("log_refresh_on_start", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setRefreshLogOnStart(boolean value) {
        try {
            prefs().putBoolean("log_refresh_on_start", value).flush();
        } catch (Throwable ignored) {
        }
    }
}
