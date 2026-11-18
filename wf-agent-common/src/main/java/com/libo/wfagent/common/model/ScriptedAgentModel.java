package com.libo.wfagent.common.model;

import com.libo.wfagent.common.runtime.AgentContext;
import com.libo.wfagent.common.runtime.AgentSpec;

import java.util.List;
import java.util.Map;

/**
 * 剧本模型：按预设顺序返回决策，不依赖任何外部模型。
 * <p>
 * 两个用途：一是没有 API Key 时也能把整条链路跑通、把 Trace 打出来；
 * 二是作为回归用例的基线——剧本代表「正确解法」，改动提示词后可以比对实际取数路径是否偏离。
 * <p>
 * 剧本的每一步可以是固定动作，也可以读取已取到的数据后分支（见 {@link ScriptStep}）。
 */
public class ScriptedAgentModel implements AgentModel {

    /** 剧本中代表「当前任务输入」的占位符。 */
    public static final String INPUT_PLACEHOLDER = "$input";

    private final Map<String, List<ScriptStep>> scripts;

    public ScriptedAgentModel(Map<String, List<ScriptStep>> scripts) {
        this.scripts = Map.copyOf(scripts);
    }

    @Override
    public String name() {
        return "scripted";
    }

    @Override
    public ModelDecision decide(AgentSpec spec, AgentContext context) {
        List<ScriptStep> script = scripts.get(spec.name());
        if (script == null || script.isEmpty()) {
            return new ModelDecision.Finish(Map.of(), "没有为该 Agent 配置剧本", "scripted-empty");
        }
        int index = context.steps().size();
        ScriptStep step = index >= script.size() ? script.get(script.size() - 1) : script.get(index);
        return step.resolve(context);
    }

    /** 简写：固定调用某工具。 */
    public static ScriptStep call(String tool, Map<String, Object> arguments, String thought) {
        return ScriptStep.fixed(new ModelDecision.CallTool(tool, arguments, thought));
    }

    /** 简写：固定给出结论。 */
    public static ScriptStep finish(Map<String, Object> output, String narrative) {
        return ScriptStep.fixed(new ModelDecision.Finish(output, narrative, "信息已足够"));
    }
}
