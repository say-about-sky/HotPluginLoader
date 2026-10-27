package com.saysky.hotplugloader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.saysky.hotplugloader.api.HotDebug;

/**
 * 【注解调试点注册表】(b46)
 *
 * <p>附加包里标了 {@link HotDebug} 的 public 方法,在附加包加载时被自动登记并<b>自动执行一次</b>
 * (仅当「开启日志」打开时;结果直接写日志,与 {@code ctx.log} 同款前缀):</p>
 * <ul>
 *   <li>无参数方法直接执行;有参数的用注解 {@code args = {...}} 提供默认参数(不足则跳过);</li>
 *   <li>输出:{@code hotplugin[<附加包id>]: [debug] <名称> -> OK(耗时ms): 返回值} 或 {@code EX: …}(异常带栈);</li>
 *   <li>用途:改代码 → 「全部重载」→ 看 hotloader.log 这一行,即可验证某个方法;配合 JDWP 可在方法内下断点。</li>
 * </ul>
 *
 * <p>要求:方法为 {@code public};参数最多 2 个(仅自动转换 String);static 方法直接可用,
 * 主类(实现 HotPlugin 的类)的实例方法也可用(执行时用加载时捕获的实例)。</p>
 *
 * <p>生命周期:附加包加载/重载时重扫(先移除该附加包旧条目)并自动执行;禁用/删除/整层重置时注销。</p>
 */
public final class HotDebugRegistry {

    private static final String TAG = "HotLoader/debug";

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<String, Entry>();

    /** 一个已登记的调试点。 */
    public static final class Entry {
        final String pluginId;
        final String name;
        final String desc;
        final String[] args;
        final Method method;
        final Object instance;

        Entry(String pluginId, String name, String desc, String[] args, Method method, Object instance) {
            this.pluginId = pluginId;
            this.name = name;
            this.desc = desc;
            this.args = args == null ? new String[0] : args;
            this.method = method;
            this.instance = instance;
        }

        /** 是否可自动执行:无参数,或注解 args 足够。 */
        boolean executable() {
            int pc = method.getParameterTypes().length;
            return pc == 0 || args.length >= pc;
        }

        /** 全名(附加包id.名称)—— call 命令用它定位。 */
        public String fullName() {
            return pluginId + "." + name;
        }

        /** 一行描述(list 输出用)。 */
        public String describe() {
            return fullName() + "  (" + method.getDeclaringClass().getName() + "#" + method.getName() + ")"
                    + (desc == null || desc.length() == 0 ? "" : "  \"" + desc + "\"");
        }
    }

    private HotDebugRegistry() {
    }

    /** 附加包加载后扫描它的本体文件,登记全部 @HotDebug 方法(内部吞异常,不影响加载)。 */
    public static synchronized void scan(String pluginId, File source, ClassLoader loader, Object mainInstance) {
        if (pluginId == null || source == null || loader == null) {
            return;
        }
        removePlugin(pluginId);
        int found = 0;
        for (String name : classNamesOf(source)) {
            try {
                Class<?> c = Class.forName(name, false, loader);
                found += registerClass(pluginId, c, mainInstance);
            } catch (Throwable ignored) {
            }
        }
        if (found > 0) {
            SafeLog.i(TAG + ": " + found + " debug point(s) registered from " + pluginId
                    + " [" + source.getName() + "]");
        }
    }

    /** 手动登记一个类里的 @HotDebug 方法(供测试/特殊场景;返回新增数量)。 */
    public static synchronized int register(String pluginId, Class<?> cls) {
        return registerClass(pluginId, cls, null);
    }

    /** 注销某附加包的全部调试点(禁用/删除/重载时用)。 */
    public static synchronized void removePlugin(String pluginId) {
        if (pluginId == null) {
            return;
        }
        Iterator<Map.Entry<String, Entry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext()) {
            if (pluginId.equals(it.next().getValue().pluginId)) {
                it.remove();
            }
        }
    }

    /** 清空全部(宿主 resetAll 时用)。 */
    public static synchronized void clear() {
        ENTRIES.clear();
    }

    /** 当前调试点数量。 */
    public static synchronized int count() {
        return ENTRIES.size();
    }

    /** 按全名查找。 */
    public static synchronized Entry find(String fullName) {
        return fullName == null ? null : ENTRIES.get(fullName.trim());
    }

    /** 列表文本(命令 {@code list} 的输出)。 */
    public static synchronized String list() {
        if (ENTRIES.isEmpty()) {
            return "no debug points(给方法标 @HotDebug,附加包加载时自动登记)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("debug points (").append(ENTRIES.size()).append("):");
        for (Entry e : ENTRIES.values()) {
            sb.append("\n  ").append(e.describe());
        }
        return sb.toString();
    }

    /** 调用一个调试点(参数按 String 传入;数量不足补 null;异常摘出前 6 层堆栈)。不抛出。 */
    public static String invoke(Entry e, String[] args) {
        if (e == null) {
            return "ERR: null entry";
        }
        long t0 = System.currentTimeMillis();
        try {
            Class<?>[] pt = e.method.getParameterTypes();
            Object[] a = new Object[pt.length];
            for (int i = 0; i < pt.length; i++) {
                if (pt[i] == String.class) {
                    a[i] = args != null && i < args.length ? args[i] : null;
                } else {
                    a[i] = null; // 只自动转换 String;其他类型传 null
                }
            }
            Object self = null;
            if (!Modifier.isStatic(e.method.getModifiers())) {
                self = e.instance;
                if (self == null) {
                    return "ERR: 非 static 且无实例可调 " + e.describe();
                }
            }
            Object r = e.method.invoke(self, a);
            long ms = System.currentTimeMillis() - t0;
            if (e.method.getReturnType() == void.class) {
                return "OK(" + ms + "ms)";
            }
            return "OK(" + ms + "ms): " + r;
        } catch (Throwable t) {
            Throwable c = t;
            if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
                c = t.getCause();
            }
            StringBuilder sb = new StringBuilder("EX: ").append(c);
            StackTraceElement[] st = c.getStackTrace();
            for (int i = 0; i < st.length && i < 6; i++) {
                sb.append("\n    at ").append(st[i]);
            }
            return sb.toString();
        }
    }

    /**
     * 自动执行某附加包的全部可执行调试点(无参,或注解 args 提供的参数足够),结果写日志
     * (与 {@code ctx.log} 同款前缀);不可执行的记一条跳过日志。返回执行数(不抛出)。
     */
    public static synchronized int runMarked(String pluginId) {
        if (pluginId == null) {
            return 0;
        }
        List<Entry> mine = new ArrayList<Entry>();
        for (Entry e : ENTRIES.values()) {
            if (pluginId.equals(e.pluginId)) {
                mine.add(e);
            }
        }
        int n = 0;
        for (Entry e : mine) {
            if (!e.executable()) {
                SafeLog.i(TAG + ": skip " + e.fullName() + " (有参数且注解未提供 args)");
                continue;
            }
            String r = invoke(e, e.args);
            SafeLog.i("hotplugin[" + e.pluginId + "]: [debug] " + e.fullName()
                    + " -> " + r.replace('\n', ' '));
            n++;
        }
        return n;
    }

    /* ------------------------------------------------------------------ */

    private static int registerClass(String pluginId, Class<?> cls, Object mainInstance) {
        if (pluginId == null || cls == null) {
            return 0;
        }
        Method[] ms;
        try {
            ms = cls.getDeclaredMethods();
        } catch (Throwable t) {
            return 0;
        }
        int n = 0;
        for (Method m : ms) {
            try {
                HotDebug ann = m.getAnnotation(HotDebug.class);
                if (ann == null || !Modifier.isPublic(m.getModifiers())
                        || m.getParameterTypes().length > 2) {
                    continue;
                }
                boolean isStatic = Modifier.isStatic(m.getModifiers());
                Object self = null;
                if (!isStatic) {
                    if (mainInstance == null || !m.getDeclaringClass().isInstance(mainInstance)) {
                        continue; // 非 static 且非主类实例:无法调用,跳过
                    }
                    self = mainInstance;
                }
                String name = ann.value() == null || ann.value().trim().isEmpty()
                        ? m.getName() : ann.value().trim();
                String key = pluginId + "." + name;
                if (ENTRIES.containsKey(key)) {
                    continue; // 同名:先到先得
                }
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                }
                ENTRIES.put(key, new Entry(pluginId, name, ann.desc(), ann.args(), m, self));
                n++;
                SafeLog.i(TAG + ": debug point " + key + " -> "
                        + cls.getName() + "#" + m.getName());
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /** 本体文件里的类名清单(.dex 纯字节解析;.zip 逐 classes*.dex;.jar 读 .class 条目)。 */
    private static List<String> classNamesOf(File source) {
        List<String> out = new ArrayList<String>();
        String n = source.getName().toLowerCase(Locale.ROOT);
        try {
            if (n.endsWith(".dex")) {
                out.addAll(DexManifest.listClassNames(PluginManifest.readAll(source)));
            } else if (n.endsWith(".zip") || n.endsWith(".jar")) {
                ZipFile zf = new ZipFile(source);
                try {
                    Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        String nm = e.getName();
                        if (n.endsWith(".zip") && nm.endsWith(".dex") && nm.indexOf('/') < 0) {
                            try {
                                out.addAll(DexManifest.listClassNames(readEntry(zf, e)));
                            } catch (Throwable ignored) {
                            }
                        } else if (n.endsWith(".jar") && nm.endsWith(".class")) {
                            out.add(nm.substring(0, nm.length() - 6).replace('/', '.'));
                        }
                    }
                } finally {
                    try {
                        zf.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            SafeLog.e(TAG + ": class list failed for " + source.getName(), t);
        }
        return out;
    }

    private static byte[] readEntry(ZipFile zf, ZipEntry e) throws Exception {
        InputStream in = zf.getInputStream(e);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(
                    e.getSize() > 0 && e.getSize() < Integer.MAX_VALUE ? (int) e.getSize() : 65536);
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) != -1) {
                bos.write(buf, 0, k);
            }
            return bos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
