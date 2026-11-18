package com.libo.wfagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libo.wfagent.domain.model.Application;
import com.libo.wfagent.domain.model.DecisionRecord;
import com.libo.wfagent.domain.model.EclParam;
import com.libo.wfagent.domain.model.ExternalDataRecord;
import com.libo.wfagent.domain.model.StrategyVersion;
import com.libo.wfagent.domain.model.VariableMetric;
import com.libo.wfagent.domain.model.VariableSnapshot;
import com.libo.wfagent.domain.repository.EclRepository;
import com.libo.wfagent.domain.repository.RiskDataRepository;
import com.libo.wfagent.domain.repository.VariableMetricRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 暴露给 Agent 的只读工具集。
 * <p>
 * <b>先把数量口径说死，避免面试时被问住：</b>
 * <ul>
 *   <li>本类共 <b>9 个工具</b>，也就是 {@code tools/list} 返回的数量，MCP 侧与进程内注册表完全一致；</li>
 *   <li>按业务语义归为 <b>5 类查询</b>：进件、决策流水、变量快照、策略版本、外部数据；</li>
 *   <li>排查 Agent 的白名单是 <b>6 个</b>（5 类 + 同客群参照 query_peer_snapshot），
 *       另外 2 个（list_variables / query_variable_metric）只给特征分析 Agent；</li>
 *   <li>ECL 的两个计算工具（compute_ecl_summary / compute_ecl_delta）不在这里，
 *       它们算的是业务逻辑、依赖本地代码而不是数据服务，所以不通过 MCP 暴露。</li>
 * </ul>
 * 换句话说：「5 类查询、9 个只读工具、排查 Agent 只拿 6 个」三句话都对，
 * 但说的不是同一件事，写简历和答问时必须挑准。
 * <p>
 * 设计约定：
 * <ul>
 *   <li><b>全部只读</b>，没有任何写入口——最小权限不是口号，是这一层不提供写方法；</li>
 *   <li>返回结构固定，字段名与内部系统一致，模型不需要猜字段；</li>
 *   <li>取不到数据返回 {@code found=false} 而不是抛异常，避免打断 Agent 的取数链路。</li>
 * </ul>
 * 最后一条要特别说明：<b>「查无数据」是正常业务结果，不是故障</b>。
 * 如果这里抛异常，上层就只能看到「调用失败」，无法区分「这笔进件不存在」和
 * 「数据服务挂了」——这两件事在业务上完全相反，混淆的代价是把一次宕机说成客户不存在。
 * 真正的故障（连接超时、SQL 报错）由调用侧包装成 ok=false 并在 Agent 里降级处理。
 */
@Component
public class RiskQueryTools {

    private final RiskDataRepository riskDataRepository;
    private final VariableMetricRepository variableMetricRepository;
    private final EclRepository eclRepository;
    private final ObjectMapper objectMapper;

    public RiskQueryTools(RiskDataRepository riskDataRepository,
                          VariableMetricRepository variableMetricRepository,
                          EclRepository eclRepository,
                          ObjectMapper objectMapper) {
        this.riskDataRepository = riskDataRepository;
        this.variableMetricRepository = variableMetricRepository;
        this.eclRepository = eclRepository;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "query_application",
            description = "按进件编号查询进件主信息：渠道、产品、申请金额、审批结论、拒绝码。排查的第一步，用来确认这笔进件是什么、结论是什么。")
    public String queryApplication(
            @ToolParam(description = "进件编号，例如 APPL20260003") String applicationId) {
        return riskDataRepository.findApplication(applicationId)
                .map(this::write)
                .orElseGet(() -> notFound("进件", applicationId));
    }

    @Tool(name = "query_decision_trace",
            description = "按进件编号查询决策流水：每条规则在哪个策略版本下是否命中、扣了多少分。用于还原“为什么被拒”。")
    public String queryDecisionTrace(
            @ToolParam(description = "进件编号，例如 APPL20260003") String applicationId) {
        List<DecisionRecord> rows = riskDataRepository.findDecisionTraces(applicationId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", !rows.isEmpty());
        result.put("count", rows.size());
        result.put("hitRules", rows.stream().filter(DecisionRecord::matched).map(this::toRuleView).toList());
        result.put("allRules", rows.stream().map(this::toRuleView).toList());
        return write(result);
    }

    @Tool(name = "query_variable_snapshot",
            description = "按进件编号查询变量快照：每个变量的实际取值、阈值、是否超限。用于核对“规则判定时拿到的数是多少”，数值以这里为准，不要自行推算。")
    public String queryVariableSnapshot(
            @ToolParam(description = "进件编号，例如 APPL20260003") String applicationId) {
        List<VariableSnapshot> rows = riskDataRepository.findVariableSnapshots(applicationId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", !rows.isEmpty());
        result.put("count", rows.size());
        result.put("breached", rows.stream().filter(VariableSnapshot::breached).map(this::toVarView).toList());
        result.put("variables", rows.stream().map(this::toVarView).toList());
        return write(result);
    }

    @Tool(name = "query_strategy_version",
            description = "查询某个策略当前和历史版本、状态（ONLINE/GRAY/OFFLINE）与发布时间。用于判断“当时生效的是不是这一版策略”。")
    public String queryStrategyVersion(
            @ToolParam(description = "策略编码，例如 S_ANTIFRAUD；留空表示查询全部策略") String strategyCode) {
        List<StrategyVersion> rows = (strategyCode == null || strategyCode.isBlank())
                ? riskDataRepository.findAllStrategyVersions()
                : riskDataRepository.findStrategyVersions(strategyCode);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", !rows.isEmpty());
        result.put("versions", rows);
        return write(result);
    }

    @Tool(name = "query_external_data",
            description = "按进件编号查询外部数据原始报文（人行征信、三方多头等）。用于区分“数据源返回错了”还是“我们解析错了”。")
    public String queryExternalData(
            @ToolParam(description = "进件编号，例如 APPL20260003") String applicationId) {
        List<ExternalDataRecord> rows = riskDataRepository.findExternalData(applicationId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", !rows.isEmpty());
        result.put("count", rows.size());
        result.put("records", rows);
        return write(result);
    }

    @Tool(name = "query_peer_snapshot",
            description = "查询同一个变量在其他进件上的取值，用于给当前这笔进件找同客群参照。只返回少量样本，不做统计。")
    public String queryPeerSnapshot(
            @ToolParam(description = "变量编码，例如 v_multi_loan_cnt_3m") String varCode,
            @ToolParam(description = "需要排除的进件编号，通常是当前排查的这笔") String excludeApplicationId,
            @ToolParam(required = false, description = "返回条数，默认 5") Integer limit) {
        int size = limit == null || limit <= 0 ? 5 : Math.min(limit, 20);
        List<VariableSnapshot> rows = riskDataRepository.findPeerSnapshots(varCode, excludeApplicationId, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", !rows.isEmpty());
        result.put("samples", rows.stream().map(this::toVarView).toList());
        return write(result);
    }

    @Tool(name = "list_variables",
            description = "列出当前可分析的变量编码与可用账期、客群。做变量分析前先调用它确认变量存在，不要凭印象拼变量名。")
    public String listVariables() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("variables", variableMetricRepository.varCodes());
        result.put("periods", variableMetricRepository.periods());
        result.put("segments", variableMetricRepository.segments());
        return write(result);
    }

    @Tool(name = "query_variable_metric",
            description = "查询变量分箱指标：每个分箱的样本数和坏样本数。用于分析变量分布与区分度。返回的是平台算好的指标，不要自己重新计算。")
    public String queryVariableMetric(
            @ToolParam(description = "变量编码，例如 v_multi_loan_cnt_3m") String varCode,
            @ToolParam(required = false, description = "账期，例如 202609；留空表示全部账期") String period,
            @ToolParam(required = false, description = "客群，例如 普通客群 / 优质客群；留空表示全部客群") String segment) {
        List<VariableMetric> rows = variableMetricRepository.query(varCode, period, segment);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", !rows.isEmpty());
        result.put("count", rows.size());
        result.put("buckets", rows.stream().map(this::toMetricView).toList());
        return write(result);
    }

    @Tool(name = "query_ecl_param",
            description = "查询某一期的减值参数：各阶段、各客群的 PD/LGD/EAD/余额/客户数/模型版本。ECL 分析必须以这里的参数为准。")
    public String queryEclParam(
            @ToolParam(description = "账期，例如 202609；留空表示查询全部可用账期列表") String period) {
        if (period == null || period.isBlank()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("periods", eclRepository.periods());
            return write(result);
        }
        List<EclParam> rows = eclRepository.findByPeriod(period);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", !rows.isEmpty());
        result.put("period", period);
        result.put("count", rows.size());
        result.put("params", rows);
        return write(result);
    }

    private Map<String, Object> toRuleView(DecisionRecord record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ruleCode", record.ruleCode());
        view.put("ruleName", record.ruleName());
        view.put("strategy", record.strategyCode() + "@" + record.strategyVersion());
        view.put("hit", record.matched());
        view.put("scoreDelta", record.scoreDelta());
        view.put("decidedAt", record.decidedAt());
        return view;
    }

    private Map<String, Object> toVarView(VariableSnapshot snapshot) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("varCode", snapshot.varCode());
        view.put("varName", snapshot.varName());
        view.put("value", snapshot.varValue());
        view.put("threshold", snapshot.threshold());
        view.put("breached", snapshot.breached());
        view.put("calcTime", snapshot.calcTime());
        return view;
    }

    private Map<String, Object> toMetricView(VariableMetric metric) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("period", metric.period());
        view.put("segment", metric.segment());
        view.put("bucket", metric.bucket());
        view.put("cnt", metric.cnt());
        view.put("badCnt", metric.badCnt());
        view.put("badRate", Math.round(metric.badRate() * 10000) / 10000.0);
        return view;
    }

    private String notFound(String what, String id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", false);
        result.put("message", what + "不存在: " + id);
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
