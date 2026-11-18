package com.libo.wfagent.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP 工具服务：把风控域内部查询能力以 MCP 协议对外暴露。
 * 独立进程运行，Agent 通过 MCP 客户端连接，工具服务与 Agent 之间没有代码依赖。
 */
@SpringBootApplication(scanBasePackages = {"com.libo.wfagent.mcp", "com.libo.wfagent.tools", "com.libo.wfagent.domain"})
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
