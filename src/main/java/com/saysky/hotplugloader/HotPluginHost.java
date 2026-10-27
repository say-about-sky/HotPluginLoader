package com.saysky.hotplugloader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.badlogic.gdx.Gdx;
import com.saysky.hotplugloader.api.HotContext;
import com.saysky.hotplugloader.api.HotEvents;
import com.saysky.hotplugloader.api.HotListener;
import com.saysky.hotplugloader.api.HotPlugin;
import com.saysky.hotplugloader.convert.ConverterService;

/**
 * 附加包加载器核心:扫描 → 校验 → 建类加载器 → 实例化 → init → 登记。
 *
 * <ul>
 *   <li><b>安卓</b>:{@code .dex} 或含 {@code classes*.dex} 的 {@code .zip},
 *       优先 {@code dalvik.system.InMemoryDexClassLoader}(API 26+,读字节,天然规避
 *       Android 14 的"可写 dex 文件"限制);API &lt; 26 回退 {@code DexClassLoader};</li>
 *   <li><b>桌面</b>(开发调试):{@code .jar} 走 {@code URLClassLoader} ——
 *       JRE 不能加载 dex,桌面只用于联调附加包逻辑;</li>
 *   <li>父加载器一律 {@code HotPluginHost.class.getClassLoader()}(= 游戏加载器),
 *       所以附加包能解析游戏类 / finality-api / 宿主类;</li>
 *   <li>所有异常都收进 {@link PluginRegistry.Entry#error},不向调用方抛出。</li>
 * </ul>
 */
public final class HotPluginHost {

    private static final String TAG = "HotLoader/host";

    private HotPluginHost() {
    }

    /** 传给附加包的上下文实现。 */
    private static final class Ctx implements HotContext {
        private final PluginManifest m;
        private final String base;

        Ctx(PluginManifest m, String base) {
            this.m = m;
            this.base = base;
        }

        @Override
        public String id() {
            return m.id;
        }

        @Override
        public String name() {
            return m.name;
        }

        @Override
        public String version() {
            return m.version;
        }

        @Override
        public String hostVersion() {
            return BuildInfo.BUILD_TAG;
        }

        @Override
        public java.io.File dataDir() {
            // b39:统一资源目录 = hotplugins/resources/<附加包名>/(镜像路径 = 游戏资源覆盖,其余 = 私有)
            return LoaderPaths.resourcesFor(base == null || base.length() == 0 ? m.id : base);
        }

        @Override
        public void log(String message) {
            SafeLog.i("hotplugin[" + m.id + "]: " + message);
        }

        @Override
        public boolean logEnabled() {
            // b46:与「开启日志」总开关同源(SafeLog 同样门控;附加包可据此跳过拼接)
            return LoaderConfig.logEnabled();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 扫描                                                                */
    /* ------------------------------------------------------------------ */

    /** 扫描附加包文件(根目录 + dex/ 子目录;返回 .dex/.zip/.jar,按文件名排序)。 */
    public static List<File> scan() {
        List<File> result = new ArrayList<File>();
        try {
            LoaderPaths.ensureSubDirs();
            // b53:加载/重载期做目录规范化(纠正包容)—— 清理没有同名本体的孤儿清单
            pruneOrphanManifests();
            for (File dir : LoaderPaths.payloadDirs()) {
                File[] files = dir.listFiles();
                if (files == null) {
                    continue;
                }
                for (File f : files) {
                    if (f.isFile() && isPluginFile(f.getName())) {
                        result.add(f);
                    }
                }
            }
            // b21:按 registry.json「order」排序(未登记的按文件名,稳定)
            final List<String> order = PluginStates.order();
            Collections.sort(result, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    int ia = orderIndex(order, a.getName());
                    int ib = orderIndex(order, b.getName());
                    if (ia != ib) {
                        return ia < ib ? -1 : 1;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
        } catch (Throwable t) {
            SafeLog.e(TAG + ": scan failed", t);
        }
        return result;
    }

    private static boolean isPluginFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".dex") || n.endsWith(".zip") || n.endsWith(".jar");
    }

    /**
     * b53:运行期目录规范化(纠正包容)—— 删除 {@code json/} 里没有同名本体(dex/zip/jar)的
     * <b>孤儿清单</b>(例如本体被删除/改名后留下的旧 json);{@code resources/<名>/} 只在日志里
     * 提示、<b>不自动删除</b>(里面可能有用户数据)。
     *
     * <p>由 {@link #scan()}(预载 / 「全部重载」 / 面板刷新)自动调用;幂等。</p>
     *
     * @return 本次删除的清单数量
     */
    public static int pruneOrphanManifests() {
        int removed = 0;
        try {
            java.util.Set<String> bases = new java.util.HashSet<String>();
            for (File dir : LoaderPaths.payloadDirs()) {
                File[] fs = dir.listFiles();
                if (fs == null) {
                    continue;
                }
                for (File f : fs) {
                    if (f.isFile() && isPluginFile(f.getName())) {
                        bases.add(PluginManifest.baseName(f.getName()).toLowerCase(Locale.ROOT));
                    }
                }
            }
            // 旧版转换归档(.jar.done)也算"本体存在",其清单保留
            File[] doneDirs = new File[]{LoaderPaths.jarDir(), new File(LoaderPaths.pluginsDir(), ".jar")};
            for (File d : doneDirs) {
                File[] fs = d.isDirectory() ? d.listFiles() : null;
                if (fs == null) {
                    continue;
                }
                for (File f : fs) {
                    if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar.done")) {
                        String n = f.getName();
                        bases.add(n.substring(0, n.length() - ".jar.done".length()).toLowerCase(Locale.ROOT));
                    }
                }
            }
            File jsonDir = LoaderPaths.jsonDir();
            File[] jsons = jsonDir.isDirectory() ? jsonDir.listFiles() : null;
            if (jsons != null) {
                for (File j : jsons) {
                    if (!j.isFile() || !j.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                        continue;
                    }
                    String base = PluginManifest.baseName(j.getName()).toLowerCase(Locale.ROOT);
                    if (bases.contains(base)) {
                        continue;
                    }
                    if (j.delete()) {
                        removed++;
                        SafeLog.i(TAG + ": 规范化:清理孤儿清单 " + j.getName() + "(没有同名本体)");
                    }
                }
            }
            // 资源目录:只提示(不删)
            File resDir = new File(LoaderPaths.pluginsDir(), "resources");
            File[] dirs = resDir.isDirectory() ? resDir.listFiles() : null;
            if (dirs != null) {
                for (File d : dirs) {
                    if (d.isDirectory() && !bases.contains(d.getName().toLowerCase(Locale.ROOT))) {
                        SafeLog.i(TAG + ": 规范化提示:资源目录 resources/" + d.getName()
                                + " 没有同名本体(不会自动删除;如已废弃请手动清理)");
                    }
                }
            }
        } catch (Throwable t) {
            SafeLog.e(TAG + ": prune orphan manifests failed", t);
        }
        return removed;
    }

    /** 两文件是否同主名(忽视扩展名/大小写;更新场景判定用)。 */
    private static boolean sameBaseName(File a, File b) {
        return PluginManifest.baseName(a.getName()).equalsIgnoreCase(PluginManifest.baseName(b.getName()));
    }

    /** 当前是否运行在安卓(ART)上。 */
    public static boolean isAndroid() {
        try {
            Class.forName("dalvik.system.DexClassLoader", false, HotPluginHost.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 自动加载 / 单文件加载                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 启动预载 / 自动加载目录内全部附加包(b30:由 {@link StartupLoader} 在<b>后台线程</b>调用,
     * 冷加载期间完成;进度写入 {@link LoadStatus},加载屏/面板可读)。返回结果文案。
     */
    public static String autoLoad() {
        try {
            if (!LoaderConfig.hotloadEnabled()) {
                SafeLog.i(TAG + ": auto-load skipped (热加载已禁用)");
                LoadStatus.setLast("热加载已禁用");
                return "热加载已禁用";
            }
            List<File> files = scan();
            LoadStatus.loadBegin(files.size());
            int ok = 0;
            int fail = 0;
            int done = 0;
            for (File f : files) {
                done++;
                if (!f.isFile()) {
                    continue; // 中途被转换流程消费(jar→dex)或删除:跳过(与「全部重载」一致)
                }
                LoadStatus.loadStep(done, f.getName());
                PluginRegistry.Entry e = PluginRegistry.bySource(f);
                if (e != null && isActive(e.status)) {
                    continue;
                }
                // b19:逐文件隔离 —— 一个附加包报错只记失败并跳过,不阻塞其余
                // b37:统计口径与「全部重载」对齐:LOADED=成功;DISABLED=跳过不计;其余(ERROR/BLOCKED/空)=失败
                try {
                    PluginRegistry.Entry r = load(f);
                    if (r != null && r.status == PluginRegistry.Status.LOADED) {
                        ok++;
                    } else if (r != null && r.status == PluginRegistry.Status.DISABLED) {
                        // 注册配置禁用:跳过,不计失败
                    } else {
                        fail++;
                    }
                } catch (Throwable t) {
                    fail++;
                    SafeLog.e(TAG + ": auto-load failed for " + f.getName(), t);
                }
            }
            LoadStatus.loadEnd();
            String msg = "已预载: 成功 " + ok + ",失败 " + fail + "(" + files.size() + " 文件)";
            LoadStatus.setLast(msg);
            SafeLog.i(TAG + ": auto-load done, " + ok + " loaded / " + fail + " failed / "
                    + files.size() + " file(s)");
            // 处理待转换的 .jar(内置 D8;仅安卓;转换产物在转换线程直接加载)
            ConverterService.scanAndConvertAsync();
            return msg;
        } catch (Throwable t) {
            LoadStatus.loadEnd();
            SafeLog.e(TAG + ": auto-load failed", t);
            return "预载失败: " + t;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 重载 / 重置                                                          */
    /* ------------------------------------------------------------------ */

    /** 是否存在已实例化的附加包(诊断/测试用;原位重载的前置状态参照)。 */
    public static boolean hasLoadedInstances() {
        try {
            for (PluginRegistry.Entry e : PluginRegistry.all()) {
                if (e.instance != null) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 退订全部附加包 + 清空注册表(「全部重载」与游戏重启后的新一局都从这里开始)。 */
    public static void resetAll() {
        for (PluginRegistry.Entry e : PluginRegistry.all()) {
            try {
                if (e.instance instanceof HotListener) {
                    HotEvents.unregister((HotListener) e.instance);
                }
            } catch (Throwable t) {
                // 单个附加包退订失败不能拖垮其余附加包的重载
                SafeLog.e(TAG + ": resetAll unregister failed (" + (e.source == null ? "?" : e.source.getName()) + ")", t);
            }
        }
        PluginRegistry.clear();
        HotDebugRegistry.clear(); // b46:调试点一并清空(随重载重扫)
        SafeLog.i(TAG + ": state reset");
    }

    /** 最近一次「全部重载」是否出现致命异常(重置/扫描环节崩溃;面板据此兜底整进程重启)。 */
    private static volatile boolean lastReloadFatal = false;

    /** @see #lastReloadFatal */
    public static boolean lastReloadFatal() {
        return lastReloadFatal;
    }

    /**
     * 「全部重载」主路径 = <b>原位整层重载</b>(不动游戏进程):退订全部附加包、清空注册表,
     * 重新扫描目录并逐个加载,最后补一轮 .jar 转换(内置 D8;仅安卓)。
     *
     * <p>为什么不用重启进程:游戏冷启动要重跑 {@code menus.InitGame} 的 150+ 步加载流水线
     * (整合包上以分钟计),而附加包层本就活在主菜单之后、完全由宿主管理——原位重置即可,
     * 不必让玩家等一遍全量加载。只有重置/扫描环节出现致命异常时才由调用方兜底整进程重启
     * ({@link #lastReloadFatal()})。</p>
     *
     * @return {成功, 失败}(注册配置禁用的不计入失败)
     */
    public static int[] reloadAll() {
        int ok = 0;
        int fail = 0;
        boolean fatal = false;
        try {
            com.saysky.hotplugloader.cold.ColdBootstrap.rescanResources(); // b43:资源覆盖索引一并重扫
        } catch (Throwable ignored) {
        }
        try {
            resetAll();
        } catch (Throwable t) {
            fatal = true;
            SafeLog.e(TAG + ": reload reset failed", t);
        }
        if (!fatal) {
            try {
                for (File f : scan()) {
                    PluginRegistry.Entry r = null;
                    // b19:逐文件隔离 —— 单个附加包异常只记失败,继续后续文件
                    try {
                        r = load(f);
                    } catch (Throwable t) {
                        fail++;
                        SafeLog.e(TAG + ": reload load failed " + f.getName(), t);
                        continue;
                    }
                    if (r != null && r.status == PluginRegistry.Status.LOADED) {
                        ok++;
                    } else if (r != null && r.status == PluginRegistry.Status.DISABLED) {
                        // 注册配置禁用:跳过,不计失败
                    } else {
                        fail++;
                    }
                }
                SafeLog.i(TAG + ": reload done, " + ok + " loaded / " + fail + " failed");
            } catch (Throwable t) {
                fatal = true;
                SafeLog.e(TAG + ": reload failed", t);
            }
        }
        lastReloadFatal = fatal;
        // 重载后同样补一轮 .jar 转换(内置 D8;仅安卓) —— 转换完成的会自行 postLoad
        ConverterService.scanAndConvertAsync();
        return new int[]{ok, fail};
    }

    /** 文件在显式顺序表(order)中的下标;不在表中 = Integer.MAX_VALUE(按主名比较,忽略扩展名/大小写)。 */
    private static int orderIndex(List<String> order, String fileName) {
        String base = PluginManifest.baseName(fileName).toLowerCase(Locale.ROOT);
        for (int i = 0; i < order.size(); i++) {
            if (PluginManifest.baseName(order.get(i)).toLowerCase(Locale.ROOT).equals(base)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 调整某文件在加载顺序中的位置(b21;delta=-1 上移,+1 下移)。
     *
     * @return false = 已在边界 / 未找到 / 失败
     */
    public static boolean movePluginOrder(File f, int delta) {
        try {
            List<File> files = scan();
            int idx = -1;
            String path = f.getAbsolutePath();
            for (int i = 0; i < files.size(); i++) {
                if (files.get(i).getAbsolutePath().equals(path)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0 || delta == 0) {
                return false;
            }
            int tgt = idx + delta;
            if (tgt < 0 || tgt >= files.size()) {
                return false;
            }
            File tmp = files.get(idx);
            files.set(idx, files.get(tgt));
            files.set(tgt, tmp);
            saveOrder(files);
            SafeLog.i(TAG + ": order moved " + f.getName() + " by " + delta);
            return true;
        } catch (Throwable t) {
            SafeLog.e(TAG + ": movePluginOrder failed", t);
            return false;
        }
    }

    /** 保存显式加载顺序(按文件主名写入 registry.json 的 order 数组)。 */
    public static void saveOrder(List<File> ordered) {
        try {
            List<String> names = new ArrayList<String>();
            for (File f : ordered) {
                names.add(PluginManifest.baseName(f.getName()));
            }
            PluginStates.setOrder(names);
        } catch (Throwable t) {
            SafeLog.e(TAG + ": saveOrder failed", t);
        }
    }

    /**
     * 加载单个文件(完整管线;不抛出任何异常,结果都在返回的条目里)。
     *
     * @return 尝试结果条目;清单缺失等极端情况下也返回条目(不登记进注册表)
     */
    public static PluginRegistry.Entry load(File file) {
        SafeLog.v(TAG + ": loading " + file.getName());

        // 0. 单个 .dex 且缺清单:解析 dex 自动生成清单(写入 json/ 子目录;安卓;桌面无法加载 dex)
        try {
            if (LoaderPaths.resolveManifest(file) == null
                    && file.getName().toLowerCase(Locale.ROOT).endsWith(".dex")
                    && isAndroid()) {
                File target = LoaderPaths.manifestTargetFor(file);
                DexManifest.generate(file, target);
                SafeLog.i(TAG + ": auto-generated manifest for " + file.getName()
                        + " -> " + LoaderPaths.displayPath(target));
            }
        } catch (Throwable t) {
            String am = "自动生成清单失败: " + shortMessage(t);
            SafeLog.e(TAG + ": " + file.getName() + " " + am, t);
            return err(file, null, am, false);
        }

        // 1. 清单
        final PluginManifest manifest;
        try {
            manifest = PluginManifest.load(file);
        } catch (Throwable t) {
            String msg = shortMessage(t);
            SafeLog.e(TAG + ": " + file.getName() + " 清单错误: " + msg, t);
            return err(file, null, msg, false);
        }

        // 1.5 旧「D8 转换器」附加包:功能已内置到补丁,本文件不再需要(给出明确提示)
        if (ConverterService.LEGACY_CONVERTER_ID.equals(manifest.id)) {
            return err(file, manifest, "已内置转换器(请删除本文件)", false);
        }

        // 2. 注册配置(registry.json):被禁用的 id 只登记「已禁用」,不实例化、不执行 init
        if (PluginStates.isDisabled(manifest.id)) {
            PluginRegistry.Entry prev = PluginRegistry.get(manifest.id);
            if (prev != null && prev.instance instanceof HotListener) {
                // 之前加载过(运行中禁用 → 重启后配置生效):退订旧实例,避免幽灵监听
                HotEvents.unregister((HotListener) prev.instance);
            }
            PluginRegistry.Entry e = new PluginRegistry.Entry(file, manifest);
            e.status = PluginRegistry.Status.DISABLED;
            PluginRegistry.put(e);
            SafeLog.i(TAG + ": " + file.getName() + " disabled by registry.json (id=" + manifest.id + ")");
            return e;
        }

        // 3. id 冲突(同 id 来自不同文件)
        PluginRegistry.Entry clash = PluginRegistry.conflictOf(manifest.id, file);
        if (clash != null && sameBaseName(file, clash.source)
                && file.lastModified() > clash.source.lastModified()) {
            // 同名且更新:视为「同一附加包的新副本」(导入/手拷更新场景) → 让本文件替代旧副本;
            // 旧实例由下面的「重载语义」退订。
            SafeLog.i(TAG + ": " + file.getName() + " supersedes "
                    + LoaderPaths.displayPath(clash.source) + " (same name, newer)");
            clash = null;
        }
        if (clash != null) {
            String msg;
            if (sameBaseName(file, clash.source)
                    && file.lastModified() < clash.source.lastModified()) {
                msg = "已被较新的同名副本替代(保留 " + LoaderPaths.displayPath(clash.source) + ")";
            } else {
                msg = "id 与 " + LoaderPaths.displayPath(clash.source) + " 重复";
            }
            SafeLog.e(TAG + ": " + file.getName() + " " + msg, null);
            return err(file, manifest, msg, false);
        }

        // 4. 重载语义:先退掉旧实例的事件订阅
        PluginRegistry.Entry prev = PluginRegistry.get(manifest.id);
        if (prev != null && prev.instance instanceof HotListener) {
            HotEvents.unregister((HotListener) prev.instance);
        }

        // 5. 信任门槛
        if (!manifest.trusted && !LoaderConfig.allowUntrusted()) {
            PluginRegistry.Entry e = new PluginRegistry.Entry(file, manifest);
            e.status = PluginRegistry.Status.BLOCKED;
            e.error = "未信任(开「允许未校验附加包」或清单 trusted:true)";
            PluginRegistry.put(e);
            SafeLog.i(TAG + ": blocked (untrusted) " + file.getName());
            return e;
        }

        // 6. 建类加载器 → 实例化 → init
        Object instance = null;
        ClassLoader loader = null;
        long loadStartMs = System.currentTimeMillis();
        try {
            loader = createLoader(file);
            Class<?> cls = Class.forName(manifest.mainClass, true, loader);
            instance = cls.getDeclaredConstructor().newInstance();
            Ctx ctx = new Ctx(manifest, PluginManifest.baseName(file.getName()));
            if (instance instanceof HotPlugin) {
                ((HotPlugin) instance).init(ctx);
            } else if (instance instanceof team.rainfall.finality.api.Plugin) {
                initFrameworkPlugin((team.rainfall.finality.api.Plugin) instance, manifest);
            } else if (hasPublicInit(cls)) {
                cls.getMethod("init").invoke(instance);
            } else {
                throw new Exception("主类未实现 HotPlugin 接口,且没有 public init()");
            }
        } catch (Throwable t) {
            if (instance instanceof HotListener) {
                HotEvents.unregister((HotListener) instance);
            }
            String msg = shortMessage(t);
            SafeLog.e(TAG + ": load failed " + file.getName() + ": " + msg, t);
            return err(file, manifest, msg, true);
        }

        // 7. 登记
        PluginRegistry.Entry e = new PluginRegistry.Entry(file, manifest);
        e.status = PluginRegistry.Status.LOADED;
        e.instance = instance;
        e.loader = loader;
        e.loadMs = System.currentTimeMillis() - loadStartMs;
        e.loadedAtMs = System.currentTimeMillis();
        PluginRegistry.put(e);
        SafeLog.i(TAG + ": loaded " + manifest.id + " v" + manifest.version
                + " [" + file.getName() + "] main=" + manifest.mainClass);
        // b39:附加包已加载 → 其资源树即时生效(冷启动已扫描;这里覆盖"启动后才出现"的资源目录)
        try {
            com.saysky.hotplugloader.cold.ColdBootstrap.setResourcesActive(
                    PluginManifest.baseName(file.getName()), true);
        } catch (Throwable ignored) {
        }
        // b46/b47:扫描 @HotDebug 调试点;「开启日志」时自动执行一次(结果直接写日志)
        try {
            HotDebugRegistry.scan(manifest.id, file, loader, instance);
            if (LoaderConfig.logEnabled()) {
                HotDebugRegistry.runMarked(manifest.id);
            }
        } catch (Throwable ignored) {
        }
        return e;
    }

    /**
     * 启用/禁用。
     *
     * <ul>
     *   <li>禁用 = 停止事件派发 + 写入注册配置({@code registry.json}):下次启动不加载、不实例化;</li>
     *   <li>启用 = 移出注册配置;若此前因配置禁用而未加载,则直接完整加载。</li>
     * </ul>
     */
    public static boolean setEnabled(String id, boolean enabled) {
        try {
            PluginRegistry.Entry e = PluginRegistry.get(id);
            if (e == null) {
                return false;
            }
            if (enabled) {
                PluginStates.setDisabled(id, false);
                // b39:资源树随启用即时生效
                try {
                    com.saysky.hotplugloader.cold.ColdBootstrap.setResourcesActive(baseOf(e), true);
                } catch (Throwable ignored) {
                }
                if (e.instance == null) {
                    // 配置禁用的附加包此前没有加载:现在完整加载
                    PluginRegistry.Entry r = load(e.source);
                    boolean ok = r != null && r.status == PluginRegistry.Status.LOADED;
                    SafeLog.i(TAG + ": " + (ok ? "enabled (loaded) " : "enable failed ") + id);
                    return ok;
                }
                if (e.instance instanceof HotListener) {
                    HotEvents.setEnabled((HotListener) e.instance, true);
                }
                e.status = PluginRegistry.Status.LOADED;
                e.error = null;
                SafeLog.i(TAG + ": enabled " + id);
                return true;
            }
            if (e.instance instanceof HotListener) {
                HotEvents.setEnabled((HotListener) e.instance, false);
            }
            e.status = PluginRegistry.Status.DISABLED;
            PluginStates.setDisabled(id, true);
            // b39:资源树随禁用即时停用
            try {
                com.saysky.hotplugloader.cold.ColdBootstrap.setResourcesActive(baseOf(e), false);
            } catch (Throwable ignored) {
            }
            try {
                HotDebugRegistry.removePlugin(id); // b46:调试点随禁用注销
            } catch (Throwable ignored) {
            }
            SafeLog.i(TAG + ": disabled " + id);
            return true;
        } catch (Throwable t) {
            SafeLog.e(TAG + ": setEnabled failed", t);
            return false;
        }
    }

    /** 注销附加包(停止派发 + 移出登记表;已加载的类无法卸载,重启后彻底消失)。 */
    public static boolean remove(String id) {
        try {
            PluginRegistry.Entry e = PluginRegistry.remove(id);
            if (e == null) {
                return false;
            }
            if (e.instance instanceof HotListener) {
                HotEvents.unregister((HotListener) e.instance);
            }
            // b39:资源树即时停用(删文件与否由调用方决定)
            try {
                com.saysky.hotplugloader.cold.ColdBootstrap.setResourcesActive(baseOf(e), false);
            } catch (Throwable ignored) {
            }
            try {
                HotDebugRegistry.removePlugin(id); // b46:调试点随删除注销
            } catch (Throwable ignored) {
            }
            SafeLog.i(TAG + ": removed " + id);
            return true;
        } catch (Throwable t) {
            SafeLog.e(TAG + ": remove failed", t);
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 帧事件                                                              */
    /* ------------------------------------------------------------------ */

    /** 每帧调用一次(由 HotLoaderTick 驱动)。 */
    public static void onFrame() {
        try {
            if (!HotEvents.hasListeners(HotEvents.FRAME)) {
                return;
            }
            Object[] args = new Object[]{
                    Long.valueOf(Gdx.graphics.getFrameId()),
                    Float.valueOf(Gdx.graphics.getRawDeltaTime())};
            HotEvents.dispatch(HotEvents.FRAME, args);
        } catch (Throwable t) {
            SafeLog.e(TAG + ": frame dispatch failed", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 内部工具                                                            */
    /* ------------------------------------------------------------------ */

    /** 创建类加载器;平台不支持该文件类型时抛出带说明的异常。 */
    private static ClassLoader createLoader(File file) throws Exception {
        String name = file.getName().toLowerCase(Locale.ROOT);
        ClassLoader parent = HotPluginHost.class.getClassLoader();

        if (isAndroid()) {
            if (name.endsWith(".jar")) {
                throw new Exception("安卓端不能加载 .jar(请用 d8 转成 .dex)");
            }
            Class<?> inMemory = tryLoadClass("dalvik.system.InMemoryDexClassLoader");
            if (inMemory != null) {
                // API 26+:读字节直接建加载器(不落盘,规避"可写 dex 文件"限制)
                ByteBuffer[] buffers = name.endsWith(".zip")
                        ? readZipDex(file)
                        : new ByteBuffer[]{ByteBuffer.wrap(PluginManifest.readAll(file))};
                if (buffers.length == 1) {
                    Constructor<?> c = inMemory.getConstructor(ByteBuffer.class, ClassLoader.class);
                    return (ClassLoader) c.newInstance(buffers[0], parent);
                }
                Constructor<?> c = inMemory.getConstructor(ByteBuffer[].class, ClassLoader.class);
                return (ClassLoader) c.newInstance((Object) buffers, parent);
            }
            // API < 26 回退:DexClassLoader(zip 也可直接传路径,ART 自己解)
            Class<?> dcl = Class.forName("dalvik.system.DexClassLoader");
            Constructor<?> c = dcl.getConstructor(String.class, String.class, String.class, ClassLoader.class);
            return (ClassLoader) c.newInstance(file.getAbsolutePath(), optimizedDir(), null, parent);
        }

        // 桌面(开发调试)
        if (name.endsWith(".dex")) {
            throw new Exception("桌面端无法加载 .dex(开发调试请用 .jar;.dex 仅安卓可用)");
        }
        if (name.endsWith(".zip")) {
            throw new Exception("桌面端不支持 .zip 附加包(请用 .jar)");
        }
        return new URLClassLoader(new URL[]{file.toURI().toURL()}, parent);
    }

    private static Class<?> tryLoadClass(String className) {
        try {
            return Class.forName(className, false, HotPluginHost.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从 zip 里按 classes.dex / classes2.dex / … 的顺序读出全部 dex 字节。 */
    private static ByteBuffer[] readZipDex(File zip) throws Exception {
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
                byte[] buf = new byte[16384];
                int r;
                while ((r = zin.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
                names.add(base);
                datas.add(out.toByteArray());
            }
            if (datas.isEmpty()) {
                throw new Exception("zip 内没有 classes*.dex");
            }
            // 按数字序排列(classes.dex=1, classes2.dex=2, …)
            ByteBuffer[] buffers = new ByteBuffer[datas.size()];
            int[] used = new int[datas.size()];
            for (int i = 0; i < datas.size(); i++) {
                int best = -1;
                int bestIdx = Integer.MAX_VALUE;
                for (int j = 0; j < datas.size(); j++) {
                    if (used[j] != 0) {
                        continue;
                    }
                    int idx = dexIndex(names.get(j));
                    if (idx < bestIdx) {
                        bestIdx = idx;
                        best = j;
                    }
                }
                used[best] = 1;
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

    private static boolean isClassesDex(String base) {
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

    private static int dexIndex(String base) {
        String mid = base.substring("classes".length(), base.length() - ".dex".length());
        return mid.length() == 0 ? 1 : Integer.parseInt(mid);
    }

    /** DexClassLoader 的优化目录(API < 26 回退用)。 */
    private static String optimizedDir() {
        try {
            Object ctx = Gdx.app.getClass().getMethod("getContext").invoke(Gdx.app);
            Object dir = ctx.getClass().getMethod("getCodeCacheDir").invoke(ctx);
            if (dir instanceof File) {
                return ((File) dir).getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return System.getProperty("java.io.tmpdir");
    }

    /** 兼容分支:附加包主类继承框架 Plugin(填好内部字段再调 init)。 */
    private static void initFrameworkPlugin(team.rainfall.finality.api.Plugin plugin, PluginManifest m) {
        ReflectUtil.set(plugin, "name", m.name);
        ReflectUtil.set(plugin, "pluginID", m.id);
        try {
            ReflectUtil.set(plugin, "logger", team.rainfall.finality.api.logging.Logger.getLogger(m.name));
        } catch (Throwable ignored) {
        }
        plugin.init();
    }

    private static boolean hasPublicInit(Class<?> cls) {
        try {
            cls.getMethod("init");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isActive(PluginRegistry.Status s) {
        return s == PluginRegistry.Status.LOADED || s == PluginRegistry.Status.DISABLED;
    }

    /** 条目来源文件的主名(= resources/<附加包名>/ 的目录名;b39)。 */
    private static String baseOf(PluginRegistry.Entry e) {
        try {
            return e != null && e.source != null ? PluginManifest.baseName(e.source.getName()) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static PluginRegistry.Entry err(File file, PluginManifest manifest, String message, boolean register) {
        PluginRegistry.Entry e = new PluginRegistry.Entry(file, manifest);
        e.status = PluginRegistry.Status.ERROR;
        e.error = message;
        if (register && manifest != null) {
            PluginRegistry.put(e);
        }
        return e;
    }

    /** 取异常链最深处的原因消息(供玩家看的短文案)。 */
    private static String shortMessage(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        Throwable cur = t;
        int guard = 0;
        while (cur.getCause() != null && cur.getCause() != cur && guard++ < 10) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        if (m == null || m.length() == 0) {
            m = cur.getClass().getSimpleName();
        }
        return m;
    }
}
