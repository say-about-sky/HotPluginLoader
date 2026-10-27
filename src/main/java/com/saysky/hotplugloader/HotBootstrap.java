package com.saysky.hotplugloader;

import com.badlogic.gdx.Gdx;

/**
 * 宿主自举(由构建期 Mixin 注入 {@code Button.draw} 触发)。
 *
 * <p>为什么需要它:桌面端由框架调用 {@code HotPluginLoaderPlugin.init()} 启动;
 * 安卓整合包(暮色等)内的 Finality 是另一代实现 —— 没有 {@code api/Plugin}、
 * 没有 PluginsCollection,框架永远不会实例化插件,{@code init()} 永不执行。
 * 所以宿主自带一枚 Mixin 注入 {@code Button.draw},第一个按钮绘制时完成启动
 * (最迟跟随首屏渲染,约等于"界面就绪"信号)。</p>
 *
 * <p>本类只做一次极轻的事:{@link HotFileLog} 初始化 + 启动 {@link HotLoaderTick}
 * (幂等)。帧事件派发、主菜单挂载、自动加载全部由 Tick 循环负责,这里不重复。</p>
 */
public final class HotBootstrap {

    private static final String TAG = "HotLoader/boot";

    /** 已自举过的 GDX 实例(游戏重启 → {@code Gdx.app} 换新实例 → 对新实例重新自举)。 */
    private static volatile Object lastApp = null;

    private HotBootstrap() {
    }

    /** 由 MixinButton 注入进 Button.draw;每个按钮每帧都会调用,同一 GDX 实例只自举一次。 */
    public static void onButtonDraw() {
        Object app = Gdx.app;
        if (app == null || app == lastApp) {
            return;
        }
        lastApp = app;
        try {
            HotFileLog.init();
            HotFileLog.log(TAG + ": first Button.draw; scheduling tick loop (android="
                    + HotPluginHost.isAndroid() + ")");
        } catch (Throwable ignored) {
        }
        try {
            HotLoaderTick.schedule();
        } catch (Throwable t) {
            SafeLog.e(TAG + ": schedule tick failed", t);
        }
    }
}
