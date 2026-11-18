# Agent 生命周期 ↔ 代码结构映射

> 本项目三个 Agent（风控排查 / 特征分析 / ECL 减值）共用一套运行时。核心只有一个文件：
> `AgentRuntime.run()`，它是一个标准的 ReAct 循环；其余模块全是它的配套。
> 本文把「一次 Agent 调用的完整生命周期」的 7 个节点，逐一映射到具体代码位置。
>
> 设计前提（README 原话）：**模型只做规划和表述，数值计算、阈值判断、规则命中全部由代码完成。**

## 一、生命周期总览

一条主流水线（上 → 下）＋ 一条横切全程的观测线：

```
入口 → 上下文构造 → 循环（反复） → 护栏 → 持久化 → 返回
                            └──────── 观测（横切全程）────────┘
```

## 二、总对照表

| 生命周期节点 | 代码位置 | 干什么 |
|---|---|---|
| **0 入口** | 各 `*Controller` + `AgentRequest` | 收请求、生成 `traceId` |
| **1 上下文构造** | `AgentSpec`（静态）+ `AgentContext`（动态）+ `SpringAiAgentModel` | 系统提示 + 工具清单 + 输出契约 + 已执行步骤 + 纠错反馈，拼成 Prompt |
| **2 循环**（核心） | `AgentRuntime.run()` 的 `for` 循环 | ReAct：调模型 → 解析 →（工具/结论）→ 回填 → 再调 |
| ├ 调 LLM | `AgentModel.decide()` → `chatModel.call()` | 一次推理 |
| ├ 解析输出 | `ModelDecision`（sealed）+ `SpringAiAgentModel.parse()` | 判断是取数还是下结论 |
| ├ 工具白名单 | `spec.tools().stream().filter(...)` | 越界工具直接拒绝 |
| ├ 入参校验 | `AgentRuntime.missingParams()` | 按 `parametersSchema` 查必填项 |
| ├ 执行 + 超时 | `AgentRuntime.invokeWithTimeout()` | 独立线程 + `future.get(timeout)` |
| ├ 结果回填 | `context.withSteps()` | 步骤追加进上下文 |
| └ 循环保护 | `RuntimeProperties` | maxSteps / 重复拦截 / 单步超时 |
| **3 护栏** | `AgentRuntime.run()` 的 Finish 分支 | 必填字段 / 枚举白名单 / 数值溯源 / 数据支撑 |
| **4 持久化** | `JdbcTraceRecorder` | `agent_trace` + `agent_trace_step` 落库，可回放 |
| **5 返回** | `AgentResult` + `build()` | 先 `traceRecorder.record()` 再返回 |
| **横切·观测** | `TraceRecorder` + `AgentStep` + `costMs` | 每步 thought / 入参出参 / 耗时全落 Trace |

## 三、逐点展开

### 0 入口 —— 三个 Controller + traceId

- `wf-agent-risk-review` / `wf-agent-feature-analysis` / `wf-agent-ecl` 各自一个端口（8082/8083/8084），
  `wf-agent-app` 里的 `AgentCatalogController`（`/api/agents`、`/api/trace`）是统一入口（8080）。
- 关键在 `AgentRequest.of(input, traceId)`：**调用方可自带 traceId**，让 Agent 的 Trace 能和上游系统日志对齐。
  这就是「入口定位」的落地版——这里不是 sessionId，而是 traceId。

### 1 上下文构造 —— 拆成「静态 + 动态」两半

- **静态声明** `AgentSpec`：`systemPrompt`（角色 + 排查顺序 + 规则）+ `tools`（工具白名单）+
  `requiredFields`（输出必填字段）+ `allowedValues`（枚举白名单）+ `maxSteps`。
  三个 Agent 的差异全在这份声明里，运行时是同一套。
- **动态现场** `AgentContext`：`input`（用户输入）+ `steps`（已拿到的数据）+ `feedback`（上一轮纠错）。
  注释很关键——**「只放结构化事实，不放原始全量报文，避免上下文被单次大报文挤爆」**。
- **真正组装 Prompt** 在 `SpringAiAgentModel`：`systemPrompt()` 拼系统提示 + 工具清单 + 输出 JSON 契约；
  `userPrompt()` 拼当前任务 + 已执行步骤 + 纠错反馈。
- 工具清单是 `ToolSpec`（name/description/parametersSchema/invoker），`renderTools()` 把它们**拼成文本塞进提示词**，
  而不是原生 function calling（这个取舍见 `SpringAiAgentModel` 类注释）。

> ⚠️ 生命周期里的「记忆检索」这一步：**本项目没有长期记忆 / 向量库**，只有会话内累积的 `steps`。
> 这是刻意的——一次性排查任务不需要跨调用记忆。

### 2 循环 —— 整个 `AgentRuntime.run()`

教科书级 ReAct 循环，且生命周期里的「循环保护」全部落在代码（不是提示词）里：

```java
for (int index = 1; index <= limit; index++) {
    ModelDecision decision = model.decide(spec, context);   // ① 调 LLM
    if (decision instanceof Finish) { ... 退出 + 护栏 ... } // ④ 最终回答
    // ③ 工具调用分支：重复拦截 → 白名单 → 入参校验 → 执行(超时) → 回填
}
```

四个硬约束全在 `RuntimeProperties`：

| 参数 | 默认值 | 对应生命周期的 |
|---|---|---|
| `maxSteps` | 8 | 最大步数上限 |
| `maxRepeatPerTool` | 1 | 重复动作检测 |
| `toolTimeoutMs` | 15000 | 单步超时 |
| `maxRetry` | 1 | 字段缺失纠正轮次（护栏侧） |

工具执行用**独立线程 + `future.get(超时)`**，避免 MCP 或下游 hang 住拖死整个请求——这是「沙箱/环境执行」的轻量版。

### 3 护栏 —— 本项目最「金融」的地方

模型下结论（Finish）前过四道闸，任何一道不过就降级 `DEGRADED`（而不是 `OK`）：

1. **必填字段校验** `missingFields()`：`requiredFields` 缺了先让模型补齐一轮，还缺就 DEGRADED。
2. **枚举白名单** `invalidEnumValues()`：`decision` 只能取 `PASS/REJECT/MANUAL/NOT_FOUND/DATA_UNAVAILABLE`，
   模型自创「数据缺失」这类词直接判非法。
3. **数值溯源** `untraceableNumbers()`：**output 里每个数字都要能在某次成功工具返回里找到**，找不到就是编造 → DEGRADED。
4. **数据支撑** `lacksDataSupport`：一次工具都没成功，字段再全也不准标 OK。

> 对照通用生命周期的「安全校验（敏感词/越权）」：这里换成了金融场景更该防的
> **工具白名单 + 枚举白名单 + 数值溯源**。类注释那句话是精髓——**「降级比硬凑好」**，
> 宁可返回「数据缺失」，也不让模型编一个像真的答案。

### 4 持久化 —— 落库可回放

- `JdbcTraceRecorder` 把每次运行写进两张表：`agent_trace`（头部：status/output/narrative/warnings/步数/耗时）+
  `agent_trace_step`（明细：每步 type/toolName/arguments/output/ok/error/thought/costMs）。
- 关键设计：同 trace_id 用 `INSERT OR REPLACE` 覆盖头部、明细先删后插，保证重跑不残留旧步骤。
- `InMemoryTraceRecorder` 是内存版（环形 200 条），`wf-agent.trace.mode=memory|jdbc` 切换。

> ⚠️ 生命周期里的「检查点 / 断点续跑」：**本项目没有**。每次调用无状态（只带 input + traceId），
> 持久化只做 Trace 落库，不做跨调用状态恢复——排查任务不续跑。

### 5 返回 —— 先落 Trace 再返回

`build()` 里先 `traceRecorder.record(result)`，再返回 `AgentResult`。
返回体是 JSON（非流式，没有 SSE）。`AgentResult` 带着 `status/output/narrative/steps/warnings/totalCostMs` 完整产物。

### 横切 · 观测 —— 结构化 Trace，不是打日志

观测核心是 `TraceRecorder` 接口 + `AgentStep` 记录，做的是**结构化 Trace**（不是散落的 log）：

- `AgentStep.thought`：模型每步的**理由原文**——「排查争议时先看这一列」。
- `AgentStep.output/arguments`：工具入参出参原文，**不截断**，保证逐字回放。
- `costMs` / `totalCostMs`：每步和总耗时（延迟）。
- `JdbcTraceRecorder.find(traceId)` 能回答「上周这笔拒贷，模型当时看到哪几行数据、给的理由原文是什么」。

> ⚠️ 生命周期里的「token 用量 / 成本」：**本项目没有 token 计量**。有延迟（costMs）但没算 token 数 / 钱。

## 四、「做了 / 没做」对照（面试讲边界用）

| 生命周期子项 | 本项目 | 说明 |
|---|---|---|
| 上下文构造 | ✅ 做了 | AgentSpec + AgentContext + Prompt 组装 |
| 循环 + 循环保护 | ✅ 做了 | AgentRuntime + RuntimeProperties |
| 工具白名单 / 入参校验 | ✅ 做了 | 代码强制，不是提示词 |
| 输出护栏（反幻觉） | ✅ 做了且出彩 | 必填 / 枚举 / 数值溯源 / 数据支撑 |
| 观测 + 可回放 | ✅ 做了 | 结构化 Trace 落库 |
| 记忆检索 / 长期记忆 | ❌ 没做 | 只有会话内 steps，无跨调用记忆 |
| token 计量 / 成本 | ❌ 没做 | 只有 costMs 延迟 |
| 断点续跑 / 检查点 | ❌ 没做 | 无状态，每次独立 |
| SSE 流式返回 | ❌ 没做 | 非流式 JSON |
| 通用安全（敏感词/注入） | ⚠️ 换成金融版 | 白名单 + 溯源替代敏感词过滤 |

## 五、一句话总结（可背）

> 本项目 = **一个共享的 `AgentRuntime`（ReAct 循环 + 循环保护 + 四道输出护栏）+
> 一个可插拔的 `AgentModel`（真实模型 / 剧本模型）+ 一个结构化 `TraceRecorder`（观测 + 持久化）**；
> 三个业务 Agent 只各写一份 `AgentSpec` 声明差异。模型只负责「规划和表述」，
> 计算、阈值、规则命中、溯源全在代码里兜底——这就是「Agent = LLM + Harness」里 Harness 该有的样子。
