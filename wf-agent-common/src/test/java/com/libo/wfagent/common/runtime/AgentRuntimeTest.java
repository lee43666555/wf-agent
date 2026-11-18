package com.libo.wfagent.common.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libo.wfagent.common.model.AgentModel;
import com.libo.wfagent.common.model.ModelDecision;
import com.libo.wfagent.common.tool.ToolSpec;
import com.libo.wfagent.common.trace.InMemoryTraceRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行时的四条硬约束：重复调用拦截、工具白名单、步数上限、结构化输出校验。
 * <p>
 * 这四件事都不该指望模型自觉，必须由代码兜住。测试里用一个「不听话的模型」来模拟各种畸形行为。
 */
class AgentRuntimeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentRuntime runtime(AgentModel model, RuntimeProperties properties) {
        return new AgentRuntime(model, new InMemoryTraceRecorder(), properties, objectMapper);
    }

    private ToolSpec tool(String name) {
        return new ToolSpec(name, "测试工具", "{\"type\":\"object\",\"required\":[\"id\"]}",
                args -> "{\"found\":true,\"id\":\"" + args.get("id") + "\"}");
    }

    @Test
    @DisplayName("同一工具同一参数反复调用会被拦截")
    void blocksRepeatedCalls() {
        AgentModel stubborn = (spec, ctx) -> new ModelDecision.CallTool(
                "query", Map.of("id", "1"), "还是要查这个");

        AgentSpec spec = AgentSpec.builder("repeat-test")
                .tools(List.of(tool("query")))
                .requiredFields("result")
                .maxSteps(3)
                .build();

        AgentResult result = runtime(stubborn, RuntimeProperties.defaults())
                .run(spec, AgentRequest.of("A001"));

        assertThat(result.status()).isEqualTo(AgentResult.Status.DEGRADED);
        assertThat(result.steps()).hasSize(3);
        // 第一次放行，第二三次被拦
        assertThat(result.steps().stream().filter(s -> "REPEAT_BLOCKED".equals(s.type())).count())
                .isEqualTo(2);
        assertThat(result.warnings()).anyMatch(w -> w.contains("重复调用"));
    }

    @Test
    @DisplayName("调用未声明的工具会被拒绝，不会真的执行")
    void rejectsUndeclaredTool() {
        AgentModel rebel = (spec, ctx) -> new ModelDecision.CallTool(
                "drop_table", Map.of("id", "1"), "想干点别的");

        AgentSpec spec = AgentSpec.builder("whitelist-test")
                .tools(List.of(tool("query")))
                .requiredFields("result")
                .maxSteps(2)
                .build();

        AgentResult result = runtime(rebel, RuntimeProperties.defaults())
                .run(spec, AgentRequest.of("A001"));

        AgentStep first = result.steps().get(0);
        assertThat(first.ok()).isFalse();
        assertThat(first.error()).contains("未声明");
        assertThat(result.warnings()).anyMatch(w -> w.contains("未声明"));
    }

    @Test
    @DisplayName("工具缺少必填参数时不会发起调用")
    void rejectsMissingToolParams() {
        AgentModel sloppy = (spec, ctx) -> new ModelDecision.CallTool(
                "query", Map.of(), "忘传参数了");

        AgentSpec spec = AgentSpec.builder("param-test")
                .tools(List.of(tool("query")))
                .requiredFields("result")
                .maxSteps(1)
                .build();

        AgentResult result = runtime(sloppy, RuntimeProperties.defaults())
                .run(spec, AgentRequest.of("A001"));

        assertThat(result.warnings()).anyMatch(w -> w.contains("缺少参数"));
    }

    @Test
    @DisplayName("必填字段缺失先要求补齐，仍缺则降级而不是硬凑")
    void degradesWhenRequiredFieldsMissing() {
        AgentModel careless = (spec, ctx) -> new ModelDecision.Finish(Map.of(), "我觉得可以了", "done");

        AgentSpec spec = AgentSpec.builder("field-test")
                .tools(List.of(tool("query")))
                .requiredFields("conclusion")
                .maxSteps(3)
                .build();

        AgentResult result = runtime(careless, new RuntimeProperties(3, 1, 1, 15_000))
                .run(spec, AgentRequest.of("A001"));

        assertThat(result.status()).isEqualTo(AgentResult.Status.DEGRADED);
        // 第一次缺失 → 要求补齐；第二次仍缺 → 降级返回
        assertThat(result.steps().stream().filter(s -> "FINISH".equals(s.type())).count()).isEqualTo(2);
        assertThat(result.warnings()).anyMatch(w -> w.contains("缺少字段"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("按降级返回"));
    }

    @Test
    @DisplayName("正常收敛时状态为 OK 且 Trace 可回放")
    void normalRunIsTraceable() {
        AgentModel normal = (spec, ctx) -> ctx.steps().isEmpty()
                ? new ModelDecision.CallTool("query", Map.of("id", "A001"), "先取数")
                : new ModelDecision.Finish(Map.of("result", "ok"), "拿到数据了", "可以收敛");

        InMemoryTraceRecorder recorder = new InMemoryTraceRecorder();
        AgentRuntime runtime = new AgentRuntime(normal, recorder, RuntimeProperties.defaults(), objectMapper);

        AgentSpec spec = AgentSpec.builder("happy-path")
                .tools(List.of(tool("query")))
                .requiredFields("result")
                .maxSteps(3)
                .build();

        AgentRequest request = AgentRequest.of("A001");
        AgentResult result = runtime.run(spec, request);

        assertThat(result.status()).isEqualTo(AgentResult.Status.OK);
        assertThat(result.steps()).hasSize(2);
        assertThat(result.totalCostMs()).isGreaterThanOrEqualTo(0);

        // 回放：用 traceId 能取回同一次运行的完整步骤
        assertThat(recorder.find(request.traceId())).isPresent()
                .get()
                .satisfies(trace -> {
                    assertThat(trace.agentName()).isEqualTo("happy-path");
                    assertThat(trace.steps()).hasSize(2);
                    assertThat(trace.steps().get(0).output()).contains("found");
                });
    }

    @Test
    @DisplayName("一次都没取到数据时不允许标记为 OK")
    void noSuccessfulToolCallBlocksOkStatus() {
        // 工具永远失败，模型却照样给出结论——这正是要防的「假成功」
        AgentModel confidentButBlind = (spec, ctx) -> ctx.steps().isEmpty()
                ? new ModelDecision.CallTool("boom", Map.of("id", "A001"), "先取数")
                : new ModelDecision.Finish(Map.of("result", "看起来没问题"), "数据没问题", "下结论");

        ToolSpec brokenTool = new ToolSpec("boom", "总是失败的工具",
                "{\"type\":\"object\",\"required\":[\"id\"]}",
                args -> {
                    throw new IOException("数据服务不可用");
                });

        AgentSpec spec = AgentSpec.builder("no-data-support")
                .tools(List.of(brokenTool))
                .requiredFields("result")
                .maxSteps(3)
                .build();

        AgentResult result = runtime(confidentButBlind, RuntimeProperties.defaults())
                .run(spec, AgentRequest.of("A001"));

        assertThat(result.status()).isEqualTo(AgentResult.Status.DEGRADED);
        assertThat(result.warnings()).anyMatch(w -> w.contains("没有任何工具调用成功"));
    }

    @Test
    @DisplayName("模型抛异常时返回 FAILED，不吞掉错误")
    void modelFailureIsReported() {
        AgentModel broken = (spec, ctx) -> {
            throw new IllegalStateException("模型网关不可用");
        };

        AgentSpec spec = AgentSpec.builder("failure-test")
                .tools(List.of(tool("query")))
                .requiredFields("result")
                .maxSteps(2)
                .build();

        AgentResult result = runtime(broken, RuntimeProperties.defaults())
                .run(spec, AgentRequest.of("A001"));

        assertThat(result.status()).isEqualTo(AgentResult.Status.FAILED);
        assertThat(result.warnings()).anyMatch(w -> w.contains("模型调用失败"));
    }

    @Test
    @DisplayName("工具调用超时会失败返回而不是无限等待")
    void toolCallTimesOut() {
        AgentModel model = (spec, ctx) -> ctx.steps().isEmpty()
                ? new ModelDecision.CallTool("slow", Map.of("id", "1"), "取数")
                : new ModelDecision.Finish(Map.of("result", "x"), "好了", "下结论");

        ToolSpec slow = new ToolSpec("slow", "会卡住的工具",
                "{\"type\":\"object\",\"required\":[\"id\"]}",
                args -> {
                    Thread.sleep(300);
                    return "{\"found\":true}";
                });

        AgentSpec spec = AgentSpec.builder("timeout-test")
                .tools(List.of(slow))
                .requiredFields("result")
                .maxSteps(2)
                .build();

        AgentResult result = runtime(model, new RuntimeProperties(2, 1, 1, 50))
                .run(spec, AgentRequest.of("A001"));

        assertThat(result.status()).isEqualTo(AgentResult.Status.DEGRADED);
        assertThat(result.warnings()).anyMatch(w -> w.contains("超时"));
    }

    @Test
    @DisplayName("字段取值不在白名单时降级")
    void invalidEnumValueBlocksOk() {
        AgentModel model = (spec, ctx) -> ctx.steps().isEmpty()
                ? new ModelDecision.CallTool("query", Map.of("id", "A001"), "取数")
                : new ModelDecision.Finish(Map.of("decision", "数据缺失"), "缺了", "下结论");

        AgentSpec spec = AgentSpec.builder("enum-test")
                .tools(List.of(tool("query")))
                .requiredFields("decision")
                .allowedValues("decision", "PASS", "REJECT", "MANUAL", "NOT_FOUND", "DATA_UNAVAILABLE")
                .maxSteps(3)
                .build();

        AgentResult result = runtime(model, RuntimeProperties.defaults())
                .run(spec, AgentRequest.of("A001"));

        assertThat(result.status()).isEqualTo(AgentResult.Status.DEGRADED);
        assertThat(result.warnings()).anyMatch(w -> w.contains("取值不合法"));
    }

    @Test
    @DisplayName("输出中出现工具未返回的数值时降级，不判 OK")
    void untraceableNumberBlocksOk() {
        AgentModel fabricator = (spec, ctx) -> ctx.steps().isEmpty()
                ? new ModelDecision.CallTool("query", Map.of("id", "A001"), "先取数")
                : new ModelDecision.Finish(Map.of("score", 9999), "分数 9999", "编了个数");

        AgentSpec spec = AgentSpec.builder("traceability-test")
                .tools(List.of(tool("query")))
                .requiredFields("score")
                .maxSteps(3)
                .build();

        AgentResult result = runtime(fabricator, RuntimeProperties.defaults())
                .run(spec, AgentRequest.of("A001"));

        assertThat(result.status()).isEqualTo(AgentResult.Status.DEGRADED);
        assertThat(result.warnings()).anyMatch(w -> w.contains("未出现在任何成功工具返回"));
    }
}
