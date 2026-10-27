package com.saysky.hotplugloader.api;

import java.io.File;

/**
 * 宿主传给附加包的运行上下文。
 *
 * <p>附加包作者:主类实现 {@link HotPlugin} 后会在 {@code init(HotContext)} 收到它。</p>
 */
public interface HotContext {

    /** 附加包 id(来自清单 json)。 */
    String id();

    /** 附加包显示名。 */
    String name();

    /** 附加包版本(来自清单 json)。 */
    String version();

    /** 宿主构建标记(如 "HotLoader v0.2.0"),用于判断宿主能力。 */
    String hostVersion();

    /**
     * 附加包资源目录(自动创建)= {@code hotplugins/resources/<附加包名>/}:
     * 其中 {@code override/} 子目录内按「与游戏内一致的相对路径」摆放的文件 = 覆盖游戏资源
     * (附加包被禁用时不生效);其余文件 = 附加包私有资源/数据(可放配置、缓存等)。
     *
     * <p>读写该目录建议用宿主便捷工具(宿主 b44 起提供,纯 JDK):
     * 配置文件用 {@link HotConfig#open(File, String)},其余文件操作用 {@link HotFiles}。</p>
     */
    File dataDir();

    /** 写日志(info 级;受宿主「开启日志」总开关控制 —— 关闭时不输出任何内容)。 */
    void log(String message);

    /** 「开启日志」开关当前状态(为 false 时可直接跳过昂贵的字符串拼接)。 */
    boolean logEnabled();
}
