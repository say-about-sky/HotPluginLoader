package com.saysky.hotplugloader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.regex.Pattern;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

/**
 * 附加包清单({@code <附加包同主名>.json};位置:附加包同目录 / {@code json/} 子目录,
 * 解析顺序见 {@link LoaderPaths#resolveManifest})。
 *
 * <pre>
 * {
 *   "id":        "com.example.myplugin",   // 必填,唯一,仅 [A-Za-z0-9._-]
 *   "name":      "My Plugin",              // 可选,默认 = id
 *   "version":   "0.1.0",                  // 可选,默认 "?"
 *   "mainClass": "com.example.MyPlugin",   // 必填(缺了没法实例化)
 *   "trusted":   false,                    // 可选;true = 免开「允许未校验附加包」也能加载
 *   "gameVersion": "1.0.44",               // 可选,仅展示用
 *   "description": "..."                   // 可选
 * }
 * </pre>
 */
public final class PluginManifest {

    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    public final File source;
    public final File sidecar;
    public final String id;
    public final String name;
    public final String version;
    public final String mainClass;
    public final String gameVersion;
    public final String description;
    public final boolean trusted;

    private PluginManifest(File source, File sidecar, String id, String name, String version,
                           String mainClass, String gameVersion, String description, boolean trusted) {
        this.source = source;
        this.sidecar = sidecar;
        this.id = id;
        this.name = name;
        this.version = version;
        this.mainClass = mainClass;
        this.gameVersion = gameVersion;
        this.description = description;
        this.trusted = trusted;
    }

    /** 文件主名(去扩展名)。 */
    public static String baseName(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i <= 0 ? fileName : fileName.substring(0, i);
    }

    /**
     * 读取并校验清单。
     *
     * @throws Exception 校验失败的说明(中文,可直接展示给玩家)
     */
    public static PluginManifest load(File source) throws Exception {
        File sidecar = LoaderPaths.resolveManifest(source);
        if (sidecar == null) {
            throw new Exception("缺少清单 " + baseName(source.getName()) + ".json");
        }
        String text;
        try {
            text = new String(readAll(sidecar), "UTF-8");
        } catch (Throwable t) {
            throw new Exception("清单读取失败: " + t.getMessage());
        }
        JsonValue root;
        try {
            root = new JsonReader().parse(text);
        } catch (Throwable t) {
            throw new Exception("清单 JSON 格式错误");
        }
        if (root == null || !root.isObject()) {
            throw new Exception("清单 JSON 必须是对象");
        }
        String id = trim(root.getString("id", ""));
        String mainClass = trim(root.getString("mainClass", ""));
        if (id.length() == 0) {
            throw new Exception("清单缺少 id");
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new Exception("id 含非法字符(仅限字母数字._-)");
        }
        if (mainClass.length() == 0) {
            throw new Exception("清单缺少 mainClass");
        }
        String name = trim(root.getString("name", ""));
        if (name.length() == 0) {
            name = id;
        }
        String version = trim(root.getString("version", ""));
        if (version.length() == 0) {
            version = "?";
        }
        return new PluginManifest(source, sidecar, id, name, version, mainClass,
                trim(root.getString("gameVersion", "")), trim(root.getString("description", "")),
                root.getBoolean("trusted", false));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    public static byte[] readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, (int) f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
