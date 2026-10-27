package com.saysky.hotplugloader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.saysky.hotplugloader.api.HotPlugin;
import com.saysky.hotplugloader.api.HotPluginInfo;

/**
 * 单个 {@code .dex} 没有清单时的自动生成:
 * 解析 dex 类表 → 类加载找 {@code HotPlugin} 实现 → 写 {@code <同名>.json}。
 *
 * <ul>
 *   <li><b>类名枚举</b> = 纯字节解析(dex 的 string_ids / type_ids / class_defs 三张表,
 *       只读不执行,零依赖);</li>
 *   <li><b>"是否实现 HotPlugin / 注解值"</b> = 用 {@code InMemoryDexClassLoader}(反射,API 26+;
 *       低版本回退 {@code DexClassLoader})加载后 {@code Class.forName(name, false, loader)}
 *       判断(false = 不初始化,<b>不执行附加包代码</b>);</li>
 *   <li>所以 {@link #listClassNames} 是纯解析(桌面可测,已对照 dexdump 校验);
 *       {@link #generate} 需安卓端(桌面 JVM 无法加载 dex)。</li>
 * </ul>
 */
public final class DexManifest {

    private static final String TAG = "HotLoader/dexmanifest";

    private DexManifest() {
    }

    /**
     * 解析 dex 并生成清单 json。
     *
     * @throws Exception 失败原因(短消息,可直接展示)
     */
    public static void generate(File dexFile, File outJson) throws Exception {
        byte[] data = PluginManifest.readAll(dexFile);
        List<String> names = listClassNames(data);
        if (names.isEmpty()) {
            throw new Exception("dex 里没有类");
        }
        ClassLoader cl = createLoader(dexFile, data);
        List<Class<?>> candidates = new ArrayList<Class<?>>();
        List<Class<?>> annotated = new ArrayList<Class<?>>();
        for (String name : names) {
            Class<?> c;
            try {
                c = Class.forName(name, false, cl); // false = 不初始化,不执行附加包代码
            } catch (Throwable t) {
                continue;
            }
            if (c.isInterface() || Modifier.isAbstract(c.getModifiers())) {
                continue;
            }
            if (!HotPlugin.class.isAssignableFrom(c)) {
                continue;
            }
            candidates.add(c);
            if (c.getAnnotation(HotPluginInfo.class) != null) {
                annotated.add(c);
            }
        }
        Class<?> main = null;
        if (annotated.size() == 1) {
            main = annotated.get(0);
        } else if (annotated.size() > 1) {
            throw new Exception(multipleMessage(candidates));
        } else if (candidates.size() == 1) {
            main = candidates.get(0);
        } else if (candidates.size() > 1) {
            throw new Exception(multipleMessage(candidates));
        }
        if (main == null) {
            throw new Exception("没有 HotPlugin 实现类(旧式框架插件请提供 json 或经「D8 转换器」)");
        }
        HotPluginInfo anno = main.getAnnotation(HotPluginInfo.class);
        String id = anno != null && anno.id().length() > 0 ? anno.id() : main.getName();
        String name = anno != null && anno.name().length() > 0 ? anno.name() : main.getSimpleName();
        String version = anno != null && anno.version().length() > 0 ? anno.version() : "?";
        // 用户显式导入/放入的附加包:无注解声明时默认信任(与「D8 转换器」一致)
        boolean trusted = anno != null ? anno.trusted() : true;
        String desc = anno != null ? anno.description() : "";
        writeJson(outJson, id, name, version, main.getName(), trusted, desc);
        SafeLog.i(TAG + ": generated " + outJson.getName() + " (id=" + id
                + ", main=" + main.getName() + ", via=dex)");
    }

    /**
     * 枚举 dex 里定义的全部类名(纯字节解析;类名的 dex 描述符恒为 ASCII)。
     *
     * @throws Exception magic 不符等
     */
    public static List<String> listClassNames(File dexFile) throws Exception {
        return listClassNames(PluginManifest.readAll(dexFile));
    }

    public static List<String> listClassNames(byte[] dex) throws Exception {
        if (dex.length < 0x70
                || dex[0] != 0x64 || dex[1] != 0x65 || dex[2] != 0x78 || dex[3] != 0x0A) {
            throw new Exception("不是有效的 dex 文件(magic 不符)");
        }
        int stringIdsSize = leInt(dex, 0x38);
        int stringIdsOff = leInt(dex, 0x3C);
        int typeIdsSize = leInt(dex, 0x40);
        int typeIdsOff = leInt(dex, 0x44);
        int classDefsSize = leInt(dex, 0x60);
        int classDefsOff = leInt(dex, 0x64);
        if (stringIdsSize <= 0 || stringIdsOff <= 0 || typeIdsSize <= 0 || typeIdsOff <= 0
                || classDefsSize <= 0 || classDefsOff <= 0) {
            return new ArrayList<String>();
        }
        List<String> names = new ArrayList<String>(Math.min(classDefsSize, 1 << 16));
        for (int i = 0; i < classDefsSize; i++) {
            int off = classDefsOff + i * 32;
            if (off + 4 > dex.length) {
                break;
            }
            int classIdx = leInt(dex, off); // class_def_item.class_idx
            if (classIdx < 0 || classIdx >= typeIdsSize) {
                continue;
            }
            int descIdx = leInt(dex, typeIdsOff + classIdx * 4); // type_id.descriptor_idx
            String desc = stringAt(dex, stringIdsOff, stringIdsSize, descIdx);
            if (desc != null && desc.length() >= 2 && desc.charAt(0) == 'L' && desc.endsWith(";")) {
                names.add(desc.substring(1, desc.length() - 1).replace('/', '.'));
            }
        }
        return names;
    }

    /* ------------------------------------------------------------------ */

    /** 从 dex 字节建类加载器(反射 dalvik;仅安卓端调用)。 */
    private static ClassLoader createLoader(File dexFile, byte[] data) throws Exception {
        ClassLoader parent = DexManifest.class.getClassLoader();
        Class<?> imc = tryLoadClass("dalvik.system.InMemoryDexClassLoader", parent);
        if (imc != null) {
            return (ClassLoader) imc.getConstructor(ByteBuffer.class, ClassLoader.class)
                    .newInstance(ByteBuffer.wrap(data), parent);
        }
        Class<?> dcl = Class.forName("dalvik.system.DexClassLoader", true, parent);
        return (ClassLoader) dcl.getConstructor(String.class, String.class, String.class, ClassLoader.class)
                .newInstance(dexFile.getAbsolutePath(), cacheDir(), null, parent);
    }

    private static Class<?> tryLoadClass(String className, ClassLoader cl) {
        try {
            return Class.forName(className, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** DexClassLoader 的优化目录(API < 26 回退用)。 */
    private static String cacheDir() {
        try {
            Object ctx = com.badlogic.gdx.Gdx.app.getClass().getMethod("getContext").invoke(com.badlogic.gdx.Gdx.app);
            Object dir = ctx.getClass().getMethod("getCodeCacheDir").invoke(ctx);
            if (dir instanceof File) {
                return ((File) dir).getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return System.getProperty("java.io.tmpdir");
    }

    private static String multipleMessage(List<Class<?>> candidates) {
        StringBuilder sb = new StringBuilder("有多个 HotPlugin 实现类,请给主类加 @HotPluginInfo:");
        for (Class<?> c : candidates) {
            sb.append("\n  - ").append(c.getName());
        }
        return sb.toString();
    }

    /** 写清单 json(与 HotManifestGen / D8 转换器同格式)。 */
    private static void writeJson(File out, String id, String name, String version,
                                  String mainClass, boolean trusted, String description) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        field(sb, "id", id, true);
        field(sb, "name", name, true);
        field(sb, "version", version, true);
        field(sb, "mainClass", mainClass, true);
        sb.append("  \"trusted\": ").append(trusted);
        if (description != null && description.length() > 0) {
            sb.append(",\n");
            field(sb, "description", description, false);
        } else {
            sb.append("\n");
        }
        sb.append("}\n");
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
        try {
            w.write(sb.toString());
        } finally {
            w.close();
        }
    }

    private static void field(StringBuilder sb, String key, String value, boolean comma) {
        sb.append("  \"").append(key).append("\": \"").append(esc(value)).append("\"");
        sb.append(comma ? ",\n" : "\n");
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /** string_ids[idx] → string_data,读 MUTF-8(类名描述符为 ASCII,读到 NUL 即可)。 */
    private static String stringAt(byte[] dex, int stringIdsOff, int stringIdsSize, int idx) {
        try {
            if (idx < 0 || idx >= stringIdsSize) {
                return null;
            }
            int dataOff = leInt(dex, stringIdsOff + idx * 4);
            if (dataOff <= 0 || dataOff >= dex.length) {
                return null;
            }
            int p = dataOff;
            // uleb128(utf16 长度):跳到最后一字节之后
            while (p < dex.length && (dex[p] & 0x80) != 0) {
                p++;
            }
            p++;
            ByteArrayOutputStream out = new ByteArrayOutputStream(32);
            while (p < dex.length && dex[p] != 0) {
                out.write(dex[p++]);
                if (out.size() > 512) {
                    return null; // 防御:类名描述符不会这么长
                }
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
