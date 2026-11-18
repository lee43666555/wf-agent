package com.libo.wfagent.riskreview;

import com.libo.wfagent.common.runtime.AgentRequest;
import com.libo.wfagent.common.runtime.AgentResult;
import com.libo.wfagent.common.runtime.AgentRuntime;
import com.libo.wfagent.common.runtime.AgentSpec;
import com.libo.wfagent.common.tool.ToolRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 风控排查 Agent：给定进件编号，还原审批过程——结论是什么、被哪条规则拦下、依据是什么数据。
 * <p>
 * 它解决的是策略同学最耗时的一件事：一次拒贷复盘要在四五个系统里手工对数，
 * 而且过程不留档，事后复核找不到依据。
 */
@Service
public class RiskReviewAgent {

    public static final String NAME = "risk-review";

    /** 只申请这六个工具，多一个都不给，模型没有能力越界取数。 */
    private static final List<String> TOOL_NAMES = List.of(
            "query_application",
            "query_decision_trace",
            "query_variable_snapshot",
            "query_strategy_version",
            "query_external_data",
            "query_peer_snapshot");

    private static final String SYSTEM_PROMPT = """
            你是消费金融公司的风控排查助手。给定一个进件编号，还原这笔进件的审批过程，
            回答三件事：结论是什么、被哪条规则拦下、拦下时依据的数据是什么。

            排查顺序（可以按实际情况调整，但不要跳步下结论）：
            1. 查进件主信息，确认审批结论与拒绝码；
            2. 结论为拒绝或转人工时，查决策流水，找出 hit=true 的规则；
            3. 查变量快照，核对被命中规则依赖的变量实际取值与阈值；
            4. 需要判断是否数据源问题时，查外部数据原始报文；
            5. 需要同客群参照时，查 peer snapshot，只做对照，不要据此下新结论。

            规则：
            - 变量取值一律以快照为准。快照是进件时刻算出来的，后续重跑不影响它；
            - 不要自行计算变量值、不要推断阈值、不要估算分数，全部引用工具返回；
            - 某个环节查不到数据时，在 dataCompleteness 里写明缺什么，不要用推测填补；
            - conclusion 只描述已取到的事实，不要给改进建议。

            关于 decision 的取值（只能取以下五个字符串，不要自创，例如不要写「数据缺失」）：
            - PASS / REJECT / MANUAL：进件存在，取工具返回的审批结论；
            - NOT_FOUND：进件不存在（query_application 返回 found=false）。此时 hitRules 和
              variableEvidence 必须是空数组，conclusion 写明「查无此进件，未做任何推断」；
            - DATA_UNAVAILABLE：取数本身失败（工具返回错误），这与「查无此件」是两件事，
              不得把取数失败写成 NOT_FOUND。

            FINISH 的 output 结构：
            {
              "applicationId": "进件编号",
              "decision": "PASS / REJECT / MANUAL / NOT_FOUND / DATA_UNAVAILABLE",
              "rejectCode": "拒绝码，无则 null",
              "hitRules": [{"ruleCode":"", "ruleName":"", "strategy":"", "scoreDelta":0}],
              "variableEvidence": [{"varCode":"", "varName":"", "value":"", "threshold":"", "breached":true}],
              "dataCompleteness": "完整，或缺哪几类数据",
              "conclusion": "三到五句话，只讲事实"
            }
            """;

    private final AgentRuntime runtime;
    private final AgentSpec spec;

    public RiskReviewAgent(AgentRuntime runtime, ToolRegistry toolRegistry) {
        this.runtime = runtime;
        this.spec = AgentSpec.builder(NAME)
                .systemPrompt(SYSTEM_PROMPT)
                .tools(toolRegistry.subset(TOOL_NAMES))
                .requiredFields("applicationId", "decision", "hitRules", "variableEvidence", "conclusion")
                .allowedValues("decision", "PASS", "REJECT", "MANUAL", "NOT_FOUND", "DATA_UNAVAILABLE")
                .maxSteps(8)
                .build();
    }

    public AgentSpec spec() {
        return spec;
    }

    public AgentResult review(String applicationId) {
        return runtime.run(spec, AgentRequest.of(applicationId));
    }

    public AgentResult review(String applicationId, String traceId) {
        return runtime.run(spec, AgentRequest.of(applicationId, traceId));
    }
}
