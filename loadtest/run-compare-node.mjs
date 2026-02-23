import fs from "node:fs";
import path from "node:path";
import { promptPool, pickRandomPromptFromGroup } from "./prompts.mjs";

const baseUrl = process.env.BASE_URL || "http://127.0.0.1:9061";
const rpsLevels = (process.env.RPS_LEVELS || "4,8,12")
  .split(",")
  .map((v) => Number(v.trim()))
  .filter((v) => Number.isFinite(v) && v > 0);
const durationSec = Number(process.env.DURATION_SEC || 120);
const fixedPrompt = process.env.PROMPT || "";
const outDir = process.env.OUT_DIR || path.join("loadtest", "results");

if (!fs.existsSync(outDir)) {
  fs.mkdirSync(outDir, { recursive: true });
}

// 全局唯一 idempotencyKey 生成器（确保不冲突）
let idempotencyCounter = 0;
function generateUniqueIdempotencyKey() {
  const counter = ++idempotencyCounter;
  const timestamp = Date.now();
  const random = Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15);
  const processId = process.pid || 0;
  return `node-${timestamp}-${counter}-${processId}-${random}`;
}

const csvPath = path.join(outDir, "compare.csv");
fs.writeFileSync(
  csvPath,
  "algo,target_rps,duration_sec,actual_rps,error_rate,latency_p95_ms,gc_per_min_avg,cpu_usage_avg,qps_avg,success_rate_avg,failure_rate_avg,throughput_avg,resource_usage_avg,reject_queue_full,reject_budget,reject_sampling\n",
  "utf8"
);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function choosePrompt() {
  if (fixedPrompt && fixedPrompt.trim().length > 0) {
    return fixedPrompt.trim();
  }
  const p = Math.random();
  let group = "medium";
  if (p < 0.2) group = "short";
  else if (p > 0.8) group = "long";
  return pickRandomPromptFromGroup(group);
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
      // 移除超时限制，允许无上限等待
    });
    const text = await res.text();
    const latency = Date.now() - started;
    return {
      ok: res.ok && text.length > 0,
      status: res.status,
      latency,
    };
  } catch (e) {
    return {
      ok: false,
      status: 0,
      latency: Date.now() - started,
    };
  }
}

async function sampleMetrics() {
  const [m, statuses] = await Promise.all([
    httpJson(`${baseUrl}/admin/monitor-metrics`),
    httpJson(`${baseUrl}/admin/load-balancing/strategy-statuses`),
  ]);
  const active = Array.isArray(statuses)
    ? statuses.find((s) => s.state === "ACTIVE") || statuses[0]
    : null;
  return {
    monitor: m,
    activeStatus: active || {},
  };
}

function avg(values) {
  if (!values.length) return 0;
  return values.reduce((a, b) => a + b, 0) / values.length;
}

async function runCase(algo, targetRps) {
  console.log(`Running ${algo} @ ${targetRps} rps for ${durationSec}s`);

  const metricsSamples = [];
  const latencies = [];
  let total = 0;
  let failed = 0;
  const started = Date.now();
  const endAt = started + durationSec * 1000;
  const allReqPromises = [];

  const before = await sampleMetrics();
  const caseSamplesPath = path.join(outDir, `${algo}-rps${targetRps}-samples.jsonl`);
  if (fs.existsSync(caseSamplesPath)) fs.unlinkSync(caseSamplesPath);
  while (Date.now() < endAt) {
    const secStart = Date.now();
    const reqs = [];
    for (let i = 0; i < targetRps; i++) {
      // 不阻塞当前秒的节奏：发出请求后将处理结果的逻辑挂在 Promise 链上
      const p = callChat()
        .then((r) => {
          total += 1;
      latencies.push(r.latency);
          if (!r.ok) {
            failed += 1;
          }
        })
        .catch((e) => {
          // 视为一次失败请求
          failed += 1;
          latencies.push(0);
        });
      allReqPromises.push(p);
      reqs.push(p);
    }

    try {
      const s = await sampleMetrics();
      metricsSamples.push(s);
      fs.appendFileSync(
        caseSamplesPath,
        JSON.stringify({
          ts: Date.now(),
          monitor: s.monitor,
          activeStatus: s.activeStatus,
        }) + "\n",
        "utf8"
      );
    } catch {
      // ignore sampling errors
    }

    const elapsed = Date.now() - secStart;
    if (elapsed < 1000) {
      await sleep(1000 - elapsed);
    }
  }

  // 等待所有已经发出的请求完成（或失败），避免统计遗漏
  await Promise.allSettled(allReqPromises);

  const after = await sampleMetrics();

  // 为了体感更直观，实际 RPS 以配置的窗口为准，而不是包含尾部清理时间
  const caseDurationSec = durationSec;
  const errorRate = total > 0 ? failed / total : 1;
  const actualRps = total / Math.max(1, caseDurationSec);
  const p95 = percentile(latencies, 0.95);

  const gcAvg = avg(metricsSamples.map((s) => Number(s.monitor?.gcRatePerMin || 0)));
  const cpuAvg = avg(metricsSamples.map((s) => Number(s.monitor?.cpuUsage || 0)));
  const qpsAvg = avg(metricsSamples.map((s) => Number(s.monitor?.qps || 0)));
  const succAvg = avg(metricsSamples.map((s) => Number(s.monitor?.successRate || 0)));
  const failAvg = avg(metricsSamples.map((s) => Number(s.monitor?.failureRate || 0)));
  const throughputAvg = avg(metricsSamples.map((s) => Number(s.monitor?.throughput || 0)));
  const resourceAvg = avg(metricsSamples.map((s) => Number(s.monitor?.resourceUsage || 0)));

  const rejectQueueFull = Math.max(
    0,
    Number(after.activeStatus.rejectQueueFull || 0) -
      Number(before.activeStatus.rejectQueueFull || 0)
  );
  const rejectBudget = Math.max(
    0,
    Number(after.activeStatus.rejectBudget || 0) -
      Number(before.activeStatus.rejectBudget || 0)
  );
  const rejectSampling = Math.max(
    0,
    Number(after.activeStatus.rejectSampling || 0) -
      Number(before.activeStatus.rejectSampling || 0)
  );

  const row = [
    algo,
    targetRps,
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
  return {
    algo,
    targetRps,
    actualRps,
    errorRate,
    p95,
    gcAvg,
    cpuAvg,
    qpsAvg,
    succAvg,
    failAvg,
    throughputAvg,
    resourceAvg,
    rejectQueueFull,
    rejectBudget,
    rejectSampling,
  };
}

async function main() {
  const results = [];
  for (const algo of ["TRADITIONAL"]) {
    for (const rps of rpsLevels) {
      // eslint-disable-next-line no-await-in-loop
      const r = await runCase(algo, rps);
      results.push(r);
    }
  }

  console.log("\n=== Summary ===");
  for (const r of results) {
    console.log(
      `${r.algo}@${r.targetRps} rps | actual=${r.actualRps.toFixed(2)} | err=${(
        r.errorRate * 100
      ).toFixed(2)}% | p95=${r.p95.toFixed(0)}ms | cpu=${(r.cpuAvg * 100).toFixed(
        1
      )}% | gc=${r.gcAvg.toFixed(2)}/min | res=${(r.resourceAvg * 100).toFixed(1)}%`
    );
  }
  console.log(`\nCSV: ${csvPath}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
