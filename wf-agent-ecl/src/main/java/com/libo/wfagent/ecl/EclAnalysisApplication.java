package com.libo.wfagent.ecl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ECL 减值分析 Agent 独立启动入口，端口 8084。
 */
@SpringBootApplication(scanBasePackages = {
        "com.libo.wfagent.ecl",
        "com.libo.wfagent.tools",
        "com.libo.wfagent.domain",
        "com.libo.wfagent.common"})
public class EclAnalysisApplication {

    public static void main(String[] args) {
        SpringApplication.run(EclAnalysisApplication.class, args);
    }
}
