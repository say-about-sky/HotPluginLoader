package com.saysky.hotplugloader;

import team.rainfall.finality.api.Plugin;

/**
 * 附加包热加载系统(宿主)。
 *
 * <p>v0.2.0 范围:</p>
 * <ul>
 *   <li>主菜单「附加包」行按钮 + 附加包列表面板(扫描/加载/启用禁用/错误显示);</li>
 *   <li>附加包加载管线(安卓:d8 dex → InMemoryDexClassLoader;桌面:.jar 调试);</li>
 *   <li>事件总线(首批 frame 事件)+ 附加包登记表;</li>
 *   <li>加载器设置面板(自动加载开关 / 信任门槛)。</li>
 * </ul>
 *
 * <p>桌面端由框架调用 {@link #init()} 启动;安卓整合包没有框架插件机制,
 * 由构建期 Mixin({@code MixinButton} → Button.draw)自举,见 {@link HotBootstrap}。
 * 其余全部逻辑在运行时完成,不需要 Mixin 任何游戏类。</p>
 */
public class HotPluginLoaderPlugin extends Plugin {

    @Override
    public void init() {
        try {
            HotFileLog.init();
            HotFileLog.log(BuildInfo.BUILD_TAG + ": desktop init via framework (" + getPluginID() + ")");
        } catch (Throwable ignored) {
        }
        try {
            SafeLog.i(BuildInfo.BUILD_TAG + ": init (" + getPluginID() + ")");
        } catch (Throwable ignored) {
        }
        try {
            HotLoaderTick.schedule();
        } catch (Throwable t) {
            SafeLog.e(BuildInfo.BUILD_TAG + ": schedule tick failed", t);
        }
    }
}
