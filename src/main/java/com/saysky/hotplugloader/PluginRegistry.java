package com.saysky.hotplugloader;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 已尝试加载的附加包登记表(进程内,重启即清空)。
 *
 * <p>按 id 索引;同一个 id 只保留最后一次尝试的结果。面板用它显示每个文件的
 * 「未加载 / 已加载 / 已禁用 / 未信任 / 错误」状态。</p>
 */
public final class PluginRegistry {

    public enum Status {
        /** 已加载,事件正常派发。 */
        LOADED,
        /** 已加载但被禁用(停止事件派发)。 */
        DISABLED,
        /** 加载失败(详情见 {@link Entry#error})。 */
        ERROR,
        /** 被信任门槛拦截(未开「允许未校验附加包」且清单非 trusted)。 */
        BLOCKED
    }

    public static final class Entry {
        public final File source;
        /** 解析成功时非 null;失败时可能为 null(如缺清单)。 */
        public final PluginManifest manifest;
        public Status status;
        /** ERROR / BLOCKED 时的简短原因。 */
        public String error;
        /** LOADED / DISABLED 时的附加包实例。 */
        public Object instance;
        public ClassLoader loader;
        public long loadedAtMs;
        /** 最近一次实例化+init 的耗时(毫秒;行悬停显示;0 = 未测得)。 */
        public long loadMs;

        Entry(File source, PluginManifest manifest) {
            this.source = source;
            this.manifest = manifest;
        }

        /** 展示名(无清单时退回文件名)。 */
        public String label() {
            if (manifest == null) {
                return source.getName();
            }
            return manifest.name + " v" + manifest.version;
        }
    }

    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<String, Entry>();

    private PluginRegistry() {
    }

    public static synchronized void put(Entry entry) {
        ENTRIES.put(entry.manifest.id, entry);
    }

    public static synchronized Entry get(String id) {
        return id == null ? null : ENTRIES.get(id);
    }

    public static synchronized Entry remove(String id) {
        return id == null ? null : ENTRIES.remove(id);
    }

    /** 清空登记表(游戏重启 / 软重载时用)。 */
    public static synchronized void clear() {
        ENTRIES.clear();
    }

    /** 按源文件查条目(面板行状态用)。 */
    public static synchronized Entry bySource(File source) {
        if (source == null) {
            return null;
        }
        String path = source.getAbsolutePath();
        for (Entry e : ENTRIES.values()) {
            if (e.source.getAbsolutePath().equals(path)) {
                return e;
            }
        }
        return null;
    }

    /** 全量快照(面板/设置页统计用)。 */
    public static synchronized List<Entry> all() {
        return new ArrayList<Entry>(ENTRIES.values());
    }

    /** 同 id 但来自其它文件的条目(冲突检查;没有返回 null)。 */
    public static synchronized Entry conflictOf(String id, File source) {
        Entry e = ENTRIES.get(id);
        if (e == null) {
            return null;
        }
        if (source != null && e.source.getAbsolutePath().equals(source.getAbsolutePath())) {
            return null;
        }
        return e;
    }

    /** 处于「加载中」状态的附加包数(LOADED + DISABLED)。 */
    public static synchronized int countActive() {
        int n = 0;
        for (Entry e : ENTRIES.values()) {
            if (e.status == Status.LOADED || e.status == Status.DISABLED) {
                n++;
            }
        }
        return n;
    }
}
