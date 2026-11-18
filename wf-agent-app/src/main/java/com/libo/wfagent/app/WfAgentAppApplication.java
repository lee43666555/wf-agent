package com.libo.wfagent.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 统一启动入口，端口 8080，把三个 Agent 装进同一个进程。
 * <p>
 * 三个 Agent 模块各自也能独立启动（8082/8083/8084）。
 * 它们各自带 {@code @SpringBootApplication}，全量扫描会触发
 * 「multiple @SpringBootConfiguration」错误，所以这里显式排除它们的启动类。
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.libo.wfagent",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.libo\\.wfagent\\.(riskreview|feature|ecl)\\..*Application"))
public class WfAgentAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(WfAgentAppApplication.class, args);
    }
}
