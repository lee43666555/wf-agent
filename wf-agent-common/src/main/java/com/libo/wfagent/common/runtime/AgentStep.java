package com.libo.wfagent.common.runtime;

import java.util.Map;

/**
 * Agent 执行过程中的一步。整份 Trace 就是这些步骤的序列。
 *
 * @param type     TOOL_CALL / FINISH / REPEAT_BLOCKED / DEGRADE
 * @param thought  模型给出的理由原文，排查争议时先看这一列
 * @param output   工具返回或最终输出的原文，不截断，保证可回放
 */
public record AgentStep(
        int index,
        String type,
        String toolName,
        Map<String, Object> arguments,
        String output,
        boolean ok,
        String error,
        String thought,
        long costMs) {

    public static AgentStep toolCall(int index, String toolName, Map<String, Object> arguments,
                                     String thought, String output, boolean ok, String error, long costMs) {
        return new AgentStep(index, "TOOL_CALL", toolName, arguments, output, ok, error, thought, costMs);
    }

    public static AgentStep finish(int index, String thought, String output, long costMs) {
        return new AgentStep(index, "FINISH", null, Map.of(), output, true, null, thought, costMs);
    }

    public static AgentStep blocked(int index, String toolName, Map<String, Object> arguments, String thought) {
        return new AgentStep(index, "REPEAT_BLOCKED", toolName, arguments, null, false,
                "同一工具与参数的重复调用已被拦截", thought, 0);
    }
}
