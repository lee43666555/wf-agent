package com.libo.wfagent.common.tool;

/**
 * 工具调用结果。失败不抛异常，交给运行时决定是重试还是降级，避免一次取数失败打断整轮排查。
 */
public record ToolResult(
        boolean ok,
        String output,
        String error,
        long costMs) {

    public static ToolResult ok(String output, long costMs) {
        return new ToolResult(true, output, null, costMs);
    }

    public static ToolResult fail(String error, long costMs) {
        return new ToolResult(false, null, error, costMs);
    }
}
