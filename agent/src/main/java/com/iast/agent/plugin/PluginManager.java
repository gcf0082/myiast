package com.iast.agent.plugin;

import com.iast.agent.LogWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件管理器
 * 负责插件的注册、获取和调用
 */
public class PluginManager {
    private static volatile PluginManager instance;
    private final Map<String, IastPlugin> plugins = new ConcurrentHashMap<>();
    
    private PluginManager() {}
    
    public static PluginManager getInstance() {
        if (instance == null) {
            synchronized (PluginManager.class) {
                if (instance == null) {
                    instance = new PluginManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 注册插件
     */
    public void registerPlugin(String name, IastPlugin plugin) {
        plugins.put(name, plugin);
    }
    
    /**
     * 获取插件
     */
    public IastPlugin getPlugin(String name) {
        return plugins.get(name);
    }
    
    /**
     * 处理方法调用（异常不影响原始进程）
     */
    public void handleMethodCall(String pluginName, MethodContext context) {
        IastPlugin plugin = plugins.get(pluginName);
        if (plugin != null) {
            try {
                plugin.handleMethodCall(context);
            } catch (Exception e) {
                // 插件异常不影响原始进程，只记录日志
                LogWriter.getInstance().info("[IAST Plugin] Plugin " + pluginName + " error: " + e.getMessage());
            } catch (Throwable t) {
                // 捕获所有异常，包括Error
                LogWriter.getInstance().info("[IAST Plugin] Plugin " + pluginName + " error: " + t.getMessage());
            }
        }
    }
    
    /**
     * 销毁所有插件
     */
    public void destroyAll() {
        for (IastPlugin plugin : plugins.values()) {
            try {
                plugin.destroy();
            } catch (Exception e) {
                // 忽略销毁异常
            }
        }
        plugins.clear();
    }
}
