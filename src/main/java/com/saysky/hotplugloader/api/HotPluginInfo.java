package com.saysky.hotplugloader.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 附加包主类元数据注解(可选,但推荐)。
 *
 * <p>写在主类上后,用工具 {@code .tools\hotjson\HotManifestGen} 可以从 jar
 * <b>自动生成</b>清单 json(免手写;演示工程 {@code HotPluginDemo\build-hotdex.bat} 已接入)。</p>
 *
 * <p>为什么用注解而不是 jar 里的资源文件:d8 只搬 {@code .class},
 * jar 内的 json/资源不会进 dex —— 而 {@code RUNTIME} 注解会随 class 一起进 dex,
 * 所以 dex 与 jar 两条链路都拿得到元数据。</p>
 *
 * <pre>
 * &#64;HotPluginInfo(id = "com.example.myplugin", name = "My Plugin", version = "0.1.0", trusted = true)
 * public class MyPlugin implements HotPlugin { ... }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface HotPluginInfo {

    /** 唯一标识(id)。 */
    String id();

    /** 显示名;留空则退回 jar 清单的 Implementation-Title / 类名。 */
    String name() default "";

    /** 版本;留空则退回 jar 清单的 Implementation-Version / "?"。 */
    String version() default "";

    /** true = 免开「允许未校验附加包」也能加载。 */
    boolean trusted() default false;

    /** 描述(可选)。 */
    String description() default "";
}
