package com.saysky.hotplugloader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射小工具:全部"失败返回 null/false 而不抛出",便于在游戏进程里安全使用。
 *
 * <p><b>b51</b>:{@code Field}/{@code Method} 做缓存。{@code Class.getDeclaredField}/
 * {@code getMethod} 在 ART 上每次都会重新查找并新建对象,而 {@code MainMenuMount} 这类
 * 每帧钩子会重复查同一批字段(实测每帧 60~70 次)→ 属于纯浪费的帧开销。
 * 命中缓存后 = 一次 hash 查找 + {@code Field.get}。</p>
 */
public final class ReflectUtil {

    /** 字段缓存 key = ["own:"]类名#字段名。只缓存“找到”的结果(找不到的每帧重试一次也不多)。 */
    private static final ConcurrentHashMap<String, Field> FIELD_CACHE =
            new ConcurrentHashMap<String, Field>();
    /** 方法缓存 key = 类名#方法名(参数类型1,参数类型2...)。 */
    private static final ConcurrentHashMap<String, Method> METHOD_CACHE =
            new ConcurrentHashMap<String, Method>();
    /** 缓存上限(防止遍历未知类型时无限膨胀)。 */
    private static final int CACHE_MAX = 512;

    private ReflectUtil() {
    }

    private static String fieldKey(Class<?> cls, String name, boolean own) {
        return (own ? "own:" : "") + cls.getName() + '#' + name;
    }

    private static String methodKey(Class<?> cls, String name, Class<?>[] types) {
        StringBuilder sb = new StringBuilder(cls.getName()).append('#').append(name).append('(');
        if (types != null) {
            for (int i = 0; i < types.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(types[i] == null ? "?" : types[i].getName());
            }
        }
        return sb.append(')').toString();
    }

    /** 沿类层次查找字段(含私有字段/父类字段)。b51:带缓存。 */
    public static Field findField(Class<?> cls, String name) {
        String key = fieldKey(cls, name, false);
        Field cached = FIELD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                if (FIELD_CACHE.size() < CACHE_MAX) {
                    FIELD_CACHE.put(key, f);
                }
                return f;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** 仅查对象"自身类"声明的字段(不查父类,避免同名基类字段被误伤)。b51:带缓存。 */
    public static Field findOwnField(Object obj, String name) {
        String key = fieldKey(obj.getClass(), name, true);
        Field cached = FIELD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            if (FIELD_CACHE.size() < CACHE_MAX) {
                FIELD_CACHE.put(key, f);
            }
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object get(Object obj, String name) {
        Field f = findField(obj.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object getOwn(Object obj, String name) {
        Field f = findOwnField(obj, name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Integer getInt(Object obj, String name) {
        Field f = findField(obj.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            return f.getInt(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Integer getOwnInt(Object obj, String name) {
        Field f = findOwnField(obj, name);
        if (f == null) {
            return null;
        }
        try {
            return f.getInt(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean setInt(Object obj, String name, int value) {
        Field f = findField(obj.getClass(), name);
        if (f == null) {
            return false;
        }
        try {
            f.setInt(obj, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean setOwnInt(Object obj, String name, int value) {
        Field f = findOwnField(obj, name);
        if (f == null) {
            return false;
        }
        try {
            f.setInt(obj, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 写布尔字段(含父类;主菜单「不可滚动」不变量等场景)。 */
    public static boolean setBoolean(Object obj, String name, boolean value) {
        Field f = findField(obj.getClass(), name);
        if (f == null) {
            return false;
        }
        try {
            f.setBoolean(obj, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 写对象字段(含父类;值类型自动匹配失败则返回 false)。 */
    public static boolean set(Object obj, String name, Object value) {
        Field f = findField(obj.getClass(), name);
        if (f == null) {
            return false;
        }
        try {
            f.set(obj, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 调公开无参/带参方法(仅 public)。b51:带 Method 缓存(原来每帧 getMethod ≈ 5~20µs)。 */
    public static Object call(Object obj, String name, Class<?>[] types, Object[] args) {
        try {
            String key = methodKey(obj.getClass(), name, types);
            Method m = METHOD_CACHE.get(key);
            if (m == null) {
                m = obj.getClass().getMethod(name, types);
                if (METHOD_CACHE.size() < CACHE_MAX) {
                    METHOD_CACHE.put(key, m);
                }
            }
            return m.invoke(obj, args);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
