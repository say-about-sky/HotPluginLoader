package com.saysky.hotplugloader.api;

/**
 * 附加包主类契约。
 *
 * <p>清单 json 里的 {@code mainClass} 必须指向一个:</p>
 * <ul>
 *   <li>public 类;</li>
 *   <li>有无参构造函数;</li>
 *   <li>实现本接口。</li>
 * </ul>
 *
 * <p>也可以改为继承框架类 {@code team.rainfall.finality.api.Plugin}(宿主会填好
 * name / pluginID / logger 后调用其 {@code init()}),但注意其 interactions 只在本插件
 * 内部可用(附加包不在框架的静态插件表里)。</p>
 *
 * <p>⚠ 附加包工程必须把宿主 jar 用 {@code compileOnly} 引用、<b>绝不打包</b>,
 * 否则宿主类会被复制进附加包 dex,导致 {@code instanceof} 失败。</p>
 */
public interface HotPlugin {

    /**
     * 附加包初始化(加载成功时调用一次)。此方法抛出异常时,附加包会被标记为「错误」。
     * 若要收事件,在这里调用 {@link HotEvents#register(HotListener, String...)}。
     */
    void init(HotContext context);
}
