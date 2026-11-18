package com.libo.wfagent.common.runtime;

import java.util.List;

/**
 * 交给模型看的运行现场：用户输入 + 已经拿到的数据 + 上一轮的纠正提示。
 * <p>
 * 只放结构化事实，不放原始全量报文，避免上下文被单次大报文挤爆。
 */
public record AgentContext(
        String input,
        List<AgentStep> steps,
        String feedback) {

    public static AgentContext initial(String input) {
        return new AgentContext(input, List.of(), null);
    }

    public AgentContext withSteps(List<AgentStep> newSteps) {
        return new AgentContext(input, List.copyOf(newSteps), feedback);
    }

    public AgentContext withFeedback(String message) {
        return new AgentContext(input, steps, message);
    }

    /** 已经成功取到数据的工具名，用于提示模型不要重复取数。 */
    public List<String> succeededTools() {
        return steps.stream()
                .filter(s -> "TOOL_CALL".equals(s.type()) && s.ok())
                .map(AgentStep::toolName)
                .distinct()
                .toList();
    }
}
