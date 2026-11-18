package com.libo.wfagent.app.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.libo.wfagent.common.runtime.AgentResult;
import com.libo.wfagent.common.runtime.AgentStep;
import com.libo.wfagent.common.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 落库版 Trace：把每次运行完整写进 {@code agent_trace} / {@code agent_trace_step}。
 * <p>
 * <b>这是「结论可回放」真正落地的地方。</b>内存实现只能回答「刚才那次跑成什么样」，
 * 落库实现能回答「上周三这笔拒贷，模型当时看到的是哪几行数据、给的理由原文是什么」。
 * 后一个问题才是审计和复核真正会问的。
 * <p>
 * 放在 wf-agent-app 而不是 wf-agent-common，是分层上的刻意选择：
 * <ul>
 *   <li>common 是运行时内核，不应该认识 JdbcTemplate / DataSource 这些基础设施；</li>
 *   <li>三个独立 Agent 模块也不应该被迫带一个数据库连接池；</li>
 *   <li>app 是组装根（composition root），只有它天然同时拥有「运行时」和「数据源」，
 *       适配器放这里最自然。</li>
 * </ul>
 * 接口 {@link TraceRecorder} 仍在 common，运行时只依赖接口。
 * <p>
 * 并发与幂等：
 * <ul>
 *   <li>同一个 trace_id 重跑（回归用例里很常见）用 {@code INSERT OR REPLACE} 覆盖头部，
 *       明细先删后插，保证不会残留上一次的旧步骤；</li>
 *   <li>SQLite 单写者模型下这套写法足够；换 MySQL 时把 REPLACE 改成
 *       {@code INSERT ... ON DUPLICATE KEY UPDATE} 即可，接口不用动。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "wf-agent.trace.mode", havingValue = "jdbc")
public class JdbcTraceRecorder implements TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(JdbcTraceRecorder.class);

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> ARG_MAP = new TypeReference<>() {
    };

    /**
     * 头部行的映射只取标量字段，steps 单独查——避免一次回放把几十步 JSON 全塞进一行结果里。
     */
    private static final RowMapper<TraceHeader> HEADER_MAPPER = (rs, rowNum) -> new TraceHeader(
            rs.getString("trace_id"),
            rs.getString("agent_name"),
            AgentResult.Status.valueOf(rs.getString("status")),
            rs.getString("output_json"),
            rs.getString("narrative"),
            rs.getString("warnings_json"),
            rs.getInt("step_count"),
            rs.getLong("total_cost_ms"),
            rs.getString("created_at"));

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 明细行的映射：需要注入的 ObjectMapper 来还原 arguments，所以做成实例字段而不是静态常量。
     * ok 在 SQLite 里是 0/1，这里还原成 boolean，避免调用方到处写 == 1。
     */
    private final RowMapper<AgentStep> stepMapper;

    public JdbcTraceRecorder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.stepMapper = (rs, rowNum) -> new AgentStep(
                rs.getInt("step_index"),
                rs.getString("type"),
                rs.getString("tool_name"),
                readArgs(rs.getString("arguments_json")),
                rs.getString("output"),
                rs.getInt("ok") == 1,
                rs.getString("error"),
                rs.getString("thought"),
                rs.getLong("cost_ms"));
    }

    @Override
    public void record(AgentResult result) {
        try {
            // 1) 头部：同一 trace_id 覆盖写。审计上「最后一次」才是有意义的事实，
            //    重跑保留旧版本会造成同号两解，反而不可信。
            jdbcTemplate.update("""
                            INSERT OR REPLACE INTO agent_trace
                            (trace_id, agent_name, status, output_json, narrative, warnings_json,
                             step_count, total_cost_ms, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    result.traceId(),
                    result.agentName(),
                    result.status().name(),
                    toJson(result.output()),
                    result.narrative(),
                    toJson(result.warnings()),
                    result.steps().size(),
                    result.totalCostMs(),
                    Instant.now().toString());

            // 2) 明细：先删后插。不能只 INSERT——重跑时 (trace_id, step_index) 主键冲突会抛异常。
            jdbcTemplate.update("DELETE FROM agent_trace_step WHERE trace_id = ?", result.traceId());

            List<Object[]> rows = new ArrayList<>(result.steps().size());
            for (AgentStep step : result.steps()) {
                rows.add(new Object[]{
                        result.traceId(),
                        step.index(),
                        step.type(),
                        step.toolName(),
                        toJson(step.arguments()),
                        // 工具返回原文不截断：截断就失去了复核价值
                        step.output(),
                        step.ok() ? 1 : 0,
                        step.error(),
                        step.thought(),
                        step.costMs()});
            }
            if (!rows.isEmpty()) {
                jdbcTemplate.batchUpdate("""
                        INSERT INTO agent_trace_step
                        (trace_id, step_index, type, tool_name, arguments_json, output, ok, error, thought, cost_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, rows);
            }
        } catch (Exception e) {
            // Trace 写失败不能把业务结论一起带崩：结论已经算出来了，
            // 这里降级为日志告警，由监控去补。宁可丢一条 Trace，不可丢一次结论。
            log.error("Trace 落库失败 agent={} traceId={}", result.agentName(), result.traceId(), e);
        }
    }

    @Override
    public Optional<AgentResult> find(String traceId) {
        List<TraceHeader> headers = jdbcTemplate.query(
                "SELECT * FROM agent_trace WHERE trace_id = ?", HEADER_MAPPER, traceId);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        TraceHeader header = headers.get(0);
        List<AgentStep> steps = jdbcTemplate.query("""
                        SELECT step_index, type, tool_name, arguments_json, output, ok, error, thought, cost_ms
                        FROM agent_trace_step WHERE trace_id = ? ORDER BY step_index
                        """,
                stepMapper, traceId);
        return Optional.of(toResult(header, steps));
    }

    @Override
    public List<AgentResult> recent(int limit) {
        // rowid 是 SQLite 的隐式自增列，按它倒序就是「最近写入」，
        // 比 created_at 字符串排序更稳（同一毫秒内多次写入不会乱序）。
        List<TraceHeader> headers = jdbcTemplate.query(
                "SELECT * FROM agent_trace ORDER BY rowid DESC LIMIT ?", HEADER_MAPPER, Math.max(limit, 1));
        List<AgentResult> results = new ArrayList<>(headers.size());
        for (TraceHeader header : headers) {
            List<AgentStep> steps = jdbcTemplate.query("""
                            SELECT step_index, type, tool_name, arguments_json, output, ok, error, thought, cost_ms
                            FROM agent_trace_step WHERE trace_id = ? ORDER BY step_index
                            """,
                    stepMapper, header.traceId());
            results.add(toResult(header, steps));
        }
        return results;
    }

    private AgentResult toResult(TraceHeader header, List<AgentStep> steps) {
        return new AgentResult(
                header.agentName(),
                header.traceId(),
                header.status(),
                readTree(header.outputJson()),
                header.narrative(),
                steps,
                readWarnings(header.warningsJson()),
                header.totalCostMs());
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Trace 字段序列化失败，按字符串兜底", e);
            return String.valueOf(value);
        }
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.nullNode();
        }
    }

    private List<String> readWarnings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> readArgs(String json) {
        // 参数是扁平 key-value；解析失败退化成空 Map，不让一步的参数丢掉整次回放
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, ARG_MAP);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 头部行的中间态。不直接映射成 AgentResult，是因为 steps 需要二次查询。 */
    private record TraceHeader(
            String traceId,
            String agentName,
            AgentResult.Status status,
            String outputJson,
            String narrative,
            String warningsJson,
            int stepCount,
            long totalCostMs,
            String createdAt) {
    }
}
