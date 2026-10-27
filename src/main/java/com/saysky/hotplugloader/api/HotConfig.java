package com.saysky.hotplugloader.api;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 【附加包配置文件助手】(b44 引入;纯 JDK,扁平 JSON 键值)。
 *
 * <p>典型用法(放附加包数据目录 {@code ctx.dataDir()} 下):</p>
 * <pre>
 *   cfg = HotConfig.open(ctx.dataDir(), "army.json")
 *           .def("enabled", true)
 *           .def("modelPx", 64f)
 *           .def("model", "knight.g3db");
 *   enabled = cfg.bool("enabled");        // 缺键/坏值 → 自动回退 def 值
 *   model   = cfg.str("model");
 *   // 用户改配置后:cfg.set("enabled", false).save();
 * </pre>
 *
 * <p><b>行为约定</b>:</p>
 * <ul>
 *   <li>首次读值时惰性加载;文件不存在 → 按已登记的 {@code def} 顺序自动生成美化 JSON
 *       (用户拿到一份带全部键的现成模板);</li>
 *   <li>解析<b>宽容</b>:允许 {@code //} 行注释、{@code /* *}{@code /} 块注释与尾逗号
 *       (方便用户手写);但 {@link #save()} 重写时不保留注释;</li>
 *   <li>附加包没登记的键(含对象/数组等嵌套结构)<b>原样保留</b>并在 {@code save()} 时
 *       逐字节回写 —— 用户手工加的高级键不会被吃掉;</li>
 *   <li>任何 IO/解析错误都不抛异常:回退默认值,详情见 {@link #lastError()}
 *       (建议附加包用 {@code ctx.log} 打出来);</li>
 *   <li>值类型只支持布尔/整数/浮点/字符串(嵌套结构请用 {@link #rawOf(String)}
 *       自行处理或改存独立文件),刻意保持小巧。</li>
 * </ul>
 *
 * <p>键顺序:输出文件时先按 {@code def} 登记顺序,再追加其余键。</p>
 */
public final class HotConfig {

    private final File file;
    private boolean loaded;
    /** 现值:键 → JSON 原文片段(字符串含引号、嵌套为原样子串)。 */
    private final LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
    /** 默认值:键 → 已格式化的 JSON 原文(同时决定生成文件的键顺序)。 */
    private final LinkedHashMap<String, String> defaults = new LinkedHashMap<String, String>();
    private String lastError;

    private HotConfig(File file) {
        this.file = file;
    }

    /** 打开数据目录下的配置文件(此刻还不读盘;首次取值才加载/生成)。 */
    public static HotConfig open(File dataDir, String fileName) {
        return new HotConfig(new File(dataDir, fileName));
    }

    /** 直接指定文件打开(数据目录之外的场景用)。 */
    public static HotConfig forFile(File file) {
        return new HotConfig(file);
    }

    /** 配置文件本体。 */
    public File file() {
        return file;
    }

    /** 最近一次加载/解析错误描述;正常 = null。 */
    public String lastError() {
        return lastError;
    }

    /* ------------------------------------------------------------------ */
    /* 默认值登记(链式)                                                  */
    /* ------------------------------------------------------------------ */

    public HotConfig def(String key, boolean value) {
        return defRaw(key, value ? "true" : "false");
    }

    public HotConfig def(String key, int value) {
        return defRaw(key, String.valueOf(value));
    }

    public HotConfig def(String key, long value) {
        return defRaw(key, String.valueOf(value));
    }

    public HotConfig def(String key, float value) {
        return defRaw(key, num(String.valueOf(value)));
    }

    public HotConfig def(String key, double value) {
        return defRaw(key, num(String.valueOf(value)));
    }

    public HotConfig def(String key, String value) {
        return defRaw(key, quote(value == null ? "" : value));
    }

    private HotConfig defRaw(String key, String raw) {
        if (key != null && raw != null) {
            defaults.put(key, raw);
        }
        return this;
    }

    /* ------------------------------------------------------------------ */
    /* 读值(全部惰性加载;缺键/坏值回退)                                */
    /* ------------------------------------------------------------------ */

    public boolean bool(String key) {
        return bool(key, false);
    }

    public boolean bool(String key, boolean fallback) {
        String raw = peekRaw(key);
        if (raw == null) {
            return fallback;
        }
        String s = unquote(raw);
        if (s.equalsIgnoreCase("true") || s.equals("1")) {
            return true;
        }
        if (s.equalsIgnoreCase("false") || s.equals("0")) {
            return false;
        }
        return fallback;
    }

    public int intOf(String key) {
        return intOf(key, 0);
    }

    public int intOf(String key, int fallback) {
        long v = longOf(key, Long.MIN_VALUE);
        return v == Long.MIN_VALUE ? fallback : (int) v;
    }

    public long longOf(String key) {
        return longOf(key, 0L);
    }

    public long longOf(String key, long fallback) {
        String raw = peekRaw(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(unquote(raw).trim());
        } catch (Throwable ignored) {
        }
        try {
            return (long) Double.parseDouble(unquote(raw).trim());
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public float floatOf(String key) {
        return floatOf(key, 0f);
    }

    public float floatOf(String key, float fallback) {
        double v = dblOf(key, Double.NaN);
        return Double.isNaN(v) ? fallback : (float) v;
    }

    public double dblOf(String key) {
        return dblOf(key, 0.0);
    }

    public double dblOf(String key, double fallback) {
        String raw = peekRaw(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(unquote(raw).trim());
        } catch (Throwable ignored) {
        }
        try {
            return Long.parseLong(unquote(raw).trim());
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public String str(String key) {
        return str(key, null);
    }

    public String str(String key, String fallback) {
        String raw = peekRaw(key);
        return raw == null ? fallback : unquote(raw);
    }

    /** 取 JSON 原文片段(字符串含引号;嵌套结构为原样子串;高级用法)。 */
    public String rawOf(String key) {
        return peekRaw(key);
    }

    /** 键是否存在(现值或默认值里登记过)。 */
    public boolean has(String key) {
        ensureLoaded();
        return values.containsKey(key) || defaults.containsKey(key);
    }

    /** 全部键(现值 + 已登记默认值,按出现顺序)。 */
    public Set<String> keys() {
        ensureLoaded();
        LinkedHashSet<String> all = new LinkedHashSet<String>(values.keySet());
        all.addAll(defaults.keySet());
        return all;
    }

    private String peekRaw(String key) {
        ensureLoaded();
        if (values.containsKey(key)) {
            return values.get(key);
        }
        return defaults.get(key);
    }

    /* ------------------------------------------------------------------ */
    /* 改值 / 保存                                                         */
    /* ------------------------------------------------------------------ */

    public HotConfig set(String key, boolean value) {
        return setRaw(key, value ? "true" : "false");
    }

    public HotConfig set(String key, int value) {
        return setRaw(key, String.valueOf(value));
    }

    public HotConfig set(String key, long value) {
        return setRaw(key, String.valueOf(value));
    }

    public HotConfig set(String key, float value) {
        return setRaw(key, num(String.valueOf(value)));
    }

    public HotConfig set(String key, double value) {
        return setRaw(key, num(String.valueOf(value)));
    }

    public HotConfig set(String key, String value) {
        return setRaw(key, quote(value == null ? "" : value));
    }

    private HotConfig setRaw(String key, String raw) {
        ensureLoaded();
        if (key != null && raw != null) {
            values.put(key, raw);
        }
        return this;
    }

    /** 把当前全部键值重写回文件(def 顺序 + 其余键;未知/嵌套键原样保留)。 */
    public boolean save() {
        ensureLoaded();
        return writeOut();
    }

    /* ------------------------------------------------------------------ */
    /* 加载与生成                                                          */
    /* ------------------------------------------------------------------ */

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        lastError = null;
        if (file != null && file.isFile()) {
            String text = HotFiles.read(file);
            if (text == null) {
                lastError = "cannot read " + file.getName();
            } else {
                try {
                    values.putAll(parseFlat(text));
                } catch (IllegalArgumentException e) {
                    lastError = e.getMessage();
                    values.clear(); // 坏文件 → 全部走默认值(不覆盖原文件,save() 才重写)
                }
            }
        } else if (!defaults.isEmpty()) {
            writeOut(); // 首次打开:按 def 生成模板文件
        }
    }

    private boolean writeOut() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        LinkedHashSet<String> order = new LinkedHashSet<String>(defaults.keySet());
        order.addAll(values.keySet());
        int i = 0;
        int n = order.size();
        for (String key : order) {
            String raw = values.containsKey(key) ? values.get(key) : defaults.get(key);
            if (raw == null) {
                n--;
                continue;
            }
            sb.append("  ").append(quote(key)).append(": ").append(raw);
            if (++i < n) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("}\n");
        return HotFiles.write(file, sb.toString());
    }

    /* ------------------------------------------------------------------ */
    /* 迷你 JSON 解析(扁平对象;宽容:注释/尾逗号;值保留原文片段)      */
    /* ------------------------------------------------------------------ */

    /** 解析顶层为对象;非法输入抛 IllegalArgumentException(带位置描述)。 */
    static LinkedHashMap<String, String> parseFlat(String text) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        Cursor c = new Cursor(text);
        c.skipWs();
        if (!c.eat('{')) {
            throw new IllegalArgumentException("config not a JSON object (pos " + c.pos + ")");
        }
        c.skipWs();
        if (c.eat('}')) {
            return out;
        }
        while (true) {
            c.skipWs();
            String key = c.parseQuoted();
            c.skipWs();
            if (!c.eat(':')) {
                throw new IllegalArgumentException("expected ':' after key '" + key + "' (pos " + c.pos + ")");
            }
            c.skipWs();
            int start = c.pos;
            c.parseValue();
            out.put(key, text.substring(start, c.pos).trim());
            c.skipWs();
            if (c.eat(',')) {
                c.skipWs();
                if (c.eat('}')) { // 尾逗号收尾
                    break;
                }
                continue;
            }
            if (c.eat('}')) {
                break;
            }
            throw new IllegalArgumentException("expected ',' or '}' (pos " + c.pos + ")");
        }
        c.skipWs();
        if (!c.atEnd()) {
            throw new IllegalArgumentException("trailing content (pos " + c.pos + ")");
        }
        return out;
    }

    /** 字符游标(内部类,避免正则与多遍扫描)。 */
    private static final class Cursor {
        final String s;
        final int len;
        int pos;

        Cursor(String s) {
            this.s = s;
            this.len = s.length();
        }

        boolean atEnd() {
            return pos >= len;
        }

        char peek() {
            return pos < len ? s.charAt(pos) : '\0';
        }

        boolean eat(char ch) {
            if (pos < len && s.charAt(pos) == ch) {
                pos++;
                return true;
            }
            return false;
        }

        /** 空白 + // 行注释 + /* * / 块注释。 */
        void skipWs() {
            while (pos < len) {
                char ch = s.charAt(pos);
                if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                    pos++;
                } else if (ch == '/' && pos + 1 < len) {
                    char n = s.charAt(pos + 1);
                    if (n == '/') {
                        while (pos < len && s.charAt(pos) != '\n') {
                            pos++;
                        }
                    } else if (n == '*') {
                        pos += 2;
                        while (pos + 1 < len && !(s.charAt(pos) == '*' && s.charAt(pos + 1) == '/')) {
                            pos++;
                        }
                        pos = Math.min(len, pos + 2);
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        /** 读一个带引号的字符串键/值,返回 JSON 原文(含引号)。 */
        String parseQuotedToken() {
            int start = pos;
            if (!eat('"')) {
                throw new IllegalArgumentException("expected '\"' (pos " + start + ")");
            }
            while (pos < len) {
                char ch = s.charAt(pos);
                if (ch == '\\') {
                    pos += 2;
                    continue;
                }
                pos++;
                if (ch == '"') {
                    return s.substring(start, pos);
                }
            }
            throw new IllegalArgumentException("unterminated string (pos " + start + ")");
        }

        /** 读键(返回解码后的字符串)。 */
        String parseQuoted() {
            return unquote(parseQuotedToken());
        }

        /** 跳过一个值(字符串/数字/布尔/null/对象/数组),pos 停在值之后。 */
        void parseValue() {
            char ch = peek();
            if (ch == '{' || ch == '[') {
                int depth = 0;
                while (pos < len) {
                    char c2 = s.charAt(pos);
                    if (c2 == '"' ) {
                        parseQuotedToken(); // 内部串(含转义)整体跳过
                        continue;
                    }
                    if (c2 == '{' || c2 == '[') {
                        depth++;
                    } else if (c2 == '}' || c2 == ']') {
                        depth--;
                        if (depth == 0) {
                            pos++;
                            return;
                        }
                    }
                    pos++;
                }
                throw new IllegalArgumentException("unbalanced object/array (pos " + pos + ")");
            }
            if (ch == '"') {
                parseQuotedToken();
                return;
            }
            int start = pos;
            while (pos < len) {
                char c2 = s.charAt(pos);
                if (c2 == ',' || c2 == '}' || c2 == ']' || c2 == '{' || c2 == '[' || c2 == '"'
                        || c2 == ' ' || c2 == '\t' || c2 == '\r' || c2 == '\n' || c2 == '/') {
                    break;
                }
                pos++;
            }
            if (pos == start) {
                throw new IllegalArgumentException("expected value (pos " + start + ")");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 字符串转义/反转义                                                   */
    /* ------------------------------------------------------------------ */

    /** 转成 JSON 字符串字面量(含引号)。 */
    static String quote(String v) {
        StringBuilder sb = new StringBuilder(v.length() + 2);
        sb.append('"');
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 去引号 + 反转义;非字符串字面量则原样返回(trim 后)。 */
    static String unquote(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.length() < 2 || t.charAt(0) != '"' || t.charAt(t.length() - 1) != '"') {
            return t;
        }
        StringBuilder sb = new StringBuilder(t.length() - 2);
        for (int i = 1; i < t.length() - 1; i++) {
            char ch = t.charAt(i);
            if (ch != '\\') {
                sb.append(ch);
                continue;
            }
            i++;
            if (i >= t.length()) {
                break;
            }
            char e = t.charAt(i);
            switch (e) {
                case '"':  sb.append('"');  break;
                case '\\': sb.append('\\'); break;
                case '/':  sb.append('/');  break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case 'u':
                    if (i + 4 < t.length()) {
                        try {
                            sb.append((char) Integer.parseInt(t.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Throwable ignored) {
                            sb.append('?');
                        }
                    }
                    break;
                default:
                    sb.append(e);
            }
        }
        return sb.toString();
    }

    /** 数字字面量兜底(NaN/Infinity → "0")。 */
    private static String num(String literal) {
        if (literal.indexOf('N') >= 0 || literal.indexOf('I') >= 0
                || literal.indexOf('n') >= 0 || literal.indexOf('i') >= 0) {
            // NaN / Infinity / -Infinity 不是合法 JSON
            return "0";
        }
        return literal;
    }
}
