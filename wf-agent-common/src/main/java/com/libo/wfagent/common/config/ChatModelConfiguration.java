package com.libo.wfagent.common.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 真实模型装配。走 OpenAI 兼容协议，所以内部统一网关、DeepSeek、通义、Ollama 都只需要改 base-url。
 * <p>
 * 只在 {@code wf-agent.model.mode=spring-ai} 时生效，默认的剧本模式不会加载这里。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "wf-agent.model.mode", havingValue = "spring-ai")
public class ChatModelConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatModel chatModel(
            @Value("${wf-agent.model.base-url}") String baseUrl,
            @Value("${wf-agent.model.api-key:}") String apiKey,
            @Value("${wf-agent.model.model:deepseek-chat}") String modelName,
            @Value("${wf-agent.model.temperature:0.0}") Double temperature) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "wf-agent.model.api-key 未配置。设置环境变量 WF_AGENT_MODEL_API_KEY，"
                            + "或把 wf-agent.model.mode 改回 scripted");
        }

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(temperature)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }
}
