package com.libo.wfagent.common.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libo.wfagent.common.runtime.AgentSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 胶水层单测：受约束 JSON 的解析与围栏剥离。
 * <p>
 * 这一层是「换模型行为一致」的关键——不认识的 action、非法 JSON、Markdown 代码块
 * 都必须按降级收场，而不是抛异常打断整轮排查。
 */
class SpringAiAgentModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SpringAiAgentModel model =
            new SpringAiAgentModel(mock(ChatModel.class), objectMapper);

    private AgentSpec spec() {
        return AgentSpec.builder("test")
                .requiredFields("decision", "hitRules")
                .build();
    }

    @Test
    @DisplayName("剥掉 ```json 围栏，保留裸 JSON 与空输入")
    void stripFenceRemovesMarkdownFence() {
        assertThat(model.stripFence("```json\n{\"action\":\"FINISH\"}\n```"))
                .isEqualTo("{\"action\":\"FINISH\"}");
        assertThat(model.stripFence("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(model.stripFence(null)).isEmpty();
    }

    @Test
    @DisplayName("CALL_TOOL 与 FINISH 能正确解析")
    void parseValidDecisions() {
        ModelDecision call = model.parse(
                "{\"action\":\"CALL_TOOL\",\"tool\":\"query\",\"arguments\":{\"id\":\"A\"},\"thought\":\"查\"}",
                spec());
        assertThat(call).isInstanceOf(ModelDecision.CallTool.class);
        assertThat(((ModelDecision.CallTool) call).tool()).isEqualTo("query");

        ModelDecision finish = model.parse(
                "{\"action\":\"FINISH\",\"output\":{\"decision\":\"REJECT\",\"hitRules\":[]},"
                        + "\"narrative\":\"n\",\"thought\":\"t\"}",
                spec());
        assertThat(finish).isInstanceOf(ModelDecision.Finish.class);
        assertThat(((ModelDecision.Finish) finish).output()).containsEntry("decision", "REJECT");
    }

    @Test
    @DisplayName("不认识的 action 与非法 JSON 都按降级收场")
    void parseDegradesOnGarbage() {
        ModelDecision unknown = model.parse("{\"action\":\"SEARCH\"}", spec());
        assertThat(unknown).isInstanceOf(ModelDecision.Finish.class);
        assertThat(((ModelDecision.Finish) unknown).output()).isEmpty();

        ModelDecision garbage = model.parse("不是 JSON", spec());
        assertThat(garbage).isInstanceOf(ModelDecision.Finish.class);
        assertThat(((ModelDecision.Finish) garbage).output()).isEmpty();
    }
}
