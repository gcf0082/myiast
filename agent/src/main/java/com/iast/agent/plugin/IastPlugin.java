package com.iast.agent.plugin;

/**
 * IAST插件接口
 * 所有插件必须实现此接口
 */
public interface IastPlugin {
    /**
     * 插件初始化
     * @param config 插件配置
     */
    void init(java.util.Map<String, Object> config);
    
    /**
     * 处理方法调用（统一入口，根据phase区分）
     * @param context 完整的方法上下文
     */
    void handleMethodCall(MethodContext context);
    
    /**
     * 插件销毁
     */
    void destroy();
    
    /**
     * 获取插件名称
     */
    String getName();
}
