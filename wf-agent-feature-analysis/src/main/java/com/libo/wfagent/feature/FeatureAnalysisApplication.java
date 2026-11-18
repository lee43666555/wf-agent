package com.libo.wfagent.feature;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 特征分析 Agent 独立启动入口，端口 8083。
 */
@SpringBootApplication(scanBasePackages = {
        "com.libo.wfagent.feature",
        "com.libo.wfagent.tools",
        "com.libo.wfagent.domain",
        "com.libo.wfagent.common"})
public class FeatureAnalysisApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeatureAnalysisApplication.class, args);
    }
}
