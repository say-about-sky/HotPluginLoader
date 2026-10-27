package com.saysky.hotplugloader;

import com.badlogic.gdx.Gdx;

/**
 * 冷加载「起步闸」(b33 建;b35 跳过启动图;b36 起步调回最前;b38 加载屏文字):附加包预载在
 * <b>冷加载一开始</b>(第一步,{@link #START_DELAY_MS}=0)就于<b>后台线程</b>点火
 * {@link StartupLoader};预载窗口内在加载屏文字行显示 {@code Loading hotplugins  <当前文件>}
 * (与游戏自身 {@code Loading ...} 同风格、位于加载序列最前;含转换队列)—— 不显示任何消息气泡。
 *
 * <p>启动图(logo)跳过(b35,等效"图标持续时间=0"):把 {@code InitGame.splash_id} 强制置 -1
 * —— 这就是游戏自身的"切到加载屏"信号({@code InitGame.draw()} 里 {@code splash_id < 0}
 * 才走加载屏路径),启动直达加载屏、立刻看到进度条,不再有"卡图标"与"褪去时机"问题。
 * 安全门槛:等 {@code InitGame.background != null}(流水线第 0 步 {@code loadBackground()} 已跑)
 * 才切,避免加载屏渲染取背景时空指针。想恢复图标:把 {@link #SKIP_SPLASH_ICON} 改 false。</p>
 *
 * <p>启动链:替换版 {@code FileManager$3} 构造(游戏第一条指令)时 ColdBootstrap 换装并
 * {@code ensureStarted()};本类以 postRunnable 接力(每帧执行跳图标 + 写加载文字),
 * 主菜单出现后退出。兜底:{@link HotLoaderTick} 首帧(主菜单出现)也会点火 {@link StartupLoader}。
 * 本类实现 {@link Runnable};永不抛出。</p>
 */
public final class ColdTick implements Runnable {

    private static final String TAG = "HotLoader/coldgate";
    /** 跳过启动图(logo)= 等效"图标持续时间=0":启动直达加载屏。改 false 则恢复图标显示。 */
    private static final boolean SKIP_SPLASH_ICON = true;
    /** 起步延迟(毫秒):0 = 冷加载一开始(第一步)就加载模组(最前);>0 可延后起步。 */
    private static final long START_DELAY_MS = 0L;
    /** 防呆上限(帧)。 */
    private static final long MAX_TICKS = 30000L;

    private static final ColdTick INSTANCE = new ColdTick();
    private static volatile Object lastApp;
    private static volatile boolean running = false;
    private static long ticks = 0L;
    private static long armedAtMs = 0L;
    private static boolean loggedStart = false;
    private static boolean engaged = false;

    // 跳图标 + 诊断:InitGame.splash_id(>=0 = 图标阶段;<0 = 加载屏),InitGame.background(安全门槛)
    private static java.lang.reflect.Field splashField;
    private static boolean splashProbed = false;
    private static boolean iconSkipLogged = false;
    private static boolean iconSkipBlockedLogged = false;
    private static boolean iconSkipFailed = false;
    private static java.lang.reflect.Field bgField;
    private static boolean bgProbed = false;

    // 加载屏文字(b38):预载窗口内显示 "Loading hotplugins  <当前文件>",窗口结束还原游戏文字
    private static java.lang.reflect.Field nameField;
    private static boolean nameProbed = false;
    private static boolean textDone = false;
    private static boolean textStartedLogged = false;
    private static String lastGameText = "";
    private static String lastInjected = null;

    private ColdTick() {
    }

    /** ColdBootstrap(冷加载最早路径)/ HotLoaderTick(兜底)调用;按 GDX 实例幂等。 */
    public static void ensureStarted() {
        Object app = Gdx.app;
        if (app == null || app == lastApp) {
            return;
        }
        lastApp = app;
        running = true;
        ticks = 0L;
        armedAtMs = 0L;
        loggedStart = false;
        engaged = false;
        splashProbed = false;
        iconSkipLogged = false;
        iconSkipBlockedLogged = false;
        iconSkipFailed = false;
        bgProbed = false;
        nameProbed = false;
        textDone = false;
        textStartedLogged = false;
        lastGameText = "";
        lastInjected = null;
        post();
    }

    private static void post() {
        try {
            Gdx.app.postRunnable(INSTANCE);
        } catch (Throwable t) {
            SafeLog.e(TAG + ": post failed", t);
            running = false;
        }
    }

    @Override
    public void run() {
        if (!running) {
            return;
        }
        ticks++;
        try {
            if (!loggedStart) {
                loggedStart = true;
                armedAtMs = System.currentTimeMillis();
                SafeLog.i(TAG + ": cold gate armed (icon-skip=" + SKIP_SPLASH_ICON
                        + ", start delay " + START_DELAY_MS + "ms; silent preload)");
            }
            forceSkipIcon();
            updateLoadingText();
            if (!engaged) {
                long elapsed = armedAtMs > 0L ? System.currentTimeMillis() - armedAtMs : 0L;
                if (elapsed >= START_DELAY_MS) {
                    engaged = true;
                    SafeLog.i(TAG + ": engaging silent preload (elapsed=" + elapsed + "ms"
                            + ", splash_id=" + splashDesc() + ")");
                    StartupLoader.ensureStarted();
                }
            }
        } catch (Throwable t) {
            SafeLog.e(TAG + ": tick failed", t);
        }
        boolean menuReady = false;
        try {
            menuReady = MainMenuMount.isMounted();
        } catch (Throwable ignored) {
        }
        if (menuReady || ticks > MAX_TICKS) {
            running = false;
            SafeLog.i(TAG + ": cold gate done (ticks=" + ticks + ", engaged=" + engaged + ")");
            StartupLoader.ensureStarted(); // 兜底:极端情况下没来得及点火
            return;
        }
        post();
    }

    /** 跳过启动图:把 InitGame.splash_id 强制置 -1(直达加载屏;"图标持续时间=0")。 */
    private static void forceSkipIcon() {
        if (!SKIP_SPLASH_ICON || iconSkipFailed) {
            return;
        }
        java.lang.reflect.Field f = splashRef();
        if (f == null) {
            return;
        }
        try {
            if (f.getInt(null) < 0) {
                return; // 已在加载屏路径
            }
            // 安全门槛:加载屏背景就绪(流水线第 0 步 loadBackground)后才切,避免加载屏取空背景
            if (!backgroundReady()) {
                if (!iconSkipBlockedLogged) {
                    iconSkipBlockedLogged = true;
                    SafeLog.i(TAG + ": splash icon skip waiting for loading background ...");
                }
                return;
            }
            f.setInt(null, -1);
            if (!iconSkipLogged) {
                iconSkipLogged = true;
                SafeLog.i(TAG + ": splash icon skipped (splash_id -> -1; go straight to loading screen)");
            }
        } catch (Throwable t) {
            iconSkipFailed = true; // 字段不可写:放弃跳过(不刷日志)
        }
    }

    /** 加载屏背景是否已就绪(InitGame.background != null;字段不可读则返回 false = 不冒险)。 */
    private static boolean backgroundReady() {
        if (!bgProbed) {
            bgProbed = true;
            try {
                bgField = Class.forName("aoc.kingdoms.lukasz.menus.InitGame").getField("background");
            } catch (Throwable t) {
                bgField = null;
            }
        }
        if (bgField == null) {
            return false;
        }
        try {
            return bgField.get(null) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** InitGame.splash_id 字段(getField;不可用返回 null)。 */
    private static java.lang.reflect.Field splashRef() {
        if (!splashProbed) {
            splashProbed = true;
            try {
                splashField = Class.forName("aoc.kingdoms.lukasz.menus.InitGame")
                        .getField("splash_id");
            } catch (Throwable t) {
                splashField = null;
            }
        }
        return splashField;
    }

    /** 诊断用:读一次 splash_id(>=0 = 图标阶段;<0 = 加载屏阶段;unreadable = 字段不可读)。 */
    private static String splashDesc() {
        try {
            java.lang.reflect.Field f = splashRef();
            if (f == null) {
                return "unreadable";
            }
            return String.valueOf(f.getInt(null));
        } catch (Throwable t) {
            return "unreadable";
        }
    }

    /**
     * 加载屏文字(b38):预载窗口内显示 {@code Loading hotplugins  <当前文件>}
     * (窗口 = 预载未完成 或 转换队列忙;游戏每个阶段开始时其阶段文字会各闪现一帧,属正常);
     * 窗口结束还原游戏文字。
     */
    private static void updateLoadingText() {
        if (textDone) {
            return;
        }
        if (StartupLoader.isFinished() && !LoadStatus.busy()) {
            restoreLoadingText();
            return;
        }
        java.lang.reflect.Field f = nameRef();
        if (f == null) {
            textDone = true;
            return;
        }
        try {
            Object cur = f.get(null);
            String cv = cur == null ? "" : String.valueOf(cur);
            if (!cv.equals(lastInjected)) {
                lastGameText = cv; // 游戏写过(或首次):记住游戏原值
            }
            String name = LoadStatus.currentName();
            String text = "Loading hotplugins  " + (name.length() == 0 ? "..." : name);
            f.set(null, text);
            lastInjected = text;
            if (!textStartedLogged) {
                textStartedLogged = true;
                SafeLog.i(TAG + ": loading screen text active (Loading hotplugins ...)");
            }
        } catch (Throwable t) {
            textDone = true; // 字段不可写:放弃(不刷日志)
        }
    }

    /** 还原加载屏文字(仅当当前仍是我们写的值;游戏已自行写回更新文字则不动)。 */
    private static void restoreLoadingText() {
        textDone = true;
        java.lang.reflect.Field f = nameRef();
        if (f == null) {
            return;
        }
        try {
            Object cur = f.get(null);
            String cv = cur == null ? "" : String.valueOf(cur);
            if (!cv.equals(lastInjected)) {
                return; // 游戏已写回更新的文字:保持不动
            }
            f.set(null, lastGameText);
            SafeLog.i(TAG + ": loading screen text restored (game text: " + lastGameText + ")");
        } catch (Throwable ignored) {
        }
    }

    /** InitGame.loadingName 字段(静态 String;不可用返回 null 并记录一次)。 */
    private static java.lang.reflect.Field nameRef() {
        if (!nameProbed) {
            nameProbed = true;
            try {
                java.lang.reflect.Field f = Class.forName("aoc.kingdoms.lukasz.menus.InitGame")
                        .getField("loadingName");
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f = null;
                }
                nameField = f;
            } catch (Throwable t) {
                nameField = null;
            }
            if (nameField == null) {
                SafeLog.i(TAG + ": loadingName not writable; hotplugins loading text disabled");
            }
        }
        return nameField;
    }
}
