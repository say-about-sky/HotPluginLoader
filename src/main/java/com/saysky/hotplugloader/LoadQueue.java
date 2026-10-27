package com.saysky.hotplugloader;

import java.io.File;
import java.util.List;

import com.saysky.hotplugloader.cold.ColdBootstrap;
import com.saysky.hotplugloader.convert.ConverterService;

/**
 * 「全部重载」的队列化执行(b18):resetAll + 扫描入队后,由每帧循环
 * ({@link HotLoaderTick})以约 10ms/帧 的预算推进,进度实时写入 {@link LoadStatus}。
 *
 * <p>为什么队列化:仿游戏自身加载({@code menus.InitGame} 的分帧状态机,iStepID 逐步推进)——
 * 一口气同步加载会阻塞渲染线程、进度也没机会渲染;分帧后界面流畅、
 * 状态行/进度条能逐项跳动,每步也都有日志可查。</p>
 */
public final class LoadQueue {

    private static final String TAG = "HotLoader/queue";

    /** 每帧加载时间预算(纳秒);每帧至少推进 1 个文件。 */
    private static final long FRAME_BUDGET_NS = 10_000_000L;

    private static List<File> queue;
    private static int index;
    private static int ok;
    private static int fail;
    private static long beginMs;

    private LoadQueue() {
    }

    public static boolean isActive() {
        return queue != null;
    }

    /**
     * 开始一次「全部重载」:退订清表 → 扫描入队 → 补一轮 .jar 转换(后台并行)。
     *
     * @return false = 重置/扫描环节出现致命异常(调用方可兜底整进程重启)
     */
    public static boolean beginReloadAll() {
        if (queue != null) {
            return true; // 进行中:忽略重入
        }
        try {
            ColdBootstrap.rescanResources(); // b43:资源覆盖索引一并重扫(新文件对"之后的读取"生效)
        } catch (Throwable ignored) {
        }
        try {
            HotPluginHost.resetAll();
        } catch (Throwable t) {
            SafeLog.e(TAG + ": reset failed", t);
            return false;
        }
        try {
            queue = HotPluginHost.scan();
        } catch (Throwable t) {
            SafeLog.e(TAG + ": scan failed", t);
            return false;
        }
        index = 0;
        ok = 0;
        fail = 0;
        beginMs = System.currentTimeMillis();
        LoadStatus.loadBegin(queue.size());
        // .jar 转换与加载并行(转换完成的会自行 postLoad)
        try {
            ConverterService.scanAndConvertAsync();
        } catch (Throwable ignored) {
        }
        SafeLog.i(TAG + ": reload started, " + queue.size() + " file(s)");
        return true;
    }

    /** 每帧推进(由 HotLoaderTick 调用;未开始时零开销)。 */
    public static void tick() {
        if (queue == null) {
            return;
        }
        try {
            long deadline = System.nanoTime() + FRAME_BUDGET_NS;
            while (index < queue.size()) {
                File f = queue.get(index);
                index++;
                if (!f.isFile()) {
                    continue; // 中途被转换流程消费(jar→dex)或删除:跳过
                }
                LoadStatus.loadStep(index, f.getName());
                PluginRegistry.Entry r = null;
                try {
                    r = HotPluginHost.load(f);
                } catch (Throwable t) {
                    SafeLog.e(TAG + ": load failed " + f.getName(), t);
                }
                if (r != null && r.status == PluginRegistry.Status.LOADED) {
                    ok++;
                } else if (r != null && r.status == PluginRegistry.Status.DISABLED) {
                    // 注册配置禁用:跳过,不计失败
                } else {
                    fail++;
                }
                if (System.nanoTime() >= deadline) {
                    break; // 超出本帧预算:下一帧继续
                }
            }
        } catch (Throwable t) {
            SafeLog.e(TAG + ": queue tick failed", t);
            index = queue.size();
        }
        if (index >= queue.size()) {
            finish();
        }
    }

    private static void finish() {
        long ms = System.currentTimeMillis() - beginMs;
        String msg = "已重载: 成功 " + ok + ",失败 " + fail + "(" + ms + "ms)";
        LoadStatus.loadEnd();
        LoadStatus.setLast(msg);
        queue = null;
        SafeLog.i(TAG + ": " + msg);
        toast(msg);
        refreshScreen();
    }

    private static void toast(String msg) {
        try {
            aoc.kingdoms.lukasz.jakowski.Game.menuManager.addToast(msg);
        } catch (Throwable ignored) {
        }
    }

    private static void refreshScreen() {
        try {
            Class.forName("com.saysky.hotplugloader.ui.PluginScreen")
                    .getMethod("rebuild")
                    .invoke(null);
        } catch (Throwable ignored) {
        }
    }
}
