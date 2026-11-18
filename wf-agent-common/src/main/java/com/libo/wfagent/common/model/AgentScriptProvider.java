package com.libo.wfagent.common.model;

import java.util.List;

/**
 * 剧本提供者。每个 Agent 模块声明自己的剧本，由 common 汇总后交给 {@link ScriptedAgentModel}。
 * <p>
 * 剧本本身就是一组回归用例：它记录了「这个问题应该按什么顺序取哪些数」，
 * 以及在数据缺失时应该怎么收场。
 */
public interface AgentScriptProvider {

    String agentName();

    List<ScriptStep> script();
}
