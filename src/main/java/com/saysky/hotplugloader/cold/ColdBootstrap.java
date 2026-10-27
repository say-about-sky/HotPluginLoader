package com.saysky.hotplugloader.cold;

import java.io.File;

import aoc.kingdoms.lukasz.jakowski.FileManager;

import com.saysky.hotplugloader.LoaderPaths;
import com.saysky.hotplugloader.SafeLog;

/**
 * 【最早注入】(b28):冷加载开始前换装「底层资源覆盖接口」。
 *
 * <p>链路:启动类({@code AndroidLauncher} / {@code AlternativeLauncher})onCreate
 * → {@link #startThread()}(后台线程)→ 等游戏把 {@link FileManager#loadInterface}
 * 初始化出来(由 {@code AA_Game.create()} 的第一条指令
 * {@code FileManager.initLoadInterface()} 赋值)→ 换装成 {@link ResourceOverride}
 * (装饰原实现)→ 之后游戏所有 {@code FileManager.loadFile(...)} 都先查附加包资源树
 * ({@code hotplugins/resources/<附加包名>/override/}),未命中则原样走原接口。</p>
 *
 * <p>为什么要"等":onCreate 时接口尚未创建;而 initLoadInterface 会无条件赋值,
 * 过早换装会被它覆盖 —— 必须等它先就位(线程每 50ms 轮询,最长等 120 秒)。</p>
 *
 * <p>本类实现 {@link Runnable}(不用匿名内部类,不新增 $1 类);永不抛出异常。
 * 兜底:{@code HotLoaderTick} 每帧还会调一次 {@link #ensureInstalled()}。</p>
 */
public final class ColdBootstrap implements Runnable {

    private static final String TAG = "HotLoader/cold";
    /** 等接口就位的上限(毫秒)。 */
    private static final long WAIT_LIMIT_MS = 120000L;
    /** 轮询间隔(毫秒)。 */
    private static final long POLL_MS = 50L;

    private static final ColdBootstrap INSTANCE = new ColdBootstrap();
    private static volatile boolean started = false;
    private static volatile ResourceOverride installed = null;

    private ColdBootstrap() {
    }

    /** 启动类 onCreate 调用(幂等;永不抛出)。 */
    public static void startThread() {
        if (started) {
            return;
        }
        started = true;
        try {
            Thread t = new Thread(INSTANCE, "HotLoader-ColdBootstrap");
            t.setDaemon(true);
            t.start();
            SafeLog.i(TAG + ": bootstrap thread started");
        } catch (Throwable t) {
            SafeLog.e(TAG + ": startThread failed", t);
        }
    }

    @Override
    public void run() {
        installWhenReady();
    }

    private static void installWhenReady() {
        try {
            long waited = 0L;
            while (waited < WAIT_LIMIT_MS) {
                FileManager.LoadInterface cur = null;
                try {
                    cur = FileManager.loadInterface;
                } catch (Throwable ignored) {
                }
                if (cur != null) {
                    break;
                }
                Thread.sleep(POLL_MS);
                waited += POLL_MS;
            }
            installNow(waited);
        } catch (Throwable t) {
            SafeLog.e(TAG + ": install thread failed", t);
        }
    }

    /** 兜底(每帧 tick 调用;只做一次 volatile 读,开销可忽略)。 */
    public static void ensureInstalled() {
        if (installed != null) {
            return;
        }
        installNow(-1L);
    }

    private static synchronized void installNow(long waited) {
        if (installed != null) {
            return;
        }
        try {
            FileManager.LoadInterface cur = FileManager.loadInterface;
            if (cur == null) {
                if (waited >= 0L) {
                    SafeLog.i(TAG + ": loadInterface not ready after " + waited
                            + "ms; give up (backstop will retry)");
                }
                return;
            }
            if (cur instanceof ResourceOverride) {
                installed = (ResourceOverride) cur;
                return;
            }
            if (isOurImpl(cur)) {
                attach(cur);
                return;
            }
            ResourceOverride ro = new ResourceOverride(cur, LoaderPaths.resourcesDir());
            FileManager.loadInterface = ro;
            installed = ro;
            SafeLog.i(TAG + ": resource override installed (files=" + ro.size()
                    + ", trees=" + ro.activeTrees() + "/" + ro.treeCount()
                    + (waited >= 0L ? ", wait=" + waited + "ms" : ", backstop")
                    + ", dir=" + ro.baseDir().getAbsolutePath() + ")");
            hintLegacyOverride();
        } catch (Throwable t) {
            SafeLog.e(TAG + ": install failed", t);
        }
        kickColdTick();
    }

    /**
     * 由替换版 {@code FileManager$3} 构造时调用(发生在游戏 {@code AA_Game.create()}
     * 第一条指令 {@code FileManager.initLoadInterface()} 内部)—— 最早时机的直挂。
     * 返回建立/已有的覆盖层(未成功返回 null)。
     */
    public static ResourceOverride attach(FileManager.LoadInterface self) {
        synchronized (ColdBootstrap.class) {
            if (installed == null) {
                try {
                    installed = new ResourceOverride(self, LoaderPaths.resourcesDir());
                    SafeLog.i(TAG + ": resource override attached (files=" + installed.size()
                            + ", trees=" + installed.activeTrees() + "/" + installed.treeCount()
                            + ", dir=" + installed.baseDir().getAbsolutePath()
                            + ", via=FileManager$3)");
                    hintLegacyOverride();
                } catch (Throwable t) {
                    SafeLog.e(TAG + ": attach failed", t);
                }
            }
            kickColdTick();
            return installed;
        }
    }

    /** b33:点火冷加载后期闸(ColdTick:等启动图结束/加载屏出现后才开始静默预载)。永不抛出。 */
    private static void kickColdTick() {
        try {
            com.saysky.hotplugloader.ColdTick.ensureStarted();
        } catch (Throwable ignored) {
        }
    }

    /** b39:启用/禁用某附加包的资源树(宿主在 附加包加载/启用/禁用/删除 时调用;返回是否定位到该树)。 */
    public static boolean setResourcesActive(String base, boolean active) {
        ResourceOverride ro = installed;
        if (ro == null) {
            ensureInstalled();
            ro = installed;
        }
        return ro != null && ro.setTreeActive(base, active);
    }

    /**
     * b43:「全部重载」时重扫全部资源树(resources/&lt;附加包名&gt;/override/):把运行中新增/改动/删除的
     * 文件重新入索引,之后的读取(新开一局、按需读取的音乐等)即命中新内容;已进入内存的启动期
     * 资源(贴图/JSON/字体等)仍需重启游戏才能替换(引擎缓存模型)。返回索引条目总数;-1 = 覆盖层不可用。
     */
    public static int rescanResources() {
        ResourceOverride ro = installed;
        if (ro == null) {
            ensureInstalled();
            ro = installed;
        }
        if (ro == null) {
            return -1;
        }
        int n = ro.rescan();
        SafeLog.i(TAG + ": resources rescanned (" + n + " file(s), trees="
                + ro.activeTrees() + "/" + ro.treeCount() + ")");
        notifyArmyVoiceRescan();
        return n;
    }

    /**
     * 资源树重扫后通知可选的热更新消费者（LoaderSettings 补丁 dex 的 ArmyVoice）。
     *
     * <p>ArmyVoice 只推进内部「资源代际」：下一次点兵牌时按需重扫该国家对应的目录，
     * 因此这里不需要扫描、不加载音频，对帧无开销。反射调用，未安装该补丁时静默跳过
     * （不引入编译期依赖）。</p>
     */
    private static void notifyArmyVoiceRescan() {
        try {
            Class.forName("com.saysky.armyvoice.ArmyVoice")
                    .getMethod("invalidate")
                    .invoke(null);
        } catch (Throwable ignored) {
        }
    }

    /** 旧 override/ 目录(b28 已弃用;b40 迁到 resources/<附加包名>/override/)存在且有文件时提示一次。 */
    private static void hintLegacyOverride() {
        try {
            File legacy = new File(LoaderPaths.pluginsDir(), "override");
            File[] ls = legacy.isDirectory() ? legacy.listFiles() : null;
            if (ls != null && ls.length > 0) {
                SafeLog.i(TAG + ": 旧 override/ 目录已弃用,请把文件移到 resources/<附加包名>/override/ 下(镜像同样路径)");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 当前接口是否为我们替换版 FileManager$3(反射识别特征字段)。 */
    private static boolean isOurImpl(FileManager.LoadInterface cur) {
        try {
            cur.getClass().getField("HB_HOTLOADER_FM3_MARKER");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 已安装的覆盖层;未安装返回 null(附加包可用它做 register/rescan)。 */
    public static ResourceOverride installed() {
        return installed;
    }

    /** 便捷 API:注册覆盖(未安装时先尝试安装;返回是否成功)。 */
    public static boolean register(String gamePath, File file) {
        ResourceOverride ro = installed;
        if (ro == null) {
            ensureInstalled();
            ro = installed;
        }
        return ro != null && ro.register(gamePath, file);
    }

    /** 便捷 API:移除覆盖(返回是否确实删掉了)。 */
    public static boolean unregister(String gamePath) {
        ResourceOverride ro = installed;
        return ro != null && ro.unregister(gamePath);
    }
}
