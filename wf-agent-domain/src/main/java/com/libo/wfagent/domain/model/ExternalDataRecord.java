package com.libo.wfagent.domain.model;

/**
 * 外部数据原文。保留供应商原始报文，便于排查时区分「数据源给错了」还是「我们解析错了」。
 */
public record ExternalDataRecord(
        long externalId,
        String applicationId,
        String provider,
        String product,
        String requestTime,
        Integer costMs,
        String responseJson) {
}
