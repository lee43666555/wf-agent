package com.libo.wfagent.common.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.libo.wfagent.common.runtime.AgentContext;
import com.libo.wfagent.common.runtime.AgentSpec;
import com.libo.wfagent.common.runtime.AgentStep;
import com.libo.wfagent.common.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实模型决策。
 * <p>
 * <b>核心取舍：不用各家原生的 function calling，要求模型每一步只输出一个受约束的 JSON。</b>
 * 这不是「不会用」，是权衡后的选择，理由有三条，面试时按这个顺序讲：
 * <ol>
 *   <li><b>跨模型行为一致。</b>金融客户不会只接一家模型，内部统一网关下可能同时挂着
 *       DeepSeek、通义、私有化部署模型。各家原生 function calling 的报文、并行调用语义、
 *       参数纠错行为都不一样，换一个模型就要重测一遍。受约束 JSON 只有一个解析器，
 *       换模型只影响「它愿不愿意按格式输出」，不影响调用链。</li>
 *   <li><b>每一步可落 Trace、可回放、可断言。</b>原生 function calling 的调用意图藏在
 *       厂商专有的消息结构里，落库就是一段不可读的 JSON；受约束 JSON 里 action / tool /
 *       arguments / thought 都是自己的字段，可以直接进 agent_trace_step 表，
 *       审计时能逐字回放「模型当时为什么这么选」。</li>
 *   <li><b>失败可降级。</b>模型输出不是合法 JSON、或者 action 不认识时，这里直接产出一个
 *       空 output 的 FINISH，交给运行时按「必填字段缺失」判 DEGRADED。
 *       宁可返回「数据缺失」，也不让模型硬凑一个看起来合理的答案。</li>
 * </ol>
 * 代价也要认：比原生调用多一层解析，而且要处理模型把 JSON 包在 Markdown 代码块里的情况
 * （见 {@link #stripFence(String)}）；另外失去了原生并行工具调用的能力，
 * 一次只能推进一步。对排查类场景（串行取数、每步都要看上一步结果）这个代价可以接受。
 */
public class SpringAiAgentModel implements AgentModel {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAgentModel.class);

    /** 单步上下文里工具输出的最大字符数，防止一次大报文把窗口挤爆。 */
    private static final int MAX_OUTPUT_CHARS = 3000;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public SpringAiAgentModel(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "spring-ai:" + chatModel.getClass().getSimpleName();
    }

    @Override
    public ModelDecision decide(AgentSpec spec, AgentContext context) {
        String raw = chatModel.call(new Prompt(List.of(
                new SystemMessage(systemPrompt(spec)),
                new UserMessage(userPrompt(context)))))
                .getResult()
                .getOutput()
                .getText();

        return parse(raw, spec);
    }

    private String systemPrompt(AgentSpec spec) {
        return """
                %s

                可用工具如下（只能调用这里列出的工具，不得臆造工具名）：
                %s

                每一步只输出一个 JSON 对象，不要输出任何解释性文字、不要用 Markdown 代码块包裹。

                需要继续取数时输出：
                {"action":"CALL_TOOL","tool":"工具名","arguments":{...},"thought":"为什么需要这个数据"}

                信息足够时输出：
                {"action":"FINISH","output":{...},"narrative":"基于已取数据的说明","thought":"为什么可以下结论"}

                硬性约束：
                1. 数值计算、阈值比较、规则是否命中，一律以工具返回的结果为准，不要自己算；
                2. 只能引用工具实际返回过的字段，没有返回的内容不要补，宁可写「数据缺失」；
                3. output 必须包含这些字段：%s；
                4. narrative 里出现的每个数字都必须能在工具返回里找到出处。
                """.formatted(
                spec.systemPrompt(),
                renderTools(spec.tools()),
                spec.requiredFields().isEmpty() ? "（无强制要求）" : String.join("、", spec.requiredFields()));
    }

    private String renderTools(List<ToolSpec> tools) {
        StringBuilder sb = new StringBuilder();
        for (ToolSpec tool : tools) {
            sb.append("- ").append(tool.name()).append("：").append(tool.description())
                    .append("；参数 Schema: ").append(tool.parametersSchema()).append('\n');
        }
        return sb.toString();
    }

    private String userPrompt(AgentContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前任务输入：").append(context.input()).append("\n\n");

        if (context.steps().isEmpty()) {
            sb.append("尚未取任何数据。\n");
        } else {
            sb.append("已执行步骤（按顺序）：\n");
            for (AgentStep step : context.steps()) {
                sb.append(step.index()).append(". ").append(step.type());
                if (step.toolName() != null) {
                    sb.append(' ').append(step.toolName()).append(' ').append(step.arguments());
                }
                sb.append(" -> ").append(truncate(step.ok() ? step.output() : "失败：" + step.error()));
                sb.append('\n');
            }
        }

        if (context.feedback() != null) {
            sb.append("\n上次输出有问题，请修正：").append(context.feedback()).append('\n');
        }
        return sb.toString();
    }

    private String truncate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= MAX_OUTPUT_CHARS
                ? text
                : text.substring(0, MAX_OUTPUT_CHARS) + "...(truncated)";
    }

    /**
     * 把模型返回的文本解析成决策。
     * <p>
     * 这里是「结构化输出」的第一道闸，处理三类现实问题：
     * <ul>
     *   <li>模型把 JSON 包在 ```json 代码块里 → {@link #stripFence} 剥掉；</li>
     *   <li>action 不认识（模型自创了 "SEARCH" 之类）→ 不猜，直接按降级收场；</li>
     *   <li>压根不是 JSON → 同上。两种情况都带上原始输出作为 thought，
     *       便于事后在 Trace 里定位是提示词问题还是模型能力问题。</li>
     * </ul>
     * 第二道闸在 {@code AgentRuntime}：即便这里解析成功，字段不齐仍然会被判 DEGRADED。
     * 两道闸的分工是「格式」与「内容」，不能合并成一道——否则一处放宽就全线放宽。
     */
    @SuppressWarnings("unchecked")
    ModelDecision parse(String raw, AgentSpec spec) {
        String json = stripFence(raw);
        try {
            JsonNode node = objectMapper.readTree(json);
            String action = node.path("action").asText("");
            String thought = node.path("thought").asText("");

            if ("CALL_TOOL".equals(action)) {
                String tool = node.path("tool").asText("");
                Map<String, Object> arguments = node.has("arguments")
                        ? objectMapper.convertValue(node.get("arguments"), Map.class)
                        : Map.of();
                return new ModelDecision.CallTool(tool, arguments, thought);
            }

            if ("FINISH".equals(action)) {
                Map<String, Object> output = node.has("output") && node.get("output").isObject()
                        ? objectMapper.convertValue(node.get("output"), Map.class)
                        : new LinkedHashMap<>();
                return new ModelDecision.Finish(output, node.path("narrative").asText(""), thought);
            }

            log.warn("agent={} 返回了无法识别的 action={}，按降级处理", spec.name(), action);
            return new ModelDecision.Finish(new LinkedHashMap<>(),
                    "模型返回的 action 不合法：" + action, raw);
        } catch (Exception e) {
            log.warn("agent={} 输出不是合法 JSON，按降级处理: {}", spec.name(), e.getMessage());
            return new ModelDecision.Finish(new LinkedHashMap<>(),
                    "模型输出无法解析为 JSON", raw);
        }
    }

    /** 模型常把 JSON 包在 ```json 里，这里剥掉。 */
    String stripFence(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }
}
