package com.libo.wfagent.common.model;

import com.libo.wfagent.common.runtime.AgentContext;
import com.libo.wfagent.common.runtime.AgentSpec;

/**
 * 模型决策层。两个实现：
 * <ul>
 *   <li>{@link SpringAiAgentModel}：真实调用模型，要求输出受约束 JSON</li>
 *   <li>{@link ScriptedAgentModel}：按剧本返回，用于无模型环境跑通链路与回归</li>
 * </ul>
 * 只有一个抽象方法，测试里可以直接用 lambda 模拟各种异常行为。
 */
@FunctionalInterface
public interface AgentModel {

    ModelDecision decide(AgentSpec spec, AgentContext context);

    /** 实现标识，写进 Trace 便于区分这次结果是谁产出的。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
