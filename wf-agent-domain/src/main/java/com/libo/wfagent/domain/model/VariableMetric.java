package com.libo.wfagent.domain.model;

/**
 * 变量分箱指标，特征分析 Agent 的查询目标。
 */
public record VariableMetric(
        String varCode,
        String period,
        String segment,
        String bucket,
        long cnt,
        long badCnt) {

    public double badRate() {
        return cnt == 0 ? 0.0 : (double) badCnt / cnt;
    }
}
