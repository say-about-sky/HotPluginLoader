package com.saysky.hotplugloader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

/**
 * 附加包启用/禁用注册配置({@code hotplugins/registry.json},根目录)。
 *
 * <p>格式(把附加包 id 放进 {@code disabled} 数组 = 禁用;移出 = 启用):</p>
 * <pre>
 * {
 *   "note": "把附加包 id 放进 disabled 数组可在下次启动时禁用;移出即启用。id 见附加包面板的悬停提示。",
 *   "disabled": ["com.example.pluginA"]
 * }
 * </pre>
 *
 * <p>行为:禁用 = 启动时不加载(不实例化、不执行 init);面板上的启用/禁用开关会同步写回本文件。
 * 文件缺失 = 全部启用;文件损坏/读不出时按「无禁用项」降级(不影响加载)。</p>
 */
public final class PluginStates {

    private static final String TAG = "HotLoader/states";
    private static final String FILE_NAME = "registry.json";
    private static final String NOTE = "disabled = 禁用列表;order = 加载顺序(文件主名,面板长按/滑动调整,越靠前越先加载)。id 见附加包面板的悬停提示。";

    private PluginStates() {
    }

    /** 注册配置文件(附加包根目录)。 */
    public static File file() {
        return new File(LoaderPaths.pluginsDir(), FILE_NAME);
    }

    /** id 是否被配置禁用。 */
    public static synchronized boolean isDisabled(String id) {
        return id != null && id.length() > 0 && readList("disabled").contains(id);
    }

    /** 显式加载顺序(文件主名列表;空 = 按文件名排序)。 */
    public static synchronized List<String> order() {
        return readList("order");
    }

    /** 写入加载顺序(b21;面板长按/滑动调整后调用)。 */
    public static synchronized void setOrder(List<String> names) {
        try {
            writeBoth(readList("disabled"), names == null ? new ArrayList<String>() : new ArrayList<String>(names));
        } catch (Throwable t) {
            SafeLog.e(TAG + ": setOrder failed", t);
        }
    }

    /** 写入/移除禁用项(读-改-写;失败只记日志,不影响加载流程)。 */
    public static synchronized void setDisabled(String id, boolean disabled) {
        if (id == null || id.length() == 0) {
            return;
        }
        try {
            List<String> ids = readList("disabled");
            boolean changed;
            if (disabled) {
                changed = !ids.contains(id) && ids.add(id);
            } else {
                changed = ids.remove(id);
            }
            if (changed) {
                writeBoth(ids, readList("order"));
            }
        } catch (Throwable t) {
            SafeLog.e(TAG + ": setDisabled failed", t);
        }
    }

    /* ------------------------------------------------------------------ */

    private static List<String> readList(String key) {
        List<String> ids = new ArrayList<String>();
        File f = file();
        if (!f.isFile()) {
            return ids;
        }
        try {
            String text = new String(PluginManifest.readAll(f), "UTF-8");
            JsonValue root = new JsonReader().parse(text);
            JsonValue arr = root == null ? null : root.get(key);
            if (arr == null) {
                return ids;
            }
            for (JsonValue v = arr.child; v != null; v = v.next) {
                String s = v.isString() ? v.asString() : null;
                s = s == null ? "" : s.trim();
                if (s.length() > 0 && !ids.contains(s)) {
                    ids.add(s);
                }
            }
        } catch (Throwable t) {
            SafeLog.e(TAG + ": registry.json 读取失败(按空处理,key=" + key + ")", t);
        }
        return ids;
    }

    /** 写入 disabled + order(整体重写;原子替换)。 */
    private static void writeBoth(List<String> disabled, List<String> order) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"note\": \"").append(esc(NOTE)).append("\",\n");
        sb.append("  \"disabled\": ").append(jsonArray(disabled)).append(",\n");
        sb.append("  \"order\": ").append(jsonArray(order)).append("\n");
        sb.append("}\n");

        File f = file();
        String text = sb.toString();
        // 先写临时文件再改名(原子替换;失败退化为直接写)
        File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            try {
                w.write(text);
            } finally {
                w.close();
            }
            if (!tmp.renameTo(f)) {
                directWrite(f, text);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable t) {
            directWrite(f, text);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        SafeLog.i(TAG + ": " + FILE_NAME + " updated (" + disabled.size() + " disabled, "
                + order.size() + " ordered)");
    }

    /** 字符串数组 → JSON 数组(带缩进;空表输出 [] )。 */
    private static String jsonArray(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < items.size(); i++) {
            sb.append("    \"").append(esc(items.get(i))).append("\"");
            sb.append(i == items.size() - 1 ? "\n" : ",\n");
        }
        sb.append("  ]");
        return sb.toString();
    }

    private static void directWrite(File f, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                    break;
            }
        }
        return b.toString();
    }
}
