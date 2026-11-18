package com.libo.wfagent.common.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 MCP 的工具注册表：工具由独立的 MCP Server 提供，这里只做一次协议适配。
 * <p>
 * 和 {@link LocalToolRegistry} 的区别只在这层适配，Agent 侧代码不需要改。
 */
public class McpToolRegistry implements ToolRegistry {

    private final Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;

    public McpToolRegistry(List<ToolCallback> callbacks, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        for (ToolCallback callback : callbacks) {
            String name = callback.getToolDefinition().name();
            if (this.callbacks.putIfAbsent(name, callback) != null) {
                throw new IllegalStateException("MCP 工具名重复: " + name);
            }
        }
    }

    @Override
    public List<ToolSpec> all() {
        return callbacks.values().stream().map(this::toSpec).toList();
    }

    @Override
    public List<ToolSpec> subset(Collection<String> names) {
        return names.stream()
                .map(name -> {
                    ToolCallback callback = callbacks.get(name);
                    if (callback == null) {
                        throw new IllegalArgumentException("MCP 未提供该工具: " + name);
                    }
                    return toSpec(callback);
                })
                .toList();
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        ToolCallback callback = callbacks.get(toolName);
        if (callback == null) {
            return ToolResult.fail("MCP 未提供该工具: " + toolName, 0);
        }
        long start = System.nanoTime();
        try {
            String payload = objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
            return ToolResult.ok(callback.call(payload), (System.nanoTime() - start) / 1_000_000);
        } catch (JsonProcessingException e) {
            return ToolResult.fail("参数序列化失败: " + e.getMessage(), (System.nanoTime() - start) / 1_000_000);
        } catch (Exception e) {
            return ToolResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage(),
                    (System.nanoTime() - start) / 1_000_000);
        }
    }

    private ToolSpec toSpec(ToolCallback callback) {
        var definition = callback.getToolDefinition();
        return new ToolSpec(
                definition.name(),
                definition.description(),
                definition.inputSchema(),
                args -> callback.call(objectMapper.writeValueAsString(args)));
    }
}
