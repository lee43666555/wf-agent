package com.libo.wfagent.app.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libo.wfagent.common.runtime.AgentResult;
import com.libo.wfagent.common.runtime.AgentStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 胶水层单测：落库版 Trace 的写入、回放与重跑幂等。
 * <p>
 * 这是「结论可回放」真正落地的地方。直接用 JdbcTemplate + 临时 SQLite 文件，
 * 不依赖 Spring 上下文，聚焦 JdbcTraceRecorder 本身的行为。
 */
class JdbcTraceRecorderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path dbFile;
    private JdbcTraceRecorder recorder;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("trace", ".db");
        dbFile.toFile().deleteOnExit();

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_trace (
                    trace_id TEXT PRIMARY KEY, agent_name TEXT NOT NULL, status TEXT NOT NULL,
                    output_json TEXT, narrative TEXT, warnings_json TEXT,
                    step_count INTEGER NOT NULL, total_cost_ms INTEGER NOT NULL, created_at TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_trace_step (
                    trace_id TEXT NOT NULL, step_index INTEGER NOT NULL, type TEXT NOT NULL,
                    tool_name TEXT, arguments_json TEXT, output TEXT, ok INTEGER NOT NULL,
                    error TEXT, thought TEXT, cost_ms INTEGER NOT NULL,
                    PRIMARY KEY (trace_id, step_index))
                """);

        recorder = new JdbcTraceRecorder(jdbc, objectMapper);
    }

    private AgentResult result(String traceId, int stepCount) {
        List<AgentStep> steps = new ArrayList<>();
        for (int i = 1; i <= stepCount; i++) {
            steps.add(AgentStep.toolCall(i, "query_" + i, Map.of("id", "A" + i),
                    "为什么取数", "{\"found\":true,\"id\":\"A" + i + "\"}", true, null, 5));
        }
        return new AgentResult("risk-review", traceId, AgentResult.Status.OK,
                objectMapper.valueToTree(Map.of("decision", "REJECT")), "结论", steps, List.of(), 42);
    }

    @Test
    @DisplayName("record 后可按 traceId 回放，步骤原文与理由不丢")
    void recordAndReplay() {
        recorder.record(result("T1", 2));

        assertThat(recorder.find("T1")).isPresent().get().satisfies(trace -> {
            assertThat(trace.agentName()).isEqualTo("risk-review");
            assertThat(trace.status()).isEqualTo(AgentResult.Status.OK);
            assertThat(trace.steps()).hasSize(2);
            assertThat(trace.steps().get(0).toolName()).isEqualTo("query_1");
            assertThat(trace.steps().get(0).output()).contains("A1");
            assertThat(trace.steps().get(0).thought()).isEqualTo("为什么取数");
        });

        assertThat(recorder.recent(10)).hasSize(1);
        assertThat(recorder.find("NOPE")).isEmpty();
    }

    @Test
    @DisplayName("同一 traceId 重跑覆盖头部、明细先删后插")
    void reRecordingReplacesPrevious() {
        recorder.record(result("T2", 2));
        recorder.record(result("T2", 3));

        assertThat(recorder.find("T2")).isPresent().get()
                .satisfies(trace -> assertThat(trace.steps()).hasSize(3));
        assertThat(recorder.recent(10)).hasSize(1);
    }
}
