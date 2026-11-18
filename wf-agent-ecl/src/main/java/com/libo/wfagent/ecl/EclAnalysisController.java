package com.libo.wfagent.ecl;

import com.libo.wfagent.common.runtime.AgentResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ECL 分析 Agent 的调试入口。传入两个账期，返回差额与归因。
 */
@RestController
@RequestMapping("/api/ecl-analysis")
public class EclAnalysisController {

    private final EclAnalysisAgent agent;

    public EclAnalysisController(EclAnalysisAgent agent) {
        this.agent = agent;
    }

    @GetMapping
    public AgentResult analyze(@RequestParam(defaultValue = "202608") String periodA,
                               @RequestParam(defaultValue = "202609") String periodB,
                               @RequestParam(required = false) String traceId) {
        return agent.analyze(periodA, periodB, traceId);
    }

    @PostMapping
    public AgentResult analyzePost(@RequestBody EclRequest request) {
        return agent.analyze(request.periodA(), request.periodB(), request.traceId());
    }

    public record EclRequest(String periodA, String periodB, String traceId) {
    }
}
