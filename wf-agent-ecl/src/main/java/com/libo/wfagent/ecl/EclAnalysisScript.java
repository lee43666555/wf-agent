package com.libo.wfagent.ecl;

import com.libo.wfagent.common.model.AgentScriptProvider;
import com.libo.wfagent.common.model.ScriptStep;
import com.libo.wfagent.common.model.ScriptedAgentModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ECL 剧本：对比 202608 与 202609 两期计提。
 * <p>
 * 下面的金额与 db/data.sql 中的 ecl_param 原始数据一一对应，可以手工复算核对：
 * ECL = EAD × PD × LGD，两期差额按量价拆分后三项之和等于总差额。
 */
@Component
public class EclAnalysisScript implements AgentScriptProvider {

    @Override
    public String agentName() {
        return EclAnalysisAgent.NAME;
    }

    @Override
    public List<ScriptStep> script() {
        return List.of(
                ScriptedAgentModel.call("query_ecl_param", Map.of("period", ""),
                        "先确认有哪些账期可用，避免用不存在的账期去算"),

                ScriptedAgentModel.call("query_ecl_param", Map.of("period", "202608"),
                        "取较早一期参数，确认阶段、客群、模型版本"),

                ScriptedAgentModel.call("query_ecl_param", Map.of("period", "202609"),
                        "取较晚一期参数，和上一期逐项对齐"),

                ScriptedAgentModel.call("compute_ecl_delta",
                        Map.of("periodA", "202608", "periodB", "202609"),
                        "金额和拆分必须由工具算，不能自己乘"),

                ScriptedAgentModel.finish(finishOutput(), finishNarrative()));
    }

    private Map<String, Object> finishOutput() {
        return Map.of(
                "periodA", "202608",
                "periodB", "202609",
                "totalA", 14_970_100.0,
                "totalB", 17_527_660.0,
                "totalDelta", 2_557_560.0,
                "segments", List.of(
                        segment("STAGE1", "优质客群", 464_400.0, 510_300.0, 45_900.0, -10_800.0, 56_700.0, 0.0),
                        segment("STAGE1", "普通客群", 2_766_400.0, 3_155_360.0, 388_960.0, -72_800.0, 461_760.0, 0.0),
                        segment("STAGE2", "优质客群", 1_047_480.0, 1_322_400.0, 274_920.0, 149_640.0, 125_280.0, 0.0),
                        segment("STAGE2", "普通客群", 4_204_620.0, 5_176_080.0, 971_460.0, 447_300.0, 524_160.0, 0.0),
                        segment("STAGE3", "优质客群", 1_713_600.0, 1_944_800.0, 231_200.0, 142_800.0, 88_400.0, 0.0),
                        segment("STAGE3", "普通客群", 4_773_600.0, 5_418_720.0, 645_120.0, 440_640.0, 204_480.0, 0.0)),
                "drivers", List.of(
                        "STAGE2-普通客群：+971,460 元，其中规模 +447,300 元、风险 +524,160 元、损失率 +0 元",
                        "STAGE3-普通客群：+645,120 元，其中规模 +440,640 元、风险 +204,480 元、损失率 +0 元",
                        "STAGE1-普通客群：+388,960 元，其中规模 -72,800 元、风险 +461,760 元、损失率 +0 元"),
                "conclusion", "202609 计提 1,752.77 万元，较 202608 的 1,497.01 万元增加 255.76 万元，增幅 17.1%。"
                        + "增量集中在普通客群，合计 200.55 万元，占总增量的 78.4%。"
                        + "其中 STAGE2 普通客群贡献 97.15 万元，风险效应 52.42 万元、规模效应 44.73 万元，"
                        + "PD 由 14.20% 升至 15.80% 是主因；STAGE3 普通客群贡献 64.51 万元，以规模增长为主（44.06 万元）。"
                        + "优质客群三期合计增加 55.20 万元，占比 21.6%。"
                        + "两期之间模型版本由 MDL_2026Q2 切换为 MDL_2026Q3，PD 调整与客群划分同时发生变化，"
                        + "上述归因需在此前提下解读。");
    }

    private Map<String, Object> segment(String stage, String segment,
                                        double eclA, double eclB, double delta,
                                        double scaleEffect, double riskEffect, double lossRateEffect) {
        return Map.of(
                "stage", stage,
                "segment", segment,
                "eclA", eclA,
                "eclB", eclB,
                "delta", delta,
                "scaleEffect", scaleEffect,
                "riskEffect", riskEffect,
                "lossRateEffect", lossRateEffect);
    }

    private String finishNarrative() {
        return "总差额 255.76 万元里，规模效应和风险效应各占一半左右，损失率两期没变。"
                + "增量主要落在普通客群，优质客群基本稳定。";
    }
}
