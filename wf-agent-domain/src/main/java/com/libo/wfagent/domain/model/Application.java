package com.libo.wfagent.domain.model;

/**
 * 进件主信息。
 */
public record Application(
        String applicationId,
        String customerId,
        String channel,
        String productCode,
        String applyTime,
        double applyAmount,
        String decision,
        String rejectCode,
        String rejectDesc) {
}
