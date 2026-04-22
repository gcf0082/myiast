package com.iast.agent;

import net.bytebuddy.description.type.TypeDescription;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 收集 Byte Buddy 实际织入了 advice 的类，给 CLI {@code transformed} 命令用。
 *
 * <p>{@link IastAgent} 的 {@code AgentBuilder.Listener.Adapter} 在 {@code onTransformation} /
 * {@code onError} 里调本类的 record 方法。CLI 通过 {@link #snapshotTransformed()} 拉一份不可
 * 变快照来打印。
 *
 * <h3>不变量</h3>
 * <ul>
 *   <li>同一 className 跨多个 ClassLoader 共享一个 entry，{@link Entry#loaders} 集合记多个</li>
 *   <li>同一 (className, loader) 多次 transform（retransformation）→ {@link Entry#count} 自增</li>
 *   <li>错误用独立 map 存，不污染 transformed 列表；CLI v1 不 expose 错误，给后续
 *       {@code transformed errors} 子命令留接口</li>
 *   <li>线程安全：transformation 回调可能在任何 class-loading 线程上触发</li>
 * </ul>
 */
public final class TransformedClasses {

    private static final TransformedClasses INSTANCE = new TransformedClasses();

    public static TransformedClasses getInstance() {
        return INSTANCE;
    }

    private TransformedClasses() {}

    private final ConcurrentHashMap<String, Entry> transformed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ErrorEntry> errors = new ConcurrentHashMap<>();

    /** onTransformation 回调里调。typeDescription / classLoader 直接来自 Byte Buddy。 */
    public void recordTransform(TypeDescription typeDescription, ClassLoader classLoader) {
        if (typeDescription == null) return;
        String name = typeDescription.getName();
        String loader = describeLoader(classLoader);
        long now = System.currentTimeMillis();
        Entry e = transformed.computeIfAbsent(name, k -> new Entry(now));
        e.loaders.add(loader);
        e.count.incrementAndGet();
    }

    /** onError 回调里调。 */
    public void recordError(String typeName, ClassLoader classLoader, Throwable t) {
        if (typeName == null) return;
        String key = typeName + "@" + describeLoader(classLoader);
        ErrorEntry ee = errors.computeIfAbsent(key, k -> new ErrorEntry(typeName, describeLoader(classLoader), System.currentTimeMillis()));
        ee.count.incrementAndGet();
        if (t != null) {
            ee.lastMessage = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 给 CLI 用的不可变快照，按 className 字典序排好。 */
    public Map<String, Entry> snapshotTransformed() {
        // 用 LinkedHashMap 保序，便于 CLI 直接 iterate；entry 内部字段读时是当下值，可接受
        Map<String, Entry> sorted = new LinkedHashMap<>();
        transformed.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return Collections.unmodifiableMap(sorted);
    }

    /** 错误快照：v1 暂未 expose 到 CLI，给后续命令留接口。 */
    public Map<String, ErrorEntry> snapshotErrors() {
        Map<String, ErrorEntry> sorted = new LinkedHashMap<>();
        errors.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return Collections.unmodifiableMap(sorted);
    }

    /** loader 指纹：null → "bootstrap"；其余 → "<simpleName>@<identityHashHex>" */
    public static String describeLoader(ClassLoader cl) {
        if (cl == null) return "bootstrap";
        String sn = cl.getClass().getSimpleName();
        if (sn.isEmpty()) sn = cl.getClass().getName();
        return sn + "@" + Integer.toHexString(System.identityHashCode(cl));
    }

    public static final class Entry {
        public final Set<String> loaders = new CopyOnWriteArraySet<>();
        public final AtomicLong count = new AtomicLong(0L);
        public final long firstAtMs;

        Entry(long firstAtMs) {
            this.firstAtMs = firstAtMs;
        }
    }

    public static final class ErrorEntry {
        public final String typeName;
        public final String loader;
        public final long firstAtMs;
        public final AtomicLong count = new AtomicLong(0L);
        public volatile String lastMessage;

        ErrorEntry(String typeName, String loader, long firstAtMs) {
            this.typeName = typeName;
            this.loader = loader;
            this.firstAtMs = firstAtMs;
        }
    }
}
