package aoc.kingdoms.lukasz.jakowski;

import java.io.File;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import com.saysky.hotplugloader.cold.ColdBootstrap;
import com.saysky.hotplugloader.cold.ResourceOverride;

/**
 * 【整类替换】游戏的安卓端文件加载实现 {@code FileManager$3}(b29)。
 *
 * <p>为什么可以换:游戏 {@code AA_Game.create()} 的<b>第一条指令</b>
 * {@code FileManager.initLoadInterface()} 会执行 {@code new FileManager$3()}
 * 并赋给 {@code FileManager.loadInterface}——补丁 dex 先加载、同名类先到先得,
 * 所以只要替换本类,<b>从游戏代码的第一条指令起,游戏的"文件接口"就是我们</b>:
 * 构造函数内建立 {@link ResourceOverride} 覆盖层(扫描 {@code hotplugins/resources/<附加包名>/override/} 资源树),
 * 零等待、零竞态。</p>
 *
 * <p>行为必须逐字复刻运行时版(暮色/polaris 框架版,dexdump -d 逐指令核对),
 * 否则会丢框架改动:</p>
 * <pre>
 * loadFile:  [?] FontFix.isLocalStorage() 存在时:
 *                true  → local → internal
 *                false → Steam 模组链(逐 modsFolder: external 命中即返)→ external → local → internal
 *            [?] 无 FontFix(原版底座)→ local → internal(原版行为)
 *            任何情况下先查覆盖层(命中即返)
 * getSaveType: 有 FontFix → isLocalStorage() ? external : local;无 → local
 * </pre>
 *
 * <p>注意:本类不 import FontFix(它在框架包里、原版没有)——用反射惰性探测,
 * 两条底座一套代码。特征字段 {@link #HB_HOTLOADER_FM3_MARKER} 供宿主反射识别
 * (兜底路径看到它就知道"已经是我们了",不再重复包装)。</p>
 */
public class FileManager$3 implements FileManager.LoadInterface {

    /** 替换版特征(宿主 ColdBootstrap 反射识别;勿改勿删)。 */
    public static String HB_HOTLOADER_FM3_MARKER = "hotloader-fm3-b29";

    private static volatile ResourceOverride OVR;

    /** FontFix.isLocalStorage 探测状态:0=未探测,1=有(方法已缓存),2=无。 */
    private static volatile int ffState = 0;
    private static java.lang.reflect.Method ffMethod;

    public FileManager$3() {
        try {
            OVR = ColdBootstrap.attach(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public FileHandle loadFile(String sFile) {
        // ① 覆盖层(最高优先级)
        try {
            ResourceOverride ro = OVR;
            if (ro == null) {
                ro = ColdBootstrap.installed();
                OVR = ro;
            }
            if (ro != null) {
                File f = ro.find(sFile);
                if (f != null && f.isFile()) {
                    return new FileHandle(f);
                }
            }
        } catch (Throwable ignored) {
        }
        // ② 原逻辑(逐指令复刻运行时版)
        java.lang.reflect.Method m = fontFix();
        if (m != null) {
            boolean local;
            try {
                local = (Boolean) m.invoke(null);
            } catch (Throwable t) {
                local = false;
            }
            if (local) {
                if (Gdx.files.local(sFile).exists()) {
                    return Gdx.files.local(sFile);
                }
                return Gdx.files.internal(sFile);
            }
            java.util.List<String> folders = steamModsFolders();
            if (folders != null) {
                for (int i = 0; i < folders.size(); i++) {
                    String p = folders.get(i) + sFile;
                    if (Gdx.files.external(p).exists()) {
                        return Gdx.files.external(p);
                    }
                }
            }
            if (Gdx.files.external(sFile).exists()) {
                return Gdx.files.external(sFile);
            }
            if (Gdx.files.local(sFile).exists()) {
                return Gdx.files.local(sFile);
            }
            return Gdx.files.internal(sFile);
        }
        // ③ 无框架(原版底座):原版安卓语义 local → internal
        if (Gdx.files.local(sFile).exists()) {
            return Gdx.files.local(sFile);
        }
        return Gdx.files.internal(sFile);
    }

    @Override
    public FileHandle getSaveType(String sFile) {
        java.lang.reflect.Method m = fontFix();
        if (m != null) {
            try {
                if ((Boolean) m.invoke(null)) {
                    return Gdx.files.external(sFile);
                }
            } catch (Throwable ignored) {
            }
            return Gdx.files.local(sFile);
        }
        return Gdx.files.local(sFile);
    }

    /* ------------------------------------------------------------------ */

    /** SteamManager 反射缓存(编译子集 jar 不一定收该类,运行期在):0=未探测,1=有,2=无。 */
    private static volatile int smState = 0;
    private static java.lang.reflect.Field smSizeF;
    private static java.lang.reflect.Field smListF;

    /** 取 SteamManager.modsFolders(不可用时返回 null;与运行时循环逐字对应)。 */
    @SuppressWarnings("unchecked")
    private static java.util.List<String> steamModsFolders() {
        if (smState == 0) {
            synchronized (FileManager$3.class) {
                if (smState == 0) {
                    try {
                        Class<?> c = Class.forName("aoc.kingdoms.lukasz.jakowski.Steam.SteamManager");
                        smSizeF = c.getField("modsFoldersSize");
                        smListF = c.getField("modsFolders");
                        smState = 1;
                    } catch (Throwable t) {
                        smState = 2;
                    }
                }
            }
        }
        if (smState != 1) {
            return null;
        }
        try {
            java.util.List<String> l = (java.util.List<String>) smListF.get(null);
            if (l == null) {
                return null;
            }
            int n = smSizeF.getInt(null);
            return n >= 0 && n < l.size() ? l.subList(0, n) : l;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 惰性探测 {@code team.rainfall.fontFix.FontFix.isLocalStorage()}(框架版才有)。 */
    private static java.lang.reflect.Method fontFix() {
        if (ffState == 0) {
            synchronized (FileManager$3.class) {
                if (ffState == 0) {
                    try {
                        ffMethod = Class.forName("team.rainfall.fontFix.FontFix")
                                .getMethod("isLocalStorage");
                        ffState = 1;
                    } catch (Throwable t) {
                        ffState = 2;
                    }
                }
            }
        }
        return ffState == 1 ? ffMethod : null;
    }
}
