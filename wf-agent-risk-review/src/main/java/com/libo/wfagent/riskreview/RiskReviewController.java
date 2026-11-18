package com.libo.wfagent.riskreview;

import com.libo.wfagent.common.runtime.AgentResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 排查 Agent 的调试入口。
 */
@RestController
@RequestMapping("/api/risk-review")
public class RiskReviewController {

    private final RiskReviewAgent agent;

    public RiskReviewController(RiskReviewAgent agent) {
        this.agent = agent;
    }

    @GetMapping
    public AgentResult review(@RequestParam String applicationId,
                              @RequestParam(required = false) String traceId) {
        return agent.review(applicationId, traceId);
    }

    @PostMapping
    public AgentResult reviewPost(@RequestBody ReviewRequest request) {
        return agent.review(request.applicationId(), request.traceId());
    }

    public record ReviewRequest(String applicationId, String traceId) {
    }
}
