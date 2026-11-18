package com.libo.wfagent.app.regression;

import com.libo.wfagent.common.model.AgentModel;
import com.libo.wfagent.common.runtime.AgentResult;
import com.libo.wfagent.common.trace.TraceRecorder;
import com.libo.wfagent.ecl.EclAnalysisAgent;
import com.libo.wfagent.feature.FeatureAnalysisAgent;
import com.libo.wfagent.riskreview.RiskReviewAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 回归跑批。
 * <p>
 * <b>它在整个项目里承担两个作用，面试时要能分开讲：</b>
 * <ol>
 *   <li><b>改提示词后判断有没有变坏</b>：跑一遍，看通过数和取数路径有没有偏离剧本。
 *       没有这套东西，调 prompt 就是凭感觉，改好了不知道好多少，改坏了下周才发现。</li>
 *   <li><b>产出简历里那两个数字</b>：{@code total/passed} 和 {@code p95Ms}。
 *       同一批用例在 scripted 与 spring-ai 两种模式下都跑一遍，
 *       才能说「模型驱动路径跑通了」而不是「剧本跑通了」。</li>
 * </ol>
 * <p>
 * 除每条用例自己的断言外，这里还统一下两条<b>全局不变量</b>，对所有用例生效：
 * <ul>
 *   <li><b>可回放</b>：跑完必须能按 traceId 从 TraceRecorder 原样取回（内存或落库都算）。
 *       取不回来就说明「结论可复核」这句话是假的。</li>
 *   <li><b>步数不越界</b>：执行步数不得超过该 Agent 声明的 maxSteps。
 *       越界说明运行时约束失效，模型已经在无限取数了。</li>
 * </ul>
 * 这两条不写在每条用例里，是因为它们是全系统性的，写在用例里会重复十遍还容易漏。
 */
@Service
public class RegressionService {

    private static final Logger log = LoggerFactory.getLogger(RegressionService.class);

    private final Map<String, Function<String, AgentResult>> dispatchers;
    private final Map<String, Integer> maxSteps;
    private final TraceRecorder traceRecorder;
    private final AgentModel agentModel;

    public RegressionService(RiskReviewAgent riskReviewAgent,
                             FeatureAnalysisAgent featureAnalysisAgent,
                             EclAnalysisAgent eclAnalysisAgent,
                             TraceRecorder traceRecorder,
                             AgentModel agentModel) {
        this.traceRecorder = traceRecorder;
        this.agentModel = agentModel;

        // 三个 Agent 的入参形状不一样（进件号 / 自然语言 / 两个账期），
        // 在装配处做一次适配，用例本身就不用关心调用方式了。
        Map<String, Function<String, AgentResult>> map = new LinkedHashMap<>();
        map.put(RiskReviewAgent.NAME, riskReviewAgent::review);
        map.put(FeatureAnalysisAgent.NAME, featureAnalysisAgent::analyze);
        map.put(EclAnalysisAgent.NAME, input -> {
            String[] periods = input.split("\\|");
            if (periods.length != 2) {
                throw new IllegalArgumentException("ECL 用例输入必须是 期A|期B，实际为 " + input);
            }
            return eclAnalysisAgent.analyze(periods[0].trim(), periods[1].trim());
        });
        this.dispatchers = Map.copyOf(map);

        this.maxSteps = Map.of(
                RiskReviewAgent.NAME, riskReviewAgent.spec().maxSteps(),
                FeatureAnalysisAgent.NAME, featureAnalysisAgent.spec().maxSteps(),
                EclAnalysisAgent.NAME, eclAnalysisAgent.spec().maxSteps());
    }

    /**
     * 跑完整个用例集。任何一条失败都不会中断后续用例——
     * 跑批的价值在于一次看到全部问题，而不是遇到第一个红就停。
     */
    public RegressionReport run() {
        List<RegressionCase> cases = RegressionCases.all();
        List<RegressionReport.CaseOutcome> outcomes = new ArrayList<>(cases.size());
        int passed = 0;
        int replayable = 0;

        for (RegressionCase testCase : cases) {
            long startNano = System.nanoTime();
            List<String> failures = new ArrayList<>();
            AgentResult result = null;

            try {
                Function<String, AgentResult> dispatcher = dispatchers.get(testCase.agent());
                if (dispatcher == null) {
                    failures.add("未知的 Agent: " + testCase.agent());
                } else {
                    result = dispatcher.apply(testCase.input());
                }
            } catch (Exception e) {
                // 用例抛异常也算失败，且要留下异常类型——三种 Agent 抛的异常含义不同
                log.warn("回归用例 {} 执行异常", testCase.id(), e);
                failures.add("执行异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            long costMs = (System.nanoTime() - startNano) / 1_000_000;

            if (result != null) {
                // 全局不变量一：步数不得越过该 Agent 声明的上限
                int limit = maxSteps.getOrDefault(testCase.agent(), Integer.MAX_VALUE);
                if (result.steps().size() > limit) {
                    failures.add("步数 " + result.steps().size() + " 超过上限 " + limit);
                }

                // 全局不变量二：结论必须能按 traceId 原样取回，否则「可回放」不成立
                boolean replayableNow = traceRecorder.find(result.traceId()).isPresent();
                if (replayableNow) {
                    replayable++;
                } else {
                    failures.add("Trace 未落库，traceId=" + result.traceId() + " 无法回放");
                }

                // 用例自己的断言放在最后：全局不变量破了，单条断言再对也没有意义
                String reason = testCase.assertion().apply(result);
                if (reason != null) {
                    failures.add(reason);
                }
            }

            boolean ok = failures.isEmpty();
            if (ok) {
                passed++;
            }
            outcomes.add(new RegressionReport.CaseOutcome(
                    testCase.id(), testCase.agent(), testCase.description(), ok, costMs, List.copyOf(failures)));
        }

        List<Long> costs = outcomes.stream().map(RegressionReport.CaseOutcome::costMs).sorted().toList();
        return new RegressionReport(
                agentModel.name(),
                Instant.now().toString(),
                outcomes.size(),
                passed,
                outcomes.size() - passed,
                round(costs.stream().mapToLong(Long::longValue).average().orElse(0)),
                round(percentile(costs, 0.50)),
                round(percentile(costs, 0.95)),
                costs.isEmpty() ? 0 : costs.get(costs.size() - 1),
                replayable,
                outcomes);
    }

    /**
     * 最近秩法（nearest-rank）取分位：样本少的时候比插值法诚实——
     * 10 条用例的 P95 实际就是「第二慢的那条」，报告里不夸大。
     */
    private static double percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(p * sorted.size());
        int index = Math.min(Math.max(rank - 1, 0), sorted.size() - 1);
        return sorted.get(index);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** 用例清单，供接口展示「这套回归到底在守什么」，也方便面试时直接念。 */
    public List<Map<String, String>> catalog() {
        return RegressionCases.all().stream()
                .map(c -> Map.of("id", c.id(), "agent", c.agent(), "description", c.description()))
                .sorted(Comparator.comparing(m -> m.get("id")))
                .toList();
    }
}
