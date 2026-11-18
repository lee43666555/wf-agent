package com.libo.wfagent.common.tool;

import java.util.Map;

/**
 * 工具的执行体。实现方必须是只读的：Agent 工具层不允许写库、不允许直连生产库。
 */
@FunctionalInterface
public interface ToolInvoker {

    /**
     * @param arguments 模型给出的参数，已按 {@link ToolSpec#parametersSchema()} 校验过类型
     * @return 工具输出，建议为 JSON 字符串，便于模型理解字段
     */
    String invoke(Map<String, Object> arguments) throws Exception;
}
