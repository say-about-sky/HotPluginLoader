package com.saysky.hotplugloader;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

/**
 * 主菜单「设置 / 附加包」按钮布局配置(b53)。
 *
 * <p>配置文件 = <b>hotplugins 同级</b>的 {@code hotpluginsUI.json}(安卓:
 * {@code Android/data/<包名>/files/hotpluginsUI.json};桌面:游戏根目录)。
 * 首次运行若不存在会自动生成一份 {@link #DEFAULT_JSON}(Display=false,不生效)。</p>
 *
 * <p>格式:</p>
 * <pre>
 * {
 *   "Display": false,                         // false = 本文件不生效(默认);true = 按下面摆放
 *   "buttons": {
 *     "settings": { "mode": "absolute", "x": 60, "y": 420 },
 *     "plugins":  { "mode": "snap", "anchor": "编辑器", "side": "bottom",
 *                   "offsetX": 0, "offsetY": 12, "width": 0 }
 *   }
 * }
 * </pre>
 * <ul>
 *   <li>{@code mode=absolute}:屏幕坐标 {@code x/y}(可选 {@code width},0/省略 = 默认宽);</li>
 *   <li>{@code mode=snap}:吸附已有元素 —— {@code anchor} 可填元素文本(如 载入游戏 / 编辑器),
 *       或语言键 {@code LoadGame} / {@code Editor},或 {@code @settings} / {@code @plugins}
 *       (两个按钮彼此);{@code side} = top/bottom/left/right(贴在上/下/左/右,默认 bottom);
 *       {@code offsetX/offsetY} 像素微调;</li>
 *   <li>{@code mode} 省略/implicit = 保持默认位置。</li>
 * </ul>
 *
 * <p>本类为纯解析/计算(仅依赖 gdx 的 JsonReader,桌面可单测),不碰任何游戏类。</p>
 */
public final class UiConfig {

    public static final int MODE_DEFAULT = 0;
    public static final int MODE_ABSOLUTE = 1;
    public static final int MODE_SNAP = 2;

    public static final int SIDE_TOP = 0;
    public static final int SIDE_BOTTOM = 1;
    public static final int SIDE_LEFT = 2;
    public static final int SIDE_RIGHT = 3;

    /** 首次运行自动生成的默认配置(Display=false:不生效,附标准示例)。 */
    public static final String DEFAULT_JSON =
            "{\n"
                    + "  \"Display\": false,\n"
                    + "  \"_说明\": [\n"
                    + "    \"Display=false:本文件不生效,主菜单两个按钮保持加载器默认位置(默认值)。\",\n"
                    + "    \"Display=true:按 buttons 里的设置摆放「设置」与「附加包」两个按钮。\",\n"
                    + "    \"mode=absolute:屏幕坐标定位 x / y(可选 width;0/省略=默认宽)。\",\n"
                    + "    \"mode=snap:吸附已有元素 —— anchor 填元素文本(如 载入游戏 / 编辑器),或 LoadGame / Editor(语言键),或 @settings / @plugins(两个按钮彼此)。\",\n"
                    + "    \"side=top|bottom|left|right(贴在上/下/左/右,默认 bottom);offsetX / offsetY 像素微调。\",\n"
                    + "    \"改完重启游戏生效;APK 首启播种不会覆盖已存在的本文件。\"\n"
                    + "  ],\n"
                    + "  \"buttons\": {\n"
                    + "    \"settings\": { \"mode\": \"absolute\", \"x\": 60, \"y\": 420 },\n"
                    + "    \"plugins\": { \"mode\": \"snap\", \"anchor\": \"编辑器\", \"side\": \"bottom\", \"offsetX\": 0, \"offsetY\": 12 }\n"
                    + "  }\n"
                    + "}\n";

    /** 总开关(仅当 true 时才应用下面的摆放)。 */
    public boolean display = false;
    /** 「设置」按钮(齿轮)摆放。 */
    public final Btn settings = new Btn();
    /** 「附加包」按钮摆放。 */
    public final Btn plugins = new Btn();
    /** 解析失败原因(为 null 表示正常;Display 视为 false)。 */
    public String lastError;

    /** 单个按钮的摆放规则。 */
    public static final class Btn {
        public int mode = MODE_DEFAULT;
        /** absolute 模式:屏幕坐标。 */
        public int x;
        public int y;
        /** 可选宽度(<=0 = 用加载器默认宽度)。 */
        public int width;
        /** snap 模式:锚点(元素文本 / LoadGame / Editor / @settings / @plugins)。 */
        public String anchor;
        /** snap 模式:贴边方向(SIDE_*)。 */
        public int side = SIDE_BOTTOM;
        /** snap 模式:像素微调。 */
        public int offsetX;
        public int offsetY;
    }

    private UiConfig() {
    }

    /** 解析配置文本(绝不抛出;null/空/坏 JSON → display=false 并记录 lastError)。 */
    public static UiConfig parse(String jsonText) {
        UiConfig c = new UiConfig();
        if (jsonText == null || jsonText.trim().length() == 0) {
            return c;
        }
        try {
            JsonValue root = new JsonReader().parse(jsonText);
            if (root == null) {
                return c;
            }
            c.display = boolOf(root, "Display", false);
            JsonValue btns = root.get("buttons");
            readBtn(c.settings, firstOf(btns, root, "settings"));
            readBtn(c.plugins, firstOf(btns, root, "plugins"));
        } catch (Throwable t) {
            c.display = false;
            c.lastError = "parse failed: " + t.getMessage();
        }
        return c;
    }

    /**
     * 计算吸附位置(纯数学,便于单测)。
     *
     * @return {x, y}(已含 offset;调用方负责 setPosX/setPosY)
     */
    public static int[] place(Btn b, int ax, int ay, int aw, int ah, int sw, int sh, int gap) {
        int x;
        int y;
        switch (b.side) {
            case SIDE_TOP:
                x = ax + (aw - sw) / 2;
                y = ay - sh - gap;
                break;
            case SIDE_LEFT:
                x = ax - sw - gap;
                y = ay + (ah - sh) / 2;
                break;
            case SIDE_RIGHT:
                x = ax + aw + gap;
                y = ay + (ah - sh) / 2;
                break;
            case SIDE_BOTTOM:
            default:
                x = ax + (aw - sw) / 2;
                y = ay + ah + gap;
                break;
        }
        return new int[]{x + b.offsetX, y + b.offsetY};
    }

    /* ------------------------------------------------------------------ */

    private static JsonValue firstOf(JsonValue container, JsonValue root, String name) {
        JsonValue v = container == null ? null : container.get(name);
        if (v == null && root != null) {
            v = root.get(name); // 也允许把 settings/plugins 直接写在顶层
        }
        return v;
    }

    private static void readBtn(Btn b, JsonValue v) {
        if (v == null || !v.isObject()) {
            return;
        }
        String mode = str(v, "mode", "default");
        if ("absolute".equalsIgnoreCase(mode)) {
            b.mode = MODE_ABSOLUTE;
            b.x = intOf(v, "x", 0);
            b.y = intOf(v, "y", 0);
        } else if ("snap".equalsIgnoreCase(mode)) {
            b.mode = MODE_SNAP;
            b.anchor = str(v, "anchor", null);
            String side = str(v, "side", "bottom");
            if ("top".equalsIgnoreCase(side)) {
                b.side = SIDE_TOP;
            } else if ("left".equalsIgnoreCase(side)) {
                b.side = SIDE_LEFT;
            } else if ("right".equalsIgnoreCase(side)) {
                b.side = SIDE_RIGHT;
            } else {
                b.side = SIDE_BOTTOM;
            }
            b.offsetX = intOf(v, "offsetX", 0);
            b.offsetY = intOf(v, "offsetY", 0);
        } else {
            b.mode = MODE_DEFAULT;
        }
        b.width = intOf(v, "width", 0);
    }

    private static boolean boolOf(JsonValue v, String key, boolean def) {
        JsonValue n = v == null ? null : v.get(key);
        if (n == null) {
            return def;
        }
        try {
            if (n.isBoolean()) {
                return n.asBoolean();
            }
            if (n.isString()) {
                return "true".equalsIgnoreCase(n.asString().trim());
            }
            if (n.isNumber()) {
                return n.asInt() != 0;
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static int intOf(JsonValue v, String key, int def) {
        try {
            return v.getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static String str(JsonValue v, String key, String def) {
        try {
            return v.getString(key, def);
        } catch (Throwable t) {
            return def;
        }
    }
}
