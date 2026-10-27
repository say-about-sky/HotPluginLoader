package com.saysky.hotplugloader.android;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.saysky.hotplugloader.SafeLog;

/**
 * 安卓整进程重启(兜底):重新进入游戏加载界面(重启游戏循环)。
 * 「全部重载」正常走原位整层重载(b17 起),仅当重载出现致命异常时才调用这里。
 *
 * <p>做法 = <b>整进程重启</b>:用 {@code AlarmManager} 注册一个约 1.2 秒后的
 * "自拉起"闹钟(系统持有,进程死了也有效),然后 {@code killProcess} 杀掉自己 ——
 * 系统到点冷启动游戏,等同"彻底关闭再打开",但全自动。</p>
 *
 * <p>b15:闹钟优先级升级为 {@code setAlarmClock}(闹钟级,最不易被系统/电池管理清理)
 * → {@code setExactAndAllowWhileIdle} → {@code setAndAllowWhileIdle} → {@code set} 逐级降级,
 * 并把实际生效的通道写进日志(诊断个别机型拦截)。</p>
 *
 * <p>⚠ 真机教训(b12 → b14):<b>不能用</b>同进程的 {@code Activity.recreate()}
 * 或"重拉 Activity"重启 —— 进程里的静态状态与已失效的 GL 资源会被复用
 * (libGDX 不支持同进程二次 initialize),实测结果是游戏只剩纯色画布("蓝屏")。
 * 因此这里只有整进程重启一条路;失败返回 false,调用方据此保持原位状态不动(不冒险重建)。</p>
 *
 * <p>本类含 android.* 引用,仅安卓端会被加载(入口经 {@code HotBridge} 反射)。</p>
 */
public final class HotRestart {

    private static final String TAG = "HotLoader/restart";

    private HotRestart() {
    }

    /** 由 HotBridge 反射调用(游戏线程)。true = 已安排重启(本进程随即被杀,系统稍后冷启动)。 */
    public static boolean restartGameFromGame() {
        try {
            Object app = com.badlogic.gdx.Gdx.app; // AndroidApplication extends Activity
            if (!(app instanceof Activity)) {
                SafeLog.e(TAG + ": Gdx.app is not an Activity: " + app, null);
                return false;
            }
            Activity activity = (Activity) app;
            Intent intent = activity.getPackageManager()
                    .getLaunchIntentForPackage(activity.getPackageName());
            if (intent == null) {
                SafeLog.e(TAG + ": launch intent not found", null);
                return false;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pi = PendingIntent.getActivity(activity, 0, intent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) activity.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                SafeLog.e(TAG + ": AlarmManager unavailable", null);
                return false;
            }
            long trigger = android.os.SystemClock.elapsedRealtime() + 1200L;
            // 逐级降级:闹钟级(最不易被清理)→ 精确 → 空闲可用(非精确)→ 非精确。实际通道写日志,便于诊断机型拦截
            String via;
            try {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(trigger, pi), pi);
                via = "setAlarmClock";
            } catch (Throwable t1) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
                    via = "setExactAndAllowWhileIdle";
                } catch (Throwable t2) {
                    try {
                        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
                        via = "setAndAllowWhileIdle(inexact)";
                    } catch (Throwable t3) {
                        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
                        via = "set(inexact)";
                    }
                }
            }
            boolean exactAllowed = true;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    exactAllowed = am.canScheduleExactAlarms();
                }
            } catch (Throwable ignored) {
            }
            SafeLog.i(TAG + ": restart scheduled via " + via + " (relaunch +1200ms; exactAllowed="
                    + exactAllowed + "); killing process");
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0); // 理论上到不了这(self-kill 立即生效);保底
            return true;
        } catch (Throwable t) {
            SafeLog.e(TAG + ": restart failed", t);
            return false;
        }
    }
}
