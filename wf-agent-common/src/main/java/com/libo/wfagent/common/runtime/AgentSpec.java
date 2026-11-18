package com.libo.wfagent.common.runtime;

import com.libo.wfagent.common.tool.ToolSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 Agent 的静态声明：叫什么、用什么提示、能碰哪些工具、结果必须有哪些字段。
 * <p>
 * 三个 Agent 的差异全部体现在这份声明里，运行时是同一套。
 */
public record AgentSpec(
        String name,
        String systemPrompt,
        List<ToolSpec> tools,
        List<String> requiredFields,
        Map<String, List<String>> allowedValues,
        int maxSteps) {

    public AgentSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("agent name 不能为空");
        }
        if (tools == null) {
            tools = List.of();
        }
        if (requiredFields == null) {
            requiredFields = List.of();
        }
        if (allowedValues == null) {
            allowedValues = Map.of();
        }
        if (maxSteps <= 0) {
            maxSteps = 8;
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String systemPrompt = "";
        private List<ToolSpec> tools = List.of();
        private List<String> requiredFields = List.of();
        private final Map<String, List<String>> allowedValues = new LinkedHashMap<>();
        private int maxSteps = 8;

        private Builder(String name) {
            this.name = name;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder tools(List<ToolSpec> tools) {
            this.tools = tools;
            return this;
        }

        public Builder requiredFields(String... fields) {
            this.requiredFields = List.of(fields);
            return this;
        }

        /**
         * 声明某个字符串字段的合法取值域（枚举白名单）。与工具白名单同构：
         * 工具名只能在 tools 里选，字段值只能在 allowedValues 里选，都靠代码兜住。
         */
        public Builder allowedValues(String field, String... values) {
            this.allowedValues.put(field, List.of(values));
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public AgentSpec build() {
            return new AgentSpec(name, systemPrompt, tools, requiredFields, allowedValues, maxSteps);
        }
    }
}
