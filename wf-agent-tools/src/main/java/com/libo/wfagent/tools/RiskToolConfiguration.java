package com.libo.wfagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libo.wfagent.common.tool.LocalToolRegistry;
import com.libo.wfagent.common.tool.ToolRegistry;
import com.libo.wfagent.common.tool.ToolSpecs;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具装配。
 * <p>
 * 同一份 {@link RiskQueryTools} 有两种暴露方式：
 * <ul>
 *   <li>{@link ToolCallbackProvider}：MCP Server 自动把这些方法注册成 MCP 工具</li>
 *   <li>{@link ToolRegistry}：Agent 直接在本进程内调用，开发调试用</li>
 * </ul>
 * 两者工具名、描述、参数 Schema 完全一致，切换不会改变 Agent 行为。
 */
@Configuration(proxyBeanMethods = false)
public class RiskToolConfiguration {

    @Bean
    public ToolCallbackProvider riskQueryToolCallbackProvider(RiskQueryTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "wf-agent.tools.mode", havingValue = "local", matchIfMissing = true)
    public ToolRegistry localToolRegistry(RiskQueryTools tools, ObjectProvider<ObjectMapper> objectMapperProvider) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build()
                .getToolCallbacks();
        return new LocalToolRegistry(
                ToolSpecs.fromCallbacks(callbacks, objectMapperProvider.getIfAvailable(ObjectMapper::new)));
    }
}
