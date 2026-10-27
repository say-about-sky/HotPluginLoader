package com.saysky.hotplugloader.mixin;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import com.saysky.hotplugloader.HotBootstrap;
import team.rainfall.finality.luminosity2.CallbackInfo;
import team.rainfall.finality.luminosity2.annotations.Inject;
import team.rainfall.finality.luminosity2.annotations.Mixin;

/**
 * 引导 Mixin:注入 {@code Button.draw}(每个按钮每帧都会经过这里)。
 *
 * <p><b>为什么用 Button:</b></p>
 * <ul>
 *   <li>安卓整合包(暮色等)运行的是<b>另一代 Finality 框架</b> —— 没有 {@code api/Plugin}、
 *       没有 PluginsCollection,插件的 {@code init()} 永远不会被框架调用;
 *       因此宿主自带这枚构建期 Mixin 作为"引导启动器";</li>
 *   <li>{@code Button} 不在任何现有 Mixin 的目标清单里(不是"多方共享类"),
 *       可以安全地被本附加包独占注入 —— 这一点很关键:Luminosity2 每合并一个来源都会
 *       重扫全类 {@code @Inject} 注解,给"已有其它 Mixin 的类"再加来源会造成重复注入;</li>
 *   <li>{@code Button.draw} 在主菜单/游戏内都会每帧执行,是"界面已就绪"的可靠信号。</li>
 * </ul>
 *
 * <p>回调极轻:首次触发即启动 {@link com.saysky.hotplugloader.HotLoaderTick} 每帧循环
 * (幂等,后续调用立即返回);帧事件派发、主菜单挂载、自动加载全部由 Tick 负责。
 * 绝不在绘制路径里直接改菜单数据。</p>
 */
@Mixin(mixinClass = "aoc.kingdoms.lukasz.menu_element.button.Button")
public class MixinButton {

    @Inject(methodName = "draw")
    protected final void hb$onButtonDraw(SpriteBatch oSB, int iTranslateX, int iTranslateY,
                                         boolean isActive, boolean scrollableY, CallbackInfo callbackInfo) {
        try {
            HotBootstrap.onButtonDraw();
        } catch (Throwable ignored) {
        }
    }
}
