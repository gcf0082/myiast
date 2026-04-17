package com.iast.agent.inject;

import com.iast.agent.LogWriter;
import net.bytebuddy.dynamic.loading.ClassInjector;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 {@code BufferingHttpServletRequestWrapper} 按字节定义进目标应用 ClassLoader。
 *
 * <p>为什么不让 Agent 直接 new 这个类：wrapper 继承 jakarta.servlet.http.HttpServletRequestWrapper，
 * 而 Agent 处于 bootstrap ClassLoader，bootstrap 里没有 jakarta.servlet.*。打包时 wrapper .class 被
 * 重命名成 {@code .class.bin} 放在 hidden resource 路径下，bootstrap ClassLoader 遵循 FQCN 查找约定
 * 不会去加载它。
 *
 * <p>运行期由本类读资源字节，经 ByteBuddy ClassInjector 注入目标 CL（通常是 webapp/Spring CL）。
 * 每个 ClassLoader 注入一次即可——缓存在 {@link #CACHE}。
 */
public final class WrapperInjector {
    private static final String WRAPPER_FQCN = "com.iast.agent.runtime.jakarta.BufferingHttpServletRequestWrapper";
    private static final String INNER_FQCN   = WRAPPER_FQCN + "$CachedServletInputStream";
    private static final String WRAPPER_RES  = "iast/runtime/jakarta-BufferingHttpServletRequestWrapper.class.bin";
    private static final String INNER_RES    = "iast/runtime/jakarta-BufferingHttpServletRequestWrapper$CachedServletInputStream.class.bin";
    private static final String REQ_IFACE    = "jakarta.servlet.http.HttpServletRequest";

    private static final ConcurrentHashMap<ClassLoader, Class<?>> CACHE = new ConcurrentHashMap<>();
    // 多次失败的 CL 不反复重试，避免每请求都触发一次注入抛错
    private static final ConcurrentHashMap<ClassLoader, Boolean> FAILED = new ConcurrentHashMap<>();

    private static volatile byte[] wrapperBytes;
    private static volatile byte[] innerBytes;

    private WrapperInjector() {}

    /**
     * 把传进来的 HttpServletRequest 换成 BufferingHttpServletRequestWrapper 实例。
     * 失败（注入失败 / 不是 HttpServletRequest）时返回 null，调用方应透传原 request。
     */
    public static Object wrap(Object req, long hardLimitBytes) {
        if (req == null) return null;
        ClassLoader cl = req.getClass().getClassLoader();
        if (cl == null) return null;
        if (FAILED.containsKey(cl)) return null;

        Class<?> wrapperCls = getOrInject(cl);
        if (wrapperCls == null) return null;

        try {
            Class<?> reqIface = Class.forName(REQ_IFACE, false, cl);
            if (!reqIface.isInstance(req)) return null;
            return wrapperCls.getConstructor(reqIface, long.class)
                    .newInstance(req, hardLimitBytes);
        } catch (Throwable t) {
            LogWriter.getInstance().info("[ServletBody] Instantiate wrapper failed: " + t);
            return null;
        }
    }

    private static Class<?> getOrInject(ClassLoader target) {
        Class<?> existing = CACHE.get(target);
        if (existing != null) return existing;
        synchronized (WrapperInjector.class) {
            existing = CACHE.get(target);
            if (existing != null) return existing;
            try {
                Class<?> injected = doInject(target);
                if (injected != null) {
                    CACHE.put(target, injected);
                }
                return injected;
            } catch (Throwable t) {
                LogWriter.getInstance().info("[ServletBody] Wrapper inject failed on " + target + ": " + t);
                FAILED.put(target, Boolean.TRUE);
                return null;
            }
        }
    }

    private static Class<?> doInject(ClassLoader target) throws Exception {
        if (wrapperBytes == null) wrapperBytes = readResource(WRAPPER_RES);
        if (innerBytes == null)   innerBytes   = readResource(INNER_RES);

        ClassInjector injector = pickInjector(target);
        if (injector == null) {
            throw new IllegalStateException("no available ClassInjector (tried UsingReflection + UsingUnsafe)");
        }

        // 两个类要一起注入（内部类先定义，外类引用内部类时若未定义会 NoClassDefFound；
        // LinkedHashMap 保序，外类在前也不会出问题——JVM 在 resolve 阶段才查找内部类）。
        Map<String, byte[]> types = new LinkedHashMap<>();
        types.put(WRAPPER_FQCN, wrapperBytes);
        types.put(INNER_FQCN,   innerBytes);
        Map<String, Class<?>> defined = injector.injectRaw(types);
        return defined.get(WRAPPER_FQCN);
    }

    /**
     * 选择一个可用的 ClassInjector：
     *   1. UsingReflection —— 需要 `--add-opens java.base/java.lang=ALL-UNNAMED`（JDK 17 以上默认禁）
     *   2. UsingUnsafe     —— 走 sun.misc.Unsafe.defineClass，不吃 --add-opens，覆盖面最广
     * 都不可用时返回 null。
     */
    private static ClassInjector pickInjector(ClassLoader target) {
        try {
            if (ClassInjector.UsingReflection.isAvailable()) {
                return new ClassInjector.UsingReflection(target);
            }
        } catch (Throwable ignore) { /* 往下 fallback */ }

        try {
            if (ClassInjector.UsingUnsafe.isAvailable()) {
                return new ClassInjector.UsingUnsafe(target);
            }
        } catch (Throwable ignore) { /* 给 null */ }

        return null;
    }

    private static byte[] readResource(String path) throws Exception {
        InputStream is = WrapperInjector.class.getClassLoader() != null
                ? WrapperInjector.class.getClassLoader().getResourceAsStream(path)
                : ClassLoader.getSystemResourceAsStream(path);
        if (is == null) is = ClassLoader.getSystemResourceAsStream(path);
        if (is == null) throw new IllegalStateException("resource not found: " + path);
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
