-- 风控域模拟数据（对应简历中「进件 / 决策流水 / 变量快照 / 策略版本 / 外部数据」五类查询）
-- 全部表只读使用：Agent 工具层只有 SELECT 权限

CREATE TABLE IF NOT EXISTS application (
    application_id TEXT PRIMARY KEY,
    customer_id    TEXT    NOT NULL,
    channel        TEXT    NOT NULL,
    product_code   TEXT    NOT NULL,
    apply_time     TEXT    NOT NULL,
    apply_amount   REAL    NOT NULL,
    decision       TEXT    NOT NULL, -- PASS / REJECT / MANUAL
    reject_code    TEXT,
    reject_desc    TEXT
);

CREATE TABLE IF NOT EXISTS decision_trace (
    trace_id         INTEGER PRIMARY KEY,
    application_id   TEXT NOT NULL,
    strategy_code    TEXT NOT NULL,
    strategy_version TEXT NOT NULL,
    rule_code        TEXT NOT NULL,
    rule_name        TEXT NOT NULL,
    hit              INTEGER NOT NULL, -- 1 命中 0 未命中
    score_delta      REAL,
    decided_at       TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS variable_snapshot (
    snapshot_id    INTEGER PRIMARY KEY,
    application_id TEXT NOT NULL,
    var_code       TEXT NOT NULL,
    var_name       TEXT NOT NULL,
    var_value      TEXT NOT NULL,
    value_type     TEXT NOT NULL, -- NUMBER / STRING / BOOLEAN
    threshold      TEXT,
    passed         INTEGER,       -- 1 通过 0 超限 NULL 无阈值
    calc_time      TEXT NOT NULL,
    batch_no       TEXT
);

CREATE TABLE IF NOT EXISTS strategy_version (
    strategy_code TEXT NOT NULL,
    version       TEXT NOT NULL,
    status        TEXT NOT NULL, -- ONLINE / GRAY / OFFLINE
    published_at  TEXT NOT NULL,
    owner         TEXT,
    remark        TEXT,
    PRIMARY KEY (strategy_code, version)
);

CREATE TABLE IF NOT EXISTS external_data (
    external_id    INTEGER PRIMARY KEY,
    application_id TEXT NOT NULL,
    provider       TEXT NOT NULL,
    product        TEXT NOT NULL,
    request_time   TEXT NOT NULL,
    cost_ms        INTEGER,
    response_json  TEXT NOT NULL
);

-- ECL 减值参数（两期，用于差异归因与可复算）
CREATE TABLE IF NOT EXISTS ecl_param (
    period         TEXT NOT NULL, -- 202608 / 202609
    stage          TEXT NOT NULL, -- STAGE1 / STAGE2 / STAGE3
    segment        TEXT NOT NULL, -- 客群
    pd             REAL NOT NULL,
    lgd            REAL NOT NULL,
    ead            REAL NOT NULL,
    balance        REAL NOT NULL,
    customer_count INTEGER NOT NULL,
    model_version  TEXT NOT NULL,
    PRIMARY KEY (period, stage, segment)
);

-- 变量分箱指标（特征分析 Agent 的查询目标）
CREATE TABLE IF NOT EXISTS variable_metric (
    var_code TEXT NOT NULL,
    period   TEXT NOT NULL,
    segment  TEXT NOT NULL,
    bucket   TEXT NOT NULL,
    cnt      INTEGER NOT NULL,
    bad_cnt  INTEGER NOT NULL,
    PRIMARY KEY (var_code, period, segment, bucket)
);

-- ============================================================================
-- Agent Trace 持久化（M1）
--
-- 为什么必须落库：金融场景里「结论可回放」是底线要求，不是加分项。
-- 内存实现（InMemoryTraceRecorder）进程一重启就全丢，面试官问「上周这笔拒贷
-- 当时模型看到了什么数据」时无法回答。落库之后，trace_id 就是审计凭证。
--
-- 两张表的分工：
--   agent_trace      一次运行的头部信息：谁跑的、什么状态、多久、结论是什么
--   agent_trace_step 这次运行的每一步明细：调了哪个工具、参数是什么、
--                    模型当时给的理由原文、工具返回原文
--
-- 拆两张表而不是塞一个 JSON 字段，是为了能按 step 级别做查询和审计：
-- 「哪些运行里模型重复调过同一个工具」「哪次结论没有数据支撑」这类问题，
-- 必须能在 SQL 层问出来，不能靠应用层反序列化全表。
-- ============================================================================
CREATE TABLE IF NOT EXISTS agent_trace (
    trace_id      TEXT    PRIMARY KEY, -- 调用方可以自带，便于和上游日志对齐
    agent_name    TEXT    NOT NULL,    -- risk-review / feature-analysis / ecl-analysis
    status        TEXT    NOT NULL,    -- OK / DEGRADED / FAILED，DEGRADED 必须能说明缺什么
    output_json   TEXT,                -- 结构化结论原文，字段由 AgentSpec.requiredFields 约束
    narrative     TEXT,                -- 模型写的文字说明
    warnings_json TEXT,                -- JSON 数组：降级原因、重复调用拦截、取数失败等
    step_count    INTEGER NOT NULL,    -- 步数，用于排查「为什么跑这么多步」
    total_cost_ms INTEGER NOT NULL,    -- 端到端耗时，P95 就是拿这一列算的
    created_at    TEXT    NOT NULL     -- ISO-8601，审计时按时间检索
);

CREATE TABLE IF NOT EXISTS agent_trace_step (
    trace_id       TEXT    NOT NULL,
    step_index     INTEGER NOT NULL,   -- 从 1 开始的执行序号
    type           TEXT    NOT NULL,   -- TOOL_CALL / FINISH / REPEAT_BLOCKED
    tool_name      TEXT,               -- FINISH 步为空
    arguments_json TEXT,               -- 工具入参原文，回放时按这个重放
    output         TEXT,               -- 工具返回原文，不截断（截断就无法复核）
    ok             INTEGER NOT NULL,   -- 1 成功 0 失败
    error          TEXT,
    thought        TEXT,               -- 模型给出的理由原文，争议时先看这一列
    cost_ms        INTEGER NOT NULL,
    PRIMARY KEY (trace_id, step_index)
);
