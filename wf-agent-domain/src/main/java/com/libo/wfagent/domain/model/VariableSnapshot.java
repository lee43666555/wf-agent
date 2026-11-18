package com.libo.wfagent.domain.model;

/**
 * 进件时刻的变量取值快照。用于排查时还原「当时算出来是多少」，不受后续重跑影响。
 *
 * @param passed 1 通过阈值 / 0 超限 / null 无阈值
 */
public record VariableSnapshot(
        long snapshotId,
        String applicationId,
        String varCode,
        String varName,
        String varValue,
        String valueType,
        String threshold,
        Boolean passed,
        String calcTime,
        String batchNo) {

    public boolean breached() {
        return Boolean.FALSE.equals(passed);
    }
}
