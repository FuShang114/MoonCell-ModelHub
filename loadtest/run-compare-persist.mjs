import fs from "node:fs";
import path from "node:path";

const baseUrl = process.env.BASE_URL || "http://127.0.0.1:9061";
const rpsLevels = (process.env.RPS_LEVELS || "4,8,12")
  .split(",")
  .map((v) => Number(v.trim()))
  .filter((v) => Number.isFinite(v) && v > 0);
const durationSec = Number(process.env.DURATION_SEC || 300);
const prompt = process.env.PROMPT || "请用三点说明系统稳定性优化建议，每点不超过18字。";
const rootDir = process.env.OUT_DIR || path.join("loadtest", "results");
const batchName = process.env.BATCH_NAME || "";

const runId = new Date().toISOString().replace(/[:.]/g, "-");
const runDir = path.join(rootDir, resolveBatchDirName(rootDir, batchName));
const samplesDir = path.join(runDir, "samples");
fs.mkdirSync(samplesDir, { recursive: true });

const csvPath = path.join(runDir, "compare.csv");
const progressPath = path.join(runDir, "progress.jsonl");
const metaPath = path.join(runDir, "meta.json");

// 全局唯一 idempotencyKey 生成器（确保不冲突）
let idempotencyCounter = 0;
function generateUniqueIdempotencyKey() {
  const counter = ++idempotencyCounter;
  const timestamp = Date.now();
  const random = Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15);
  const processId = process.pid || 0;
  return `persist-${timestamp}-${counter}-${processId}-${random}`;
}

fs.writeFileSync(
  csvPath,
  "algo,target_rps,duration_sec,actual_rps,error_rate,latency_p95_ms,gc_per_min_avg,cpu_usage_avg,qps_avg,success_rate_avg,failure_rate_avg,throughput_avg,resource_usage_avg,reject_queue_full,reject_budget,reject_sampling\n",
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
      prompt,
    },
    null,
    2
  ),
  "utf8"
);

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
  // 确保 prompt 不为空
  const message = (prompt && prompt.trim().length > 0) ? prompt.trim() : "请简要分析系统稳定性优化建议。";
  const body = {
    message,
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
    return { ok: res.ok && text.length > 0, latency: Date.now() - started, status: res.status };
  } catch {
    return { ok: false, latency: Date.now() - started, status: 0 };
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
  appendProgress("case_start", { algo, rps });
  await setAlgorithm(algo);
  await sleep(3000);

  const caseKey = `${algo}-rps${rps}`;
  const sampleFile = path.join(samplesDir, `${caseKey}.jsonl`);
  const latencies = [];
  const samples = [];
  let total = 0;
  let failed = 0;
  const started = Date.now();
  const endAt = started + durationSec * 1000;
  const before = await sampleMetrics();

  while (Date.now() < endAt) {
    const secStart = Date.now();
    const reqs = Array.from({ length: rps }, () => callChat());
    const results = await Promise.all(reqs);
    total += results.length;
    for (const r of results) {
      latencies.push(r.latency);
      if (!r.ok) failed++;
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
          case: { algo, rps },
          req: { total, failed },
        }) + "\n",
        "utf8"
      );
      appendProgress("sample", {
        algo,
        rps,
        total,
        failed,
        qps: Number(s.monitor?.qps || 0),
        successRate: Number(s.monitor?.successRate || 0),
      });
    } catch {
      appendProgress("sample_error", { algo, rps });
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
    actualRps,
    errorRate,
    p95,
    rejectQueueFull,
    rejectBudget,
    rejectSampling,
  });
}

async function main() {
  appendProgress("run_start", { runDir });
  console.log(`Batch dir: ${runDir}`);
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
