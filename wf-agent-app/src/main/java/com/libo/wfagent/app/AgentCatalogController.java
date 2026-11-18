package com.libo.wfagent.app;

import com.libo.wfagent.common.runtime.AgentResult;
import com.libo.wfagent.common.runtime.AgentSpec;
import com.libo.wfagent.common.tool.ToolSpec;
import com.libo.wfagent.common.trace.TraceRecorder;
import com.libo.wfagent.ecl.EclAnalysisAgent;
import com.libo.wfagent.feature.FeatureAnalysisAgent;
import com.libo.wfagent.riskreview.RiskReviewAgent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聚合层：只做自省与 Trace 查询，各 Agent 的业务入口仍在自己的模块里。
 */
@RestController
@RequestMapping("/api")
public class AgentCatalogController {

    private final RiskReviewAgent riskReviewAgent;
    private final FeatureAnalysisAgent featureAnalysisAgent;
    private final EclAnalysisAgent eclAnalysisAgent;
    private final TraceRecorder traceRecorder;

    public AgentCatalogController(RiskReviewAgent riskReviewAgent,
                                  FeatureAnalysisAgent featureAnalysisAgent,
                                  EclAnalysisAgent eclAnalysisAgent,
                                  TraceRecorder traceRecorder) {
        this.riskReviewAgent = riskReviewAgent;
        this.featureAnalysisAgent = featureAnalysisAgent;
        this.eclAnalysisAgent = eclAnalysisAgent;
        this.traceRecorder = traceRecorder;
    }

    /** 列出三个 Agent 的声明：名字、可用工具、必填输出字段。用于确认装配是否符合预期。 */
    @GetMapping("/agents")
    public List<Map<String, Object>> agents() {
        return List.of(describe(riskReviewAgent.spec()),
                describe(featureAnalysisAgent.spec()),
                describe(eclAnalysisAgent.spec()));
    }

    @GetMapping("/trace")
    public List<AgentResult> recentTraces(@RequestParam(defaultValue = "20") int limit) {
        return traceRecorder.recent(limit);
    }

    @GetMapping("/trace/detail")
    public AgentResult trace(@RequestParam String traceId) {
        return traceRecorder.find(traceId).orElse(null);
    }

    private Map<String, Object> describe(AgentSpec spec) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", spec.name());
        view.put("tools", spec.tools().stream().map(ToolSpec::name).toList());
        view.put("requiredFields", spec.requiredFields());
        view.put("maxSteps", spec.maxSteps());
        return view;
    }
}
