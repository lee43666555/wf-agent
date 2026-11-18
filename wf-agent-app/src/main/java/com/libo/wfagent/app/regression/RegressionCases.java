package com.libo.wfagent.app.regression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.libo.wfagent.common.runtime.AgentResult;
import com.libo.wfagent.common.runtime.AgentStep;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * 回归用例集：10 条，覆盖三个 Agent 的关键不变量。
 * <p>
 * <b>这套用例守的不是「业务数字对不对」，而是「运行时约束有没有被破坏」。</b>
 * 原因很实际：POC 阶段数据是造的，业务数字本来就不该拿来当基准；
 * 真正会出事的是另外四类问题——
 * <ol>
 *   <li><b>分支走错</b>：查无此件被当成取数失败，或反过来把系统故障说成业务结论；</li>
 *   <li><b>结构残缺</b>：必填字段没给全却照样返回，下游拿去用就出事；</li>
 *   <li><b>无法复核</b>：结论出来了但 Trace 没落库，事后问不出当时看到了什么；</li>
 *   <li><b>算错却不自知</b>：分客群差额加不回总差额，说明拆分逻辑和总额口径已经不一致。</li>
 * </ol>
 * 这四类正是银行技术面最会追的地方，所以每条用例都对应其中一类。
 * <p>
 * 用例不依赖具体金额：ECL 的三条全部是恒等式（总额差 = 分项之和、三项效应之和 = 分项差额），
 * 换一批数据照样成立。这也是它能从 POC 一直用到生产的原因。
 */
public final class RegressionCases {

    /** 不存在的进件号，用于验证「查无此件」这条分支。 */
    private static final String MISSING_APPLICATION = "APPL99999999";

    /** 存在且有完整链路的进件，data.sql 里的主案例。 */
    private static final String MAIN_APPLICATION = "APPL20260003";

    private RegressionCases() {
    }

    public static List<RegressionCase> all() {
        return List.of(
                // ---------- 风控排查 Agent ----------
                new RegressionCase("RR-01", "risk-review", "正常进件应还原出拒绝结论与拒绝码",
                        MAIN_APPLICATION,
                        r -> firstFailure(
                                requireStatus(r, AgentResult.Status.OK),
                                require(out(r).path("decision").asText("").equals("REJECT"),
                                        "decision 应为 REJECT，实际为 " + out(r).path("decision").asText("<空>")))),

                new RegressionCase("RR-02", "risk-review", "命中规则与变量证据都必须落到具体条目",
                        MAIN_APPLICATION,
                        r -> firstFailure(
                                require(out(r).path("hitRules").isArray() && !out(r).path("hitRules").isEmpty(),
                                        "hitRules 为空：结论说被规则拦下，却没有给出任何命中规则"),
                                require(out(r).path("variableEvidence").isArray()
                                                && !out(r).path("variableEvidence").isEmpty(),
                                        "variableEvidence 为空：结论缺少变量取值依据"))),

                new RegressionCase("RR-03", "risk-review", "查无此件时不得产出任何规则命中或推断",
                        MISSING_APPLICATION,
                        r -> firstFailure(
                                require(out(r).path("decision").asText("").equals("NOT_FOUND"),
                                        "查无此件时 decision 应为 NOT_FOUND，实际为 "
                                                + out(r).path("decision").asText("<空>")),
                                require(out(r).path("hitRules").isEmpty(),
                                        "查无此件却给出了命中规则，属于无数据推断"))),

                new RegressionCase("RR-04", "risk-review", "取数顺序必须先把决策流水定位清楚再核对变量",
                        MAIN_APPLICATION,
                        r -> {
                            OptionalInt decisionStep = indexOfTool(r, "query_decision_trace");
                            OptionalInt variableStep = indexOfTool(r, "query_variable_snapshot");
                            if (decisionStep.isEmpty()) {
                                return "没有调用 query_decision_trace：缺少定位命中规则的步骤";
                            }
                            if (variableStep.isPresent() && decisionStep.getAsInt() > variableStep.getAsInt()) {
                                return "先核对变量、后查决策流水：顺序颠倒，等于不知道要找哪条规则的依据";
                            }
                            return null;
                        }),

                new RegressionCase("RR-05", "risk-review", "数据完整性必须显式给出，不能默认「完整」",
                        MAIN_APPLICATION,
                        r -> require(!out(r).path("dataCompleteness").asText("").isBlank(),
                                "dataCompleteness 为空：无法判断结论建立在几类数据之上")),

                // ---------- 特征分析 Agent ----------
                new RegressionCase("FA-01", "feature-analysis", "自然语言必须先落成四要素查询计划，不能直接拼 SQL",
                        "分析近3月多头借贷申请次数在普通客群上的坏账率分布",
                        r -> {
                            JsonNode plan = out(r).path("queryPlan");
                            return firstFailure(
                                    require(plan.isObject(), "queryPlan 缺失或不是对象"),
                                    require(!plan.path("varCode").asText("").isBlank(), "queryPlan 缺 varCode"),
                                    require(!plan.path("period").asText("").isBlank(), "queryPlan 缺 period"),
                                    require(!plan.path("segment").asText("").isBlank(), "queryPlan 缺 segment"),
                                    require(!plan.path("metric").asText("").isBlank(), "queryPlan 缺 metric"));
                        }),

                new RegressionCase("FA-02", "feature-analysis", "换一种说法不应改变取数口径",
                        "帮我看看近三个月多头查询次数，普通客群，坏账率怎么分布的",
                        r -> {
                            JsonNode distribution = out(r).path("distribution");
                            return firstFailure(
                                    require(distribution.isArray() && distribution.size() >= 2,
                                            "distribution 分箱不足 2 档，无法判断分布形态"),
                                    require(out(r).path("queryPlan").path("varCode").asText("")
                                                    .equals("v_multi_loan_cnt_3m"),
                                            "换措辞后变量编码发生变化，说明查询计划被措辞带偏"));
                        }),

                // ---------- ECL 减值 Agent ----------
                new RegressionCase("ECL-01", "ecl-analysis", "总差额必须等于两期总额之差",
                        "202608|202609",
                        r -> {
                            double a = out(r).path("totalA").asDouble(Double.NaN);
                            double b = out(r).path("totalB").asDouble(Double.NaN);
                            double delta = out(r).path("totalDelta").asDouble(Double.NaN);
                            if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(delta)) {
                                return "totalA / totalB / totalDelta 缺失";
                            }
                            return require(closeTo(b - a, delta),
                                    "总差额不自洽：totalB-totalA=" + (b - a) + "，totalDelta=" + delta);
                        }),

                new RegressionCase("ECL-02", "ecl-analysis", "分客群差额之和必须等于总差额（可复算的第一层）",
                        "202608|202609",
                        r -> {
                            JsonNode segments = out(r).path("segments");
                            if (!segments.isArray() || segments.isEmpty()) {
                                return "segments 为空：无法复算";
                            }
                            double sum = 0;
                            for (JsonNode seg : segments) {
                                sum += seg.path("delta").asDouble(0);
                            }
                            double delta = out(r).path("totalDelta").asDouble(Double.NaN);
                            return require(closeTo(sum, delta),
                                    "分客群差额之和 " + sum + " 与总差额 " + delta + " 不一致");
                        }),

                new RegressionCase("ECL-03", "ecl-analysis", "每个客群的三项效应之和必须等于该客群差额（可复算的第二层）",
                        "202608|202609",
                        r -> {
                            JsonNode segments = out(r).path("segments");
                            if (!segments.isArray() || segments.isEmpty()) {
                                return "segments 为空：无法复算";
                            }
                            List<String> broken = new ArrayList<>();
                            for (JsonNode seg : segments) {
                                double effect = seg.path("scaleEffect").asDouble(0)
                                        + seg.path("riskEffect").asDouble(0)
                                        + seg.path("lossRateEffect").asDouble(0);
                                double delta = seg.path("delta").asDouble(0);
                                if (!closeTo(effect, delta)) {
                                    broken.add(seg.path("stage").asText("?") + "/"
                                            + seg.path("segment").asText("?")
                                            + " 效应和=" + effect + " 差额=" + delta);
                                }
                            }
                            return broken.isEmpty() ? null : "量价拆分不平：" + broken;
                        }));
    }

    // ------------------------------------------------------------------
    // 断言工具：都写成小函数，是为了失败信息里能带上实际值，
    // 而不是只报一句 "expected true but was false"。
    // ------------------------------------------------------------------

    /**
     * 取结构化结论。DEGRADED / FAILED 时 output 可能是 null，
     * 统一兜成 MissingNode，让断言里不用到处判空——缺字段就自然不满足断言。
     */
    private static JsonNode out(AgentResult result) {
        return result.output() == null ? MissingNode.getInstance() : result.output();
    }

    /** 找到某次工具调用在步骤序列里的下标，用于校验取数顺序。 */
    private static OptionalInt indexOfTool(AgentResult result, String toolName) {
        for (int i = 0; i < result.steps().size(); i++) {
            AgentStep step = result.steps().get(i);
            if ("TOOL_CALL".equals(step.type()) && toolName.equals(step.toolName())) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /** 返回第一个非 null 的失败原因；全通过时返回 null。 */
    private static String firstFailure(String... reasons) {
        for (String reason : reasons) {
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private static String require(boolean condition, String message) {
        return condition ? null : message;
    }

    private static String requireStatus(AgentResult result, AgentResult.Status expected) {
        return require(result.status() == expected,
                "状态应为 " + expected + "，实际为 " + result.status()
                        + (result.warnings().isEmpty() ? "" : "，warnings=" + result.warnings()));
    }

    /**
     * 金额比较必须带容差：ECL 是 double 连乘出来的，末位必然有误差。
     * 容差取 0.01 元——比「一分钱」严格，比浮点误差宽松。
     */
    private static boolean closeTo(double a, double b) {
        return Math.abs(a - b) <= 0.01;
    }
}
