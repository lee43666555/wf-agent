# 面试讲稿：AgentRuntime 循环与护栏

> 目标：把 wf-agent 的运行时，讲成一段有逻辑、经得起追问的面试答案。
> 主线只有一条：**一次调用怎么跑 → 怎么防止模型失控 → 怎么保证结论可信 → 怎么可回放**。
>
> 每个问题按「面试官可能怎么问 → 回答要点 → 对应代码证据」组织。

## 开场一句话（先立主线）

> 我们三个 Agent 共用一套运行时，模型只负责「规划和表述」——决定下一步查什么、结论怎么写；
> **计算、阈值判断、规则命中、数值溯源，全部由代码兜底**。所以我讲循环的时候，
> 核心不是「怎么调模型」，而是「模型不可信时，代码怎么拦住它」。

这句话把「模型 vs 代码」的分工先立住，后面每个问题都能回到这条线上。

---

## Q1：你们 agent 的执行循环是怎么写的？

**回答要点**：一个标准 ReAct 循环，但每一步都带护栏。

```
for (index = 1; index <= maxSteps; index++) {
    decision = model.decide(spec, context)   // ① 调模型，返回一个 JSON
    if (decision 是 Finish) { ...过护栏、出结论... }   // ④ 结束
    // ③ 否则是 CallTool：重复拦截 → 白名单 → 入参校验 → 执行(超时) → 回填 → 回到 ①
}
```

- 模型每步只输出**一个受约束 JSON**（`action/tool/arguments/thought`），只有两种动作：`CALL_TOOL` 或 `FINISH`。
- 这是 `ModelDecision` 这个 sealed interface 强制的——**模型没有第三种自由**。

**代码证据**：`AgentRuntime.run()` 的 for 循环、`common/model/ModelDecision.java`（sealed，只有两个 record）。

**追问应对**：「为什么不用框架自带的循环？」——框架的循环你只能配参数，插不进「数值溯源」这种业务护栏；
自己写 `for` 循环，每一处拦截点都是我们的业务规则。

---

## Q2：怎么防止模型无限调用、烧钱烧到天荒地老？

**回答要点**：四个硬约束，全部在**代码**里，不是写在提示词里。

| 参数 | 默认 | 作用 |
|---|---|---|
| `maxSteps` | 8 | 步数上限，到了就 DEGRADED 返回 |
| `maxRepeatPerTool` | 1 | 同一工具 + 同一参数第 2 次就拦截（模型原地打转的典型症状） |
| `toolTimeoutMs` | 15000 | 单步超时，独立线程执行，超时判失败 |
| `maxRetry` | 1 | 字段缺失只纠正一轮 |

**关键话术**：

> 提示词是「建议」，代码是「强制」。模型可以无视「最多调 8 次」这句话继续调第 9 次，
> 但它没法绕过 for 循环的 `limit`。金融场景里，「它应该会停下来」和「它一定会停下来」是两回事，
> 只有后者能写进验收标准。

**代码证据**：`RuntimeProperties.java`（`maxSteps=8 / maxRepeatPerTool=1 / maxRetry=1 / toolTimeoutMs=15000`）、
`AgentRuntime.invokeWithTimeout()`（独立线程 + `future.get(timeout)`）。

**追问应对**：「重复调用怎么发现的？」——把「工具名 + 规范化参数」拼成 fingerprint 计数，
参数排序后再序列化，保证「同样的参数不同顺序」也被判为同一次调用（`AgentRuntime.canonical()`）。

---

## Q3：模型输出你们怎么校验、怎么防幻觉？

**回答要点**：四道闸，任何一道不过就 `DEGRADED`，绝不返回一个「看起来完整」的猜测。

1. **必填字段**：`requiredFields` 缺了 → 先让模型补一轮 → 还缺就 DEGRADED。
2. **枚举白名单**：`decision` 只能取声明的 5 个值，模型自创「数据缺失」这种词直接判非法。
3. **数值溯源**（最硬的一条）：**output 里每个数字都要能在某次成功工具返回里找到**，找不到就是编造 → DEGRADED。
4. **数据支撑**：一次工具都没成功，字段再全也不准标 `OK`。

**关键话术**：

> 结构化输出校验只管「字段在不在」，管不了「数字真假」。模型编的数往往**长得和真的一样**，
> 因为它符合 Schema。所以我又加了一条数值溯源——每个数字必须能在成功取数里找到出处。
> 找不到，就降级返回并写明缺什么，业务方看到「数据缺失」会去补数据；
> 看到「结论完整」就直接用了，后者的代价更大。

**代码证据**：`AgentRuntime.run()` 的 Finish 分支——
`missingFields()` / `invalidEnumValues()` / `untraceableNumbers()` / `lacksDataSupport`。

**追问应对**：「为什么降级而不是重试？」——字段缺失最多纠一轮（`maxRetry=1`），
第一轮漏字段通常是没理解任务，再问也补不齐；多给几轮只是把一次错误拖成三次调用。

---

## Q4：为什么不用各家原生 function calling，而是约束 JSON？

**回答要点**：三条理由，按这个顺序讲（都在 `SpringAiAgentModel` 类注释里）：

1. **跨模型行为一致**：金融客户不会只接一家模型（DeepSeek / 通义 / 私有化都有）。
   各家原生 function calling 的报文、并行语义、参数纠错都不一样，换模型就要重测。
   受约束 JSON 只有一个解析器，换模型只影响「它愿不愿意按格式输出」，不影响调用链。
2. **每步可落 Trace、可回放、可断言**：原生 function calling 的调用意图藏在厂商专有结构里，
   落库是一段不可读 JSON；约束 JSON 的 `action/tool/arguments/thought` 都是自己的字段，
   能直接进 `agent_trace_step` 表，审计时逐字回放「模型当时为什么这么选」。
3. **失败可降级**：输出不是合法 JSON、或 action 不认识，这里直接产出一个空 output 的 FINISH，
   交给运行时按「必填字段缺失」判 DEGRADED。宁可「数据缺失」，不让模型硬凑。

**关键话术（认代价，显得不浮夸）**：

> 代价也认：比原生多一层解析，还要处理模型把 JSON 包在 Markdown 代码块里的情况（`stripFence()`）；
> 另外丢了原生并行工具调用的能力，一次只能推进一步。但对排查场景（串行取数、每步要看上一步结果）
> 这个代价可以接受。

**代码证据**：`SpringAiAgentModel.java`（类注释 + `systemPrompt()` 里的 JSON 契约 + `parse()` / `stripFence()`）。

---

## Q5：可观测性 / 可回放怎么做？

**回答要点**：不是打日志，是**结构化 Trace 落库**，能按 traceId 逐字回放。

- 每次运行产生 `AgentResult`，里面是 `List<AgentStep>`；每一步都记
  `type / toolName / arguments / output / ok / error / thought / costMs`。
- 接口 `TraceRecorder`（`record / find / recent`）有两个实现：
  - `InMemoryTraceRecorder`：内存环形 200 条，调试用，重启即丢。
  - `JdbcTraceRecorder`：落 `agent_trace` + `agent_trace_step`，生产用，可回放。

**关键话术**：

> 内存实现只能回答「刚才那次跑成什么样」，落库实现能回答
> **「上周三这笔拒贷，模型当时看到的是哪几行数据、给的理由原文是什么」**——后一个才是审计真正会问的。

> 还有一个细节：Trace 写失败**不能把业务结论一起带崩**——结论已经算出来了，这里降级为日志告警，
> 宁可丢一条 Trace，不可丢一次结论（`JdbcTraceRecorder.record()` 的 catch 块）。

**代码证据**：`common/trace/TraceRecorder.java`、`common/trace/InMemoryTraceRecorder.java`、
`app/trace/JdbcTraceRecorder.java`。

**追问应对**：「thought 为什么单独存？」——模型每步的「理由原文」是排查争议的第一现场，
工具返回原文不截断，因为截断就失去复核价值。

---

## Q6：模型不可用 / 失败怎么降级？

**回答要点**：三态结果 + 「降级比硬凑好」的兜底原则。

- `AgentResult.Status`：`OK`（正常收敛）/ `DEGRADED`（步数用尽、字段缺失、无数据支撑）/
  `FAILED`（模型调用本身失败）。
- 模型 `decide()` 抛异常 → 直接 `FAILED`，返回「模型不可用，未产出结论」，绝不硬凑。
- 字段缺失 / 无依据 / 数值编造 → `DEGRADED` 并写明「缺什么」。

**关键话术**：

> 我们把「数据缺失」和「结论完整」分得很开：业务方看到前者会去补数据，看到后者就直接用了，
> 所以**让模型硬凑一个像真的答案，代价比如实说「缺失」大得多**。

**代码证据**：`AgentResult.java`（Status 枚举）、`AgentRuntime.run()` 的异常 catch 与降级分支。

---

## 收尾一句（把六个问题串起来）

> 一句话总结我们这套运行时：**ReAct 循环是骨架，四个硬约束管「能不能停下来」，
> 四道输出护栏管「结论能不能信」，结构化 Trace 管「事后能不能查」。**
> 模型只是其中的「决策器」，真正保证可信的是外面这层代码。

---

## 附：最容易被打的两个点（提前备好答案）

1. **「为什么不用 LangChain / Spring AI 的 Agent 抽象？」**
   答：框架的循环能配参数，但插不进「数值溯源」这种业务护栏；而且我们要的是「每步都能回放」，
   自写 for 循环里每一处拦截点都是我们自己的业务规则，审计时能讲清楚。

2. **「这样写是不是放弃了原生并行工具调用？」**
   答：是，主动放弃。排查场景是串行取数（每步要看上一步结果），并行反而增加不确定性；
   而且原生并行调用的报文各家不一，破坏「跨模型一致」和「可回放」这两条底线。收益换不来这两条。
