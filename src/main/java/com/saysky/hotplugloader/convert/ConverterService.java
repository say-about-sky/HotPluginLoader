package com.saysky.hotplugloader.convert;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.saysky.hotplugloader.HotPluginHost;
import com.saysky.hotplugloader.LoadStatus;
import com.saysky.hotplugloader.LoaderConfig;
import com.saysky.hotplugloader.LoaderPaths;
import com.saysky.hotplugloader.PluginRegistry;
import com.saysky.hotplugloader.SafeLog;

/**
 * 内置 D8 转换服务(b12:原独立附加包「D8 转换器」已合并进宿主)。
 *
 * <p>把附加包目录里的 {@code .jar} 在游戏进程内转换成 <b>{@code <同名>.dex} 并直接放进 {@code dex\}
 * 目录</b>(b16:单 dex 直出,不再打 zip;多 dex 极少见时退回 {@code <同名>.zip})+
 * {@code <同名>.json}(清单),然后直接加载。<b>转换成功后源 jar 删除、不留档</b>
 * (外部还有原文件,重导即可);加载器现在是<b>一个库</b>:加载 + 转换都在补丁 dex 里。</p>
 *
 * <p>触发时机:①启动自动加载完成后扫一遍目录(处理之前放入的 jar);
 * ②导入完成后({@code HotImporter} 与 {@code IMPORT_DONE} 事件)再扫。</p>
 *
 * <p>实现:转换/清单核心 = {@link JarToDex}(纯 Java,桌面可测);
 * 类加载器用 {@code dalvik.system.InMemoryDexClassLoader}(反射调用,API 26+);
 * 全程后台线程(单飞锁),完成后回主线程加载 + 提示 + 刷新界面。</p>
 *
 * <p>跳过规则:输出 zip+json 已存在且不旧于 jar 时跳过(重复扫描不重转)。
 * 仅安卓执行(桌面直接加载 .jar,无需转换);受「启用热加载」总开关控制。</p>
 */
public final class ConverterService {

    private static final String TAG = "HotLoader/d8";

    /** 原独立转换器附加包的 id:检测到旧文件时给出明确提示(功能已内置,旧件无必要)。 */
    public static final String LEGACY_CONVERTER_ID = "com.saysky.hotconverter";

    private static final Object LOCK = new Object();

    /** 失败记忆:jar 路径 → 失败时的 lastModified。文件没变化则不再重试(避免每次扫描重复报错/重复转换)。 */
    private static final Map<String, Long> FAILED = new HashMap<String, Long>();

    private ConverterService() {
    }

    /** 扫描并转换待处理 jar(后台线程,单飞;仅安卓 + 热加载开关开启时生效)。 */
    public static void scanAndConvertAsync() {
        scanAndConvertAsync(false);
    }

    /**
     * 扫描并转换待处理 jar。
     *
     * @param retryFailed true = 忽略失败记忆强制重试(面板点 jar 行的手动触发用;
     *                    自动路径传 false:同一文件没变化不再重试)
     */
    public static void scanAndConvertAsync(final boolean retryFailed) {
        if (!HotPluginHost.isAndroid() || !LoaderConfig.hotloadEnabled()) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (LOCK) {
                    try {
                        scanPending(retryFailed);
                    } catch (Throwable t) {
                        SafeLog.e(TAG + ": scan failed", t);
                    }
                }
            }
        }, "HotD8");
        t.setDaemon(true);
        // b48:D8 转换是全流程最重的 CPU/IO 活 —— 同样降优先级,不与冷加载进度条争抢
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void scanPending(boolean retryFailed) {
        // 先收集待转换清单(状态行需要总数;转换本体逐项进行)
        List<File> jobs = new ArrayList<File>();
        for (File dir : LoaderPaths.payloadDirs()) {
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (!f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                if (!retryFailed) {
                    Long failedMtime = FAILED.get(f.getAbsolutePath());
                    if (failedMtime != null && failedMtime.longValue() == f.lastModified()) {
                        continue; // 上次已失败且文件未变化:不再重试
                    }
                }
                jobs.add(f);
            }
        }
        if (jobs.isEmpty()) {
            return;
        }
        int i = 0;
        LoadStatus.convBegin(jobs.size());
        try {
            for (File f : jobs) {
                i++;
                LoadStatus.convStep(i, f.getName());
                try {
                    convertOne(f);
                    FAILED.remove(f.getAbsolutePath());
                } catch (Throwable t) {
                    FAILED.put(f.getAbsolutePath(), Long.valueOf(f.lastModified()));
                    SafeLog.e(TAG + ": convert failed: " + f.getName() + " -> " + t, t);
                    toast("转换失败: " + f.getName() + "(详情见日志)");
                }
            }
        } finally {
            LoadStatus.convEnd();
        }
    }

    private static void convertOne(File jar) throws Exception {
        String base = baseName(jar.getName());
        File zip = new File(jar.getParentFile(), base + ".zip");                       // 多 dex 回退(旧版留档格式)
        File dexOut = new File(LoaderPaths.importDirFor(base + ".dex"), base + ".dex"); // 单 dex 直出(b16)
        // 清单:已有(附加包同目录 / json 子目录)就地更新,否则写 json 子目录
        File json = LoaderPaths.resolveManifest(jar);
        if (json == null) {
            json = LoaderPaths.manifestTargetFor(jar);
        }
        // 跳过:已有产物(.dex 或 .zip)且不旧于 jar,清单也在 → 视为已转换(顺带清掉冗余源文件)
        long outTime = Math.max(dexOut.isFile() ? dexOut.lastModified() : 0L,
                zip.isFile() ? zip.lastModified() : 0L);
        if (outTime >= jar.lastModified() && json.isFile()) {
            deleteRedundant(jar, dexOut.isFile() ? dexOut : zip);
            return;
        }
        SafeLog.i(TAG + ": converting " + jar.getName() + " (" + jar.length() + "B) ...");
        File work = new File(LoaderPaths.pluginsDir(), ".convert-work");
        File tmpZip = null;
        try {
            LoadStatus.convStageD8();
            List<File> dexFiles = JarToDex.toDexFiles(jar, work);
            LoadStatus.convStageWrite();
            final File out;
            if (dexFiles.size() == 1) {
                // 单 dex:直接把 classes.dex 落成 dex\<主名>.dex(不再打 zip)
                JarToDex.Info info = JarToDex.writeManifest(jar, json, loaderForDex(dexFiles.get(0)));
                moveFile(dexFiles.get(0), dexOut);
                SafeLog.i(TAG + ": converted " + jar.getName() + " -> " + LoaderPaths.displayPath(dexOut)
                        + " (" + dexOut.length() + "B) id=" + info.id + " main=" + info.mainClass
                        + " via=" + info.source);
                out = dexOut;
                postLoad(info, out);
            } else {
                // 多 dex(极少见):退回 zip 方案(zip 落在源 jar 同目录)
                tmpZip = new File(work, base + ".zip");
                JarToDex.zipDexFiles(dexFiles, tmpZip);
                JarToDex.Info info = JarToDex.writeManifest(jar, json, loaderFor(tmpZip));
                moveFile(tmpZip, zip);
                SafeLog.i(TAG + ": converted (multi-dex) " + jar.getName() + " -> " + zip.getName()
                        + " (" + zip.length() + "B) id=" + info.id + " main=" + info.mainClass
                        + " via=" + info.source);
                out = zip;
                postLoad(info, out);
            }
            // 成功:源 jar 删除(不留档;外部还有原文件)+ 清旧产物(.done / 旧 zip)
            deleteRedundant(jar, out);
            JarToDex.deleteRecursively(new File(work, "dex"));
        } catch (Throwable t) {
            // 清理:临时 zip / 临时 dex 目录 / 同名孤儿 zip(是历次失败尝试的残渣)
            deleteQuietly(tmpZip);
            JarToDex.deleteRecursively(new File(work, "dex"));
            try {
                if (zip.isFile() && LoaderPaths.resolveManifest(zip) == null) {
                    deleteQuietly(zip);
                }
            } catch (Throwable ignored) {
            }
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            throw new Exception(String.valueOf(t), t);
        }
    }

    /**
     * 转换成功后的加载(b30:直接在转换线程完成 —— 附加包 init 不再占用渲染线程;
     * 只有 toast/界面刷新回主线程)。
     */
    private static void postLoad(final JarToDex.Info info, final File out) {
        final String label = info.name + " v" + info.version;
        final String msg;
        try {
            PluginRegistry.Entry e = HotPluginHost.load(out);
            if (e != null && e.status == PluginRegistry.Status.LOADED) {
                msg = "已转换并加载: " + label;
            } else if (e != null && e.error != null) {
                msg = "已转换: " + out.getName() + ";加载失败: " + e.error;
            } else {
                msg = "已转换: " + out.getName() + "(点击列表行加载)";
            }
        } catch (Throwable t) {
            SafeLog.e(TAG + ": post-load failed", t);
            return;
        }
        post(new Runnable() {
            @Override
            public void run() {
                try {
                    toast(msg);
                    refreshScreen();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 删除已无必要的源文件:源 jar(不留档)、旧的 .jar.done、同名旧 zip(keepOut 除外)。 */
    private static void deleteRedundant(File jar, File keepOut) {
        deleteQuietly(jar);
        deleteQuietly(new File(jar.getParentFile(), jar.getName() + ".done"));
        try {
            File oldZip = LoaderPaths.convertedZipFor(jar);
            if (oldZip.isFile() && !oldZip.equals(keepOut)) {
                deleteQuietly(oldZip);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 转换产物(多 dex zip)→ 类加载器(安卓 dalvik,反射调用)。 */
    private static ClassLoader loaderFor(File zip) throws Exception {
        ClassLoader parent = ConverterService.class.getClassLoader();
        ByteBuffer[] buffers = JarToDex.readZipDexBuffers(zip);
        Class<?> imc = Class.forName("dalvik.system.InMemoryDexClassLoader", true, parent);
        if (buffers.length == 1) {
            return (ClassLoader) imc.getConstructor(ByteBuffer.class, ClassLoader.class)
                    .newInstance(buffers[0], parent);
        }
        return (ClassLoader) imc.getConstructor(ByteBuffer[].class, ClassLoader.class)
                .newInstance(new Object[]{buffers, parent});
    }

    /** 单 dex 产物 → 类加载器(安卓 dalvik,反射调用)。 */
    private static ClassLoader loaderForDex(File dex) throws Exception {
        ClassLoader parent = ConverterService.class.getClassLoader();
        ByteBuffer buffer = ByteBuffer.wrap(java.nio.file.Files.readAllBytes(dex.toPath()));
        Class<?> imc = Class.forName("dalvik.system.InMemoryDexClassLoader", true, parent);
        return (ClassLoader) imc.getConstructor(ByteBuffer.class, ClassLoader.class)
                .newInstance(buffer, parent);
    }

    /* ------------------------------------------------------------------ */

    /** 移动文件(先删目标;renameTo 失败则退化为复制+删除)。 */
    private static void moveFile(File from, File to) throws Exception {
        if (to.isFile() && !to.delete()) {
            // ignore;POSIX 上 renameTo 可覆盖
        }
        if (from.renameTo(to)) {
            return;
        }
        FileInputStream in = new FileInputStream(from);
        try {
            FileOutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        deleteQuietly(from);
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String baseName(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i <= 0 ? fileName : fileName.substring(0, i);
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
