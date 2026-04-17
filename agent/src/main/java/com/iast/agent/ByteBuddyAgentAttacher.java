package com.iast.agent;

import net.bytebuddy.agent.ByteBuddyAgent;

import java.io.File;

/**
 * JRE/跨平台兜底挂载实现，基于 byte-buddy-agent。
 *
 * byte-buddy-agent 内部按顺序尝试：
 *   1. jdk.attach 模块（JDK 环境可用）
 *   2. Linux/macOS：UnixDomainSocketAddress 走 HotSpot /tmp/.java_pid&lt;pid&gt; 协议
 *   3. Windows：JNA 调用 Win32 API
 *       OpenProcess → VirtualAllocEx → WriteProcessMemory
 *       → CreateRemoteThread → LoadLibrary($JAVA_HOME/bin/attach.dll)
 *   4. 老 JDK（Java 8）的 tools.jar
 *
 * 因此这一条路径对 JRE、Linux、macOS、Windows 都通用。
 */
public final class ByteBuddyAgentAttacher {
    private ByteBuddyAgentAttacher() {}

    public static void attach(String pid, String agentJarPath, String agentArgs) {
        System.out.println("[IAST AttachTool] Attaching to process " + pid
                + " via byte-buddy-agent (cross-platform fallback)...");
        System.out.println("[IAST AttachTool] Agent jar path: " + agentJarPath);
        System.out.println("[IAST AttachTool] Loading IAST Agent...");
        String args = agentArgs == null ? "" : agentArgs;
        ByteBuddyAgent.attach(new File(agentJarPath), pid, args);
        System.out.println("[IAST AttachTool] IAST Agent loaded successfully!");
    }
}
