package com.saysky.hotplugloader;

import com.badlogic.gdx.Gdx;

/**
 * 启动预载(b30 引入;b33 定为「冷加载后期」点火):在<b>后台线程</b>完成附加包的扫描 /
 * 转换触发 / 加载 —— 不阻塞渲染线程,全程<b>静默</b>(不显示任何消息、不替换任何游戏文字)。
 *
 * <p>点火时机({@link ColdTick}):<b>冷加载一开始(第一步)即点火</b>(后台线程;不再等待图标或阶段);
 * 极端情况下由 {@link HotLoaderTick} 首帧(主菜单出现)兜底点火。</p>
 *
 * <p>做的事 = {@link HotPluginHost#autoLoad()}(扫描 → 逐文件加载;进度写入
 * {@link LoadStatus},仅面板状态行可见;结束触发 .jar 转换)。附加包 {@code init()} 运行在
 * <b>此后台线程</b>——附加包里不要直接操作 UI,需要回主线程用 {@code Gdx.app.postRunnable}。</p>
 *
 * <p>本类实现 {@link Runnable}(不用匿名内部类,不新增 $1 类);永不抛出异常。</p>
 */
public final class StartupLoader implements Runnable {

    private static final String TAG = "HotLoader/preload";

    private static final StartupLoader INSTANCE = new StartupLoader();
    private static volatile boolean started = false;
    private static volatile boolean finished = false;
    private static volatile String result = "";

    private StartupLoader() {
    }

    /** 点火(幂等;任意线程可调)。 */
    public static void ensureStarted() {
        if (started) {
            return;
        }
        synchronized (StartupLoader.class) {
            if (started) {
                return;
            }
            started = true;
        }
        try {
            Thread t = new Thread(INSTANCE, "HotLoader-Preload");
            t.setDaemon(true);
            // b48:给游戏冷加载让路 —— 渲染/主线程优先,预载只在空闲时吃 CPU/IO(进度条不被拖慢)
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
            SafeLog.i(TAG + ": preload thread started (silent)");
        } catch (Throwable t) {
            SafeLog.e(TAG + ": start failed", t);
            finished = true;
        }
    }

    public static boolean isStarted() {
        return started;
    }

    /** 预载(含转换触发)是否已跑完(转换线程可能仍在后台转换,但那不阻塞游戏)。 */
    public static boolean isFinished() {
        return finished;
    }

    /** 结果文案(结束后有效,如 "已预载: 成功 2,失败 0(2 文件)")。 */
    public static String result() {
        return result;
    }

    @Override
    public void run() {
        try {
            // Gdx 就绪等待(正常路径下由首帧点火,Gdx 必已就绪;兜底最多等 5 秒)
            for (int i = 0; i < 50 && Gdx.app == null; i++) {
                try {
                    Thread.sleep(100L);
                } catch (Throwable ignored) {
                }
            }
            SafeLog.i(TAG + ": preloading hot plugins (silent, background) ...");
            result = HotPluginHost.autoLoad();
        } catch (Throwable t) {
            SafeLog.e(TAG + ": preload failed", t);
            result = "预载失败: " + t;
        } finally {
            finished = true;
            SafeLog.i(TAG + ": preload done (" + result + ")");
        }
    }
}
