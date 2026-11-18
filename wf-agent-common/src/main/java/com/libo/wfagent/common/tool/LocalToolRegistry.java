package com.libo.wfagent.common.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程内工具注册表。开发期用它把数据库查询直接挂到 Agent 上，省掉一次 HTTP 往返。
 */
public class LocalToolRegistry implements ToolRegistry {

    private final Map<String, ToolSpec> tools = new LinkedHashMap<>();

    public LocalToolRegistry(List<ToolSpec> specs) {
        for (ToolSpec spec : specs) {
            ToolSpec previous = tools.put(spec.name(), spec);
            if (previous != null) {
                throw new IllegalStateException("工具名重复: " + spec.name());
            }
        }
    }

    @Override
    public List<ToolSpec> all() {
        return List.copyOf(tools.values());
    }

    @Override
    public List<ToolSpec> subset(Collection<String> names) {
        return names.stream()
                .map(name -> {
                    ToolSpec spec = tools.get(name);
                    if (spec == null) {
                        throw new IllegalArgumentException("未注册的工具: " + name);
                    }
                    return spec;
                })
                .toList();
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        ToolSpec spec = tools.get(toolName);
        if (spec == null) {
            return ToolResult.fail("工具不存在: " + toolName, 0);
        }
        long start = System.nanoTime();
        try {
            String output = spec.invoker().invoke(arguments == null ? Map.of() : arguments);
            return ToolResult.ok(output, elapsedMs(start));
        } catch (Exception e) {
            // 取数失败不中断循环：由运行时记录失败步并决定是否换一条路
            return ToolResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage(), elapsedMs(start));
        }
    }

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }
}
