package com.iast.agent.matcher;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Set;

/**
 * 只匹配 name 在给定集合中的类型。
 * 用于 matchType=interface 且 includeFutureClasses=false 时做"安装前已加载"过滤：
 * Agent 启动瞬间把 inst.getAllLoadedClasses() 的类名快照进来，之后新加载的实现类不在集合里，
 * 自然被 typeMatcher 排除。
 */
public final class NameInSetMatcher<T extends TypeDescription>
        extends ElementMatcher.Junction.AbstractBase<T> {

    private final Set<String> names;

    public NameInSetMatcher(Set<String> names) {
        this.names = names;
    }

    @Override
    public boolean matches(T target) {
        return target != null && names.contains(target.getName());
    }
}
