package com.libo.wfagent.common.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 胶水层单测：MCP 工具注册表把 Spring AI 的 ToolCallback 适配成运行时 ToolSpec，
 * 并保证失败不抛异常、由上层降级处理。
 */
class McpToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ToolCallback callback(String name) {
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(def.description()).thenReturn(name + " 描述");
        when(def.inputSchema()).thenReturn("{\"type\":\"object\",\"required\":[\"id\"]}");

        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(def);
        when(cb.call(anyString())).thenReturn("{\"found\":true,\"name\":\"" + name + "\"}");
        return cb;
    }

    private McpToolRegistry registry() {
        return new McpToolRegistry(
                List.of(callback("query_application"), callback("query_decision_trace")),
                objectMapper);
    }

    @Test
    @DisplayName("all 返回与回调一致的 ToolSpec")
    void allExposesToolSpecs() {
        List<ToolSpec> specs = registry().all();
        assertThat(specs).hasSize(2);
        assertThat(specs).extracting(ToolSpec::name)
                .containsExactly("query_application", "query_decision_trace");
    }

    @Test
    @DisplayName("subset 只返回声明的子集，未注册的抛异常")
    void subsetFiltersAndRejectsUnknown() {
        McpToolRegistry registry = registry();
        assertThat(registry.subset(List.of("query_application"))).hasSize(1);

        assertThatThrownBy(() -> registry.subset(List.of("not_exist")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not_exist");
    }

    @Test
    @DisplayName("invoke 转发给回调，失败时包装成 ToolResult.fail 而不是抛给上层")
    void invokeDelegatesToCallback() {
        McpToolRegistry registry = registry();

        ToolResult ok = registry.invoke("query_application", Map.of("id", "A001"));
        assertThat(ok.ok()).isTrue();
        assertThat(ok.output()).contains("query_application");

        ToolResult missing = registry.invoke("not_exist", Map.of());
        assertThat(missing.ok()).isFalse();
        assertThat(missing.error()).contains("not_exist");

        ToolCallback broken = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn("broken");
        when(def.description()).thenReturn("d");
        when(def.inputSchema()).thenReturn("{}");
        when(broken.getToolDefinition()).thenReturn(def);
        when(broken.call(anyString())).thenThrow(new RuntimeException("boom"));

        ToolResult failed = new McpToolRegistry(List.of(broken), objectMapper).invoke("broken", Map.of());
        assertThat(failed.ok()).isFalse();
        assertThat(failed.error()).contains("boom");
    }
}
