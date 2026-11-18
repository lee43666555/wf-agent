package com.libo.wfagent.app.regression;

import com.libo.wfagent.common.runtime.AgentResult;

import java.util.function.Function;

/**
 * 一条回归用例。
 * <p>
 * <b>为什么断言写成函数而不是 JSON 里的期望值：</b>
 * 期望值只能表达「字段等于某个数」，但 Agent 真正会出的问题是结构性的——
 * 「结论里出现了工具没返回过的数」「分客群差额加起来不等于总差额」
 * 「没有数据支撑却被标成 OK」。这些只能用代码表达。
 * <p>
 * 断言函数返回 {@code null} 表示通过，返回字符串表示失败原因。用失败原因而不是布尔值，
 * 是因为跑批结果要能直接看出「是哪条不变量破了」，而不是只知道某条红了。
 *
 * @param id          用例编号，报告里用来定位
 * @param agent       交给哪个 Agent：risk-review / feature-analysis / ecl-analysis
 * @param description 这条用例守的是什么不变量，失败时要能一眼看懂
 * @param input       透传给 Agent 的输入（ECL 用 "期A|期B" 的格式）
 * @param assertion   断言函数，返回 null 通过
 */
public record RegressionCase(
        String id,
        String agent,
        String description,
        String input,
        Function<AgentResult, String> assertion) {
}
