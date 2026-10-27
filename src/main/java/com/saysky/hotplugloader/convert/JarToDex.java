package com.saysky.hotplugloader.convert;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.saysky.hotplugloader.api.HotPlugin;
import com.saysky.hotplugloader.api.HotPluginInfo;

/**
 * jar → dex 转换核心(<b>无任何 android/dalvik 引用</b>,可在 PC 上直接跑测试)。
 *
 * <p>b12 起并入 HotPluginLoader 宿主:「D8 转换器」与加载器合并为一个库,
 * 随补丁 dex(game.dex.zip)一起发布。触发方见 {@link ConverterService}。</p>
 *
 * <ul>
 *   <li>{@link #convert}:调 D8(与 PC build-hotdex.bat 同一条管线)把 jar 的所有类
 *       转成 {@code classes.dex / classes2.dex …},按数字序打包进一个 zip
 *       (清单生成器与宿主加载器都支持多 dex zip);</li>
 *   <li>{@link #writeManifest}:与 {@code .tools\hotjson\HotManifestGen} 同逻辑 ——
 *       从 jar 找 {@code HotPlugin} 实现类(优先带 {@code @HotPluginInfo} 注解的)、
 *       读注解生成清单 json;类加载器由调用方给(安卓=转换产物 dex;桌面=jar URLClassLoader)。</li>
 * </ul>
 */
public final class JarToDex {

    /** 与宿主加载器保持一致(min-api 26 = InMemoryDexClassLoader 下限)。 */
    public static final int MIN_API = 26;

    /** 转换结果摘要。 */
    public static final class Info {
        public String id;
        public String name;
        public String version;
        public String mainClass;
        public boolean trusted;
        /** 元数据来源:annotation / class / plugin.json */
        public String source = "";
    }

    private JarToDex() {
    }

    /* ------------------------------------------------------------------ */
    /* jar → dex(zip)                                                      */
    /* ------------------------------------------------------------------ */

    /** 转换 jar 并把产出打包到 outZip(覆盖)。workDir 用于临时 dex 目录。 */
    public static void convert(File jar, File workDir, File outZip) throws Exception {
        File dexDir = new File(workDir, "dex");
        try {
            List<File> dexes = toDexFiles(jar, workDir);
            zipDexFiles(dexes, outZip);
        } finally {
            deleteRecursively(dexDir);
        }
    }

    /**
     * d8 转换 jar,把 classes*.dex 留在 {@code workDir/dex} 里(按数字序返回,调用方负责清理)。
     * b16:用于「单 dex 直出」链路(jar → {@code <同名>.dex} 直接落 dex/ 目录,不再打 zip)。
     */
    public static List<File> toDexFiles(File jar, File workDir) throws Exception {
        File dexDir = new File(workDir, "dex");
        deleteRecursively(dexDir);
        if (!dexDir.mkdirs() && !dexDir.isDirectory()) {
            throw new IOException("无法创建临时目录: " + dexDir);
        }
        QuietDiagnostics diag = new QuietDiagnostics();
        try {
            D8Command.Builder b = D8Command.builder(diag);
            b.addProgramFiles(jar.toPath());
            b.setMinApiLevel(MIN_API);
            b.setMode(CompilationMode.RELEASE);
            b.setOutput(dexDir.toPath(), OutputMode.DexIndexed);
            D8.run(b.build());
        } catch (Throwable t) {
            String extra = diag.summary();
            deleteRecursively(dexDir);
            throw new Exception("D8 转换失败: " + t.getMessage() + (extra.length() > 0 ? " [" + extra + "]" : ""), t);
        }
        File[] files = dexDir.listFiles();
        List<File> dexes = new ArrayList<File>();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && isClassesDex(f.getName())) {
                    dexes.add(f);
                }
            }
        }
        if (dexes.isEmpty()) {
            deleteRecursively(dexDir);
            throw new IOException("D8 没有产出 classes*.dex");
        }
        sortByDexIndex(dexes);
        return dexes;
    }

    /** 把多个 classes*.dex 按数字序打包到 outZip(entries 用文件名)。 */
    public static void zipDexFiles(List<File> dexes, File outZip) throws Exception {
        List<File> sorted = new ArrayList<File>(dexes);
        sortByDexIndex(sorted);
        if (sorted.isEmpty()) {
            throw new IOException("D8 没有产出 classes*.dex");
        }
        File parent = outZip.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outZip)));
        try {
            byte[] buf = new byte[65536];
            for (File f : sorted) {
                zos.putNextEntry(new ZipEntry(f.getName()));
                InputStream in = new FileInputStream(f);
                try {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        zos.write(buf, 0, r);
                    }
                } finally {
                    in.close();
                }
                zos.closeEntry();
            }
        } finally {
            zos.close();
        }
    }

    /** 按 classes 编号排序(classes.dex=1, classes2.dex=2, …)。 */
    private static void sortByDexIndex(List<File> dexes) {
        for (int i = 0; i < dexes.size(); i++) {
            for (int j = i + 1; j < dexes.size(); j++) {
                if (dexIndex(dexes.get(j).getName()) < dexIndex(dexes.get(i).getName())) {
                    File tmp = dexes.get(i);
                    dexes.set(i, dexes.get(j));
                    dexes.set(j, tmp);
                }
            }
        }
    }

    /** 读多 dex zip 的全部 classes*.dex(数字序)为 ByteBuffer(安卓端建加载器用)。 */
    public static ByteBuffer[] readZipDexBuffers(File zip) throws Exception {
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        try {
            List<String> names = new ArrayList<String>();
            List<byte[]> datas = new ArrayList<byte[]>();
            ZipEntry ze;
            while ((ze = zin.getNextEntry()) != null) {
                if (ze.isDirectory()) {
                    continue;
                }
                String n = ze.getName();
                String base = n.contains("/") ? n.substring(n.lastIndexOf('/') + 1) : n;
                if (!isClassesDex(base)) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int r;
                while ((r = zin.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
                names.add(base);
                datas.add(out.toByteArray());
            }
            if (datas.isEmpty()) {
                throw new IOException("zip 内没有 classes*.dex");
            }
            ByteBuffer[] buffers = new ByteBuffer[datas.size()];
            boolean[] used = new boolean[datas.size()];
            for (int i = 0; i < datas.size(); i++) {
                int best = -1;
                int bestIdx = Integer.MAX_VALUE;
                for (int j = 0; j < datas.size(); j++) {
                    if (used[j]) {
                        continue;
                    }
                    int idx = dexIndex(names.get(j));
                    if (idx < bestIdx) {
                        bestIdx = idx;
                        best = j;
                    }
                }
                used[best] = true;
                buffers[i] = ByteBuffer.wrap(datas.get(best));
            }
            return buffers;
        } finally {
            try {
                zin.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 清单生成(与 HotManifestGen 同逻辑)                                  */
    /* ------------------------------------------------------------------ */

    /**
     * 找主类并写清单 json。
     *
     * @param classLoader 用来加载 jar 里类的加载器:
     *                    安卓 = 转换产物 dex 的 InMemoryDexClassLoader;桌面测试 = jar 的 URLClassLoader
     */
    public static Info writeManifest(File jar, File outJson, ClassLoader classLoader) throws Exception {
        List<Class<?>> candidates = new ArrayList<Class<?>>();
        List<Class<?>> annotated = new ArrayList<Class<?>>();
        Attributes attrs = null;
        JarFile jf = new JarFile(jar);
        try {
            java.util.jar.Manifest mf = jf.getManifest();
            attrs = mf == null ? null : mf.getMainAttributes();
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/")) {
                    continue;
                }
                String cn = n.substring(0, n.length() - ".class".length()).replace('/', '.');
                Class<?> c;
                try {
                    c = Class.forName(cn, false, classLoader); // false = 不初始化,不执行附加包代码
                } catch (Throwable t) {
                    continue;
                }
                if (c.isInterface() || java.lang.reflect.Modifier.isAbstract(c.getModifiers())) {
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
        } finally {
            try {
                jf.close();
            } catch (Throwable ignored) {
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

        // 没有 HotPlugin 实现类 → 回退:读 jar 内嵌的 plugin.json(Finality 旧式插件都带)
        if (main == null) {
            Info legacy = manifestFromEmbeddedJson(jar);
            if (legacy != null) {
                writeManifestJson(outJson, legacy, "");
                return legacy;
            }
            throw new Exception("jar 内没有 HotPlugin 实现类,也没有内嵌 plugin.json(忘了 implements HotPlugin?)");
        }

        Info info = new Info();
        HotPluginInfo anno = main.getAnnotation(HotPluginInfo.class);
        info.id = anno != null && anno.id().length() > 0 ? anno.id() : main.getName();
        String name = anno != null ? anno.name() : "";
        if (name.length() == 0 && attrs != null) {
            name = orEmpty(attrs.getValue("Implementation-Title"));
        }
        if (name.length() == 0) {
            name = main.getSimpleName();
        }
        info.name = name;
        String version = anno != null ? anno.version() : "";
        if (version.length() == 0 && attrs != null) {
            version = orEmpty(attrs.getValue("Implementation-Version"));
        }
        info.version = version.length() == 0 ? "?" : version;
        info.mainClass = main.getName();
        // 转换器产物 = 用户显式导入/放入的附加包:无注解声明时默认信任;
        // 作者可用 @HotPluginInfo(trusted=false) 显式要求走「允许未校验附加包」门槛。
        info.trusted = anno != null ? anno.trusted() : true;
        info.source = anno != null ? "annotation" : "class";
        writeManifestJson(outJson, info,
                anno != null && anno.description().length() > 0 ? anno.description() : "");
        return info;
    }

    private static String multipleMessage(List<Class<?>> candidates) {
        StringBuilder sb = new StringBuilder("jar 内有多个 HotPlugin 实现类,请给主类加 @HotPluginInfo:");
        for (Class<?> c : candidates) {
            sb.append("\n  - ").append(c.getName());
        }
        return sb.toString();
    }

    /** 读取 jar 内嵌的 plugin.json(Finality 旧式插件清单);没有或无效返回 null。 */
    private static Info manifestFromEmbeddedJson(File jar) {
        try {
            JarFile jf = new JarFile(jar);
            try {
                JarEntry entry = jf.getJarEntry("plugin.json");
                if (entry == null) {
                    return null;
                }
                InputStream in = jf.getInputStream(entry);
                byte[] data;
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                    }
                    data = out.toByteArray();
                } finally {
                    in.close();
                }
                JsonValue root;
                try {
                    root = new JsonReader().parse(new String(data, "UTF-8"));
                } catch (Throwable t) {
                    return null;
                }
                if (root == null) {
                    return null;
                }
                String mainClass = orEmpty(root.getString("mainClass", ""));
                if (mainClass.length() == 0) {
                    return null;
                }
                Info info = new Info();
                info.mainClass = mainClass;
                String id = orEmpty(root.getString("id", ""));
                info.id = id.length() == 0 ? mainClass : id;
                String name = orEmpty(root.getString("name", ""));
                info.name = name.length() == 0 ? mainClass : name;
                String version = orEmpty(root.getString("version", ""));
                info.version = version.length() == 0 ? "?" : version;
                info.trusted = true;
                info.source = "plugin.json";
                return info;
            } finally {
                try {
                    jf.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写清单 json(与 .tools\hotjson\HotManifestGen 同格式)。 */
    private static void writeManifestJson(File outJson, Info info, String description) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        field(sb, "id", info.id, true);
        field(sb, "name", info.name, true);
        field(sb, "version", info.version, true);
        field(sb, "mainClass", info.mainClass, true);
        sb.append("  \"trusted\": ").append(info.trusted);
        if (description != null && description.length() > 0) {
            sb.append(",\n");
            field(sb, "description", description, false);
        } else {
            sb.append("\n");
        }
        sb.append("}\n");

        File parent = outJson.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        Writer w = new OutputStreamWriter(new FileOutputStream(outJson), "UTF-8");
        try {
            w.write(sb.toString());
        } finally {
            w.close();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                                */
    /* ------------------------------------------------------------------ */

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

    private static String orEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    static boolean isClassesDex(String base) {
        if (!base.startsWith("classes") || !base.endsWith(".dex")) {
            return false;
        }
        String mid = base.substring("classes".length(), base.length() - ".dex".length());
        if (mid.length() == 0) {
            return true;
        }
        for (int i = 0; i < mid.length(); i++) {
            if (!Character.isDigit(mid.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static int dexIndex(String base) {
        String mid = base.substring("classes".length(), base.length() - ".dex".length());
        return mid.length() == 0 ? 1 : Integer.parseInt(mid);
    }

    /** 递归删除(文件/目录;失败忽略)。 */
    public static void deleteRecursively(File f) {
        try {
            if (f == null || !f.exists()) {
                return;
            }
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 忽略"缺类"警告(附加包引用游戏类属正常);只收集真实错误用于报错。 */
    private static final class QuietDiagnostics implements DiagnosticsHandler {
        private final List<String> errors = new ArrayList<String>();
        private int warnings = 0;

        @Override
        public void error(Diagnostic diagnostic) {
            if (errors.size() < 5) {
                errors.add(diagnostic.getDiagnosticMessage());
            }
        }

        @Override
        public void warning(Diagnostic diagnostic) {
            warnings++;
        }

        String summary() {
            if (errors.isEmpty()) {
                return warnings > 0 ? "warnings=" + warnings : "";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < errors.size(); i++) {
                if (i > 0) {
                    sb.append(" / ");
                }
                sb.append(errors.get(i));
            }
            return sb.toString();
        }
    }
}
