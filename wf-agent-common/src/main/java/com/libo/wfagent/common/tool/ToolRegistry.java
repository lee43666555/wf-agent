package com.libo.wfagent.common.tool;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表。两种实现对应两种部署形态：
 * <ul>
 *   <li>{@link LocalToolRegistry}：进程内直连，开发调试用，链路短</li>
 *   <li>{@link McpToolRegistry}：通过 MCP 协议访问独立的工具服务，生产形态</li>
 * </ul>
 * Agent 只依赖这个接口，换实现不影响业务代码。
 */
public interface ToolRegistry {

    List<ToolSpec> all();

    /** 按名字取子集，用于给不同 Agent 划定可用工具范围。 */
    List<ToolSpec> subset(Collection<String> names);

    ToolResult invoke(String toolName, Map<String, Object> arguments);
}
