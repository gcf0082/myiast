package com.iast.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Attach 模式挂载工具——跨环境通用（JDK / JRE，Linux / macOS / Windows）。
 *
 * 自动模式优先级：
 *   1. jdk.attach 模块可用 → JdkAttacher（同进程，最简单）
 *   2. PATH 里有 jattach → JattachAttacher（外部纯 C 二进制，无 JNA 开销）
 *   3. 兜底 → ByteBuddyAgentAttacher（byte-buddy-agent 封装的跨平台实现，
 *      Linux/macOS 走 UNIX Socket，Windows 走 JNA DLL 注入）
 *
 * 强制模式（调试用）：
 *   -DiastAttach=jdk        强制走 jdk.attach
 *   -DiastAttach=jattach    强制走 jattach 外部二进制
 *   -DiastAttach=fallback   强制走 byte-buddy-agent（JDK 上也能用，便于验证 JRE 路径）
 *   -DiastAttach=socket     历史别名，等同 fallback
 */
public class AttachTool {

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }
        String pid = args[0];
        String agentArgs = args.length >= 2 ? args[1] : "";
        String agentJarPath = resolveAgentJarPath();

        String mode = System.getProperty("iastAttach", "auto");
        boolean forceJdk = "jdk".equalsIgnoreCase(mode);
        boolean forceJattach = "jattach".equalsIgnoreCase(mode);
        boolean forceFallback = "fallback".equalsIgnoreCase(mode) || "socket".equalsIgnoreCase(mode);

        try {
            if (forceJdk) {
                invokeJdkAttacher(pid, agentArgs, agentJarPath);
            } else if (forceJattach) {
                JattachAttacher.attach(pid, agentJarPath, agentArgs);
            } else if (forceFallback) {
                System.out.println("[IAST AttachTool] forced byte-buddy-agent fallback");
                ByteBuddyAgentAttacher.attach(pid, agentJarPath, agentArgs);
            } else if (isJdkAttachAvailable()) {
                invokeJdkAttacher(pid, agentArgs, agentJarPath);
            } else if (JattachAttacher.isAvailable()) {
                System.out.println("[IAST AttachTool] jdk.attach unavailable, using jattach native binary");
                JattachAttacher.attach(pid, agentJarPath, agentArgs);
            } else {
                System.out.println("[IAST AttachTool] jdk.attach/jattach unavailable, using byte-buddy-agent fallback");
                ByteBuddyAgentAttacher.attach(pid, agentJarPath, agentArgs);
            }
        } catch (NumberFormatException e) {
            System.err.println("[IAST AttachTool] Error: Invalid PID format");
            printUsage();
            System.exit(1);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("[IAST AttachTool] Error: " + cause.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("[IAST AttachTool] Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /** 判断当前JVM是否带jdk.attach模块 */
    private static boolean isJdkAttachAvailable() {
        try {
            Class.forName("com.sun.tools.attach.VirtualMachine");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 反射调用JdkAttacher.attach，避免AttachTool自身在JRE上因JdkAttacher的
     * jdk.attach 导入失败而无法加载。
     */
    private static void invokeJdkAttacher(String pid, String agentArgs, String agentJarPath) throws Exception {
        Class<?> cls = Class.forName("com.iast.agent.JdkAttacher");
        Method m = cls.getMethod("attach", String.class, String.class, String.class);
        m.invoke(null, pid, agentArgs, agentJarPath);
    }

    private static String resolveAgentJarPath() {
        try {
            return AttachTool.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void printUsage() {
        System.out.println("IAST Agent Attach Tool");
        System.out.println("Usage: java -jar iast-agent.jar <target-pid> [agent-args]");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # First attach to enable monitoring (yaml/properties均可)");
        System.out.println("  java -jar iast-agent.jar 12345 config=/path/to/iast-monitor.yaml");
        System.out.println("  # Stop / restart monitoring without restart");
        System.out.println("  java -jar iast-agent.jar 12345 stop");
        System.out.println("  java -jar iast-agent.jar 12345 start");
        System.out.println();
        System.out.println("JRE support (Linux/macOS/Windows):");
        System.out.println("  自动模式优先级：jdk.attach → jattach（若 PATH 里存在）→ byte-buddy-agent 兜底");
        System.out.println("  手动切换：-DiastAttach=jdk | jattach | fallback | auto（默认）");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  <target-pid>    PID of the target JVM process");
        System.out.println("  [agent-args]    Optional arguments passed to IAST Agent");
        System.out.println("                    config=/path/to/config.(yaml|properties)");
        System.out.println("                    stop  - Disable monitoring");
        System.out.println("                    start - Re-enable monitoring");
    }
}
