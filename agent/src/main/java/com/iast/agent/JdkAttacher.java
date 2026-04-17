package com.iast.agent;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.IOException;
import java.util.List;

/**
 * 基于 jdk.attach 模块的挂载实现。
 * 仅在 JVM 含 jdk.attach 模块（即 JDK）时可用；JRE 环境下 AttachTool 会走
 * HotSpotSocketAttacher 的 UNIX Socket 协议兜底。
 *
 * 注意：本类必须不被 AttachTool 直接静态引用，由 AttachTool 反射调用，
 * 否则 JRE 上仅加载 AttachTool 就会触发 NoClassDefFoundError。
 */
public final class JdkAttacher {
    private JdkAttacher() {}

    public static void attach(String pid, String agentArgs, String agentJarPath) throws Exception {
        // list() 只用于友好输出进程名称，结果不影响后续 attach——有些环境
        // （容器、tmpdir 非默认路径、-XX:-UsePerfData）下 list 不返回目标进程，但 attach 依然可行
        try {
            List<VirtualMachineDescriptor> vms = VirtualMachine.list();
            for (VirtualMachineDescriptor vmd : vms) {
                if (vmd.id().equals(pid)) {
                    System.out.println("[IAST AttachTool] Found target process: PID=" + pid + ", " + vmd.displayName());
                    break;
                }
            }
        } catch (Throwable t) {
            // 忽略，直接尝试 attach
        }

        System.out.println("[IAST AttachTool] Attaching to process " + pid + " via jdk.attach...");
        System.out.println("[IAST AttachTool] Agent jar path: " + agentJarPath);

        VirtualMachine vm;
        try {
            vm = VirtualMachine.attach(pid);
        } catch (AttachNotSupportedException e) {
            throw new IOException(
                    "Target process does not support attach. Possible reasons:\n"
                            + "  1. Target JVM started with -XX:+DisableAttachMechanism\n"
                            + "  2. Permission denied (different user)\n"
                            + "  3. JVM attach implementation mismatch",
                    e);
        }
        try {
            System.out.println("[IAST AttachTool] Loading IAST Agent...");
            vm.loadAgent(agentJarPath, agentArgs);
            System.out.println("[IAST AttachTool] IAST Agent loaded successfully!");
        } finally {
            vm.detach();
        }
    }
}
