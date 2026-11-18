package com.libo.wfagent.ecl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 减值计算工具。ECL Agent 专用的确定性计算入口，
 * 全流程没有任何一步把数字交给模型推算。
 */
@Component
public class EclTools {

    private final EclCalculator calculator;
    private final ObjectMapper objectMapper;

    public EclTools(EclCalculator calculator, ObjectMapper objectMapper) {
        this.calculator = calculator;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "compute_ecl_summary",
            description = "计算某一期的减值总额：ECL 合计、分阶段金额、总余额、客户数。数值由代码计算，直接引用，不要自己乘算。")
    public String summary(
            @ToolParam(description = "账期，例如 202608") String period) {
        EclCalculator.PeriodSummary summary = calculator.summary(period);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", summary.totalEcl() != 0.0);
        result.put("summary", summary);
        return write(result);
    }

    @Tool(name = "compute_ecl_delta",
            description = "比较两期减值：按阶段与客群给出 ECL 差额，并做量价拆分——规模效应、风险效应、损失率效应三项之和等于总差额。用于回答「这个月为什么多提了」。")
    public String delta(
            @ToolParam(description = "较早账期，例如 202608") String periodA,
            @ToolParam(description = "较晚账期，例如 202609") String periodB) {
        EclCalculator.Comparison comparison = calculator.compare(periodA, periodB);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", !comparison.segments().isEmpty());
        result.put("comparison", comparison);
        return write(result);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"found\":false,\"message\":\"序列化失败\"}";
        }
    }
}
