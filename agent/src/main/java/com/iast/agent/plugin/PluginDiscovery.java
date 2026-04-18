package com.iast.agent.plugin;

import com.iast.agent.LogWriter;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * 外部插件发现与加载。
 *
 * <p>工作流程：
 * <ol>
 *   <li>扫描 {@code pluginsDir} 下的 {@code *.jar}</li>
 *   <li>把所有 jar URL 一起喂给一个 {@link URLClassLoader}；parent CL 是加载本类的 CL
 *       （也就是 agent jar 所在 CL——bootstrap 追加后 {@code IastPlugin.class} 来源）。
 *       这保证外部插件里的 {@code IastPlugin} 引用和 agent 持有的是**同一个 Class 实例**，
 *       {@code ServiceLoader} 的 instanceof 检查 / 反射 / 方法表都不会乱。</li>
 *   <li>{@link ServiceLoader#load(Class, ClassLoader)} 迭代 {@code META-INF/services/
 *       com.iast.agent.plugin.IastPlugin} 列出的实现类，逐个实例化并返回</li>
 * </ol>
 *
 * <p>不做：
 * <ul>
 *   <li>插件卸载 / 热加载 —— 加载完 ClassLoader 静默持有直到 JVM 退出</li>
 *   <li>多目录 / 子目录递归 —— v1 一个扁平目录</li>
 *   <li>版本兼容校验 —— 老插件 jar 对着新 SDK 会直接 {@code AbstractMethodError}</li>
 * </ul>
 */
public final class PluginDiscovery {

    private PluginDiscovery() {}

    /**
     * 扫描目录、加载所有插件。失败单个不影响其它；返回有序列表（目录列 jar 顺序）。
     *
     * @param pluginsDir 目录路径；空 / null / 不存在都返回空列表（只打一行日志）
     * @return 成功实例化的插件列表
     */
    public static List<IastPlugin> discover(String pluginsDir) {
        if (pluginsDir == null || pluginsDir.isEmpty()) {
            return Collections.emptyList();
        }
        File dir = new File(pluginsDir);
        if (!dir.isDirectory()) {
            LogWriter.getInstance().info("[IAST Agent] pluginsDir not a directory, skipping: " + pluginsDir);
            return Collections.emptyList();
        }
        File[] jars = dir.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            LogWriter.getInstance().info("[IAST Agent] pluginsDir has no *.jar files: " + pluginsDir);
            return Collections.emptyList();
        }

        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) {
            try {
                urls[i] = jars[i].toURI().toURL();
            } catch (Exception e) {
                LogWriter.getInstance().info("[IAST Agent] bad plugin jar URL " + jars[i] + ": " + e);
                return Collections.emptyList();
            }
        }

        // parent 用 IastPlugin 自己的 CL：保证外部插件引用的 IastPlugin 和 agent 同一份，
        // 避免 "java.lang.ClassCastException: ... cannot be cast to IastPlugin" 这种 CL 双份陷阱
        ClassLoader parent = IastPlugin.class.getClassLoader();
        URLClassLoader pluginCL = new URLClassLoader(urls, parent);

        List<IastPlugin> out = new ArrayList<>();
        ServiceLoader<IastPlugin> sl = ServiceLoader.load(IastPlugin.class, pluginCL);
        Iterator<IastPlugin> it = sl.iterator();
        while (true) {
            try {
                if (!it.hasNext()) break;
                IastPlugin p = it.next();
                if (p == null) continue;
                out.add(p);
                LogWriter.getInstance().info("[IAST Agent] Discovered external plugin: "
                        + p.getName() + " (" + p.getClass().getName() + ")");
            } catch (ServiceConfigurationError e) {
                LogWriter.getInstance().info("[IAST Agent] ServiceLoader error while discovering plugin: " + e.getMessage());
                // 继续尝试下一个，不中断
            } catch (Throwable t) {
                LogWriter.getInstance().info("[IAST Agent] Unexpected error while discovering plugin: " + t);
            }
        }
        LogWriter.getInstance().info("[IAST Agent] External plugin discovery done: " + out.size() + " loaded from " + jars.length + " jar(s)");
        return out;
    }
}
