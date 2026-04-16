package com.iast.agent;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.IOException;
import java.util.List;

/**
 * Attach模式挂载工具
 * 支持动态将IAST Agent挂载到正在运行的JVM进程中
 */
public class AttachTool {

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String pid = args[0];
        String agentArgs = args.length >= 2 ? args[1] : "";

        try {
            // 1. 检查PID是否存在
            boolean pidExists = false;
            List<VirtualMachineDescriptor> vms = VirtualMachine.list();
            for (VirtualMachineDescriptor vmd : vms) {
                if (vmd.id().equals(pid)) {
                    pidExists = true;
                    System.out.println("[IAST AttachTool] Found target process: PID=" + pid + ", " + vmd.displayName());
                    break;
                }
            }

            if (!pidExists) {
                System.err.println("[IAST AttachTool] Error: PID " + pid + " not found");
                System.exit(1);
            }

            // 2. 挂载到目标JVM
            System.out.println("[IAST AttachTool] Attaching to process " + pid + "...");
            VirtualMachine vm = VirtualMachine.attach(pid);

            // 3. 获取Agent jar路径
            String agentJarPath = AttachTool.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            System.out.println("[IAST AttachTool] Agent jar path: " + agentJarPath);

            // 4. 加载Agent
            System.out.println("[IAST AttachTool] Loading IAST Agent...");
            vm.loadAgent(agentJarPath, agentArgs);

            // 5. 卸载
            vm.detach();
            System.out.println("[IAST AttachTool] IAST Agent loaded successfully!");
            System.out.println("[IAST AttachTool] Check target process output for agent logs.");

        } catch (NumberFormatException e) {
            System.err.println("[IAST AttachTool] Error: Invalid PID format");
            printUsage();
            System.exit(1);
        } catch (AttachNotSupportedException e) {
            System.err.println("[IAST AttachTool] Error: Target process does not support attach");
            System.err.println("[IAST AttachTool] Possible reasons:");
            System.err.println("  1. Target JVM started with -XX:+DisableAttachMechanism");
            System.err.println("  2. Target process is a different user/permission denied");
            System.err.println("  3. JDK version mismatch between attach tool and target JVM");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("[IAST AttachTool] Error: Attach failed - " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("[IAST AttachTool] Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("IAST Agent Attach Tool");
        System.out.println("Usage: java -jar iast-agent.jar <target-pid> [agent-args]");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  # First attach to enable monitoring");
        System.out.println("  java -jar iast-agent.jar 12345 config=/path/to/iast-monitor.properties");
        System.out.println("  # Stop monitoring (restore target process to normal, no need to restart)");
        System.out.println("  java -jar iast-agent.jar 12345 stop");
        System.out.println("  # Restart monitoring again");
        System.out.println("  java -jar iast-agent.jar 12345 start");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  <target-pid>    PID of the target JVM process to attach");
        System.out.println("  [agent-args]    Optional arguments passed to IAST Agent");
        System.out.println("                  Supported arguments:");
        System.out.println("                    config=/path/to/custom/config.properties");
        System.out.println("                    stop   - Disable monitoring, restore target process");
        System.out.println("                    start  - Re-enable monitoring after stopped");
    }
}