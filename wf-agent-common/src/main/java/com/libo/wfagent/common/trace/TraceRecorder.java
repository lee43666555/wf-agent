package com.libo.wfagent.common.trace;

import com.libo.wfagent.common.runtime.AgentResult;

import java.util.List;
import java.util.Optional;

/**
 * Trace 记录器。每条结论都要能按 traceId 回放，这是金融场景的底线要求。
 * <p>
 * 当前实现放内存，接生产时换成落库（表结构见 README），接口不变。
 */
public interface TraceRecorder {

    void record(AgentResult result);

    Optional<AgentResult> find(String traceId);

    List<AgentResult> recent(int limit);
}
