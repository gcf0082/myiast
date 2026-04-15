# IAST Java Agent

Java Agent 实现的交互式应用安全测试工具，支持监控任意JDK方法调用，无需修改应用代码。

## 功能特性
- ✅ 基于Java字节码增强（ASM），性能损耗极低
- ✅ 支持监控任意JDK类/方法调用
- ✅ 配置化管理监控规则，无需修改代码
- ✅ 支持方法名、参数、返回值、调用栈全链路采集
- ✅ 纯pre-agent模式，无需修改应用代码
- ✅ 完全兼容Java 8 ~ Java 21
- ✅ 无需额外启动参数（无需`-Xbootclasspath/a`）

## 快速开始

### 1. 构建Agent
```bash
cd agent
./build.sh
```
构建成功后会在`agent/target/`目录下生成`iast-agent.jar`

### 2. 配置监控规则
修改`iast-monitor.properties`配置文件，添加需要监控的方法：
```properties
# 配置格式：monitor.<全类名> = <方法1名>#<方法1描述符>,<方法2名>#<方法2描述符>
monitor.java.io.File = exists#()Z,getAbsolutePath#()Ljava/lang/String;
monitor.java.net.Socket = connect#(Ljava/net/SocketAddress;I)V
monitor.java.util.ArrayList = add#(Ljava/lang/Object;)Z
```

### 3. 启动应用
```bash
java -javaagent:/path/to/iast-agent.jar -jar your-application.jar
```

#### 自定义配置文件路径
```bash
java -javaagent:/path/to/iast-agent.jar=config=/path/to/custom-config.properties -jar your-application.jar
```

## 方法描述符说明
| Java方法声明 | 描述符 |
|--------------|--------|
| `boolean exists()` | `()Z` |
| `int length()` | `()I` |
| `long currentTimeMillis()` | `()J` |
| `String getAbsolutePath()` | `()Ljava/lang/String;` |
| `void connect(SocketAddress addr, int timeout)` | `(Ljava/net/SocketAddress;I)V` |
| `boolean add(Object o)` | `(Ljava/lang/Object;)Z` |

## 目录结构
```
├── agent/                  # IAST Agent 核心代码
│   ├── pom.xml             # Maven 构建配置
│   ├── build.sh            # 一键构建脚本
│   └── src/main/java/com/iast/agent/
│       ├── IastAgent.java          # Agent入口，字节码增强实现
│       └── MonitorConfig.java      # 配置文件加载解析
├── demo/                   # 示例测试程序
│   ├── com/weihua/MyTest.java      # 测试代码
│   ├── build.sh                    # 构建脚本
│   ├── run.sh                      # 运行脚本
│   └── iast-monitor.properties     # 监控配置文件
└── README.md               # 项目说明文档
```

## 技术栈
- Java 21
- ASM 9.5（字节码操作）
- Maven（构建工具）

## License
MIT
