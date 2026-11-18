package com.libo.wfagent.common.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.libo.wfagent.common.model.AgentModel;
import com.libo.wfagent.common.model.ModelDecision;
import com.libo.wfagent.common.tool.ToolInvoker;
import com.libo.wfagent.common.tool.ToolResult;
import com.libo.wfagent.common.tool.ToolSpec;
import com.libo.wfagent.common.trace.TraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent 运行时。三个 Agent 共用这一套循环，差异全部由 {@link AgentSpec} 声明。
 * <p>
 * 这一层负责的事，都是金融场景里模型不能自己做的事：
 * <ol>
 *   <li>步数上限与重复调用拦截：模型想反复取同一个数是常见病，靠提示词管不住</li>
 *   <li>工具白名单：只能调用 AgentSpec 里声明过的工具，越界直接拒绝</li>
 *   <li>入参校验：按工具的参数 Schema 检查必填项，缺参数不发起调用</li>
 *   <li>工具调用超时：独立线程执行，超时判失败降级，避免下游 hang 住拖死请求</li>
 *   <li>结构化输出校验：必填字段缺失先要求模型补齐，仍缺则降级；枚举字段取值必须在白名单内</li>
 *   <li>数值溯源：output 里的每个数字都要能在成功工具返回里找到，否则视为编造</li>
 *   <li>数据支撑校验：一次工具都没成功时，即使字段齐全也不允许标记为 OK</li>
 * </ol>
 * <p>
 * 最后一条是这套运行时里最像「金融系统」的地方，值得单独解释：
 * 字段齐全只说明模型「按格式输出了」，不说明它有依据。一次运行如果从头到尾没有一次
 * 成功的取数，模型写出来的结论必然是编的——而且编得很像真的，因为它符合 Schema。
 * 所以这里额外判一次「有没有成功取到数据」，没有就降级为 DEGRADED 并写明原因。
 * <p>
 * 另一条贯穿全类原则：<b>降级比硬凑好</b>。字段缺失、步数用尽、模型不可用时，
 * 一律返回 DEGRADED / FAILED 并说明缺什么，绝不返回一个看起来完整的猜测结果。
 * 业务方看到「数据缺失」会去补数据，看到「结论完整」就直接用了——后者的代价更大。
 */
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final AgentModel model;
    private final TraceRecorder traceRecorder;
    private final RuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService toolExecutor;

    public AgentRuntime(AgentModel model, TraceRecorder traceRecorder,
                        RuntimeProperties properties, ObjectMapper objectMapper) {
        this(model, traceRecorder, properties, objectMapper, newToolExecutor());
    }

    /** 测试可注入自定义 executor，生产用默认的守护线程池。 */
    AgentRuntime(AgentModel model, TraceRecorder traceRecorder,
                 RuntimeProperties properties, ObjectMapper objectMapper,
                 ExecutorService toolExecutor) {
        this.model = model;
        this.traceRecorder = traceRecorder;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.toolExecutor = toolExecutor;
    }

    private static ExecutorService newToolExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private int seq = 1;

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "wf-agent-tool-" + seq++);
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newCachedThreadPool(factory);
    }

    public AgentResult run(AgentSpec spec, AgentRequest request) {
        long startNano = System.nanoTime();
        List<AgentStep> steps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> callCount = new HashMap<>();

        AgentContext context = AgentContext.initial(request.input());
        int limit = Math.min(spec.maxSteps(), properties.maxSteps());
        int retried = 0;

        for (int index = 1; index <= limit; index++) {
            ModelDecision decision;
            try {
                decision = model.decide(spec, context);
            } catch (Exception e) {
                log.warn("模型调用失败 agent={} traceId={}", spec.name(), request.traceId(), e);
                warnings.add("模型调用失败: " + e.getMessage());
                return build(spec, request, AgentResult.Status.FAILED, null,
                        "模型不可用，未产出结论", steps, warnings, startNano);
            }

            if (decision instanceof ModelDecision.Finish finish) {
                steps.add(AgentStep.finish(index, finish.thought(), writeJson(finish.output()),
                        (System.nanoTime() - startNano) / 1_000_000));
                List<String> missing = missingFields(finish.output(), spec.requiredFields());
                if (!missing.isEmpty() && retried < properties.maxRetry()) {
                    retried++;
                    warnings.add("输出缺少字段 " + missing + "，已要求模型补齐");
                    context = context.withSteps(steps).withFeedback(
                            "上一次输出缺少必填字段 " + missing + "。只输出 JSON，不要解释。");
                    continue;
                }

                // 一次都没取到数据就下结论，属于缺少依据，不允许标记为 OK
                boolean hasSuccessfulToolCall = steps.stream()
                        .anyMatch(s -> "TOOL_CALL".equals(s.type()) && s.ok());
                boolean lacksDataSupport = !spec.tools().isEmpty() && !hasSuccessfulToolCall;

                // 枚举字段取值必须落在 AgentSpec 声明的白名单里（和工具白名单同构）
                List<String> invalidEnums = invalidEnumValues(finish.output(), spec.allowedValues());
                if (!invalidEnums.isEmpty()) {
                    warnings.add("字段取值不合法 " + invalidEnums + "，取值域见 AgentSpec.allowedValues");
                }

                // 输出中的数值必须能在某次成功工具返回里溯源到，否则视为编造
                List<String> untraceable = untraceableNumbers(finish.output(), steps);
                if (!untraceable.isEmpty()) {
                    warnings.add("输出中的数值 " + untraceable + " 未出现在任何成功工具返回中，缺少数据支撑");
                }

                AgentResult.Status status;
                if (!missing.isEmpty()) {
                    warnings.add("必填字段仍缺失 " + missing + "，按降级返回");
                    status = AgentResult.Status.DEGRADED;
                } else if (!invalidEnums.isEmpty()) {
                    status = AgentResult.Status.DEGRADED;
                } else if (lacksDataSupport) {
                    warnings.add("本次运行没有任何工具调用成功，结论缺少数据支撑，不可直接使用");
                    status = AgentResult.Status.DEGRADED;
                } else if (!untraceable.isEmpty()) {
                    status = AgentResult.Status.DEGRADED;
                } else {
                    status = AgentResult.Status.OK;
                }
                return build(spec, request, status, toJsonNode(finish.output()),
                        finish.narrative(), steps, warnings, startNano);
            }

            ModelDecision.CallTool call = (ModelDecision.CallTool) decision;

            String fingerprint = call.tool() + "|" + canonical(call.arguments());
            int times = callCount.merge(fingerprint, 1, Integer::sum);
            if (times > properties.maxRepeatPerTool()) {
                steps.add(AgentStep.blocked(index, call.tool(), call.arguments(), call.thought()));
                warnings.add("拦截重复调用: " + call.tool());
                context = context.withSteps(steps);
                continue;
            }

            ToolSpec tool = spec.tools().stream()
                    .filter(t -> t.name().equals(call.tool()))
                    .findFirst()
                    .orElse(null);
            if (tool == null) {
                steps.add(new AgentStep(index, "TOOL_CALL", call.tool(), call.arguments(), null, false,
                        "该 Agent 未声明此工具", call.thought(), 0));
                warnings.add("模型调用了未声明的工具: " + call.tool());
                context = context.withSteps(steps);
                continue;
            }

            List<String> missingParams = missingParams(tool.parametersSchema(), call.arguments());
            if (!missingParams.isEmpty()) {
                steps.add(new AgentStep(index, "TOOL_CALL", call.tool(), call.arguments(), null, false,
                        "缺少必填参数 " + missingParams, call.thought(), 0));
                warnings.add("工具 " + call.tool() + " 缺少参数 " + missingParams);
                context = context.withSteps(steps);
                continue;
            }

            ToolResult result = invokeWithTimeout(tool.invoker(), call.arguments());
            steps.add(AgentStep.toolCall(index, call.tool(), call.arguments(), call.thought(),
                    result.output(), result.ok(), result.error(), result.costMs()));
            if (!result.ok()) {
                warnings.add("工具 " + call.tool() + " 取数失败: " + result.error());
            }
            context = context.withSteps(steps);
        }

        warnings.add("达到最大步数 " + limit + " 仍未收敛，返回已取到的部分结果");
        return build(spec, request, AgentResult.Status.DEGRADED, null,
                "未在限定步数内得出完整结论", steps, warnings, startNano);
    }

    private AgentResult build(AgentSpec spec, AgentRequest request, AgentResult.Status status,
                              JsonNode output, String narrative, List<AgentStep> steps,
                              List<String> warnings, long startNano) {
        AgentResult result = new AgentResult(
                spec.name(),
                request.traceId(),
                status,
                output,
                narrative,
                List.copyOf(steps),
                List.copyOf(warnings),
                (System.nanoTime() - startNano) / 1_000_000);
        traceRecorder.record(result);
        return result;
    }

    private List<String> missingFields(Map<String, Object> output, List<String> required) {
        if (required.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String field : required) {
            if (output == null || output.get(field) == null) {
                missing.add(field);
            }
        }
        return missing;
    }

    /** 轻量参数校验：只检查 Schema 里 required 声明的字段是否存在，不做完整 Schema 校验。 */
    private List<String> missingParams(String schema, Map<String, Object> arguments) {
        if (schema == null || schema.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(schema);
            JsonNode required = node.get("required");
            if (required == null || !required.isArray()) {
                return List.of();
            }
            List<String> missing = new ArrayList<>();
            for (JsonNode item : required) {
                String name = item.asText();
                if (arguments == null || arguments.get(name) == null) {
                    missing.add(name);
                }
            }
            return missing;
        } catch (Exception e) {
            log.debug("解析工具参数 Schema 失败，跳过校验: {}", e.getMessage());
            return List.of();
        }
    }

    /** 参数排序后再序列化，保证「同样的参数不同顺序」被判为同一次调用。 */
    private String canonical(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(arguments == null ? Map.of() : arguments));
        } catch (Exception e) {
            return String.valueOf(arguments);
        }
    }

    /** 用独立线程执行工具调用，超时则失败返回，避免 MCP 或下游 hang 住拖死整个请求。 */
    private ToolResult invokeWithTimeout(ToolInvoker invoker, Map<String, Object> arguments) {
        long start = System.nanoTime();
        Future<String> future = toolExecutor.submit(() -> invoker.invoke(arguments));
        try {
            String output = future.get(properties.toolTimeoutMs(), TimeUnit.MILLISECONDS);
            return ToolResult.ok(output, (System.nanoTime() - start) / 1_000_000);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolResult.fail("工具调用超时（>" + properties.toolTimeoutMs() + "ms）",
                    (System.nanoTime() - start) / 1_000_000);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return ToolResult.fail(cause.getClass().getSimpleName() + ": " + cause.getMessage(),
                    (System.nanoTime() - start) / 1_000_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail("工具调用被中断", (System.nanoTime() - start) / 1_000_000);
        }
    }

    /** 枚举字段白名单校验：只查「字段在且取值不合法」，字段缺失交给 missingFields 处理。 */
    private List<String> invalidEnumValues(Map<String, Object> output, Map<String, List<String>> allowed) {
        if (output == null || allowed.isEmpty()) {
            return List.of();
        }
        List<String> invalid = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : allowed.entrySet()) {
            Object value = output.get(entry.getKey());
            if (value != null && !entry.getValue().contains(String.valueOf(value))) {
                invalid.add(entry.getKey() + "=" + value);
            }
        }
        return invalid;
    }

    /**
     * 数值溯源：output 里的每个数字都要能在某次成功工具返回里找到近似相等的数字。
     * 找不到就说明模型编了一个数——结构化输出校验只管「字段在不在」，管不了「数字真假」。
     */
    private List<String> untraceableNumbers(Map<String, Object> output, List<AgentStep> steps) {
        if (output == null || output.isEmpty()) {
            return List.of();
        }
        Set<Double> source = new HashSet<>();
        for (AgentStep step : steps) {
            if ("TOOL_CALL".equals(step.type()) && step.ok() && step.output() != null) {
                try {
                    collectNumbers(objectMapper.readTree(step.output()), source);
                } catch (Exception ignored) {
                    // 非 JSON 工具返回不参与数值溯源
                }
            }
        }
        List<String> untraceable = new ArrayList<>();
        collectUntraceable(objectMapper.valueToTree(output), source, untraceable);
        return untraceable;
    }

    private void collectNumbers(JsonNode node, Set<Double> sink) {
        if (node == null) {
            return;
        }
        if (node.isNumber()) {
            sink.add(node.doubleValue());
        } else if (node.isObject() || node.isArray()) {
            node.forEach(child -> collectNumbers(child, sink));
        }
    }

    private void collectUntraceable(JsonNode node, Set<Double> source, List<String> untraceable) {
        if (node == null) {
            return;
        }
        if (node.isNumber()) {
            double value = node.doubleValue();
            boolean found = source.stream().anyMatch(s -> closeTo(s, value));
            if (!found) {
                untraceable.add(node.asText());
            }
        } else if (node.isObject() || node.isArray()) {
            node.forEach(child -> collectUntraceable(child, source, untraceable));
        }
    }

    /** 相对容差比较：金额量级从 0.021 到千万不等，固定容差会误伤，用相对误差。 */
    private boolean closeTo(double a, double b) {
        double scale = Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
        return Math.abs(a - b) <= 1e-9 * scale;
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private JsonNode toJsonNode(Map<String, Object> value) {
        return value == null ? objectMapper.nullNode() : objectMapper.valueToTree(value);
    }
}
