package com.libo.wfagent.common.model;

import com.libo.wfagent.common.runtime.AgentContext;

import java.util.function.Function;

/**
 * 剧本中的一步。可以是固定动作，也可以根据已经取到的数据分支。
 * <p>
 * 分支能力是必要的：同一个问题在「数据存在」和「数据缺失」两条路上，
 * 正确做法完全不同，固定剧本没法表达。回归测试也正是靠覆盖这些分支。
 */
public sealed interface ScriptStep {

    ModelDecision resolve(AgentContext context);

    /** 固定动作。参数里的 $input 会被替换成当前输入。 */
    record Fixed(ModelDecision decision) implements ScriptStep {

        @Override
        public ModelDecision resolve(AgentContext context) {
            return substitute(decision, context.input());
        }

        private ModelDecision substitute(ModelDecision source, String input) {
            if (source instanceof ModelDecision.CallTool call) {
                return new ModelDecision.CallTool(call.tool(), substituteMap(call.arguments(), input),
                        call.thought());
            }
            if (source instanceof ModelDecision.Finish finish) {
                return new ModelDecision.Finish(substituteMap(finish.output(), input),
                        finish.narrative(), finish.thought());
            }
            return source;
        }

        private java.util.Map<String, Object> substituteMap(java.util.Map<String, Object> source, String input) {
            if (source == null || source.isEmpty()) {
                return java.util.Map.of();
            }
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            source.forEach((key, value) -> result.put(key,
                    ScriptedAgentModel.INPUT_PLACEHOLDER.equals(value) ? input : value));
            return result;
        }
    }

    /** 根据上下文决定下一步。 */
    record Dynamic(Function<AgentContext, ModelDecision> resolver) implements ScriptStep {

        @Override
        public ModelDecision resolve(AgentContext context) {
            return resolver.apply(context);
        }
    }

    static ScriptStep fixed(ModelDecision decision) {
        return new Fixed(decision);
    }

    static ScriptStep dynamic(Function<AgentContext, ModelDecision> resolver) {
        return new Dynamic(resolver);
    }
}
