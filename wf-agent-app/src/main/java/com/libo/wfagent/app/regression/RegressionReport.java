package com.libo.wfagent.app.regression;

import java.util.List;

/**
 * 一次回归跑批的结果。
 * <p>
 * 这份结构是刻意设计给「简历填数」用的：{@code total / passed / p95Ms / model} 四个字段
 * 直接对应简历里两个占位符（`回归 case 共 __ 条`、`端到端 P95 __ 秒`），
 * 跑完 {@code GET /api/regression} 就能抄，不需要自己去日志里算。
 * <p>
 * {@code model} 必须带上：同一批用例在 {@code scripted} 和 {@code spring-ai} 两种模式下
 * 跑出来的数字含义完全不同。写简历时要说清楚是哪种模式，否则被追问「这是真实模型跑的？」会答不上来。
 *
 * @param model           本次跑批使用的模型实现（scripted / spring-ai:xxx）
 * @param startedAt       开始时间，ISO-8601
 * @param total           用例总数
 * @param passed          通过数
 * @param failed          失败数
 * @param avgMs           平均端到端耗时
 * @param p50Ms           中位数耗时
 * @param p95Ms           95 分位耗时（样本少时等于最大值，报告里要注明）
 * @param maxMs           最大耗时
 * @param traceReplayable 有多少条结果能按 traceId 从 Trace 存储里原样取回
 * @param cases           逐条明细
 */
public record RegressionReport(
        String model,
        String startedAt,
        int total,
        int passed,
        int failed,
        double avgMs,
        double p50Ms,
        double p95Ms,
        long maxMs,
        int traceReplayable,
        List<CaseOutcome> cases) {

    /**
     * 一条用例的执行结果。
     *
     * @param costMs   端到端耗时，含取数与结论生成
     * @param failures 失败原因列表；为空即通过。带原因而不只是布尔值，是为了跑批红了能直接定位
     */
    public record CaseOutcome(
            String id,
            String agent,
            String description,
            boolean passed,
            long costMs,
            List<String> failures) {
    }
}
