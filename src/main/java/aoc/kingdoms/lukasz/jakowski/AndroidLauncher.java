package aoc.kingdoms.lukasz.jakowski;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

/**
 * 【整类替换】游戏启动 Activity(与原 APK 内同名类逐方法对照重写 + 诊断)。
 *
 * <p>为什么替换而不是 Mixin:该类只存在于 APK、不在 PC game.jar,
 * Mixin 够不到;而补丁 dex 先于原包加载、同名类先到先得
 * (与 PolarisCore 的 402 个整类替换同机制)。</p>
 *
 * <p>原版逻辑(按 {@code dexdump -d} 逐指令复刻,暮色 0.25.1):onCreate → super → check() →
 * initialize(new AA_Game(), config);check() 在 INTERNET 权限可用时启动 compactScale 的 WorkThread。</p>
 *
 * <p><b>诊断(b6;b27 并入 hotloader.log)</b>:本类把每个生命周期节点写入
 * {@code <包名>/files/hotplugins/hotloader.log}(不依赖 gdx/框架,onCreate 时也能写)——
 * 用于确认"替换版是否真的在运行"以及"onActivityResult 是否被回调";写入受
 * 「开启日志」总开关门控(直接读同一份 SharedPreferences,启动早期也准确),
 * 旧 {@code launcher.log} 已废弃并自动清理。
 * {@link #HB_REPLACEMENT_MARKER} 供 HotImporter 检测当前活动类是替换版还是原版。</p>
 */
public class AndroidLauncher extends AndroidApplication {

    private static final String TAG = "HotLoader";
    private static final String HB_LOG_FILE = "hotloader.log";
    /** 旧独立日志(已废弃;写日志时顺带清理残留文件)。 */
    private static final String HB_OLD_LOG_FILE = "launcher.log";
    /** 与 LoaderConfig(Gdx Preferences)同一份存储:「开启日志」总开关。 */
    private static final String HB_PREFS = "saysky_hotloader";
    private static final String HB_PREF_LOG_ENABLED = "log_enabled";
    /** 「启动时刷新日志」(与 LoaderConfig 同一键;b30 起在启动最早时机执行)。 */
    private static final String HB_PREF_REFRESH_LOG = "log_refresh_on_start";

    /** 替换版特征字段(HotImporter 反射检查它 → 判断当前活动类是否替换生效)。 */
    public static final String HB_REPLACEMENT_MARKER = "hotloader-replacement-b7";

    public AndroidLauncher() {
        super();
    }

    private boolean hasInternetPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, "android.permission.INTERNET") == 0;
    }

    public void check() {
        try {
            boolean perm = hasInternetPermission(this);
            hbLog("check: internetPermission=" + perm);
            if (perm) {
                team.rainfall.compactScale.WorkThread workThread = new team.rainfall.compactScale.WorkThread();
                team.rainfall.compactScale.WorkThread.activity = this;
                workThread.run();
                hbLog("check: WorkThread started");
            }
        } catch (Throwable t) {
            hbLog("check failed: " + t);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        hbPrepareLog();
        hbLog("onCreate: replacement active");
        hbLog("debug: " + com.saysky.hotplugloader.android.DebugSupport.checkAndWait(this));
        hbSeedFromApk();
        super.onCreate(savedInstanceState);
        hbLog("onCreate: super done");
        check();
        // b28:最早注入点 —— 后台线程等 FileManager.loadInterface 就位后换装「资源覆盖接口」
        try {
            com.saysky.hotplugloader.cold.ColdBootstrap.startThread();
            hbLog("onCreate: ColdBootstrap thread started");
        } catch (Throwable t) {
            hbLog("onCreate: ColdBootstrap failed: " + t);
        }
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        initialize(new AA_Game(), config);
        hbLog("onCreate: initialize returned");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        hbLog("onActivityResult req=" + requestCode + " res=" + resultCode);
        try {
            if (com.saysky.hotplugloader.android.HotImporter.handleResult(this, requestCode, resultCode, data)) {
                hbLog("onActivityResult: consumed by importer");
                return;
            }
            hbLog("onActivityResult: not ours (req=" + requestCode + ")");
        } catch (Throwable t) {
            hbLog("onActivityResult: importer threw " + t);
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /* ------------------------------------------------------------------ */

    /**
     * b41:首次启动时把 APK 根目录内嵌的 hotplugins/ 提取到 files/hotplugins
     * (仅一次;完成写标记 .apk_seed_done,之后启动直接跳过 —— 不刷新用户数据;
     * 空/无内容不写标记,以后换成带内容的 APK 仍可播种)。补全结构由既有流程负责:
     * 预载时 HotPluginHost.scan → LoaderPaths.ensureSubDirs 创建缺失的 dex/ json/ resources/(jar/ 按需创建)。
     */
    private void hbSeedFromApk() {
        try {
            File dir = getExternalFilesDir("hotplugins");
            if (dir == null) {
                hbLog("apk seed: external files dir unavailable");
                return;
            }
            String apk = getApplicationInfo() == null ? null : getApplicationInfo().sourceDir;
            hbLog("apk seed: " + com.saysky.hotplugloader.ApkSeed.seed(
                    apk == null ? null : new File(apk), dir));
        } catch (Throwable t) {
            hbLog("apk seed failed: " + t);
        }
    }

    /**
     * b30:「启动时刷新日志」改在最早时机执行(onCreate 首行)—— 启动器轨迹与
     * {@code FileManager$3} 直挂日志不再被首帧的 {@code HotFileLog.init()} 清掉;
     * 并告知宿主本次会话已准备(hotloader.log 只清这一次)。
     */
    private void hbPrepareLog() {
        try {
            File dir = getExternalFilesDir("hotplugins");
            if (dir == null) {
                return;
            }
            boolean refresh = getSharedPreferences(HB_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(HB_PREF_REFRESH_LOG, false);
            if (refresh) {
                File f = new File(dir, HB_LOG_FILE);
                if (f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            com.saysky.hotplugloader.HotFileLog.markSessionPrepared();
        } catch (Throwable ignored) {
        }
    }

    private void hbLog(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
        try {
            File dir = getExternalFilesDir("hotplugins");
            if (dir == null) {
                return;
            }
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            // 旧的独立 launcher.log 已并入 hotloader.log:清理残留文件
            File old = new File(dir, HB_OLD_LOG_FILE);
            if (old.exists()) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            }
            // 「开启日志」总开关(与 LoaderConfig 同一份 SharedPreferences;onCreate 早期也准确)
            boolean enabled = getSharedPreferences(HB_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(HB_PREF_LOG_ENABLED, false);
            if (!enabled) {
                return; // 关闭日志:不写任何日志文件
            }
            File f = new File(dir, HB_LOG_FILE);
            if (f.exists() && f.length() > 512 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                        + " [launcher] " + msg + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
