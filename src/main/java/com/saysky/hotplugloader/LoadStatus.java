package com.saysky.hotplugloader;

/**
 * 加载/转换的实时进度状态(b18):由 {@link LoadQueue}(加载)与
 * {@link com.saysky.hotplugloader.convert.ConverterService}(转换)写入,
 * 面板「状态行 + 进度条」每帧读取渲染。
 *
 * <p>纯数据 + 文案组装,不碰游戏类 → JVM 可单测。</p>
 *
 * <p>线程:转换在后台线程写、UI 在渲染线程读;字段 volatile、
 * 字符串整体赋值(不原地修改),最坏情况读到新旧混排一帧,不影响正确性。</p>
 */
public final class LoadStatus {

    /* ---- 转换(后台线程) ---- */

    private static volatile boolean convActive;
    private static volatile int convCur;
    private static volatile int convTotal;
    private static volatile String convName = "";
    private static volatile String convStage = "";
    /** &gt;0 = 当前正处于 D8 调用中(UI 显示秒表)。 */
    private static volatile long convD8Start;

    /* ---- 加载(队列;渲染线程) ---- */

    private static volatile boolean loadActive;
    private static volatile int loadCur;
    private static volatile int loadTotal;
    private static volatile String loadName = "";

    /** 结果留痕:最近一次操作的结论(空闲时状态行显示它,toast 会消失它不会)。 */
    private static volatile String lastMessage = "";

    private LoadStatus() {
    }

    public static void convBegin(int total) {
        convActive = true;
        convCur = 0;
        convTotal = total;
        convName = "";
        convStage = "";
        convD8Start = 0L;
    }

    public static void convStep(int cur, String name) {
        convCur = cur;
        convName = name == null ? "" : name;
        convStage = "准备";
        convD8Start = 0L;
    }

    /** D8 开始(转换的耗时大头;UI 显示已运行秒数)。 */
    public static void convStageD8() {
        convStage = "D8 运行中";
        convD8Start = System.currentTimeMillis();
    }

    /** D8 结束,进入落盘 / 清单 / 加载阶段。 */
    public static void convStageWrite() {
        convStage = "写入 / 加载";
        convD8Start = 0L;
    }

    public static void convEnd() {
        convActive = false;
        convD8Start = 0L;
    }

    public static void loadBegin(int total) {
        loadActive = true;
        loadCur = 0;
        loadTotal = total;
        loadName = "";
    }

    public static void loadStep(int cur, String name) {
        loadCur = cur;
        loadName = name == null ? "" : name;
    }

    public static void loadEnd() {
        loadActive = false;
    }

    public static void setLast(String msg) {
        lastMessage = msg == null ? "" : msg;
    }

    /* ---- 读取(UI) ---- */

    public static boolean loadActive() {
        return loadActive;
    }

    public static boolean convActive() {
        return convActive;
    }

    public static boolean busy() {
        return loadActive || convActive;
    }

    /** 当前加载/转换中的文件名(供加载屏文案 "Loading hotplugins  <名>";空闲返回空串)。 */
    public static String currentName() {
        if (loadActive && loadName.length() > 0) {
            return loadName;
        }
        if (convActive && convName.length() > 0) {
            return convName;
        }
        return "";
    }

    /** 加载进度 0..1(未在加载时返回 0)。 */
    public static float loadFraction() {
        int total = loadTotal;
        if (!loadActive || total <= 0) {
            return 0f;
        }
        float f = (float) loadCur / (float) total;
        return f < 0f ? 0f : (f > 1f ? 1f : f);
    }

    /** 状态行文案(面板每帧读;空闲 = 上次结果留痕)。 */
    public static String display() {
        StringBuilder sb = new StringBuilder();
        if (convActive) {
            sb.append("转换中 ");
            if (convTotal > 1) {
                sb.append(convCur).append('/').append(convTotal).append(' ');
            }
            if (convName.length() > 0) {
                sb.append(clip(convName, 20)).append(' ');
            }
            if (convStage.length() > 0) {
                sb.append('(').append(convStage);
                long d8 = convD8Start;
                if (d8 > 0L) {
                    long sec = (System.currentTimeMillis() - d8) / 1000L;
                    if (sec > 0L) {
                        sb.append(' ').append(sec).append('s');
                    }
                }
                sb.append(')');
            }
        }
        if (loadActive) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("加载中 ").append(loadCur).append('/').append(loadTotal);
            if (loadName.length() > 0) {
                sb.append(" — ").append(clip(loadName, 20));
            }
        }
        if (sb.length() == 0) {
            sb.append(lastMessage.length() > 0 ? lastMessage : "就绪(更新附加包后点「全部重载」)");
        }
        return sb.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
