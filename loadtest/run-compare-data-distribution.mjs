import fs from "node:fs";
import path from "node:path";

const baseUrl = process.env.BASE_URL || "http://127.0.0.1:9061";
const rpsLevels = (process.env.RPS_LEVELS || "4,8,12")
  .split(",")
  .map((v) => Number(v.trim()))
  .filter((v) => Number.isFinite(v) && v > 0);
const durationSec = Number(process.env.DURATION_SEC || 300);
const rootDir = process.env.OUT_DIR || path.join("loadtest", "results");
const batchName = process.env.BATCH_NAME || "";

// 数据分布模式：homogeneous（同构）、heterogeneous（异构）、mixed（混合）
const dataDistribution = (process.env.DATA_DISTRIBUTION || "homogeneous").toLowerCase();
const mixedWeight = Number(process.env.MIXED_WEIGHT || 0.5); // 混合模式下，同构的权重（0-1）

const runId = new Date().toISOString().replace(/[:.]/g, "-");
const runDir = path.join(rootDir, resolveBatchDirName(rootDir, batchName));
const samplesDir = path.join(runDir, "samples");
fs.mkdirSync(samplesDir, { recursive: true });

// 全局唯一 idempotencyKey 生成器（确保不冲突）
let idempotencyCounter = 0;
function generateUniqueIdempotencyKey() {
  const counter = ++idempotencyCounter;
  const timestamp = Date.now();
  const random = Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15);
  const processId = process.pid || 0;
  return `dist-${timestamp}-${counter}-${processId}-${random}`;
}

const csvPath = path.join(runDir, "compare.csv");
const progressPath = path.join(runDir, "progress.jsonl");
const metaPath = path.join(runDir, "meta.json");

fs.writeFileSync(
  csvPath,
  "algo,data_distribution,target_rps,duration_sec,actual_rps,error_rate,latency_p95_ms,gc_per_min_avg,cpu_usage_avg,qps_avg,success_rate_avg,failure_rate_avg,throughput_avg,resource_usage_avg,reject_queue_full,reject_budget,reject_sampling\n",
  "utf8"
);
fs.writeFileSync(
  metaPath,
  JSON.stringify(
    {
      batchName: path.basename(runDir),
      runId,
      startedAt: new Date().toISOString(),
      baseUrl,
      rpsLevels,
      durationSec,
      dataDistribution,
      mixedWeight,
    },
    null,
    2
  ),
  "utf8"
);

// Prompt 池：按 token 长度分类
const promptPool = {
  short: [
    "请一句话总结这段话。",
    "给我3个关键词。",
    "把这句话改成更礼貌的表达。",
    "提炼一个20字以内标题。",
    "用一句话说明核心观点。",
    "总结成5个字。",
  ],
  medium: [
    "请用三段话分析对象池策略在高并发下的优缺点，并给出两条改进建议。",
    "用户咨询系统抖动问题，请给出排查步骤，要求分点、可执行、每点不超过25字。",
    "比较两种负载均衡方案在稳定性、延迟和资源成本上的差异，并给出适用场景。",
    "根据下面需求写一段实施计划：保证成功率优先，时延次之，成本第三。",
    "请简要分析对象池负载均衡策略的优缺点，并给出三条优化建议，每条不超过20字。",
    "说明系统稳定性优化建议，分三点，每点不超过18字。",
  ],
  long: [
    "你是一名网关架构师。请阅读以下场景并给出完整处理策略：系统存在高峰流量波动、上下游延迟抖动、实例健康状态频繁变化、请求体大小分布不均、部分请求具备高 token 消耗。请输出一个包含目标、约束、执行步骤、观测指标、回滚方案的说明，分点回答，避免冗长。",
    "请模拟一次生产事故复盘：现象是成功率下降、拒绝率升高、GC 频率波动。请从流量特征、策略参数、资源池状态、请求输入结构四个维度给出原因分析，并提出短期止血和长期优化方案。",
    "设计一个支持多租户、多模型、动态扩缩容的网关系统。要求包含：路由规则、负载均衡策略、限流熔断、监控告警、成本控制。请分模块说明实现思路，每模块不超过100字。",
  ],
};

// 数据分布选择器
function choosePrompt() {
  let group;
  
  if (dataDistribution === "homogeneous") {
    // 同构：固定使用 medium（中等长度）
    group = "medium";
  } else if (dataDistribution === "heterogeneous") {
    // 异构：随机选择 short/medium/long（20%短、60%中、20%长）
    const p = Math.random();
    if (p < 0.2) group = "short";
    else if (p > 0.8) group = "long";
    else group = "medium";
  } else if (dataDistribution === "mixed") {
    // 混合：按权重随机选择同构或异构模式
    const useHomogeneous = Math.random() < mixedWeight;
    if (useHomogeneous) {
      group = "medium"; // 同构部分固定 medium
    } else {
      // 异构部分随机
      const p = Math.random();
      if (p < 0.2) group = "short";
      else if (p > 0.8) group = "long";
      else group = "medium";
    }
  } else {
    // 默认同构
    group = "medium";
  }
  
  const arr = promptPool[group];
  if (!arr || arr.length === 0) {
    // 如果数组为空，使用默认 prompt
    return promptPool.medium[0] || "请简要分析系统稳定性优化建议。";
  }
  const prompt = arr[Math.floor(Math.random() * arr.length)];
  // 确保 prompt 不为空
  if (!prompt || prompt.trim().length === 0) {
    return promptPool.medium[0] || "请简要分析系统稳定性优化建议。";
  }
  return prompt.trim();
}

function resolveBatchDirName(root, manualName) {
  if (manualName && manualName.trim()) {
    return manualName.trim();
  }
  const entries = fs.existsSync(root)
    ? fs.readdirSync(root, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name)
    : [];
  const nums = entries
    .map((name) => {
      const m = /^batch-(\d{3})$/.exec(name);
      return m ? Number(m[1]) : 0;
    })
    .filter((n) => n > 0);
  const next = (nums.length ? Math.max(...nums) : 0) + 1;
  return `batch-${String(next).padStart(3, "0")}`;
}

function appendProgress(type, data = {}) {
  fs.appendFileSync(
    progressPath,
    JSON.stringify({
      ts: Date.now(),
      type,
      ...data,
    }) + "\n",
    "utf8"
  );
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function httpJson(url, init = {}) {
  const res = await fetch(url, init);
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} ${url}`);
  }
  return res.json();
}

function percentile(arr, p) {
  if (!arr.length) return 0;
  const sorted = [...arr].sort((a, b) => a - b);
  const idx = Math.max(0, Math.min(sorted.length - 1, Math.ceil(sorted.length * p) - 1));
  return sorted[idx];
}

async function setAlgorithm(algo) {
  await httpJson(`${baseUrl}/admin/load-balancing/settings`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ algorithm: algo }),
  });
}

async function callChat() {
  const prompt = choosePrompt();
  // 双重验证：确保 message 不为空
  if (!prompt || prompt.trim().length === 0) {
    throw new Error("Generated prompt is empty, cannot send request");
  }
  const body = {
    message: prompt.trim(),
    idempotencyKey: generateUniqueIdempotencyKey(),
  };
  const started = Date.now();
  try {
    const res = await fetch(`${baseUrl}/v1/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(30000),
    });
    const text = await res.text();
    const ok = res.ok && text.length > 0;
    let failureReason = null;
    if (!ok) {
      if (!res.ok) {
        failureReason = `HTTP_${res.status}`;
      } else if (text.length === 0) {
        failureReason = "EMPTY_RESPONSE";
      }
    }
    return { 
      ok, 
      latency: Date.now() - started, 
      status: res.status,
      failureReason 
    };
  } catch (e) {
    let failureReason = "NETWORK_ERROR";
    if (e.name === "AbortError" || e.name === "TimeoutError") {
      failureReason = "TIMEOUT";
    } else if (e.message && e.message.includes("fetch")) {
      failureReason = "FETCH_ERROR";
    }
    return { 
      ok: false, 
      latency: Date.now() - started, 
      status: 0,
      failureReason 
    };
  }
}

async function sampleMetrics() {
  const [monitor, statuses] = await Promise.all([
    httpJson(`${baseUrl}/admin/monitor-metrics`),
    httpJson(`${baseUrl}/admin/load-balancing/strategy-statuses`),
  ]);
  const active = Array.isArray(statuses)
    ? statuses.find((s) => s.state === "ACTIVE") || statuses[0]
    : null;
  return { monitor, activeStatus: active || {} };
}

function avg(values) {
  return values.length ? values.reduce((a, b) => a + b, 0) / values.length : 0;
}

async function runCase(algo, rps) {
  appendProgress("case_start", { algo, rps, dataDistribution });
  await setAlgorithm(algo);
  await sleep(3000);

  const caseKey = `${algo}-${dataDistribution}-rps${rps}`;
  const sampleFile = path.join(samplesDir, `${caseKey}.jsonl`);
  const latencies = [];
  const samples = [];
  let total = 0;
  let failed = 0;
  const failureReasons = {}; // 统计失败原因
  const started = Date.now();
  const endAt = started + durationSec * 1000;
  const before = await sampleMetrics();

  const requestDetails = []; // 记录每个请求的详细信息用于分析
  while (Date.now() < endAt) {
    const secStart = Date.now();
    const reqs = Array.from({ length: rps }, () => callChat());
    const results = await Promise.all(reqs);
    total += results.length;
    for (const r of results) {
      latencies.push(r.latency);
      requestDetails.push({
        ts: Date.now(),
        ok: r.ok,
        status: r.status,
        latency: r.latency,
        failureReason: r.failureReason || (r.ok ? "SUCCESS" : "UNKNOWN")
      });
      if (!r.ok) {
        failed++;
        const reason = r.failureReason || "UNKNOWN";
        failureReasons[reason] = (failureReasons[reason] || 0) + 1;
      }
    }

    try {
      const s = await sampleMetrics();
      samples.push(s);
      fs.appendFileSync(
        sampleFile,
        JSON.stringify({
          ts: Date.now(),
          monitor: s.monitor,
          activeStatus: s.activeStatus,
          case: { algo, rps, dataDistribution },
          req: { total, failed },
          failureReasons: { ...failureReasons },
          requestDetails: requestDetails.slice(-20), // 保留最近20个请求的详情
        }) + "\n",
        "utf8"
      );
      appendProgress("sample", {
        algo,
        rps,
        dataDistribution,
        total,
        failed,
        qps: Number(s.monitor?.qps || 0),
        successRate: Number(s.monitor?.successRate || 0),
      });
    } catch {
      appendProgress("sample_error", { algo, rps, dataDistribution });
    }

    const elapsed = Date.now() - secStart;
    if (elapsed < 1000) {
      await sleep(1000 - elapsed);
    }
  }

  const after = await sampleMetrics();
  const durationReal = (Date.now() - started) / 1000;
  const errorRate = total > 0 ? failed / total : 1;
  const actualRps = total / Math.max(1, durationReal);
  const p95 = percentile(latencies, 0.95);
  const gcAvg = avg(samples.map((s) => Number(s.monitor?.gcRatePerMin || 0)));
  const cpuAvg = avg(samples.map((s) => Number(s.monitor?.cpuUsage || 0)));
  const qpsAvg = avg(samples.map((s) => Number(s.monitor?.qps || 0)));
  const succAvg = avg(samples.map((s) => Number(s.monitor?.successRate || 0)));
  const failAvg = avg(samples.map((s) => Number(s.monitor?.failureRate || 0)));
  const throughputAvg = avg(samples.map((s) => Number(s.monitor?.throughput || 0)));
  const resourceAvg = avg(samples.map((s) => Number(s.monitor?.resourceUsage || 0)));
  const rejectQueueFull = Math.max(
    0,
    Number(after.activeStatus.rejectQueueFull || 0) - Number(before.activeStatus.rejectQueueFull || 0)
  );
  const rejectBudget = Math.max(
    0,
    Number(after.activeStatus.rejectBudget || 0) - Number(before.activeStatus.rejectBudget || 0)
  );
  const rejectSampling = Math.max(
    0,
    Number(after.activeStatus.rejectSampling || 0) - Number(before.activeStatus.rejectSampling || 0)
  );

  const row = [
    algo,
    dataDistribution,
    rps,
    durationSec,
    actualRps.toFixed(2),
    errorRate.toFixed(4),
    p95.toFixed(2),
    gcAvg.toFixed(2),
    cpuAvg.toFixed(4),
    qpsAvg.toFixed(2),
    succAvg.toFixed(4),
    failAvg.toFixed(4),
    throughputAvg.toFixed(2),
    resourceAvg.toFixed(4),
    rejectQueueFull,
    rejectBudget,
    rejectSampling,
  ].join(",");
  fs.appendFileSync(csvPath, row + "\n", "utf8");

  appendProgress("case_end", {
    algo,
    rps,
    dataDistribution,
    actualRps,
    errorRate,
    p95,
    rejectQueueFull,
    rejectBudget,
    rejectSampling,
    failureReasons: { ...failureReasons },
  });
}

async function main() {
  appendProgress("run_start", { runDir, dataDistribution, mixedWeight });
  console.log(`Batch dir: ${runDir}`);
  console.log(`Data distribution: ${dataDistribution}${dataDistribution === "mixed" ? ` (weight=${mixedWeight})` : ""}`);
  
  for (const algo of ["TRADITIONAL", "OBJECT_POOL"]) {
    for (const rps of rpsLevels) {
      // eslint-disable-next-line no-await-in-loop
      await runCase(algo, rps);
    }
  }
  appendProgress("run_end", { runDir });
  console.log(`Run done: ${runDir}`);
  console.log(`CSV: ${csvPath}`);
}

main().catch((e) => {
  appendProgress("run_error", { message: String(e?.message || e) });
  console.error(e);
  process.exit(1);
});
