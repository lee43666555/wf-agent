package com.libo.wfagent.domain.model;

/**
 * 策略版本。排查时需要确认「这条规则当时是哪个版本判的」。
 */
public record StrategyVersion(
        String strategyCode,
        String version,
        String status,
        String publishedAt,
        String owner,
        String remark) {
}
