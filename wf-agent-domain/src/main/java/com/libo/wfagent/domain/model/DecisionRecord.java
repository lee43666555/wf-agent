package com.libo.wfagent.domain.model;

/**
 * 决策流水中的一条规则执行记录。
 *
 * @param hit 1 命中 / 0 未命中
 */
public record DecisionRecord(
        long traceId,
        String applicationId,
        String strategyCode,
        String strategyVersion,
        String ruleCode,
        String ruleName,
        int hit,
        Double scoreDelta,
        String decidedAt) {

    public boolean matched() {
        return hit == 1;
    }
}
