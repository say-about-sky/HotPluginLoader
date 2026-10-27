package com.saysky.hotplugloader.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.saysky.hotplugloader.HotPluginHost;
import com.saysky.hotplugloader.LoaderPaths;
import com.saysky.hotplugloader.PluginRegistry;
import com.saysky.hotplugloader.SafeLog;

/**
 * 「导入」:调起系统文件选择器(SAF,可多选),把用户选中的文件复制进
 * {@code hotplugins} 目录。
 *
 * <p>类加载说明:本类含 android.* 引用,<b>仅安卓端会被加载</b>
 * (入口经 {@code HotBridge} 反射;文件选择器的返回结果由替换版
 * {@code aoc.kingdoms.lukasz.jakowski.AndroidLauncher.onActivityResult} 转发)。</p>
 *
 * <p>流程:选择器返回 → 后台线程逐文件读 {@code content://} 流写入目录
 * (避免大文件阻塞主线程 / ANR)→ 完成后回主线程弹提示 + 刷新界面。</p>
 */
public final class HotImporter {

    /** startActivityForResult 请求码('HL')。 */
    public static final int REQUEST_CODE = 0x484C;

    private static final String TAG = "HotLoader/import";

    private HotImporter() {
    }

    /** 「导入」按钮入口(经 HotBridge 反射调用;游戏主线程)。 */
    public static void startImportFromGame() {
        try {
            Object app = com.badlogic.gdx.Gdx.app; // AndroidApplication extends Activity
            if (!(app instanceof Activity)) {
                SafeLog.e(TAG + ": Gdx.app is not an Activity: " + app, null);
                toast("当前环境不支持打开系统选择器");
                return;
            }
            Class<?> cls = app.getClass();
            boolean replaced = false;
            try {
                cls.getField("HB_REPLACEMENT_MARKER");
                replaced = true;
            } catch (Throwable ignored) {
            }
            SafeLog.i(TAG + ": activity=" + cls.getName() + " replacedLauncher=" + replaced
                    + (replaced ? "" : "  << replacement NOT active; patch dex must be FIRST"));
            startImport((Activity) app);
        } catch (Throwable t) {
            SafeLog.e(TAG + ": startImportFromGame failed", t);
        }
    }

    /** 打开系统文件选择器(ACTION_OPEN_DOCUMENT,多选)。 */
    public static void startImport(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        activity.startActivityForResult(intent, REQUEST_CODE);
        SafeLog.i(TAG + ": picker opened");
    }

    /**
     * 由替换版 AndroidLauncher 转发。
     *
     * @return true = 本次结果属于本模块(已消费);false = 交给 super 处理
     */
    public static boolean handleResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) {
            return false;
        }
        try {
            if (resultCode != Activity.RESULT_OK || data == null) {
                SafeLog.i(TAG + ": import cancelled");
                return true;
            }
            final Activity act = activity;
            final Intent intent = data;
            // 文件可能来自网盘等内容提供器,复制走后台线程,避免阻塞主线程
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    runImport(act, intent);
                }
            }, "HotImport");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable t) {
            SafeLog.e(TAG + ": handleResult failed", t);
        }
        return true;
    }

    /* ------------------------------------------------------------------ */

    private static void runImport(Activity activity, Intent data) {
        int ok = 0;
        int fail = 0;
        int jars = 0;
        java.util.List<String> names = new java.util.ArrayList<String>();
        java.util.List<File> savedFiles = new java.util.ArrayList<File>();
        try {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                for (int i = 0; i < n; i++) {
                    File saved = importOne(activity, data.getClipData().getItemAt(i).getUri());
                    if (saved != null) {
                        ok++;
                        names.add(saved.getName());
                        savedFiles.add(saved);
                        if (isJar(saved.getName())) {
                            jars++;
                        }
                    } else {
                        fail++;
                    }
                }
            } else if (data.getData() != null) {
                File saved = importOne(activity, data.getData());
                if (saved != null) {
                    ok++;
                    names.add(saved.getName());
                    savedFiles.add(saved);
                    if (isJar(saved.getName())) {
                        jars++;
                    }
                } else {
                    fail++;
                }
            }
        } catch (Throwable t) {
            SafeLog.e(TAG + ": runImport failed", t);
        }
        final int okF = ok;
        final int failF = fail;
        final int jarsF = jars;
        final String[] namesF = names.toArray(new String[0]);
        final File[] savedF = savedFiles.toArray(new File[0]);
        // 导入完成 → 派发事件(兼容仍在监听的组件)+ 由内置转换服务接续转换 .jar
        final boolean convF = namesF.length > 0;
        if (convF) {
            com.saysky.hotplugloader.api.HotEvents.dispatch(
                    com.saysky.hotplugloader.api.HotEvents.IMPORT_DONE, new Object[]{namesF});
            com.saysky.hotplugloader.convert.ConverterService.scanAndConvertAsync();
        }
        SafeLog.i(TAG + ": import done; ok=" + ok + " fail=" + fail + " jars=" + jars
                + " -> " + LoaderPaths.pluginsDir().getAbsolutePath());
        post(new Runnable() {
            @Override
            public void run() {
                if (okF > 0) {
                    toast("已导入 " + okF + " 个文件到加载目录" + (failF > 0 ? "(失败 " + failF + ")" : ""));
                    if (jarsF > 0) {
                        toast("已开始转换 .jar …");
                    }
                    // 导入的单个 .dex:自动生成清单(若缺,load() 内部完成)并尝试加载
                    for (File f : savedF) {
                        if (!f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".dex")) {
                            continue;
                        }
                        try {
                            PluginRegistry.Entry e = HotPluginHost.load(f);
                            if (e != null && e.status == PluginRegistry.Status.LOADED) {
                                toast("已加载: " + e.label());
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                } else if (failF > 0) {
                    toast("导入失败,请检查文件是否可读");
                }
                refreshScreen();
            }
        });
    }

    /**
     * 复制单个选中文件到附加包目录(.json 归 json 子目录,其余归 dex 子目录)。
     *
     * @return 落地文件;失败返回 null
     */
    private static File importOne(Activity activity, Uri uri) {
        InputStream in = null;
        OutputStream out = null;
        File dst = null;
        try {
            String name = displayName(activity, uri);
            if (name == null || name.length() == 0) {
                String last = uri == null ? null : uri.getLastPathSegment();
                name = last == null ? "imported-" + System.currentTimeMillis() : new File(last).getName();
            }
            name = sanitize(name);
            // 同名文件已在磁盘(任意子目录) → 就地覆盖(更新场景,不产生副本)
            dst = LoaderPaths.importFileFor(name);
            ContentResolver cr = activity.getContentResolver();
            in = cr.openInputStream(uri);
            if (in == null) {
                SafeLog.e(TAG + ": openInputStream returned null: " + uri, null);
                return null;
            }
            out = new FileOutputStream(dst);
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
            out.flush();
            SafeLog.i(TAG + ": imported " + LoaderPaths.displayPath(dst) + " (" + dst.length() + "B)");
            return dst;
        } catch (Throwable t) {
            SafeLog.e(TAG + ": import " + uri + " failed", t);
            // 写了一半的残件:删掉(避免半截文件被当成附加包文件)
            try {
                if (out != null && dst != null && dst.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    dst.delete();
                }
            } catch (Throwable ignored) {
            }
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** SAF 显示名(ContentResolver 查询 OpenableColumns)。 */
    private static String displayName(Activity activity, Uri uri) {
        try {
            Cursor c = activity.getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) {
                        return c.getString(idx);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String sanitize(String name) {
        String n = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        String lower = n.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".jar") || lower.endsWith(".dex") || lower.endsWith(".zip")
                || lower.endsWith(".json"))) {
            n = n + ".jar";
        }
        return n;
    }

    private static boolean isJar(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static void post(Runnable r) {
        try {
            com.badlogic.gdx.Gdx.app.postRunnable(r);
        } catch (Throwable t) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
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

    private static void toast(String msg) {
        try {
            aoc.kingdoms.lukasz.jakowski.Game.menuManager.addToast(msg);
        } catch (Throwable ignored) {
        }
    }
}
