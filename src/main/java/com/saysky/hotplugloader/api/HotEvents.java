package com.saysky.hotplugloader.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.saysky.hotplugloader.SafeLog;

/**
 * 附加包事件总线。
 *
 * <p>宿主在关键时机派发事件;附加包用 {@link #register} 订阅、{@link #unregister} 退订。
 * 派发在游戏渲染线程上,逐监听者隔离异常(一个附加包抛异常不影响其它附加包与游戏)。</p>
 *
 * <h3>事件清单(v0.2.0)</h3>
 * <ul>
 *   <li>{@link #FRAME} — 游戏每渲染帧一次。args = { Long frameId, Float rawDeltaSeconds }。</li>
 * </ul>
 *
 * <p>更多事件(地图点击/视图切换等)在后续版本加入;注册时传入未知类型不会报错,
 * 只是永远不会触发。</p>
 */
public final class HotEvents {

    /** 每渲染帧事件。args[0]=Long frameId, args[1]=Float 原始帧间隔(秒)。 */
    public static final String FRAME = "frame";

    /** 导入完成事件(「导入」按钮复制文件后派发;args[0] = String[] 复制落地的文件名)。 */
    public static final String IMPORT_DONE = "import.done";

    private static final class Reg {
        final HotListener listener;
        final Set<String> types = new LinkedHashSet<String>();
        boolean enabled = true;
        /** 失败诊断(b19):计数 + 最近一次记日志时间(dispatch 内更新,对数限流用)。 */
        int failCount;
        long lastFailLogMs;

        Reg(HotListener listener) {
            this.listener = listener;
        }
    }

    private static final List<Reg> REGS = new ArrayList<Reg>(4);

    private HotEvents() {
    }

    /**
     * 订阅事件(重复调用会合并订阅类型并重新启用)。
     *
     * @param types 关心的事件类型;不传表示订阅全部事件
     */
    public static void register(HotListener listener, String... types) {
        if (listener == null) {
            return;
        }
        synchronized (REGS) {
            Reg reg = null;
            for (int i = 0; i < REGS.size(); i++) {
                if (REGS.get(i).listener == listener) {
                    reg = REGS.get(i);
                    break;
                }
            }
            if (reg == null) {
                reg = new Reg(listener);
                REGS.add(reg);
            }
            reg.enabled = true;
            if (types != null) {
                for (String t : types) {
                    if (t != null && t.length() > 0) {
                        reg.types.add(t);
                    }
                }
            }
        }
    }

    /** 退订全部事件。 */
    public static void unregister(HotListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (REGS) {
            for (int i = 0; i < REGS.size(); i++) {
                if (REGS.get(i).listener == listener) {
                    REGS.remove(i);
                    return;
                }
            }
        }
    }

    /** 启用/禁用某监听者的派发(面板「禁用」附加包用);未注册过返回 false。 */
    public static boolean setEnabled(HotListener listener, boolean enabled) {
        if (listener == null) {
            return false;
        }
        synchronized (REGS) {
            for (int i = 0; i < REGS.size(); i++) {
                if (REGS.get(i).listener == listener) {
                    REGS.get(i).enabled = enabled;
                    return true;
                }
            }
        }
        return false;
    }

    /** 是否存在(启用中的)某事件监听者;宿主借此跳过参数构造开销。 */
    public static boolean hasListeners(String type) {
        synchronized (REGS) {
            for (int i = 0; i < REGS.size(); i++) {
                Reg r = REGS.get(i);
                if (r.enabled && (r.types.isEmpty() || r.types.contains(type))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 派发事件(宿主调用;也允许附加包用它向其它附加包广播自定义事件)。 */
    public static void dispatch(String type, Object[] args) {
        if (type == null) {
            return;
        }
        Reg[] snapshot;
        synchronized (REGS) {
            snapshot = REGS.toArray(new Reg[REGS.size()]);
        }
        for (Reg r : snapshot) {
            if (!r.enabled) {
                continue;
            }
            if (!r.types.isEmpty() && !r.types.contains(type)) {
                continue;
            }
            try {
                r.listener.onEvent(type, args);
            } catch (Throwable t) {
                // b19:日志限流(FRAME 事件每帧派发,监听者持续报错会把日志刷爆)
                // 前 3 次必记,之后每 30s 至多 1 条(带累计次数);监听者继续保留(不自动禁用)
                long now = System.currentTimeMillis();
                r.failCount++;
                if (r.failCount <= 3 || now - r.lastFailLogMs > 30000L) {
                    r.lastFailLogMs = now;
                    SafeLog.e("HotEvents: listener failed on '" + type + "' ("
                            + r.listener.getClass().getName() + "; 累计 " + r.failCount + " 次)", t);
                }
            }
        }
    }
}
