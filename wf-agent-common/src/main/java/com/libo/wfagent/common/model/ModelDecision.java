package com.libo.wfagent.common.model;

import java.util.Map;

/**
 * 模型在当前这一步的决策。只允许两种动作，避免模型自由发挥。
 */
public sealed interface ModelDecision {

    /** 模型给出的理由，必须落 Trace。 */
    String thought();

    /**
     * 调用一个工具取数。
     */
    record CallTool(String tool, Map<String, Object> arguments, String thought) implements ModelDecision {
    }

    /**
     * 认为信息已经足够，给出结论。
     *
     * @param output    结构化结果，字段受 {@code AgentSpec#requiredFields} 约束
     * @param narrative 文字说明，只允许复述 output 中的事实
     */
    record Finish(Map<String, Object> output, String narrative, String thought) implements ModelDecision {
    }
}
