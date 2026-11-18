package com.libo.wfagent.domain.repository;

import com.libo.wfagent.domain.model.VariableMetric;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 变量分箱指标查询。特征分析 Agent 只能通过这里取数，
 * 聚合口径由代码固定，模型拿不到拼 SQL 的能力。
 */
@Repository
public class VariableMetricRepository {

    private static final RowMapper<VariableMetric> MAPPER = (rs, rowNum) -> new VariableMetric(
            rs.getString("var_code"),
            rs.getString("period"),
            rs.getString("segment"),
            rs.getString("bucket"),
            rs.getLong("cnt"),
            rs.getLong("bad_cnt"));

    private final JdbcTemplate jdbcTemplate;

    public VariableMetricRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 三个过滤条件都可为空，内部按需拼条件，参数全部走占位符。
     */
    public List<VariableMetric> query(String varCode, String period, String segment) {
        StringBuilder sql = new StringBuilder("SELECT * FROM variable_metric WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (varCode != null && !varCode.isBlank()) {
            sql.append(" AND var_code = ?");
            args.add(varCode);
        }
        if (period != null && !period.isBlank()) {
            sql.append(" AND period = ?");
            args.add(period);
        }
        if (segment != null && !segment.isBlank()) {
            sql.append(" AND segment = ?");
            args.add(segment);
        }
        sql.append(" ORDER BY var_code, period, segment, bucket");
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    public List<String> varCodes() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT var_code FROM variable_metric ORDER BY var_code", String.class);
    }

    public List<String> periods() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT period FROM variable_metric ORDER BY period", String.class);
    }

    public List<String> segments() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT segment FROM variable_metric ORDER BY segment", String.class);
    }
}
