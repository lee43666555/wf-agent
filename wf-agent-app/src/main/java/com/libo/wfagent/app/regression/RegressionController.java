package com.libo.wfagent.app.regression;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 回归跑批入口。
 * <p>
 * 用 HTTP 而不是只在测试里跑，原因有两个：
 * <ul>
 *   <li>要能在<b>真实模型模式</b>下跑。单测跑的是脚本模式，那是确定性的；
 *       切到 {@code --wf-agent.model.mode=spring-ai} 起服务再用这个接口跑一遍，
 *       拿到的才是「模型驱动路径」的通过率和 P95，这两个数才写进简历。</li>
 *   <li>演示时可以直接投屏：一次请求返回 10 条用例的通过情况和耗时，比讲架构有说服力。</li>
 * </ul>
 * <p>
 * 示例：
 * <pre>
 * curl -s "http://localhost:8080/api/regression" | jq '{model, total, passed, p95Ms}'
 * curl -s "http://localhost:8080/api/regression/cases" | jq
 * </pre>
 */
@RestController
@RequestMapping("/api/regression")
public class RegressionController {

    private final RegressionService regressionService;

    public RegressionController(RegressionService regressionService) {
        this.regressionService = regressionService;
    }

    /** 跑一遍全部用例，返回逐条结果与汇总指标。不缓存：每次都反映当前代码与当前模型。 */
    @GetMapping
    public RegressionReport run() {
        return regressionService.run();
    }

    /** 只列用例清单，不执行。用于确认「这套回归覆盖了什么」。 */
    @GetMapping("/cases")
    public List<Map<String, String>> cases() {
        return regressionService.catalog();
    }
}
