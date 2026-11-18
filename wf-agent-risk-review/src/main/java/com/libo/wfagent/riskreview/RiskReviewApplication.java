package com.libo.wfagent.riskreview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 排查 Agent 独立启动入口，端口 8082。
 */
@SpringBootApplication(scanBasePackages = {
        "com.libo.wfagent.riskreview",
        "com.libo.wfagent.tools",
        "com.libo.wfagent.domain",
        "com.libo.wfagent.common"})
public class RiskReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskReviewApplication.class, args);
    }
}
