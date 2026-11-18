package com.libo.wfagent.common.runtime;

import java.util.UUID;

/**
 * Agent 入参。
 *
 * @param input   业务输入，例如进件编号、自然语言分析请求
 * @param traceId 调用方可以自带，便于和上游系统日志对齐
 */
public record AgentRequest(String input, String traceId) {

    public static AgentRequest of(String input) {
        return new AgentRequest(input, UUID.randomUUID().toString());
    }

    public static AgentRequest of(String input, String traceId) {
        return new AgentRequest(input, traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString()
                : traceId);
    }
}
