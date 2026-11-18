package com.libo.wfagent.feature;

import com.libo.wfagent.common.model.AgentScriptProvider;
import com.libo.wfagent.common.model.ScriptStep;
import com.libo.wfagent.common.model.ScriptedAgentModel;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 特征分析剧本：分析「近3月多头借贷申请次数在普通客群上的坏账率分布」。
 * 对应数据见 db/data.sql 中 variable_metric 表 202609 普通客群四行。
 */
@Component
public class FeatureAnalysisScript implements AgentScriptProvider {

    @Override
    public String agentName() {
        return FeatureAnalysisAgent.NAME;
    }

    @Override
    public List<ScriptStep> script() {
        return List.of(
                ScriptedAgentModel.call("list_variables", Map.of(),
                        "先确认变量编码和可选账期、客群，避免用错变量名"),

                ScriptedAgentModel.call("query_variable_metric",
                        Map.of("varCode", "v_multi_loan_cnt_3m",
                                "period", "202609",
                                "segment", "普通客群"),
                        "取该变量最新账期在普通客群上的分箱指标"),

                ScriptedAgentModel.finish(finishOutput(), finishNarrative()));
    }

    private Map<String, Object> finishOutput() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("varCode", "v_multi_loan_cnt_3m");
        plan.put("period", "202609");
        plan.put("segment", "普通客群");
        plan.put("metric", "badRate");

        return Map.of(
                "queryPlan", plan,
                "distribution", List.of(
                        Map.of("bucket", "[0,2)", "cnt", 46800, "badCnt", 983, "badRate", 0.0210),
                        Map.of("bucket", "[2,5)", "cnt", 22100, "badCnt", 928, "badRate", 0.0420),
                        Map.of("bucket", "[5,8)", "cnt", 7900, "badCnt", 585, "badRate", 0.0741),
                        Map.of("bucket", "[8,)", "cnt", 2650, "badCnt", 371, "badRate", 0.1400)),
                "conclusion", "近3月多头借贷申请次数与坏账率呈单调关系：申请 0~2 次的客群坏账率 2.10%，"
                        + "8 次及以上升到 14.00%，后者是前者的 6.7 倍。四个分箱样本量均超过 2000，"
                        + "不涉及样本不足问题。现行反欺诈规则阈值取 8，落在坏账率抬升最陡的区间之后，"
                        + "从数据上看该阈值能把 8 次以上这一档单独切出来。");
    }

    private String finishNarrative() {
        return "四个分箱样本量都够，坏账率随多头次数单调上升，阈值 8 的切分位置有数据支撑。";
    }
}
