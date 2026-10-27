package com.saysky.hotplugloader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 【APK 内嵌 hotplugins 首启播种】(b41)
 *
 * <p>发行版 APK 的<b>根目录</b>(zip 根,不是 assets/)可以放一个 {@code hotplugins/} 文件夹:
 * 游戏<b>首次启动</b>时(启动 Activity {@code onCreate} 最早期)把它提取到
 * {@code Android/data/<包名>/files/hotplugins/} —— 同名文件覆盖、目标里多出来的文件保留;
 * 随后照常走加载器已有的检测/补全流程({@code HotPluginHost.scan()} →
 * {@code LoaderPaths.ensureSubDirs()} 会补出缺失的 dex/ json/ resources/ 结构; jar/ 按需创建),
 * 所以 APK 里的 hotplugins 空或残缺都不影响后续使用。</p>
 *
 * <p><b>只播种一次</b>:提取完成(复制到至少 1 个文件)后在目标目录写标记
 * {@link #MARKER};之后每次启动看到标记就直接跳过 —— <b>绝不刷新</b>玩家在
 * files/hotplugins 里的导入/改动。想重新播种:删除该标记文件(或清除应用数据)后重启游戏。</p>
 *
 * <p>跳过:标记自身、{@code hotloader.log}(加载器日志)与任何含 {@code ..} 等不安全路径的条目;
 * APK 里没有 {@code hotplugins/} 或其中没有任何文件时<b>不写标记</b>(以后换成带内容的 APK 仍可播种)。
 * 本类为纯 Java(无 android/gdx 依赖),桌面可直接单测(zip 即"伪 APK")。</p>
 */
public final class ApkSeed {

    /** 播种源目录名(APK 根下;小写)。 */
    public static final String SEED_DIR = "hotplugins";
    /** 一次性播种标记(目标 hotplugins 目录内;删除它 = 允许下次启动重新播种)。 */
    public static final String MARKER = ".apk_seed_done";

    private ApkSeed() {
    }

    /**
     * 执行播种(内部全 catch,不会抛出;返回结果摘要,供启动器日志直接写入)。
     *
     * @param apkFile 当前 APK 文件(如 {@code getApplicationInfo().sourceDir});可为 null
     * @param destDir 目标目录(如 {@code getExternalFilesDir("hotplugins")});可为 null
     */
    public static String seed(File apkFile, File destDir) {
        if (apkFile == null || destDir == null) {
            return "seed skipped (apk or storage unavailable)";
        }
        File marker = new File(destDir, MARKER);
        if (marker.exists()) {
            return "seed skipped (already seeded)";
        }
        if (!apkFile.isFile()) {
            return "seed skipped (apk not found: " + apkFile + ")";
        }
        int files = 0;
        int dirs = 0;
        int skipped = 0;
        long bytes = 0;
        boolean sawHotplugins = false;
        ZipFile zip = null;
        try {
            zip = new ZipFile(apkFile);
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/');
                if (name.equals(SEED_DIR) || name.equals(SEED_DIR + "/")) {
                    sawHotplugins = true;
                    continue;
                }
                if (!name.startsWith(SEED_DIR + "/")) {
                    continue; // APK 根下其它内容(classes.dex、assets/… )一律不动
                }
                sawHotplugins = true;
                String rel = name.substring(SEED_DIR.length() + 1);
                while (rel.startsWith("./")) {
                    rel = rel.substring(2);
                }
                while (rel.startsWith("/")) {
                    rel = rel.substring(1);
                }
                if (rel.isEmpty()) {
                    continue;
                }
                if (rel.equals(MARKER) || rel.equals("hotloader.log")) {
                    skipped++; // 加载器自身状态文件,不参与覆盖
                    continue;
                }
                if (!isSafePath(rel)) {
                    skipped++; // zip-slip 防护(.. / 空段)
                    continue;
                }
                File out = new File(destDir, rel);
                if (e.isDirectory() || name.endsWith("/")) {
                    mkdirs(out);
                    dirs++;
                    continue;
                }
                mkdirs(out.getParentFile());
                copy(zip.getInputStream(e), out);
                files++;
                bytes += out.length();
            }
        } catch (Throwable t) {
            return "seed failed: " + t; // 不写标记 → 下次启动自动重试
        } finally {
            closeQuietly(zip);
        }
        if (!sawHotplugins) {
            return "seed: no " + SEED_DIR + "/ in apk (nothing to do)";
        }
        if (files <= 0) {
            // 空/只有目录:不算"已播种"、不写标记 —— 以后换成带文件的 APK 仍可播种
            return "seed: apk " + SEED_DIR + " empty (files=0 dirs=" + dirs + " skipped=" + skipped + ")";
        }
        try {
            writeMarker(marker, apkFile, files, dirs, skipped, bytes);
        } catch (Throwable t) {
            return "seed done (files=" + files + ") but marker write failed: " + t;
        }
        return "seed done: files=" + files + " dirs=" + dirs + " skipped=" + skipped
                + " bytes=" + bytes + " -> " + destDir;
    }

    /* ------------------------------------------------------------------ */

    /** 安全相对路径:拒绝空段、"."、".."(zip-slip 防护)。 */
    static boolean isSafePath(String rel) {
        for (String seg : rel.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static void mkdirs(File d) {
        if (d != null && !d.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
    }

    private static void copy(InputStream in, File out) throws IOException {
        try {
            FileOutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    os.write(buf, 0, n);
                }
            } finally {
                os.close();
            }
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void writeMarker(File marker, File apk, int files, int dirs, int skipped, long bytes)
            throws IOException {
        FileWriter w = new FileWriter(marker);
        try {
            w.write("# HotPluginLoader APK seed marker (b41)\n");
            w.write("# APK 内置 hotplugins 已完成一次性播种;删除本文件后重启游戏 = 允许下次启动重新播种。\n");
            w.write("seededAt=" + new Date() + "\n");
            w.write("apk=" + apk.getAbsolutePath() + "\n");
            w.write("apkSize=" + apk.length() + "\n");
            w.write("files=" + files + "\n");
            w.write("dirs=" + dirs + "\n");
            w.write("skipped=" + skipped + "\n");
            w.write("bytes=" + bytes + "\n");
        } finally {
            w.close();
        }
    }

    private static void closeQuietly(ZipFile zip) {
        if (zip != null) {
            try {
                zip.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
