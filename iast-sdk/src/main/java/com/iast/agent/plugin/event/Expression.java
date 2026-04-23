package com.iast.agent.plugin.event;

import com.iast.agent.plugin.MethodContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量表达式求值器
 * 支持语法：Root ( '.' Ident '()' )*
 *   Root :=
 *     params[N]   → 第N个参数（0开始，params[0]为第一个）
 *     target      → 被监控对象（this）
 *     return      → 返回值对象（仅EXIT阶段有值；别名：result）
 *     throwable   → 抛出的异常（仅EXCEPTION阶段）
 *     requestId   → 当前请求跟踪ID
 *     callId      → 本次调用自增ID
 *     className / methodName → 当前拦截的类名/方法名
 *     context / ctx → 返回 {@link EvalContext}，进一步 .xxx() 可取：
 *                      pid / hostname / instanceName / agentStartTime /
 *                      stackTrace / stackTraceClasses /
 *                      threadId / threadName / phase / duration / enterTime / exitTime /
 *                      requestId / callId / className / methodName / descriptor
 *                      例：context.stackTraceClasses / ctx.pid / ctx.hostname
 *   Method: 仅无参方法
 * 任一步异常 → 返回null（每类异常仅记录一次告警）
 * 防重入：求值过程中再次触发表达式执行，嵌套调用直接返回null
 */
public final class Expression {

    private static final ThreadLocal<Boolean> IN_EVAL = new ThreadLocal<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private final String source;
    private final Step root;
    private final List<String> methodChain;

    private interface Step {
        Object apply(MethodContext ctx);
    }

    private Expression(String source, Step root, List<String> methodChain) {
        this.source = source;
        this.root = root;
        this.methodChain = methodChain;
    }

    public String getSource() {
        return source;
    }

    public static Expression compile(String expr) {
        if (expr == null) {
            throw new IllegalArgumentException("expression is null");
        }
        String s = expr.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("expression is empty");
        }

        // 切分 root.method().method()...
        List<String> parts = splitTopLevel(s);
        String rootTok = parts.get(0);
        Step root = parseRoot(rootTok);

        List<String> chain = new ArrayList<>();
        for (int i = 1; i < parts.size(); i++) {
            String seg = parts.get(i);
            // 同时接受 foo() 和 foo 两种写法：
            //   foo()      —— 传统显式无参方法调用（老用法，向后兼容）
            //   foo        —— JavaBean 属性简写，运行时按 foo / getFoo / isFoo 顺序查找方法
            // 这让 "context.pid" / "target.simpleName" 之类的简写能像属性访问一样写。
            String name;
            if (seg.endsWith("()")) {
                name = seg.substring(0, seg.length() - 2).trim();
            } else {
                name = seg;
            }
            if (name.isEmpty() || !isIdent(name)) {
                throw new IllegalArgumentException("invalid segment: " + seg);
            }
            chain.add(name);
        }

        return new Expression(s, root, chain);
    }

    public Object eval(MethodContext ctx) {
        Boolean flag = IN_EVAL.get();
        if (flag != null && flag) {
            return null;
        }
        IN_EVAL.set(Boolean.TRUE);
        try {
            Object cur = root.apply(ctx);
            for (String m : methodChain) {
                if (cur == null) return null;
                cur = invokeNoArg(cur, m);
                if (cur == null) return null;
            }
            return cur;
        } catch (Throwable t) {
            String key = source + "|" + t.getClass().getName();
            if (WARNED.add(key)) {
                com.iast.agent.LogWriter.getInstance().info(
                        "[CustomEventPlugin] expression eval failed: '" + source + "' -> " + t);
            }
            return null;
        } finally {
            IN_EVAL.remove();
        }
    }

    // -------- root parsing --------

    private static Step parseRoot(String tok) {
        String t = tok.trim();
        if (t.startsWith("params[") && t.endsWith("]")) {
            String idxStr = t.substring("params[".length(), t.length() - 1).trim();
            final int idx;
            try {
                idx = Integer.parseInt(idxStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid params index: " + tok);
            }
            return ctx -> {
                Object[] args = ctx.getArgs();
                if (args == null || idx < 0 || idx >= args.length) return null;
                return args[idx];
            };
        }
        switch (t) {
            case "target":     return MethodContext::getTarget;
            case "return":
            case "result":     return MethodContext::getResult;
            case "throwable":  return MethodContext::getThrowable;
            case "requestId":  return ctx -> {
                String id = com.iast.agent.plugin.RequestIdHolder.get();
                return id != null ? id : ctx.getRequestId();
            };
            case "callId":     return ctx -> ctx.getCallId();
            case "className":  return MethodContext::getClassName;
            case "methodName": return MethodContext::getMethodName;
            case "context":
            case "ctx":        return EvalContext::new;
            default:
                throw new IllegalArgumentException("unsupported root: " + tok);
        }
    }

    // -------- splitting helpers --------

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int bracket = 0, paren = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') bracket++;
            else if (c == ']') bracket--;
            else if (c == '(') paren++;
            else if (c == ')') paren--;
            else if (c == '.' && bracket == 0 && paren == 0) {
                out.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(s.substring(start).trim());
        return out;
    }

    private static boolean isIdent(String s) {
        if (s.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    // -------- reflection helpers --------

    private static Object invokeNoArg(Object target, String methodName) throws Exception {
        Class<?> cls = target.getClass();
        String key = cls.getName() + "#" + methodName;
        Method m = METHOD_CACHE.get(key);
        if (m == null) {
            m = findNoArgMethod(cls, methodName);
            if (m == null) {
                throw new NoSuchMethodException(cls.getName() + "." + methodName + "()");
            }
            try {
                m.setAccessible(true);
            } catch (Throwable ignore) {
                // 某些JDK模块化限制，忽略
            }
            METHOD_CACHE.put(key, m);
        }
        return m.invoke(target);
    }

    private static Method findNoArgMethod(Class<?> cls, String name) {
        // 优先返回"模块可访问"类上声明的Method（JVM按虚方法分派，最终仍调用子类实现）
        // 例：sun.nio.fs.UnixPath.toString() 属于java.base未导出包，直接反射会抛IllegalAccessException
        //     改用java.lang.Object.toString()的Method，invoke时仍会分派到UnixPath的重写
        // 优先按字面方法名找；找不到再退化到 JavaBean 风格的 getXxx / isXxx，
        // 这样用户能写 context.pid / target.simpleName 之类的简写，等价于 getPid() / getSimpleName()。
        Method m = tryFindExact(cls, name);
        if (m != null) return m;
        String cap = capitalize(name);
        if (cap != null) {
            m = tryFindExact(cls, "get" + cap);
            if (m != null) return m;
            m = tryFindExact(cls, "is" + cap);
            if (m != null) return m;
        }
        return null;
    }

    private static Method tryFindExact(Class<?> cls, String name) {
        Method fallback = null;
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                if (fallback == null) fallback = m;
                if (isModuleAccessible(c)) {
                    return m;
                }
            } catch (NoSuchMethodException ignore) {
                // continue
            }
        }
        // 接口层级（如List.size）
        for (Class<?> iface : cls.getInterfaces()) {
            try {
                Method m = iface.getMethod(name);
                if (isModuleAccessible(iface)) return m;
                if (fallback == null) fallback = m;
            } catch (NoSuchMethodException ignore) {
                // continue
            }
        }
        return fallback;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return null;
        char c = s.charAt(0);
        if (!Character.isLowerCase(c)) return null;
        return Character.toUpperCase(c) + s.substring(1);
    }

    private static boolean isModuleAccessible(Class<?> c) {
        try {
            Module m = c.getModule();
            if (m == null || !m.isNamed()) return true;
            return m.isExported(c.getPackageName());
        } catch (Throwable t) {
            return true;
        }
    }
}
