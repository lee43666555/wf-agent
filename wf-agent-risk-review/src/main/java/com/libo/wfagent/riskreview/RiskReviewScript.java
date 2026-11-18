package com.libo.wfagent.riskreview;

import com.libo.wfagent.common.model.AgentScriptProvider;
import com.libo.wfagent.common.model.ModelDecision;
import com.libo.wfagent.common.model.ScriptStep;
import com.libo.wfagent.common.model.ScriptedAgentModel;
import com.libo.wfagent.common.runtime.AgentStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 排查 Agent 的剧本（无模型环境下走这条）。
 * <p>
 * 覆盖两条分支：
 * <ul>
 *   <li>进件存在：按「结论 → 命中规则 → 变量依据 → 外部数据」的顺序取数，最后给完整结论</li>
 *   <li>进件不存在：第一步就发现查无此件，直接收场，不编结论</li>
 * </ul>
 * 第二条分支是回归用例的重点——错误数据下不产结论，比产出结论更重要。
 * <p>
 * <b>这里其实要区分三种结果，不是两种，面试常在这里被追问：</b>
 * <ul>
 *   <li><b>取数失败</b>（工具 ok=false）：系统故障，输出 decision=DATA_UNAVAILABLE，
 *       并明确写「不代表该进件不存在」；</li>
 *   <li><b>查无此件</b>（工具成功但 found=false）：正常业务结果，输出 decision=NOT_FOUND，
 *       不产任何规则命中，也不做推断；</li>
 *   <li><b>正常</b>：继续按顺序取数直到给出结论。</li>
 * </ul>
 * 前两者在接口层面长得很像——都表现为「拿不到数据」，但业务含义完全相反。
 * 混淆的代价是：把一次服务宕机说成「该客户不存在」，这是要出事故的。
 * 回归用例 RR-03 专门守这一条。
 */
@Component
public class RiskReviewScript implements AgentScriptProvider {

    @Override
    public String agentName() {
        return RiskReviewAgent.NAME;
    }

    @Override
    public List<ScriptStep> script() {
        return List.of(
                ScriptedAgentModel.call("query_application",
                        Map.of("applicationId", ScriptedAgentModel.INPUT_PLACEHOLDER),
                        "先确认这笔进件的审批结论和拒绝码，决定后续要不要往下查"),

                // 分支点：取数失败和查无此件是两件事，必须分开处理
                ScriptStep.dynamic(context -> {
                    AgentStep first = context.steps().isEmpty() ? null : context.steps().get(0);
                    if (first == null) {
                        return new ModelDecision.Finish(
                                Map.of("applicationId", context.input(),
                                        "decision", "DATA_UNAVAILABLE",
                                        "hitRules", List.of(),
                                        "variableEvidence", List.of(),
                                        "dataCompleteness", "没有任何取数结果",
                                        "conclusion", "本次未取到任何数据，不产出结论。"),
                                "没有可用步骤", "无数据不下结论");
                    }

                    // 取数失败 = 系统故障，不能推断任何业务结论
                    if (!first.ok()) {
                        return new ModelDecision.Finish(
                                Map.of("applicationId", context.input(),
                                        "decision", "DATA_UNAVAILABLE",
                                        "hitRules", List.of(),
                                        "variableEvidence", List.of(),
                                        "dataCompleteness", "取数失败：" + first.error(),
                                        "conclusion", "进件数据取数失败，本次未产出结论。"
                                                + "这是数据服务不可用，不代表该进件不存在。"),
                                "工具调用失败，终止排查",
                                "取数失败与查无数据必须区分，否则会把系统故障说成业务事实");
                    }

                    // 查无此件 = 正常业务结果
                    if (first.output() == null || first.output().contains("\"found\":false")) {
                        return new ModelDecision.Finish(
                                Map.of("applicationId", context.input(),
                                        "decision", "NOT_FOUND",
                                        "hitRules", List.of(),
                                        "variableEvidence", List.of(),
                                        "dataCompleteness", "进件信息缺失，无法继续排查",
                                        "conclusion", "系统中查无此进件，请确认进件编号。未做任何推断。"),
                                "第一步取数返回 found=false，继续取数没有意义",
                                "数据缺失时应当停下来，而不是按经验猜结论");
                    }
                    return new ModelDecision.CallTool("query_decision_trace",
                            Map.of("applicationId", context.input()),
                            "结论已确认，继续定位到具体命中的规则");
                }),

                ScriptedAgentModel.call("query_variable_snapshot",
                        Map.of("applicationId", ScriptedAgentModel.INPUT_PLACEHOLDER),
                        "命中规则依赖哪些变量、实际取值和阈值是多少，必须以快照为准"),

                ScriptedAgentModel.call("query_external_data",
                        Map.of("applicationId", ScriptedAgentModel.INPUT_PLACEHOLDER),
                        "多头数据来自外部，要确认是数据源返回如此，还是我们解析错了"),

                ScriptedAgentModel.finish(finishOutput(), finishNarrative()));
    }

    private Map<String, Object> finishOutput() {
        return Map.of(
                "applicationId", ScriptedAgentModel.INPUT_PLACEHOLDER,
                "decision", "REJECT",
                "rejectCode", "R_MULTI_LOAN",
                "hitRules", List.of(
                        Map.of("ruleCode", "R_MULTI_LOAN_3M",
                                "ruleName", "近3月多头借贷申请次数超限",
                                "strategy", "S_ANTIFRAUD@v3.2",
                                "scoreDelta", -30),
                        Map.of("ruleCode", "R_MULTI_ORG_6M",
                                "ruleName", "近6月多头借贷机构数超限",
                                "strategy", "S_ANTIFRAUD@v3.2",
                                "scoreDelta", -20)),
                "variableEvidence", List.of(
                        Map.of("varCode", "v_multi_loan_cnt_3m", "varName", "近3月多头借贷申请次数",
                                "value", "12", "threshold", "8", "breached", true),
                        Map.of("varCode", "v_multi_loan_org_cnt_6m", "varName", "近6月多头借贷机构数",
                                "value", "7", "threshold", "6", "breached", true),
                        Map.of("varCode", "v_credit_score", "varName", "内部信用分",
                                "value", "682", "threshold", "620", "breached", false),
                        Map.of("varCode", "v_debt_ratio", "varName", "负债收入比",
                                "value", "0.48", "threshold", "0.55", "breached", false)),
                "dataCompleteness", "完整：进件、决策流水、变量快照、外部数据均已取到",
                "conclusion", "该笔进件被反欺诈策略 S_ANTIFRAUD v3.2 拒绝，拒绝码 R_MULTI_LOAN。"
                        + "命中两条规则：近3月多头借贷申请次数 12 次超过阈值 8，扣 30 分；"
                        + "近6月多头借贷机构数 7 家超过阈值 6，扣 20 分。"
                        + "变量快照与外部数据报文一致（人行征信 loanCount=12、orgCount=7，"
                        + "三方多头 applyCnt3m=12、orgCnt6m=7），可排除数据源异常与解析错误。"
                        + "信用准入策略与定价策略均未命中，该笔拒绝仅由反欺诈多维多头规则驱动。");
    }

    private String finishNarrative() {
        return "两条多头规则同时命中，且快照值与外部原始报文一致，判定为真实多头行为而非数据异常，"
                + "拒绝结论成立。";
    }
}
