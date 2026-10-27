package com.saysky.hotplugloader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.badlogic.gdx.Gdx;

/**
 * 附加包目录解析。
 *
 * <ul>
 *   <li>安卓:{@code getExternalFilesDir("hotplugins")} = {@code /Android/data/<包名>/files/hotplugins/}
 *       (免存储权限、用户可用 MT 等文件管理器访问、复制进去即可);</li>
 *   <li>桌面:游戏根目录下 {@code hotplugins/}(调试用)。</li>
 * </ul>
 *
 * <p>目录结构(b10 起;b39 资源整合进 resources/):</p>
 * <pre>
 * hotplugins/
 *   ├─ dex/          附加包本体(.dex / .zip / .jar;导入与转换产物默认落这里)
 *   ├─ jar/          待转换/多 dex 的 .jar 与 .zip(b16 起单 dex 转换直出 dex/,源 jar 删除;
 *   │                 <b>按需创建</b> —— 没有 jar/zip 时不再预建空目录,已存在的照样扫描)
 *   ├─ json/         清单(<同名>.json;自动生成与转换器清单一律放这里)
 *   ├─ resources/    附加包资源(b39;每附加包一个子目录 <附加包名>/:
 *   │                 镜像游戏内路径摆放 = 覆盖游戏资源;其余 = 附加包私有资源/数据;
 *   │                 见「资源覆盖接口使用帮助.md」)
 *   ├─ registry.json 启用/禁用注册配置(见 {@link PluginStates})
 *   ├─ .apk_seed_done  APK 根内嵌 hotplugins 的一次性播种标记(b41;存在 = 之后启动不再播种,见 ApkSeed)
 *   ├─ .wait_debugger  调试:存在时启动最早期等待 JDWP 调试器接入(b45;默认上限 180s;见调试指南)
 *   └─ (根目录仍然扫描:旧文件、直接丢放继续可用)
 * </pre>
 *
 * <p>兼容读取:子目录名也支持隐藏式 {@code .dex/}、{@code .json/}(已存在时同样识别);
 * 写入统一用规范名 {@code dex/}、{@code json/}(隐藏式已存在则沿用)。</p>
 */
public final class LoaderPaths {

    private static final String DEX_DIR = "dex";
    private static final String DEX_DIR_ALT = ".dex";
    private static final String JAR_DIR = "jar";
    private static final String JAR_DIR_ALT = ".jar";
    private static final String JSON_DIR = "json";
    private static final String JSON_DIR_ALT = ".json";
    private static final String RESOURCES_DIR = "resources";
    /** 主菜单按钮布局配置(b53;与 hotplugins 同级)。 */
    private static final String UI_CONFIG = "hotpluginsUI.json";

    private LoaderPaths() {
    }

    /** 附加包目录(不存在时自动创建)。 */
    public static File pluginsDir() {
        try {
            Object ctx = Gdx.app.getClass().getMethod("getContext").invoke(Gdx.app);
            Object f = ctx.getClass().getMethod("getExternalFilesDir", String.class).invoke(ctx, "hotplugins");
            if (f instanceof File) {
                File d = (File) f;
                if (!d.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    d.mkdirs();
                }
                if (d.isDirectory()) {
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        File d = new File("hotplugins");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /* ------------------------------------------------------------------ */
    /* 子目录                                                              */
    /* ------------------------------------------------------------------ */

    /** 附加包本体子目录(规范名 {@code dex/})。 */
    public static File dexDir() {
        return new File(pluginsDir(), DEX_DIR);
    }

    /** 清单子目录(规范名 {@code json/})。 */
    public static File jsonDir() {
        return new File(pluginsDir(), JSON_DIR);
    }

    /** 附加包资源根目录(hotplugins/resources;不存在时自动创建;b39)。 */
    public static File resourcesDir() {
        File d = new File(pluginsDir(), RESOURCES_DIR);
        mkdirs(d);
        return d;
    }

    /** 某附加包的资源目录(hotplugins/resources/<附加包名>/;不存在时自动创建;b39)。 */
    public static File resourcesFor(String baseName) {
        File d = new File(resourcesDir(), baseName == null ? "" : baseName);
        mkdirs(d);
        return d;
    }

    /** 主菜单按钮布局配置文件:与 hotplugins <b>同级</b>的 hotpluginsUI.json(b53)。 */
    public static File uiConfigFile() {
        File parent = pluginsDir().getParentFile();
        return new File(parent == null ? new File(".") : parent, UI_CONFIG);
    }

    /** 确保 hotpluginsUI.json 存在(不存在 → 写一份 Display=false 的默认模板;b53)。 */
    public static void ensureUiConfig() {
        try {
            File f = uiConfigFile();
            if (f.isFile()) {
                return;
            }
            File p = f.getParentFile();
            if (p != null) {
                mkdirs(p);
            }
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            try {
                os.write(UiConfig.DEFAULT_JSON.getBytes("UTF-8"));
            } finally {
                os.close();
            }
            SafeLog.i("HotLoader/paths: 已生成默认 hotpluginsUI.json(Display=false)");
        } catch (Throwable ignored) {
        }
    }

    private static File dexDirAlt() {
        return new File(pluginsDir(), DEX_DIR_ALT);
    }

    /** jar 子目录(规范名 {@code jar/};.jar / .jar.done / .zip 的家;按需创建,不预建)。 */
    public static File jarDir() {
        return new File(pluginsDir(), JAR_DIR);
    }

    private static File jarDirAlt() {
        return new File(pluginsDir(), JAR_DIR_ALT);
    }

    private static File jsonDirAlt() {
        return new File(pluginsDir(), JSON_DIR_ALT);
    }

    /**
     * 创建 dex/、json/ 子目录(幂等;隐藏式替代名已存在时不重复创建),并搬运 dex/ 遗留的 jar/zip。
     *
     * <p>jar/ <b>不再预建</b>(b52):b16 起单 dex 转换直出 dex/ 且源 jar 删除,大多数用户那里永远是空目录;
     * 需要时由 {@link #importDirFor(String)}(导入 .jar/.zip)与 {@link #migrateDexPayloads()}(遗留搬运)创建,
     * 已存在的 jar/ 仍然参与扫描({@link #payloadDirs()})。</p>
     */
    public static void ensureSubDirs() {
        if (!dexDir().isDirectory() && !dexDirAlt().isDirectory()) {
            mkdirs(dexDir());
        }
        if (!jsonDir().isDirectory() && !jsonDirAlt().isDirectory()) {
            mkdirs(jsonDir());
        }
        // b39:附加包资源目录(启动即创建,便于用户直接放文件;每个附加包一个子目录)
        mkdirs(resourcesDir());
        // b53:主菜单按钮布局配置(与 hotplugins 同级;不存在 → 生成 Display=false 默认模板)
        ensureUiConfig();
        migrateDexPayloads();
    }

    /** 可能存放附加包本体(.dex/.zip/.jar)的目录:根目录 + dex/ + .dex/ + jar/ + .jar/。 */
    public static List<File> payloadDirs() {
        List<File> dirs = new ArrayList<File>(5);
        dirs.add(pluginsDir());
        dirs.add(dexDir());
        dirs.add(dexDirAlt());
        dirs.add(jarDir());
        dirs.add(jarDirAlt());
        return dirs;
    }

    /* ------------------------------------------------------------------ */
    /* 清单 / 产物路径                                                      */
    /* ------------------------------------------------------------------ */

    /** 解析附加包的清单(json):附加包同目录 → json/ → .json/ → 根目录;都没有返回 null。 */
    public static File resolveManifest(File pluginFile) {
        String base = PluginManifest.baseName(pluginFile.getName());
        File parent = pluginFile.getParentFile();
        File[] candidates = new File[]{parent, jsonDir(), jsonDirAlt(), pluginsDir()};
        for (File dir : candidates) {
            if (dir == null) {
                continue;
            }
            File f = new File(dir, base + ".json");
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /** 按附加包主名找清单(json/ → .json/ → 根目录);都没有返回 null(b39;资源树禁用判定用)。 */
    public static File resolveManifestByBase(String base) {
        if (base == null || base.length() == 0) {
            return null;
        }
        File[] candidates = new File[]{jsonDir(), jsonDirAlt(), pluginsDir()};
        for (File dir : candidates) {
            if (dir == null) {
                continue;
            }
            File f = new File(dir, base + ".json");
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /** 清单写入目标:json 子目录里的 {@code <同主名>.json}(目录确保存在;隐藏式子目录已存在则沿用)。 */
    public static File manifestTargetFor(File pluginFile) {
        File dir = existingOrCanonical(jsonDir(), jsonDirAlt());
        mkdirs(dir);
        return new File(dir, PluginManifest.baseName(pluginFile.getName()) + ".json");
    }

    /** 导入落点:{@code .json} 归 json/,{@code .jar/.zip} 归 jar/,其余(裸 dex)归 dex/。 */
    public static File importDirFor(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        File dir;
        if (lower.endsWith(".json")) {
            dir = existingOrCanonical(jsonDir(), jsonDirAlt());
        } else if (lower.endsWith(".jar") || lower.endsWith(".zip")) {
            dir = existingOrCanonical(jarDir(), jarDirAlt());
        } else {
            dir = existingOrCanonical(dexDir(), dexDirAlt());
        }
        mkdirs(dir);
        return dir;
    }

    /**
     * 导入落地文件:磁盘上已有同名文件(根目录 / dex/ / .dex/ / jar/ / .jar/ / json/ / .json/)时
     * <b>就地覆盖</b>(附加包更新场景,避免产生同 id 的第二份副本);否则放规范子目录。
     */
    public static File importFileFor(String fileName) {
        File[] dirs = new File[]{pluginsDir(), dexDir(), dexDirAlt(), jarDir(), jarDirAlt(), jsonDir(), jsonDirAlt()};
        for (File dir : dirs) {
            File f = new File(dir, fileName);
            if (f.isFile()) {
                return f;
            }
        }
        return new File(importDirFor(fileName), fileName);
    }

    /** jar 对应的转换产物 zip:同目录优先,其次 jar/、.jar/、dex/、.dex/、根目录;都没有返回同目录同主名(用于提示)。 */
    public static File convertedZipFor(File jar) {
        String base = PluginManifest.baseName(jar.getName());
        File[] candidates = new File[]{jar.getParentFile(), jarDir(), jarDirAlt(), dexDir(), dexDirAlt(), pluginsDir()};
        for (File dir : candidates) {
            if (dir == null) {
                continue;
            }
            File f = new File(dir, base + ".zip");
            if (f.isFile()) {
                return f;
            }
        }
        return new File(jar.getParentFile(), base + ".zip");
    }

    /** 展示用相对路径(相对附加包根目录;如 {@code dex/foo.dex};根目录文件 = 文件名)。 */
    public static String displayPath(File f) {
        try {
            String root = pluginsDir().getAbsolutePath();
            String path = f.getAbsolutePath();
            if (path.startsWith(root)) {
                String rel = path.substring(root.length());
                while (rel.startsWith("/") || rel.startsWith("\\")) {
                    rel = rel.substring(1);
                }
                if (rel.length() > 0) {
                    return rel;
                }
            }
        } catch (Throwable ignored) {
        }
        return f.getName();
    }

    private static File existingOrCanonical(File canonical, File alt) {
        if (canonical.isDirectory()) {
            return canonical;
        }
        if (alt.isDirectory()) {
            return alt;
        }
        return canonical;
    }

    /**
     * dex/ 里遗留的 jar/zip 搬到 jar/(b15 起 dex\ 只放 .dex);与附加包同目录的旧式清单一并搬到 json/。
     * 幂等:没有可搬的就什么都不做;目标已有同名时不动(避免覆盖)。
     */
    private static void migrateDexPayloads() {
        try {
            File[] froms = new File[]{dexDir(), dexDirAlt()};
            for (File from : froms) {
                if (!from.isDirectory()) {
                    continue;
                }
                File[] files = from.listFiles();
                if (files == null) {
                    continue;
                }
                for (File f : files) {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    boolean payload = n.endsWith(".jar") || n.endsWith(".jar.done") || n.endsWith(".zip");
                    if (!f.isFile() || !payload) {
                        continue;
                    }
                    File toDir = existingOrCanonical(jarDir(), jarDirAlt());
                    File to = new File(toDir, f.getName());
                    if (to.isFile()) {
                        continue;
                    }
                    // jar/ 按需创建:真有遗留件要搬时才建目录
                    mkdirs(toDir);
                    if (!f.renameTo(to)) {
                        continue;
                    }
                    SafeLog.i("LoaderPaths: moved " + from.getName() + "/" + f.getName()
                            + " -> " + to.getParentFile().getName() + "/" + to.getName());
                    // 旧式:清单与附加包同目录 → 一并搬到 json/
                    File mj = new File(from, PluginManifest.baseName(f.getName()) + ".json");
                    if (mj.isFile()) {
                        File toj = new File(existingOrCanonical(jsonDir(), jsonDirAlt()), mj.getName());
                        if (!toj.isFile() && mj.renameTo(toj)) {
                            SafeLog.i("LoaderPaths: moved " + from.getName() + "/" + mj.getName()
                                    + " -> " + toj.getParentFile().getName() + "/" + toj.getName());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            SafeLog.e("LoaderPaths: migrate dex payloads failed", t);
        }
    }

    private static void mkdirs(File d) {
        if (!d.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
    }
}
