package com.iast.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class IastAgent {
    private static final AtomicInteger globalCallCount = new AtomicInteger(0);

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[IAST Agent] Starting IAST Agent...");
        System.out.println("[IAST Agent] Java version: " + System.getProperty("java.version"));
        
        // 初始化配置，支持agent参数指定配置文件路径
        MonitorConfig.init(agentArgs);
        
        // 注册字节码转换器
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
                // 检查当前类是否在监控列表中
                List<MonitorConfig.MethodRule> methodRules = MonitorConfig.getMethodRules(className);
                if (methodRules.isEmpty()) {
                    return null;
                }
                
                System.out.println("[IAST Agent] Transforming class: " + className.replace('/', '.'));
                
                ClassReader cr = new ClassReader(classfileBuffer);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
                ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                        
                        // 检查当前方法是否在监控规则中
                        for (MonitorConfig.MethodRule rule : methodRules) {
                            if (rule.getMethodName().equals(name) && rule.getDescriptor().equals(descriptor)) {
                                System.out.println("[IAST Agent] Adding monitor to method: " + name + descriptor);
                                return new MethodVisitor(Opcodes.ASM9, mv) {
                                    private final String fullMethodName = className.replace('/', '.') + "." + name + descriptor;
                                    
                                    @Override
                                    public void visitCode() {
                                        // 方法进入：调用通用日志方法，传入方法名
                                        mv.visitLdcInsn(fullMethodName);
                                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/iast/agent/IastAgent", "logEnter", "(Ljava/lang/String;)V", false);
                                        super.visitCode();
                                    }

                                    @Override
                                    public void visitInsn(int opcode) {
                                        // 根据返回类型处理方法退出
                                        if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) {
                                            // 有返回值的情况
                                            if (opcode == Opcodes.IRETURN) {
                                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/iast/agent/IastAgent", "logExitInt", "(Ljava/lang/String;I)I", false);
                                            } else if (opcode == Opcodes.LRETURN) {
                                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/iast/agent/IastAgent", "logExitLong", "(Ljava/lang/String;J)J", false);
                                            } else if (opcode == Opcodes.FRETURN) {
                                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/iast/agent/IastAgent", "logExitFloat", "(Ljava/lang/String;F)F", false);
                                            } else if (opcode == Opcodes.DRETURN) {
                                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/iast/agent/IastAgent", "logExitDouble", "(Ljava/lang/String;D)D", false);
                                            } else if (opcode == Opcodes.ARETURN) {
                                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/iast/agent/IastAgent", "logExitObject", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
                                            }
                                        } else if (opcode == Opcodes.RETURN) {
                                            // void返回类型
                                            mv.visitLdcInsn(fullMethodName);
                                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/iast/agent/IastAgent", "logExitVoid", "(Ljava/lang/String;)V", false);
                                        }
                                        super.visitInsn(opcode);
                                    }
                                };
                            }
                        }
                        return mv;
                    }
                };
                cr.accept(cv, 0);
                return cw.toByteArray();
            }
        }, true);
        
        // 批量重转换所有需要监控的类
        List<String> monitoredClasses = MonitorConfig.getMonitoredClasses();
        for (String internalClassName : monitoredClasses) {
            try {
                Class<?> clazz = Class.forName(internalClassName.replace('/', '.'));
                inst.retransformClasses(clazz);
                System.out.println("[IAST Agent] Successfully retransformed class: " + internalClassName.replace('/', '.'));
            } catch (Exception e) {
                System.err.println("[IAST Agent] Failed to retransform class " + internalClassName.replace('/', '.') + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
            
        System.out.println("[IAST Agent] Agent installed successfully, monitoring " + monitoredClasses.size() + " classes");
    }

    /**
     * 通用方法进入日志
     */
    public static void logEnter(String methodName) {
        int callId = globalCallCount.incrementAndGet();
        System.out.println("[IAST Agent] [" + callId + "] === Intercepted method call ===");
        System.out.println("[IAST Agent] [" + callId + "] Method: " + methodName);
        
        // 打印调用栈（跳过前2层：本方法和调用点）
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        if (stackTrace.length > 2) {
            System.out.println("[IAST Agent] [" + callId + "] Caller: " + stackTrace[2]);
        }
    }

    /**
     * void返回类型日志
     */
    public static void logExitVoid(String methodName) {
        int callId = globalCallCount.get();
        System.out.println("[IAST Agent] [" + callId + "] Returned: void");
        System.out.println("[IAST Agent] [" + callId + "] ========================================");
    }

    /**
     * int/boolean/byte/char/short返回类型日志
     */
    public static int logExitInt(String methodName, int result) {
        int callId = globalCallCount.get();
        System.out.println("[IAST Agent] [" + callId + "] Returned: " + result);
        System.out.println("[IAST Agent] [" + callId + "] ========================================");
        return result;
    }

    /**
     * long返回类型日志
     */
    public static long logExitLong(String methodName, long result) {
        int callId = globalCallCount.get();
        System.out.println("[IAST Agent] [" + callId + "] Returned: " + result);
        System.out.println("[IAST Agent] [" + callId + "] ========================================");
        return result;
    }

    /**
     * float返回类型日志
     */
    public static float logExitFloat(String methodName, float result) {
        int callId = globalCallCount.get();
        System.out.println("[IAST Agent] [" + callId + "] Returned: " + result);
        System.out.println("[IAST Agent] [" + callId + "] ========================================");
        return result;
    }

    /**
     * double返回类型日志
     */
    public static double logExitDouble(String methodName, double result) {
        int callId = globalCallCount.get();
        System.out.println("[IAST Agent] [" + callId + "] Returned: " + result);
        System.out.println("[IAST Agent] [" + callId + "] ========================================");
        return result;
    }

    /**
     * 对象返回类型日志
     */
    public static Object logExitObject(String methodName, Object result) {
        int callId = globalCallCount.get();
        System.out.println("[IAST Agent] [" + callId + "] Returned: " + (result == null ? "null" : result.toString()));
        System.out.println("[IAST Agent] [" + callId + "] ========================================");
        return result;
    }
}
