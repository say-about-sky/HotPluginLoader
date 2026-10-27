package com.saysky.hotplugloader.api;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 【附加包文件小工具】(b44 引入;纯 JDK、两端行为一致、全部不抛异常)。
 *
 * <p>给附加包读写 {@code HotContext.dataDir()} 下私有数据用的最小工具集:
 * 读失败返回 {@code null}、写失败返回 {@code false},错误处理策略留给附加包自己
 * (建议配合 {@code ctx.log(...)} 输出)。桌面与安卓同一条代码路径,
 * 不涉及 gdx FileHandle 的 Internal/Local/External 类型差异。</p>
 *
 * <p>编码统一 UTF-8。{@link #write}/{@link #writeBytes} 为原子落位
 * (先写 {@code <目标名>.tmp} 再改名替换),中断不会留下半截文件。</p>
 */
public final class HotFiles {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int BUF = 8192;

    private HotFiles() {
    }

    /* ------------------------------------------------------------------ */
    /* 读                                                                  */
    /* ------------------------------------------------------------------ */

    /** 读文本文件(UTF-8)。文件不存在/读失败 = null。 */
    public static String read(File f) {
        return read(f, null);
    }

    /** 读文本文件(UTF-8),失败时返回 fallback。 */
    public static String read(File f, String fallback) {
        byte[] b = readBytes(f);
        return b == null ? fallback : new String(b, UTF8);
    }

    /** 读字节(配置以外的模型/贴图等)。失败 = null。 */
    public static byte[] readBytes(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        long len = f.length();
        if (len <= 0 || len > Integer.MAX_VALUE) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(len, 1 << 20));
            byte[] buf = new byte[BUF];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        } catch (Throwable ignored) {
            return null;
        } finally {
            close(in);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 写(原子落位)                                                      */
    /* ------------------------------------------------------------------ */

    /** 原子写文本(UTF-8;父目录自动创建)。成功 = true。 */
    public static boolean write(File f, String text) {
        if (text == null) {
            return false;
        }
        return writeBytes(f, text.getBytes(UTF8));
    }

    /** 原子写字节(UTF-8 文本请走 {@link #write})。 */
    public static boolean writeBytes(File f, byte[] data) {
        if (f == null || data == null) {
            return false;
        }
        ensureDir(f.getParentFile());
        File tmp = new File(f.getAbsoluteFile().getPath() + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(data);
            out.flush();
            out.close();
            out = null;
            if (renameOver(tmp, f)) {
                return true;
            }
            // 改名失败(极少数文件系统语义差异)→ 直接覆写目标兜底
            return writeDirect(f, data);
        } catch (Throwable ignored) {
            return false;
        } finally {
            close(out);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** 追加文本(UTF-8;配置文件日志用,非原子)。 */
    public static boolean append(File f, String text) {
        if (f == null || text == null) {
            return false;
        }
        ensureDir(f.getParentFile());
        OutputStream out = null;
        try {
            out = new FileOutputStream(f, true);
            out.write(text.getBytes(UTF8));
            out.flush();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            close(out);
        }
    }

    /** 直接覆写(不经过临时文件;仅供内部兜底)。 */
    private static boolean writeDirect(File f, byte[] data) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            out.write(data);
            out.flush();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            close(out);
        }
    }

    /** 改名覆盖目标(Windows 上目标已存在时先删)。 */
    private static boolean renameOver(File from, File to) {
        try {
            if (to.exists() && !to.delete()) {
                return false;
            }
            return from.renameTo(to);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 目录 / 复制 / 删除                                                  */
    /* ------------------------------------------------------------------ */

    /** 复制文件(目标父目录自动创建;目标已存在则覆盖)。 */
    public static boolean copy(File from, File to) {
        byte[] b = readBytes(from);
        return b != null && writeBytes(to, b);
    }

    /** 目录存在或创建成功 = true(null/失败 = false)。 */
    public static boolean ensureDir(File dir) {
        if (dir == null) {
            return false;
        }
        return dir.isDirectory() || dir.mkdirs();
    }

    /** 拼子路径(不创建目录):child(dir, "save", "slot1.json")。 */
    public static File child(File dir, String... segments) {
        File cur = dir;
        if (segments != null) {
            for (String s : segments) {
                cur = new File(cur, s);
            }
        }
        return cur;
    }

    /** 拼子路径并创建全部目录(返回末级目录;创建失败也返回该 File)。 */
    public static File subDir(File dir, String... segments) {
        File f = child(dir, segments);
        ensureDir(f);
        return f;
    }

    /** 递归删除(文件或整个目录树;根不存在视为已删除 = true)。 */
    public static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return true;
        }
        Deque<File> stack = new ArrayDeque<File>();
        stack.push(f);
        boolean ok = true;
        while (!stack.isEmpty()) {
            File cur = stack.pop();
            File[] children = cur.isDirectory() ? cur.listFiles() : null;
            if (children != null && children.length > 0) {
                stack.push(cur); // 先压自身,再压子项 → 子项先弹,最后删自身
                for (File c : children) {
                    stack.push(c);
                }
                continue;
            }
            if (!cur.delete()) {
                ok = false;
            }
        }
        return ok;
    }

    /** 去扩展名的文件主名(清单/dex/资源目录按主名对应的场景用)。 */
    public static String nameWithoutExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 静默 close(工具内部与附加包都方便用)。 */
    public static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
