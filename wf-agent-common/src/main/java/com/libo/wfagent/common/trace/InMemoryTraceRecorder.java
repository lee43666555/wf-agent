package com.libo.wfagent.common.trace;

import com.libo.wfagent.common.runtime.AgentResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 Trace：保留最近 {@code capacity} 条，进程重启即丢。
 * <p>
 * <b>适用边界要说清楚，别在面试里含糊。</b>
 * 它只适合两种情况：单模块调试（单独起 risk-review / feature-analysis / ecl 之一），
 * 以及单元测试。一旦进入「结论要能事后复核」的场景，必须换成落库实现——
 * wf-agent-app 里的 {@code JdbcTraceRecorder}，对应表 agent_trace / agent_trace_step。
 * <p>
 * 切换方式：{@code wf-agent.trace.mode=memory|jdbc}。默认 memory，是为了让三个独立
 * 模块在没有数据库连接池时也能启动；统一入口 wf-agent-app 的 application.yml 里
 * 显式配成 jdbc。
 * <p>
 * 这层做成接口而不是直接用 Map，唯一原因是：Trace 的存储形态会随部署环境变
 * （本地内存 → 单机 SQLite → 生产 MySQL/ClickHouse），但「按 traceId 回放」这个语义
 * 不能变。运行时 {@code AgentRuntime} 只依赖接口，换实现不动一行业务代码。
 */
@Component
@ConditionalOnProperty(name = "wf-agent.trace.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryTraceRecorder implements TraceRecorder {

    /**
     * 环形缓冲容量。取 200 是因为调试时通常只看最近几十次。
     * 这个值刻意不做成配置项——内存实现本来就是临时方案，参数化反而会让人误以为它能上生产。
     */
    private static final int CAPACITY = 200;

    private final Map<String, AgentResult> byTraceId = new ConcurrentHashMap<>();
    private final Deque<String> order = new ArrayDeque<>();

    @Override
    public synchronized void record(AgentResult result) {
        byTraceId.put(result.traceId(), result);
        order.addLast(result.traceId());
        // 超出容量时按写入顺序淘汰最老的一条，避免调试跑久了堆内存持续上涨
        while (order.size() > CAPACITY) {
            String evicted = order.pollFirst();
            if (evicted != null) {
                byTraceId.remove(evicted);
            }
        }
    }

    @Override
    public Optional<AgentResult> find(String traceId) {
        return Optional.ofNullable(byTraceId.get(traceId));
    }

    @Override
    public synchronized List<AgentResult> recent(int limit) {
        return order.stream()
                .skip(Math.max(0, order.size() - limit))
                .map(byTraceId::get)
                .filter(Objects::nonNull)
                .toList();
    }
}
