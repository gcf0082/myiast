# IAST SDK

IAST Agent 的公共 SDK 模块，给**第三方插件作者**使用。包含插件作者需要的最小类集合：
接口定义 + 上下文容器 + 日志 / 事件 / 表达式工具。

## 包含哪些类

| 类 | 作用 |
|----|------|
| `com.iast.agent.plugin.IastPlugin` | 插件宪法接口：`init` / `handleMethodCall` / `destroy` / `getName` |
| `com.iast.agent.plugin.MethodContext` | 每次方法调用的上下文（args / target / return / throwable / phase / callId） |
| `com.iast.agent.plugin.IastContext` | 线程级请求上下文门面（requestId、属性槽） |
| `com.iast.agent.plugin.RequestIdHolder` | IastContext 薄壳，`RequestIdHolder.get()` 直接拿 requestId |
| `com.iast.agent.LogWriter` | 统一日志出口（写 `/tmp/iast-agent-<pid>.log`） |
| `com.iast.agent.plugin.event.EventWriter` | JSONL 事件输出到 `/tmp/iast-events-<pid>.jsonl` |
| `com.iast.agent.plugin.event.Expression` | `params[N]` / `target.xxx()` / `return` 表达式求值 |
| `com.iast.agent.plugin.event.JsonWriter` | 零依赖 JSON 序列化 |

## 为什么存在

Agent 的主体逻辑（字节码插桩、CLI、分发、配置解析）用户用不着；只有"写插件时
要 import 哪几个类"才需要暴露。把这些类搬到独立 SDK，让插件作者的 `pom.xml` 只要：

```xml
<dependency>
    <groupId>com.iast</groupId>
    <artifactId>iast-sdk</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

就能编译。`<scope>provided</scope>` 的含义：**SDK 类已经被 shade 进 iast-agent.jar**，
运行期目标 JVM 的 agent classloader 自带一份，插件 jar 不要重复打包，避免 class 双份
带来的 instanceof / ClassCastException 问题。

## 构建

```bash
mvn -q install -DskipTests   # 装到本地 m2 给 agent / plugin-demo 依赖
```

## 写你自己的插件

见仓库里 `iast-plugin-demo/` 模块，复制整个目录改改就能上。核心：

1. 实现 `IastPlugin`
2. `META-INF/services/com.iast.agent.plugin.IastPlugin` 里写实现类 FQCN（一行一个）
3. `mvn package` 得到 jar
4. 丢进 agent 的 `monitor.default.pluginsDir` 目录
5. `iast-monitor.yaml` 里 `plugin: <你的 getName() 返回值>` 引用它

## 兼容性

- Java 8+ source / target（和 agent、iast-cli 一致）
- 无任何运行期依赖——纯 JDK 标准库
- 后续若 SDK 加方法：
  - 新增方法有默认实现 → 老插件 jar 不改也能跑
  - 改接口签名 / 新增抽象方法 → 老插件 jar 运行时 `AbstractMethodError`，需要重新编译
