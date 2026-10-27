package com.saysky.hotplugloader.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 【调试注解】(b46;b47 起自动执行)给方法标一行注解,附加包加载后宿主会自动执行它一次,
 * 结果直接写日志(与 {@code ctx.log} 同款)—— 不用写日志代码、不用改 UI、不用调试器:
 *
 * <ol>
 *   <li>附加包加载时自动登记(日志: {@code debug point <附加包id>.<名称> -> 类#方法}),便于 JDWP 下方法断点;</li>
 *   <li>「开启日志」打开时,加载后自动执行:无参数方法直接跑;有参数的用 {@link #args()} 提供
 *       默认参数(数量不足则跳过);结果/异常写 {@code hotplugin[<附加包id>]: [debug] …} 日志行。</li>
 * </ol>
 *
 * <p>与 JDWP 组合:先在方法里下断点,加载/「全部重载」时的自动执行会带你命中一次。</p>
 *
 * <p>要求:方法为 {@code public};参数最多 2 个(仅自动转换 {@link String});static 方法直接可用,
 * 主类(实现 HotPlugin 的类)的实例方法也可用(用加载时捕获的实例调用)。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HotDebug {

    /** 调试点名称(默认 = 方法名)。 */
    String value() default "";

    /** 说明(显示在登记日志里)。 */
    String desc() default "";

    /** 自动执行时传入的参数(仅 String;数量需 ≥ 方法参数个数才能自动执行)。 */
    String[] args() default {};
}
