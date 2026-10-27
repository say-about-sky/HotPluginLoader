package com.saysky.hotplugloader.api;

/**
 * 附加包事件监听器。
 *
 * <p>用 {@link HotEvents#register(HotListener, String...)} 订阅感兴趣的事件;
 * 只订阅需要的事件(如只订阅 {@link HotEvents#FRAME}),避免不必要的开销。</p>
 *
 * <p>回调在游戏渲染线程上执行:禁止阻塞,异常会被宿主捕获并记日志(不会崩溃游戏),
 * 但抛出的异常意味着本次事件被跳过。</p>
 */
public interface HotListener {

    /**
     * 事件回调。
     *
     * @param type 事件类型(如 {@link HotEvents#FRAME})
     * @param args 事件参数;约定见 {@link HotEvents} 各常量说明
     */
    void onEvent(String type, Object[] args);
}
