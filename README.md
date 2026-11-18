# wf-agent

风控域 Agent 应用骨架：三个各自独立的 Agent（风控排查 / 特征分析 / ECL 减值），共用一套运行时。

设计前提只有一条：**模型只做规划和表述，数值计算、阈值判断、规则命中全部由代码完成。**
运行时用代码强制这条前提，不指望提示词。

---

## 一、模块划分

| 模块 | 职责 | 内部依赖 |
|---|---|---|
| `wf-agent-common` | Agent 运行时：ReAct 循环、模型抽象、工具抽象、Trace、输出校验 | 无 |
| `wf-agent-domain` | 领域模型 + 只读数据访问（SQLite 模拟五类内部系统） | 无 |
| `wf-agent-tools` | 只读工具集（`@Tool` 方法），MCP Server 与三个 Agent 共用同一份实现 | common, domain |
| `wf-agent-mcp-server` | 把工具集暴露为 MCP 服务（端口 8081） | tools |
| `wf-agent-risk-review` | 排查 Agent（端口 8082） | common, domain, tools |
| `wf-agent-feature-analysis` | 特征分析 Agent（端口 8083） | common, domain, tools |
| `wf-agent-ecl` | ECL 减值分析 Agent（端口 8084） | common, domain, tools |
| `wf-agent-app` | 统一入口，聚合三个 Agent（端口 8080） | 三个 agent |

依赖方向是单向的，`common` 不认识 `domain`，`domain` 不认识任何 AI 库：

```
common ──────┐
             ├──> tools ──> mcp-server
domain ──────┘        │
                      ├──> risk-review ─────┐
                      ├──> feature-analysis ├──> app
                      └──> ecl ─────────────┘
```

**为什么单独拆 tools**：MCP Server 和三个 Agent 需要的是同一批查询能力。
如果工具写在 mcp-server 里，Agent 要么依赖 mcp-server（依赖倒挂），要么各写一遍（三份重复）。
拆出来之后，同一份 `@Tool` 方法既能被 MCP 注册，也能被进程内直接调用，工具名、描述、参数 Schema 完全一致。

---

## 二、跑起来

要求 JDK 17 + Maven 3.6.3+。

```bash
export JAVA_HOME=/path/to/jdk-17

mvn clean install -DskipTests

# 统一入口，三个 Agent 都装在这个进程里
java -jar wf-agent-app/target/wf-agent-app-1.0.0-SNAPSHOT.jar
```

默认端口 8080。启动后先确认装配：

```bash
curl http://localhost:8080/api/agents
```

应该看到三个 Agent 各自的工具白名单和必填输出字段。

### 三个 Agent 各跑一次

```bash
# 1. 风控排查：给定进件号，还原审批过程
curl "http://localhost:8080/api/risk-review?applicationId=APPL20260003"

# 2. 特征分析：自然语言提问
curl --get --data-urlencode "question=分析近3月多头借贷申请次数在普通客群上的坏账率分布" \
     "http://localhost:8080/api/feature-analysis"

# 3. ECL 减值：两期对比 + 量价拆分
curl "http://localhost:8080/api/ecl-analysis?periodA=202608&periodB=202609"

# 看最近几次运行的 Trace
curl "http://localhost:8080/api/trace?limit=10"
```

### 单独启动某一个 Agent

调试时更方便，改哪个跑哪个：

```bash
mvn -pl wf-agent-risk-review spring-boot:run
mvn -pl wf-agent-feature-analysis spring-boot:run
mvn -pl wf-agent-ecl spring-boot:run
```

### 单独启动 MCP 工具服务

```bash
java -jar wf-agent-mcp-server/target/wf-agent-mcp-server-1.0.0-SNAPSHOT.jar
```

端口 8081，SSE 传输。`GET /sse` 会返回一个 `sessionId`，后续 JSON-RPC 请求发到
`POST /mcp/message?sessionId=xxx`。已实测 `initialize` 与 `tools/list` 返回 9 个工具。

---

## 三、两种模式

两个独立开关，可以自由组合。

### 模型：`wf-agent.model.mode`

| 值 | 行为 | 用途 |
|---|---|---|
| `scripted`（默认） | 走预置剧本，不调用任何外部模型 | 没有 API Key 也能跑通链路、看 Trace、做回归 |
| `spring-ai` | 真实调用模型 | 验证真实取数路径 |

接真实模型只需要改三个环境变量，走的是 OpenAI 兼容协议，所以内部统一网关、DeepSeek、
通义、Ollama 都能用，不用改代码：

```bash
export WF_AGENT_MODEL_BASE_URL=https://api.deepseek.com
export WF_AGENT_MODEL_API_KEY=sk-xxxx
export WF_AGENT_MODEL_NAME=deepseek-chat

java -jar wf-agent-app/target/*.jar --wf-agent.model.mode=spring-ai
```

### 工具：`wf-agent.tools.mode`

| 值 | 行为 |
|---|---|
| `local`（默认） | 进程内直连 SQLite，链路短，开发调试用 |
| `mcp` | 通过 MCP 协议访问独立的工具服务 |

两种模式都实测过。启用 MCP 模式：

```bash
# 1. 先起工具服务（端口 8081）
java -jar wf-agent-mcp-server/target/wf-agent-mcp-server-1.0.0-SNAPSHOT.jar

# 2. 再起应用，切成 mcp profile
WF_AGENT_MCP_URL=http://localhost:8081 \
  java -jar wf-agent-app/target/wf-agent-app-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=mcp
```

切换只改配置和 profile，Agent 代码一行不动——`ToolRegistry` 这层抽象就是为这个存在。

**实测结论：**

| 场景 | 实际表现 |
|---|---|
| MCP 在线 | 三个 Agent 的工具全部从远端拉取，工具名与本地模式完全一致；单次查询约 45ms（本地约 5ms） |
| MCP 断开 | 工具调用失败 → `status=DEGRADED`、`decision=DATA_UNAVAILABLE`，结论写「这是数据服务不可用，不代表该进件不存在」 |
| MCP 恢复、客户端未重启 | **仍然失败**。MCP SSE 是长连接，断后客户端不会自动重连 |
| 重启 Agent 应用 | 恢复，重新拉到 9 个工具 |

第三条是要记住的运维事实：**数据工具服务重启后，Agent 进程必须跟着重启**，否则一直拿着
一条死连接。骨架没做自动重连，生产环境需要在上层加健康检查与重连策略。

另外一处混合形态：ECL 的两个计算工具（`compute_ecl_summary`、`compute_ecl_delta`）
是模块自带的，不走 MCP——它算的是业务逻辑，依赖的是本地代码而不是数据服务。
其余查询类工具全部走 MCP。

---

## 四、运行时做了什么

`AgentRuntime` 是所有 Agent 共用的一条循环，全部约束由代码强制：

| 约束 | 实现 | 为什么必须在代码里 |
|---|---|---|
| 步数上限 | `maxSteps`，超出即降级返回部分结果 | 模型会无限取数，提示词管不住 |
| 重复调用拦截 | 工具名 + 排序后的参数做指纹，超过 `maxRepeatPerTool` 直接拦 | 模型反复查同一个数是常见病 |
| 工具白名单 | 只能调 `AgentSpec` 里声明过的工具，越界不执行 | 最小权限，防止它去查不该查的表 |
| 工具调用超时 | 独立线程执行，超过 `toolTimeoutMs` 判失败降级 | MCP / 下游 hang 住会把整个请求拖死 |
| 输出校验 | 必填字段缺失先要求补齐（`maxRetry`），仍缺则降级 | 宁可返回「数据缺失」，也不让模型硬凑 |
| 枚举白名单 | 字段取值必须落在 `AgentSpec.allowedValues` 声明的取值域 | 结构化输出只管「字段在不在」，管不了「取值合不合法」 |
| 数值溯源 | `output` 里的每个数字都要能在成功工具返回里找到 | 模型可以成功调一次工具然后编一堆数字，仍被判 `OK` |
| 数据支撑校验 | 一次工具调用都没成功时，不允许标记为 `OK` | 没有数据支撑的结论，是最难被发现的假成功 |

每次运行产出 `AgentResult`，包含 `status`（OK / DEGRADED / FAILED）、结构化 `output`、
完整 `steps` 和 `warnings`。`steps` 里每一步都留存模型给的理由原文和工具返回原文，
按 `traceId` 可以完整回放。

`TraceRecorder` 当前是内存实现（保留最近 200 条）。要落库只需换一个实现，
`AgentResult` 的结构可以直接映射成一张表。

---

## 五、三个 Agent

| Agent | 输入 | 工具 | 输出要点 |
|---|---|---|---|
| `risk-review` | 进件号 | 进件、决策流水、变量快照、策略版本、外部数据、同客群参照 | 结论、命中规则、变量证据、数据完整度 |
| `feature-analysis` | 自然语言 | 变量字典、变量分箱指标 | 查询计划（变量/账期/客群/指标）、分箱分布、结论 |
| `ecl-analysis` | 两个账期 | 减值参数、期次汇总、两期差额 | 分客群差额、量价拆分、前三驱动项 |

### 排查 Agent 的分支

剧本覆盖两条路：进件存在时走完整取数，**进件不存在时第二步就收场，明确写「查无此件，未做任何推断」**。
第二条分支是刻意设计的——错误数据下不产结论，比产出结论重要。

### 特征分析为什么不给模型写 SQL 的能力

模型只输出四要素的查询计划（变量 / 账期 / 客群 / 指标），由平台编译执行。
理由：风控口径一旦被模型改写，后面所有策略结论都不可信。多写一层 DSL，换口径可控。

### ECL 的量价拆分

`ECL = EAD × PD × LGD`，两期差额拆成三块：

```
规模效应   = (EAD₁ - EAD₀) × PD₀ × LGD₀
风险效应   = EAD₁ × (PD₁ - PD₀) × LGD₀
损失率效应 = EAD₁ × PD₁ × (LGD₁ - LGD₀)
```

三项之和恰好等于 `ECL₁ - ECL₀`，这一点由 `EclCalculatorTest` 断言守住。
业务方问「为什么这个月多提了」，答案能落到是规模涨了还是风险恶化，而不是「模型跑出来就是这个数」。

---

## 六、数据

`wf-agent-domain/src/main/resources/db/`：

- `schema.sql`：7 张表，对应进件、决策流水、变量快照、策略版本、外部数据、减值参数、变量分箱指标
- `data.sql`：5 笔进件的演示数据，主案例是 `APPL20260003`（多头借贷被拒）

每次启动都会执行 `schema.sql` 与 `data.sql`。建表用 `IF NOT EXISTS`、插数用 `INSERT OR IGNORE`，
重复启动不会产生重复数据。**要重置数据，删掉工作目录下的 `wf-agent.db` 即可。**

> 脚本模式的输出是对着这份数据写死的，验证其他进件请切到 `spring-ai` 模式。

---

## 七、测试

```bash
mvn test
```

现在共 **24 个测试**，分两层：

**第一层：单元测试（22 个）——守住运行时、算法与胶水层**

- `AgentRuntimeTest`（10 个）：重复调用拦截、未声明工具拒绝、缺参数不发起调用、
  必填字段缺失降级、**无成功取数时禁止标记 OK**、正常收敛、模型异常上报、
  **工具调用超时**、**枚举取值不合法降级**、**数值无法溯源降级**
- `EclCalculatorTest`（4 个）：量价拆分三项之和等于总差额（含损失率变化场景）、
  分客群差额之和等于总额差、**新增/消失段不被静默丢弃**
- `SpringAiAgentModelTest`（3 个）：围栏剥离、CALL_TOOL/FINISH 解析、非法 action/JSON 降级
- `McpToolRegistryTest`（3 个）：ToolSpec 暴露、子集过滤、调用转发与失败包装
- `JdbcTraceRecorderTest`（2 个）：落库回放、重跑幂等

**第二层：端到端回归（2 个）——守住装配之后的整体行为**

- `RegressionCaseTest`：跑完 10 条用例，要求全通过且每条都能按 `traceId` 回放；
  另有一条校验用例清单本身（编号唯一、三个 Agent 全覆盖）

两层的差别要说清楚：单测用 mock 的模型和工具，验的是「代码逻辑对不对」；
回归测试起完整 Spring 上下文、连真实 SQLite，验的是「装起来之后还对不对」。
后者才能发现「Bean 少注了一个」「Trace 没落库」这类只在启动后才暴露的问题。

> 回归测试用的是 `scripted` 模式，结果确定，适合做 CI 门禁。
> **模型驱动路径的通过率不能拿这个数字充数**，必须切 `spring-ai` 模式用
> `GET /api/regression` 单独跑（见第九节）。

---

## 八、Trace 与可回放

**结论必须能事后复核，这是金融场景的底线，不是加分项。** 所以 Trace 有两种实现，
由 `wf-agent.trace.mode` 切换：

| 值 | 实现 | 存哪 | 适用 |
|---|---|---|---|
| `memory`（默认） | `InMemoryTraceRecorder` | 进程内环形缓冲，200 条 | 单模块调试、单测 |
| `jdbc` | `JdbcTraceRecorder` | SQLite `agent_trace` / `agent_trace_step` | 统一入口 `wf-agent-app`，默认开 |

落库后的问题与答法：

```bash
# 最近 10 次运行
curl "http://localhost:8080/api/trace?limit=10"

# 按 traceId 完整回放：每一步调了什么工具、入参、工具返回原文、模型给的理由
curl "http://localhost:8080/api/trace/detail?traceId=<id>"

# 直接查库
sqlite3 wf-agent.db "select agent_name,status,step_count,total_cost_ms,created_at \
                     from agent_trace order by rowid desc limit 5;"
```

两张表的分工：

- `agent_trace`：一次运行的头部——谁跑的、什么状态、几步、多久、结论是什么；
- `agent_trace_step`：每一步明细——工具名、入参、**工具返回原文（不截断）**、模型理由原文。

拆两张表而不是塞一个 JSON 字段，是为了能在 SQL 层问出「哪次结论没有数据支撑」
「模型重复调过哪个工具」这类问题，而不是靠应用层反序列化全表。

> 口径提醒：`memory` 模式重启即丢，**不能拿它回答「上周那笔拒贷当时看到了什么」**。
> 简历里说「Trace 全链路落库」时，指的必须是 `jdbc` 这一套。

## 九、回归用例与跑批

10 条用例，`RegressionCases` 里逐条声明。**它守的不是业务数字，是运行时约束**——
POC 阶段数据是造的，业务数字本来就不该当基准；真正会出事的是下面四类：

| 类别 | 守什么 | 对应用例 |
|---|---|---|
| 分支走错 | 查无此件 / 取数失败 / 正常，三条路不能混 | RR-03 |
| 结构残缺 | 必填字段与关键证据不能缺 | RR-02、RR-05、FA-01 |
| 无法复核 | 每条结果都要能按 traceId 取回 | 全局不变量 |
| 算错不自知 | 分项之和必须等于总额（两层） | ECL-01、ECL-02、ECL-03 |

另外两条**全局不变量**对所有用例生效，由 `RegressionService` 统一检查，不写进单条用例：

1. **可回放**：跑完必须能用 `traceId` 从 `TraceRecorder` 原样取回；
2. **步数不越界**：执行步数不得超过该 Agent 声明的 `maxSteps`。

跑法：

```bash
# 1) 剧本模式（确定性，CI 门禁）。mvn test 里已经接了 RegressionCaseTest
mvn test

# 2) 真实模型模式（这才是简历里要写的数字）
java -jar wf-agent-app/target/*.jar \
     --wf-agent.model.mode=spring-ai \
     --wf-agent.model.api-key=$WF_AGENT_MODEL_API_KEY

curl -s "http://localhost:8080/api/regression" | jq '{model, total, passed, p95Ms, traceReplayable}'
curl -s "http://localhost:8080/api/regression/cases" | jq
```

返回里的 `model` 字段必须一起看：

```
scripted   → 剧本跑通，证明链路与约束没问题，但不能说「模型规划跑通」
spring-ai  → 模型驱动跑通，`passed` 和 `p95Ms` 才是简历里那两个数
```

> `p95Ms` 用最近秩法算。10 条用例的 P95 实际就是「第二慢的那条」，
> 样本少时不要把它说成统计意义上的 P95，讲「10 条中的第 2 慢」更稳。

### 实测基线（2026-10-29）

| 指标 | 剧本模式 `scripted` | 真实模型 `spring-ai`（DeepSeek deepseek-chat） |
|---|---|---|
| 通过 | 10 / 10 | 10 / 10 |
| p50 | 5 ms | 5.6 s |
| p95 | 56 ms | 14.7 s |
| Trace 可回放 | 10 / 10 | 10 / 10 |

**首轮真实模型只过了 8/10**，两个失败都指向真实的提示词缺陷，不是用例写错：

- `RR-03`：查无此件时模型输出 `decision="数据缺失"`。根因是提示词里的取值域只写了
  `PASS / REJECT / MANUAL`，没写 `NOT_FOUND`，模型就自己造了一个。
  **结构化输出只约束「字段在不在」，不约束「取值合不合法」**——所以现在 `decision` 的取值域
  落在两层：提示词给模型看建议，`AgentSpec.allowedValues` 做代码层枚举白名单兜底，换模型也不会再犯。
- `FA-01`：`queryPlan.period` 为空。模型调工具时传了空账期（等于全部账期），
  回到 queryPlan 也照抄空串。混期分析会让分箱口径不可比，属于业务错误，不是格式错误。

补完两处提示词后 10/10。这条链路本身就是面试素材：**改提示词 → 跑回归 → 8/10 变 10/10**，
比说「我很重视质量」有用得多。

### 工具数量口径（三个数字都对，别混）

| 说法 | 数字 | 出处 |
|---|---|---|
| 业务查询类别 | 5 类 | 进件、决策流水、变量快照、策略版本、外部数据 |
| 只读工具总数 | 9 个 | `RiskQueryTools`，也是 MCP `tools/list` 的返回数 |
| 排查 Agent 白名单 | 6 个 | 5 类 + `query_peer_snapshot`（同客群参照） |

ECL 的两个计算工具（`compute_ecl_summary` / `compute_ecl_delta`）**不在这里**：
它们算的是业务逻辑、依赖本地代码而不是数据服务，所以不走 MCP。

## 十、加一个新 Agent

1. 复制 `wf-agent-risk-review` 模块，改 `artifactId` 和包名；
2. 写 `XxxAgent`：用 `AgentSpec.builder(...)` 声明提示词、工具白名单、必填字段，有枚举字段再声明 `allowedValues`；
3. 写 `XxxScript implements AgentScriptProvider`：声明取数顺序和缺失数据时的收场方式；
4. 加 `Controller` 和 `Application`（端口挑一个没用的）；
5. 父 pom 加 `<module>`，`wf-agent-app` 加依赖。

运行时、Trace、校验、工具都不用重写。

---

## 十一、几个设计取舍

**不用各家原生的 function calling，改成受约束 JSON。**
每一步让模型只输出一个 JSON（`{"action":"CALL_TOOL",...}` 或 `{"action":"FINISH",...}`）。
好处是切换内部网关下的不同模型时行为一致，每一步都能落 Trace、能回放、能断言。
代价是比原生 tool calling 多一层解析，需要处理模型把 JSON 包在 Markdown 代码块里的情况（已处理）。

**剧本模式不是玩具。**
它代表「正确解法」，改动提示词后可以比对实际取数路径有没有偏离预期。
三个 Agent 的剧本合起来就是最初的一批回归用例。

**降级比硬凑好。**
字段缺失、步数用尽、模型不可用时，宁可返回 `DEGRADED` 并说明缺什么，
也不让模型用推测补全。金融场景里，一个说「不知道」的 Agent 比一个自信编造的 Agent 安全。

**「取数失败」和「查无数据」必须分开。**
这两件事在接口层面长得很像，都表现为拿不到数据，但业务含义完全相反：前者是系统故障，
后者是正常结果。混淆的代价是把一次服务宕机说成「该客户不存在」。
排查 Agent 的剧本对这两条分别处理：失败时输出 `DATA_UNAVAILABLE` 并明确写
「不代表该进件不存在」，查无时才输出 `NOT_FOUND`。

---

## 十二、注意

如果所在环境注入了 `SERVER__PORT` 之类的环境变量（Spring 的 relaxed binding 会覆盖
`server.port`），启动时用命令行参数显式指定端口优先级最高：

```bash
java -jar wf-agent-app/target/*.jar --server.port=8080
```
