package com.libo.wfagent.feature;

import com.libo.wfagent.common.runtime.AgentResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 特征分析 Agent 的调试入口。问题直接用自然语言传。
 */
@RestController
@RequestMapping("/api/feature-analysis")
public class FeatureAnalysisController {

    private final FeatureAnalysisAgent agent;

    public FeatureAnalysisController(FeatureAnalysisAgent agent) {
        this.agent = agent;
    }

    @GetMapping
    public AgentResult analyze(@RequestParam String question,
                               @RequestParam(required = false) String traceId) {
        return agent.analyze(question, traceId);
    }

    @PostMapping
    public AgentResult analyzePost(@RequestBody AnalysisRequest request) {
        return agent.analyze(request.question(), request.traceId());
    }

    public record AnalysisRequest(String question, String traceId) {
    }
}
