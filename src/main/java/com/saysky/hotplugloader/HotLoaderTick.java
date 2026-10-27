package com.saysky.hotplugloader;

import com.badlogic.gdx.Gdx;

import com.saysky.hotplugloader.cold.ColdBootstrap;
import com.saysky.hotplugloader.ui.PluginScreen;

/**
 * 热加载器每帧循环(宿主唯一的"心跳")。
 *
 * <p>用 {@code Gdx.app.postRunnable} 接力,每帧做四件事:</p>
 * <ol>
 *   <li>{@link CharsetFix#ensure()} —— 中文文案字形补全(必须早于字体烘焙);</li>
 *   <li>{@link MainMenuMount#tick()} —— 主菜单行按钮的挂载/自愈;</li>
 *   <li>{@link HotPluginHost#onFrame()} —— 向附加包派发 frame 事件;</li>
 *   <li>启动预载兜底({@link ColdTick} / {@link StartupLoader};正常路径在冷加载后期,此处覆盖异常路径)。</li>
 * </ol>
 *
 * <p>本类不 Mixin 任何游戏类;postRunnable 回链保证即使中途抛异常也会继续下一帧。</p>
 */
public final class HotLoaderTick {

    private static final String TAG = "HotLoader/tick";

    /** 已启动过的 GDX 实例(游戏重启后变化 → 对新实例重启循环并清附加包状态)。 */
    private static Object lastApp = null;
    private static boolean firstLogged = false;
    private static long ticks = 0;

    private HotLoaderTick() {
    }

    /** 入口:启动每帧循环(按 GDX 实例幂等;游戏重启后自动对新实例重启)。 */
    public static void schedule() {
        Object app = Gdx.app;
        if (app == null || app == lastApp) {
            return;
        }
        boolean restarted = lastApp != null;
        lastApp = app;
        if (restarted) {
            // 新一局(手动重启或兜底重启):退订上一局附加包、清空注册表,
            // 让自动加载完整重跑 —— 修「更新附加包后旧实例占用」问题。
            HotPluginHost.resetAll();
            MainMenuMount.onNewSession();
            SafeLog.i(TAG + ": new GDX instance detected; plugin state reset");
        }
        firstLogged = false;
        ticks = 0;
        postNext();
    }

    private static void postNext() {
        try {
            Gdx.app.postRunnable(new Runnable() {
                @Override
                public void run() {
                    tick();
                }
            });
        } catch (Throwable t) {
            SafeLog.e(TAG + ": postRunnable failed", t);
        }
    }

    private static void tick() {
        ticks++;
        try {
            if (!firstLogged) {
                firstLogged = true;
                SafeLog.i(TAG + ": loop started (" + BuildInfo.BUILD_TAG + ")");
            }
            // b28:兜底换装「资源覆盖接口」(正常路径 = 启动类 onCreate 的后台线程)
            ColdBootstrap.ensureInstalled();
            // b33:启动预载 —— 正常路径 = ColdTick 在冷加载后期(加载屏出现)点火,
            // 此处为首帧兜底(首帧 = 主菜单出现);全程静默:不显示任何消息、不替换任何游戏文字
            ColdTick.ensureStarted();
            StartupLoader.ensureStarted();
            CharsetFix.ensure();
            MainMenuMount.tick();
            PluginScreen.tick();
            HotPluginHost.onFrame();
            LoadQueue.tick();
        } catch (Throwable t) {
            SafeLog.e(TAG + ": tick failed", t);
        }
        postNext();
    }
}
