package com.libo.wfagent.common.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.List;

/**
 * 把 Spring AI 的 {@link ToolCallback} 转成运行时使用的 {@link ToolSpec}。
 * <p>
 * 有了这层转换，同一份工具实现可以有两种接入方式而不需要改 Agent 代码：
 * 本地用 {@code MethodToolCallbackProvider} 反射调用，远程用 MCP 客户端拉回来。
 */
public final class ToolSpecs {

    private ToolSpecs() {
    }

    public static List<ToolSpec> fromCallbacks(ToolCallback[] callbacks, ObjectMapper objectMapper) {
        return Arrays.stream(callbacks).map(callback -> toSpec(callback, objectMapper)).toList();
    }

    public static ToolSpec toSpec(ToolCallback callback, ObjectMapper objectMapper) {
        var definition = callback.getToolDefinition();
        return new ToolSpec(
                definition.name(),
                definition.description(),
                definition.inputSchema(),
                arguments -> callback.call(objectMapper.writeValueAsString(arguments)));
    }
}
