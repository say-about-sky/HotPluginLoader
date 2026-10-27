package com.saysky.hotplugloader.cold;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import aoc.kingdoms.lukasz.jakowski.FileManager;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.saysky.hotplugloader.LoaderPaths;
import com.saysky.hotplugloader.PluginManifest;
import com.saysky.hotplugloader.PluginStates;
import com.saysky.hotplugloader.SafeLog;

/**
 * 【底层资源覆盖接口】(b28 引入;b39 多根重构;b40 override 子目录)
 *
 * <p>装饰游戏的 {@code FileManager.LoadInterface}:查询路径命中「附加包资源树」时直接返回覆盖文件,
 * 未命中一律 100% 转发原实现 —— 桌面的 Steam 创意工坊/mods → external → internal 与安卓的
 * local → assets 原有加载机制原样保留。</p>
 *
 * <p><b>资源树(b40)</b>:{@code hotplugins/resources/<附加包名>/override/} —— 该子目录内按
 * 「与游戏内完全一致的相对路径」摆放的文件 = 该附加包对游戏资源的覆盖;
 * {@code resources/<附加包名>/} 下的其余文件 = 附加包私有资源/数据(不参与覆盖,
 * 附加包经 {@code HotContext.dataDir()} 读写)。
 * 多附加包命中同一路径时:按附加包加载顺序(registry.json 的 {@code order},未列出的按名称排序)
 * <b>先者优先</b>。</p>
 *
 * <p><b>启用门控</b>:附加包被禁用(registry.json 的 {@code disabled})时其资源树不参与命中;
 * 面板 加载/启用/禁用/删除 附加包时由宿主实时切换({@link #setTreeActive(String, boolean)})。</p>
 *
 * <p>索引 = 构造时扫描一次;运行中改动可用 {@link #rescan()};亦可
 * {@link #register(String, File)}(显式映射,优先级最高)/ {@link #unregister(String)}。</p>
 *
 * <p>匹配规则:路径统一把 {@code \\} 归一为 {@code /}、去掉前导 {@code /} 与 {@code ./};
 * 先精确匹配,未中再大小写不敏感兜底。返回的 FileHandle 为 Absolute 类型
 * (与原接口可能给的 Internal/Local 类型不同,但按字节流读的行为一致)。</p>
 */
public final class ResourceOverride implements FileManager.LoadInterface {

    private static final String TAG = "HotLoader/resources";

    private final FileManager.LoadInterface original;
    private final File baseDir;
    /** 资源树:<附加包名(=dex/json 文件主名)> → 树。 */
    private final Map<String, Tree> trees = new LinkedHashMap<String, Tree>();
    /** 显式注册(附加包 API;优先级最高)。 */
    private final Map<String, File> explicit = new HashMap<String, File>();
    private final Map<String, File> explicitLower = new HashMap<String, File>();

    /** 单个附加包的资源树。 */
    private static final class Tree {
        final File dir;
        final Map<String, File> index = new HashMap<String, File>();
        final Map<String, File> indexLower = new HashMap<String, File>();
        boolean active = true;

        Tree(File dir) {
            this.dir = dir;
        }
    }

    public ResourceOverride(FileManager.LoadInterface original, File baseDir) {
        this.original = original;
        this.baseDir = baseDir;
        rescan();
    }

    /** 重扫 resources/ 下全部附加包资源树(返回条目总数;启用状态按 registry.json 重新判定)。 */
    public synchronized int rescan() {
        trees.clear();
        List<File> dirs = new ArrayList<File>();
        File[] children = baseDir == null ? null : baseDir.listFiles();
        if (children != null) {
            for (File d : children) {
                if (d.isDirectory()) {
                    dirs.add(d);
                }
            }
        }
        sortByPluginOrder(dirs);
        int total = 0;
        for (File d : dirs) {
            File treeRoot = new File(d, "override");
            if (!treeRoot.isDirectory()) {
                continue; // 无 override/ 子目录:纯私有资源,不参与覆盖(b40)
            }
            Tree t = new Tree(treeRoot);
            scanInto(t, treeRoot, "");
            t.active = !disabledByRegistry(d.getName());
            trees.put(d.getName(), t);
            total += t.index.size();
        }
        return total;
    }

    /** 命中查询(供诊断/测试):返回覆盖文件,未命中 null。 */
    public synchronized File find(String gamePath) {
        if (gamePath == null) {
            return null;
        }
        String key = normalize(gamePath);
        String lower = key.toLowerCase(Locale.ROOT);
        File f = explicit.get(key);
        if (f == null) {
            f = explicitLower.get(lower);
        }
        if (f != null) {
            return f;
        }
        for (Tree t : trees.values()) {
            if (!t.active) {
                continue;
            }
            f = t.index.get(key);
            if (f == null) {
                f = t.indexLower.get(lower);
            }
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    @Override
    public FileHandle loadFile(String sFile) {
        File f = find(sFile);
        if (f != null) {
            try {
                return new FileHandle(f);
            } catch (Throwable ignored) {
                // 极端情况(旧版 gdx 无此构造?)退回原接口
            }
        }
        return original == null ? null : original.loadFile(sFile);
    }

    @Override
    public FileHandle getSaveType(String sFile) {
        return original == null ? null : original.getSaveType(sFile);
    }

    /** 注册一条显式覆盖(游戏路径 → 本地文件;优先级高于所有资源树;文件必须已存在)。 */
    public synchronized boolean register(String gamePath, File file) {
        if (gamePath == null || file == null || !file.isFile()) {
            return false;
        }
        String key = normalize(gamePath);
        if (key.length() == 0) {
            return false;
        }
        explicit.put(key, file);
        explicitLower.put(key.toLowerCase(Locale.ROOT), file);
        return true;
    }

    /** 移除一条显式覆盖(返回是否确实删掉了)。 */
    public synchronized boolean unregister(String gamePath) {
        if (gamePath == null) {
            return false;
        }
        String key = normalize(gamePath);
        explicitLower.remove(key.toLowerCase(Locale.ROOT));
        return explicit.remove(key) != null;
    }

    /**
     * 切换某附加包资源树的启用状态(宿主在 加载/启用/禁用/删除 时调用)。
     * 未找到该树时:{@code active=true} 且目录存在则即时纳入索引。
     */
    public synchronized boolean setTreeActive(String base, boolean active) {
        if (base == null || base.length() == 0) {
            return false;
        }
        Tree t = trees.get(base);
        if (t == null) {
            if (!active) {
                return false;
            }
            File treeRoot = new File(new File(baseDir, base), "override");
            if (!treeRoot.isDirectory()) {
                return false;
            }
            t = new Tree(treeRoot);
            scanInto(t, treeRoot, "");
            trees.put(base, t);
            SafeLog.i(TAG + ": resources tree '" + base + "' added (" + t.index.size() + " file(s))");
            return true;
        }
        if (t.active != active) {
            t.active = active;
            SafeLog.i(TAG + ": resources tree '" + base + "' -> "
                    + (active ? "active" : "inactive"));
        }
        return true;
    }

    /** 当前索引条目总数(全部资源树 + 显式注册;含未启用的树)。 */
    public synchronized int size() {
        int n = explicit.size();
        for (Tree t : trees.values()) {
            n += t.index.size();
        }
        return n;
    }

    /** 启用中的资源树数量(诊断/日志用)。 */
    public synchronized int activeTrees() {
        int n = 0;
        for (Tree t : trees.values()) {
            if (t.active) {
                n++;
            }
        }
        return n;
    }

    /** 资源树总数(诊断/日志用)。 */
    public synchronized int treeCount() {
        return trees.size();
    }

    /** 资源根目录(hotplugins/resources)。 */
    public File baseDir() {
        return baseDir;
    }

    /* ------------------------------------------------------------------ */

    private static void scanInto(Tree t, File dir, String prefix) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                scanInto(t, f, prefix + f.getName() + "/");
            } else if (f.isFile()) {
                String key = prefix + f.getName();
                t.index.put(key, f);
                String lower = key.toLowerCase(Locale.ROOT);
                if (!t.indexLower.containsKey(lower)) {
                    t.indexLower.put(lower, f);
                }
            }
        }
    }

    /** 按附加包加载顺序排序资源树(registry.json 的 order;未列出的按名称;b39)。 */
    private static void sortByPluginOrder(List<File> dirs) {
        List<String> order = PluginStates.order();
        for (int i = 0; i < dirs.size(); i++) {
            for (int j = i + 1; j < dirs.size(); j++) {
                if (ranksBefore(dirs.get(j), dirs.get(i), order)) {
                    File t = dirs.get(i);
                    dirs.set(i, dirs.get(j));
                    dirs.set(j, t);
                }
            }
        }
    }

    private static boolean ranksBefore(File a, File b, List<String> order) {
        int ra = order == null || order.isEmpty() ? -1 : order.indexOf(a.getName());
        int rb = order == null || order.isEmpty() ? -1 : order.indexOf(b.getName());
        if (ra < 0 && rb < 0) {
            return a.getName().compareToIgnoreCase(b.getName()) < 0;
        }
        if (ra < 0) {
            return false;
        }
        if (rb < 0) {
            return true;
        }
        if (ra != rb) {
            return ra < rb;
        }
        return a.getName().compareToIgnoreCase(b.getName()) < 0;
    }

    /** 附加包(按主名)是否被 registry.json 禁用(清单缺失时按启用处理;b39)。 */
    private static boolean disabledByRegistry(String base) {
        try {
            File json = LoaderPaths.resolveManifestByBase(base);
            if (json == null) {
                return false;
            }
            String text = new String(PluginManifest.readAll(json), "UTF-8");
            JsonValue root = new JsonReader().parse(text);
            String id = root == null ? null : root.getString("id", null);
            return PluginStates.isDisabled(id);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 路径归一化:{@code \\}→{@code /},去前导 {@code /} 与 {@code ./}。 */
    static String normalize(String p) {
        String s = p.replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        return s;
    }
}
