package com.libo.wfagent.domain.model;

/**
 * 某一期、某一阶段、某一客群的减值参数。
 */
public record EclParam(
        String period,
        String stage,
        String segment,
        double pd,
        double lgd,
        double ead,
        double balance,
        int customerCount,
        String modelVersion) {

    /** 单条参数的预期信用损失，由代码计算，不交给模型。 */
    public double ecl() {
        return ead * pd * lgd;
    }
}
