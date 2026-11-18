package com.libo.wfagent.common.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Agent 一次运行的完整产物。
 *
 * @param status   OK 正常收敛 / DEGRADED 达到步数上限或输出字段缺失 / FAILED 模型不可用
 * @param output   结构化结果，字段由 {@link AgentSpec#requiredFields()} 约束
 * @param narrative 模型写的文字说明，只允许基于 output 里的事实
 * @param steps    全链路步骤，落 Trace 后可按 traceId 回放
 */
public record AgentResult(
        String agentName,
        String traceId,
        Status status,
        JsonNode output,
        String narrative,
        List<AgentStep> steps,
        List<String> warnings,
        long totalCostMs) {

    public enum Status {
        OK,
        DEGRADED,
        FAILED
    }

    public boolean ok() {
        return status == Status.OK;
    }

    public Map<String, Object> toView() {
        return Map.of(
                "agent", agentName,
                "traceId", traceId,
                "status", status.name(),
                "steps", steps.size(),
                "warnings", warnings);
    }
}
