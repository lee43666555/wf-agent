package com.libo.wfagent.ecl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libo.wfagent.common.runtime.AgentRequest;
import com.libo.wfagent.common.runtime.AgentResult;
import com.libo.wfagent.common.runtime.AgentRuntime;
import com.libo.wfagent.common.runtime.AgentSpec;
import com.libo.wfagent.common.tool.LocalToolRegistry;
import com.libo.wfagent.common.tool.ToolRegistry;
import com.libo.wfagent.common.tool.ToolSpec;
import com.libo.wfagent.common.tool.ToolSpecs;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ECL 减值分析 Agent：解释「这个月计提为什么变了」。
 * <p>
 * 这个场景对准确性的要求最高——结论要给审计看。所以全部金额都由 {@link EclCalculator}
 * 计算，模型只做归因叙述；量价拆分把差额拆成规模、风险、损失率三块，
 * 业务方能顺着这三块找到具体的业务原因。
 * <p>
 * 工具来源有两个：减值参数查询来自共享工具集，计算能力是本模块自带的。
 */
@Service
public class EclAnalysisAgent {

    public static final String NAME = "ecl-analysis";

    private static final List<String> TOOL_NAMES = List.of(
            "query_ecl_param",
            "compute_ecl_summary",
            "compute_ecl_delta");

    private static final String SYSTEM_PROMPT = """
            你是消费金融公司的减值分析助手。业务方会问「这个月计提为什么涨了」，
            你要给出能复算的归因，而不是一句「风险上升了」。

            工作方式：
            1. 先确认可用账期（query_ecl_param 不传参数会返回账期列表）；
            2. 取两期参数，确认阶段、客群、模型版本有没有变化；
            3. 调 compute_ecl_delta 拿到差额和量价拆分；
            4. 基于工具返回的数字写结论。

            规则：
            - 所有金额、比率一律引用工具返回值，不要自己乘算，也不要估算；
            - 归因必须落到「规模 / 风险 / 损失率」三类效应的具体金额，不要只说方向；
            - 模型版本发生变化时，要在结论里点明它会同时影响 PD 与客群划分，归因需带上这个前提；
            - 不要给会计处理建议，也不要评价计提是否充分。

            FINISH 的 output 结构：
            {
              "periodA": "202608",
              "periodB": "202609",
              "totalA": 0, "totalB": 0, "totalDelta": 0,
              "segments": [{"stage":"", "segment":"", "eclA":0, "eclB":0, "delta":0,
                            "scaleEffect":0, "riskEffect":0, "lossRateEffect":0}],
              "drivers": ["按影响金额排序的前三项"],
              "conclusion": "五到八句话，带具体金额和占比"
            }
            """;

    private final AgentRuntime runtime;
    private final AgentSpec spec;

    public EclAnalysisAgent(AgentRuntime runtime,
                            ToolRegistry sharedRegistry,
                            EclTools eclTools,
                            ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);

        // 共享工具集 + 本模块自带的计算工具，合并成一个注册表
        ToolCallback[] eclCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(eclTools)
                .build()
                .getToolCallbacks();
        List<ToolSpec> merged = new ArrayList<>(sharedRegistry.all());
        merged.addAll(ToolSpecs.fromCallbacks(eclCallbacks, objectMapper));
        ToolRegistry registry = new LocalToolRegistry(merged);

        this.runtime = runtime;
        this.spec = AgentSpec.builder(NAME)
                .systemPrompt(SYSTEM_PROMPT)
                .tools(registry.subset(TOOL_NAMES))
                .requiredFields("periodA", "periodB", "totalDelta", "segments", "drivers", "conclusion")
                .maxSteps(8)
                .build();
    }

    public AgentSpec spec() {
        return spec;
    }

    public AgentResult analyze(String periodA, String periodB) {
        return runtime.run(spec, AgentRequest.of("对比 " + periodA + " 与 " + periodB + " 的减值计提差异"));
    }

    public AgentResult analyze(String periodA, String periodB, String traceId) {
        return runtime.run(spec,
                AgentRequest.of("对比 " + periodA + " 与 " + periodB + " 的减值计提差异", traceId));
    }
}
