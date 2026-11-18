package com.libo.wfagent.feature;

import com.libo.wfagent.common.runtime.AgentRequest;
import com.libo.wfagent.common.runtime.AgentResult;
import com.libo.wfagent.common.runtime.AgentRuntime;
import com.libo.wfagent.common.runtime.AgentSpec;
import com.libo.wfagent.common.tool.ToolRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 特征分析 Agent：把「这个变量在某个客群上表现怎么样」这类问题，
 * 从「提需求等研发排期写 SQL」变成策略同学自助出结果。
 * <p>
 * 关键设计：模型只负责把自然语言翻译成受约束的查询计划（变量 / 账期 / 客群 / 指标），
 * 不产出 SQL。查询计划的四个要素由平台编译执行，模型没有拼 SQL 的机会。
 */
@Service
public class FeatureAnalysisAgent {

    public static final String NAME = "feature-analysis";

    private static final List<String> TOOL_NAMES = List.of("list_variables", "query_variable_metric");

    private static final String SYSTEM_PROMPT = """
            你是消费金融公司的变量分析助手。策略同学用自然语言提出分析需求，
            你要把它变成一次受约束的查询，并解释结果。

            工作方式：
            1. 先用 list_variables 确认变量存在、有哪些账期和客群可选，不要凭印象拼变量名；
            2. 用 query_variable_metric 取分箱指标，参数只有四个：变量、账期、客群、指标；
            3. 基于返回的分箱数据给出结论。

            规则：
            - 你只能从这四个要素里选值，不能自己写 SQL，也不能改聚合口径；
            - period 必须落到具体账期（从 list_variables 返回的 periods 里取最近一期），不允许留空：
              留空等于把所有账期混在一起算，分箱口径不可比，结论也没有业务含义。
              queryVariableMetric 调用时就要传具体账期，不要先查全部再总结；
            - 分箱的样本量、坏样本数、坏账率一律引用工具返回值，不要自己算；
            - 样本量过小的分箱（比如 cnt 低于 500）要在结论里标注「样本不足，仅供参考」，不要直接比较坏账率；
            - 结论里不要给模型改造建议，只描述数据表现。

            FINISH 的 output 结构：
            {
              "queryPlan": {"varCode": "", "period": "", "segment": "", "metric": "badRate"},
              "distribution": [{"bucket": "", "cnt": 0, "badCnt": 0, "badRate": 0}],
              "conclusion": "三到五句话，只讲数据表现"
            }
            """;

    private final AgentRuntime runtime;
    private final AgentSpec spec;

    public FeatureAnalysisAgent(AgentRuntime runtime, ToolRegistry toolRegistry) {
        this.runtime = runtime;
        this.spec = AgentSpec.builder(NAME)
                .systemPrompt(SYSTEM_PROMPT)
                .tools(toolRegistry.subset(TOOL_NAMES))
                .requiredFields("queryPlan", "distribution", "conclusion")
                .maxSteps(6)
                .build();
    }

    public AgentSpec spec() {
        return spec;
    }

    public AgentResult analyze(String question) {
        return runtime.run(spec, AgentRequest.of(question));
    }

    public AgentResult analyze(String question, String traceId) {
        return runtime.run(spec, AgentRequest.of(question, traceId));
    }
}
