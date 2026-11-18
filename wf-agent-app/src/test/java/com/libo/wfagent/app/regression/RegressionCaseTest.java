package com.libo.wfagent.app.regression;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归用例的守护测试：把 10 条用例接进 {@code mvn test}。
 * <p>
 * <b>为什么用 Spring 上下文而不是纯单测：</b>
 * 这套用例要验证的不变量里有一条是「Trace 能按 traceId 取回」，
 * 它依赖 TraceRecorder 的真实装配（这里是落库实现 + 真实 SQLite）。
 * 用 mock 掉 Trace 的单测跑这条，等于什么都没验证。
 * <p>
 * <b>为什么数据库指到 target 目录：</b>
 * 避免测试污染开发用的 wf-agent.db；同时保留真实 SQLite 方言，
 * 这样才能覆盖 {@code INSERT OR REPLACE}、{@code rowid} 排序这些实现细节。
 * <p>
 * 注意：这个测试跑的是 <b>scripted 模式</b>，结果是确定性的，适合做 CI 门禁。
 * 「模型驱动路径」的通过率必须切 spring-ai 模式用 {@code /api/regression} 单独跑，
 * 两者的数字不能混用。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:sqlite:target/regression-test.db",
                "wf-agent.trace.mode=jdbc"
        })
class RegressionCaseTest {

    @Autowired
    private RegressionService regressionService;

    @Test
    @DisplayName("10 条回归用例在剧本模式下全部通过，且每条都能按 traceId 回放")
    void allCasesPassInScriptedMode() {
        RegressionReport report = regressionService.run();

        List<String> failures = report.cases().stream()
                .filter(c -> !c.passed())
                .map(c -> c.id() + " " + c.description() + " -> " + c.failures())
                .toList();

        assertEquals(10, report.total(), "用例数应为 10");
        assertTrue(failures.isEmpty(), "存在失败用例：\n" + String.join("\n", failures));
        assertEquals(report.total(), report.traceReplayable(),
                "每条结果都应能按 traceId 从 Trace 存储里取回，否则「结论可回放」不成立");

        // 剧本模式不调外部模型，耗时应当是毫秒级；这里只做上界粗校验，
        // 防止某次改动把单条用例拖成秒级（比如误加了真实网络调用）。
        assertTrue(report.maxMs() < 5_000, "单条用例耗时异常：" + report.maxMs() + "ms");
    }

    @Test
    @DisplayName("用例清单稳定：编号唯一且覆盖三个 Agent")
    void catalogCoversAllThreeAgents() {
        var catalog = regressionService.catalog();

        long distinctIds = catalog.stream().map(m -> m.get("id")).distinct().count();
        assertEquals(catalog.size(), distinctIds, "用例编号存在重复");

        var agents = catalog.stream().map(m -> m.get("agent")).distinct().toList();
        assertTrue(agents.containsAll(List.of("risk-review", "feature-analysis", "ecl-analysis")),
                "三个 Agent 都要有用例覆盖，实际为 " + agents);
    }
}
