package com.libo.wfagent.domain.repository;

import com.libo.wfagent.domain.model.Application;
import com.libo.wfagent.domain.model.DecisionRecord;
import com.libo.wfagent.domain.model.ExternalDataRecord;
import com.libo.wfagent.domain.model.StrategyVersion;
import com.libo.wfagent.domain.model.VariableSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 风控域只读查询。真实环境里这些查询各自对应一个内部系统（进件系统、决策引擎、变量平台、策略中心、外部数据网关），
 * 这里用一张 SQLite 库模拟，接口形状保持一致，方便后续替换成 MCP 工具。
 */
@Repository
public class RiskDataRepository {

    private static final RowMapper<Application> APPLICATION_MAPPER = (rs, rowNum) -> new Application(
            rs.getString("application_id"),
            rs.getString("customer_id"),
            rs.getString("channel"),
            rs.getString("product_code"),
            rs.getString("apply_time"),
            rs.getDouble("apply_amount"),
            rs.getString("decision"),
            rs.getString("reject_code"),
            rs.getString("reject_desc"));

    private static final RowMapper<DecisionRecord> DECISION_MAPPER = (rs, rowNum) -> new DecisionRecord(
            rs.getLong("trace_id"),
            rs.getString("application_id"),
            rs.getString("strategy_code"),
            rs.getString("strategy_version"),
            rs.getString("rule_code"),
            rs.getString("rule_name"),
            rs.getInt("hit"),
            (Double) rs.getObject("score_delta"),
            rs.getString("decided_at"));

    private static final RowMapper<VariableSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) -> {
        Object passed = rs.getObject("passed");
        Boolean passedFlag = passed == null ? null : ((Number) passed).intValue() == 1;
        return new VariableSnapshot(
                rs.getLong("snapshot_id"),
                rs.getString("application_id"),
                rs.getString("var_code"),
                rs.getString("var_name"),
                rs.getString("var_value"),
                rs.getString("value_type"),
                rs.getString("threshold"),
                passedFlag,
                rs.getString("calc_time"),
                rs.getString("batch_no"));
    };

    private static final RowMapper<StrategyVersion> STRATEGY_MAPPER = (rs, rowNum) -> new StrategyVersion(
            rs.getString("strategy_code"),
            rs.getString("version"),
            rs.getString("status"),
            rs.getString("published_at"),
            rs.getString("owner"),
            rs.getString("remark"));

    private static final RowMapper<ExternalDataRecord> EXTERNAL_MAPPER = (rs, rowNum) -> new ExternalDataRecord(
            rs.getLong("external_id"),
            rs.getString("application_id"),
            rs.getString("provider"),
            rs.getString("product"),
            rs.getString("request_time"),
            (Integer) rs.getObject("cost_ms"),
            rs.getString("response_json"));

    private final JdbcTemplate jdbcTemplate;

    public RiskDataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Application> findApplication(String applicationId) {
        List<Application> rows = jdbcTemplate.query(
                "SELECT * FROM application WHERE application_id = ?", APPLICATION_MAPPER, applicationId);
        return rows.stream().findFirst();
    }

    public List<DecisionRecord> findDecisionTraces(String applicationId) {
        return jdbcTemplate.query(
                "SELECT * FROM decision_trace WHERE application_id = ? ORDER BY trace_id",
                DECISION_MAPPER, applicationId);
    }

    public List<VariableSnapshot> findVariableSnapshots(String applicationId) {
        return jdbcTemplate.query(
                "SELECT * FROM variable_snapshot WHERE application_id = ? ORDER BY snapshot_id",
                SNAPSHOT_MAPPER, applicationId);
    }

    public List<StrategyVersion> findStrategyVersions(String strategyCode) {
        return jdbcTemplate.query(
                "SELECT * FROM strategy_version WHERE strategy_code = ? ORDER BY published_at DESC",
                STRATEGY_MAPPER, strategyCode);
    }

    public List<StrategyVersion> findAllStrategyVersions() {
        return jdbcTemplate.query(
                "SELECT * FROM strategy_version ORDER BY strategy_code, published_at DESC",
                STRATEGY_MAPPER);
    }

    public List<ExternalDataRecord> findExternalData(String applicationId) {
        return jdbcTemplate.query(
                "SELECT * FROM external_data WHERE application_id = ? ORDER BY external_id",
                EXTERNAL_MAPPER, applicationId);
    }

    /** 同变量在其他进件上的取值，用于排查报告里的「同客群基准」。 */
    public List<VariableSnapshot> findPeerSnapshots(String varCode, String excludeApplicationId, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM variable_snapshot WHERE var_code = ? AND application_id <> ? "
                        + "ORDER BY snapshot_id LIMIT ?",
                SNAPSHOT_MAPPER, varCode, excludeApplicationId, limit);
    }
}
