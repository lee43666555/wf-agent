package com.libo.wfagent.common.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运行时硬约束。这几个值决定 Agent 能不能收敛，属于上线前必须固定的参数。
 * <p>
 * <b>为什么这些参数必须在代码里、而不是写进提示词：</b>
 * 提示词是建议，代码是强制。模型可以无视「最多调 8 次」这句话继续调第 9 次，
 * 但它没法绕过 {@code AgentRuntime} 里的循环上限。金融场景里，「它应该会停下来」
 * 和「它一定会停下来」是两回事，只有后者能写进验收标准。
 * <p>
 * <b>默认值不是随手填的，每个都有依据：</b>
 * <ul>
 *   <li>{@code maxSteps=8}：排查 Agent 的正常路径是 4~6 步（进件 → 决策流水 →
 *       变量快照 → 外部数据 → 结论），留 2 步余量给模型的绕路。再多就不是绕路，
 *       而是没想清楚要查什么。</li>
 *   <li>{@code maxRepeatPerTool=1}：同一工具 + 同一参数出现第 2 次，说明模型在原地打转
 *       （典型症状：反复查同一个进件号等一个不会变的结果）。这里直接拦截而不是劝导，
 *       是因为「重复调用」在日志里极难发现，不拦就变成一次跑 40 步还没结论。</li>
 *   <li>{@code maxRetry=1}：字段缺失只纠正一轮。给第二轮的意义不大——
 *       模型第一轮漏字段通常是没理解任务，再问一次也补不齐，不如按 DEGRADED 返回，
 *       让人看到缺什么。多给几轮只会把一次错误拖成三次调用。</li>
 *   <li>{@code toolTimeoutMs=15000}：内部查询接口的 P99 在秒级，15 秒足够覆盖抖动；
 *       超过这个时间基本可以判定下游挂了，继续等只会拖垮整个请求。</li>
 * </ul>
 * 这四项合起来的效果，就是回归用例里守的那条「步数不越界」和「无数据不下结论」。
 *
 * @param maxSteps         单次运行的最大步数，防止模型无限取数
 * @param maxRepeatPerTool 同一工具 + 同一参数允许重复调用的次数，超过即拦截
 * @param maxRetry         结构化输出字段缺失时允许的纠正轮次
 * @param toolTimeoutMs    单次工具调用超时（毫秒），超过即判失败降级，由 AgentRuntime 强制执行
 */
@ConfigurationProperties(prefix = "wf-agent.runtime")
public record RuntimeProperties(
        int maxSteps,
        int maxRepeatPerTool,
        int maxRetry,
        long toolTimeoutMs) {

    public RuntimeProperties {
        if (maxSteps <= 0) {
            maxSteps = 8;
        }
        if (maxRepeatPerTool <= 0) {
            maxRepeatPerTool = 1;
        }
        if (maxRetry < 0) {
            maxRetry = 1;
        }
        if (toolTimeoutMs <= 0) {
            toolTimeoutMs = 15_000L;
        }
    }

    public static RuntimeProperties defaults() {
        return new RuntimeProperties(8, 1, 1, 15_000L);
    }
}
