package com.libo.wfagent.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libo.wfagent.common.tool.McpToolRegistry;
import com.libo.wfagent.common.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * MCP 模式下的工具装配：
 * 工具不再从本进程的 Bean 取，而是通过 MCP 协议从 {@code wf-agent-mcp-server} 拉取。
 * <p>
 * 只依赖 {@code tool.call} 的调用能力，Agent 侧代码完全不变——这正是
 * {@link ToolRegistry} 这层抽象存在的意义。
 * <p>
 * 启用方式：{@code --spring.profiles.active=mcp}，同时保证 MCP 服务已在配置的地址上运行。
 * 若 MCP 服务没起，客户端会在启动期失败，不会静默退化成“没有工具”。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "wf-agent.tools.mode", havingValue = "mcp")
public class McpToolRegistryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistryConfiguration.class);

    @Bean
    public ToolRegistry mcpToolRegistry(SyncMcpToolCallbackProvider mcpToolCallbackProvider,
                                        ObjectProvider<ObjectMapper> objectMapperProvider) {
        ToolCallback[] callbacks = mcpToolCallbackProvider.getToolCallbacks();
        List<String> names = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .toList();
        log.info("已从 MCP 拉取 {} 个工具: {}", names.size(), names);
        return new McpToolRegistry(Arrays.asList(callbacks),
                objectMapperProvider.getIfAvailable(ObjectMapper::new));
    }
}
