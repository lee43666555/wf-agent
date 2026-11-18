package com.libo.wfagent.domain.repository;

import com.libo.wfagent.domain.model.EclParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 减值参数只读查询。ECL Agent 的可复算工具就是从这里取两期参数，由代码算差额。
 */
@Repository
public class EclRepository {

    private static final RowMapper<EclParam> MAPPER = (rs, rowNum) -> new EclParam(
            rs.getString("period"),
            rs.getString("stage"),
            rs.getString("segment"),
            rs.getDouble("pd"),
            rs.getDouble("lgd"),
            rs.getDouble("ead"),
            rs.getDouble("balance"),
            rs.getInt("customer_count"),
            rs.getString("model_version"));

    private final JdbcTemplate jdbcTemplate;

    public EclRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<EclParam> findByPeriod(String period) {
        return jdbcTemplate.query(
                "SELECT * FROM ecl_param WHERE period = ? ORDER BY stage, segment", MAPPER, period);
    }

    public List<String> periods() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT period FROM ecl_param ORDER BY period", String.class);
    }
}
