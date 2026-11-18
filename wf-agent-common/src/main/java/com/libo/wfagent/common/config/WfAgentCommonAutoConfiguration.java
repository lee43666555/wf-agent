package com.libo.wfagent.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libo.wfagent.common.model.AgentModel;
import com.libo.wfagent.common.model.AgentScriptProvider;
import com.libo.wfagent.common.model.ScriptStep;
import com.libo.wfagent.common.model.ScriptedAgentModel;
import com.libo.wfagent.common.model.SpringAiAgentModel;
import com.libo.wfagent.common.runtime.AgentRuntime;
import com.libo.wfagent.common.runtime.RuntimeProperties;
import com.libo.wfagent.common.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时装配。
 * <p>
 * 模型实现由 {@code wf-agent.model.mode} 决定：
 * <ul>
 *   <li>{@code scripted}（默认）：走剧本，不需要任何 API Key，链路和 Trace 都能跑通</li>
 *   <li>{@code spring-ai}：真实调用模型，需要可用的 ChatModel</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RuntimeProperties.class)
public class WfAgentCommonAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WfAgentCommonAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AgentModel agentModel(@Value("${wf-agent.model.mode:scripted}") String mode,
                                 ObjectProvider<ChatModel> chatModels,
                                 ObjectProvider<AgentScriptProvider> scriptProviders,
                                 ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);

        if ("spring-ai".equalsIgnoreCase(mode)) {
            ChatModel chatModel = chatModels.getIfAvailable();
            if (chatModel == null) {
                throw new IllegalStateException(
                        "wf-agent.model.mode=spring-ai 但没有可用的 ChatModel，"
                                + "请检查 wf-agent.model.base-url / api-key 配置");
            }
            log.info("AgentModel 使用真实模型: {}", chatModel.getClass().getSimpleName());
            return new SpringAiAgentModel(chatModel, objectMapper);
        }

        Map<String, List<ScriptStep>> scripts = new LinkedHashMap<>();
        scriptProviders.orderedStream().forEach(provider ->
                scripts.put(provider.agentName(), provider.script()));
        log.info("AgentModel 使用剧本模式，已加载 {} 个 Agent 剧本", scripts.size());
        return new ScriptedAgentModel(scripts);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentRuntime agentRuntime(AgentModel agentModel,
                                     TraceRecorder traceRecorder,
                                     RuntimeProperties runtimeProperties,
                                     ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new AgentRuntime(agentModel, traceRecorder, runtimeProperties,
                objectMapperProvider.getIfAvailable(ObjectMapper::new));
    }
}
