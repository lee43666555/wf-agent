package com.libo.wfagent.common.tool;

/**
 * 一个可被 Agent 调用的工具定义。
 *
 * @param name             工具名，模型按名字选择工具，命名要见名知义
 * @param description      给模型看的说明：返回什么、什么时候该用、不要用它做什么
 * @param parametersSchema 参数 JSON Schema，用于约束模型输出并做入参校验
 * @param invoker          实际执行逻辑
 */
public record ToolSpec(
        String name,
        String description,
        String parametersSchema,
        ToolInvoker invoker) {
}
