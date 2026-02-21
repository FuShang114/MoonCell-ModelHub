import fs from "node:fs";
import path from "node:path";

const outDir = process.env.OUT_DIR || path.join("loadtest", "results");
const csvPath = path.join(outDir, "compare.csv");
const reportPath = path.join(outDir, "compare-report.html");

if (!fs.existsSync(csvPath)) {
  throw new Error(`CSV not found: ${csvPath}`);
}

const raw = fs.readFileSync(csvPath, "utf8").trim().split(/\r?\n/);
const header = raw[0].split(",").map((h) => h.trim());
const rows = raw.slice(1).map((line) => {
  const cols = line.split(",");
  const obj = {};
  header.forEach((h, i) => {
    const v = cols[i] != null ? cols[i].trim() : "";
    const n = Number(v);
    obj[h] = Number.isFinite(n) && h !== "algo" ? n : v;
  });
  return obj;
}).filter((r) => r.algo && Number.isFinite(r.target_rps));

const algos = ["TRADITIONAL", "OBJECT_POOL"];
const byAlgo = Object.fromEntries(
  algos.map((a) => [a, rows.filter((r) => r.algo === a).sort((x, y) => x.target_rps - y.target_rps)])
);
const labels = [...new Set(rows.map((r) => r.target_rps))].sort((a, b) => a - b);

function series(metric, algo) {
  const map = new Map(byAlgo[algo].map((r) => [r.target_rps, Number(r[metric] ?? 0)]));
  return labels.map((rps) => map.get(rps) ?? 0);
}

const metricKeys = [
  "error_rate",
  "latency_p95_ms",
  "actual_rps",
  "gc_per_min_avg",
  "cpu_usage_avg",
  "success_rate_avg",
  "throughput_avg",
  "resource_usage_avg",
  "reject_queue_full",
  "reject_budget",
  "reject_sampling",
];
const metrics = {};
for (const key of metricKeys) {
  if (header.includes(key)) {
    metrics[key] = { t: series(key, "TRADITIONAL"), o: series(key, "OBJECT_POOL") };
  }
}

const payload = { labels, metrics };

const chartConfigs = [
  { id: "c1", key: "error_rate", title: "错误率", percent: true },
  { id: "c2", key: "latency_p95_ms", title: "P95 时延 (ms)", percent: false },
  { id: "c3", key: "actual_rps", title: "实际 RPS", percent: false },
  { id: "c4", key: "gc_per_min_avg", title: "GC 频率 (次/分钟)", percent: false },
  { id: "c5", key: "cpu_usage_avg", title: "CPU 使用率", percent: true },
  { id: "c6", key: "success_rate_avg", title: "成功率", percent: true },
  { id: "c7", key: "throughput_avg", title: "吞吐 (Token/s)", percent: false },
  { id: "c8", key: "resource_usage_avg", title: "资源使用率", percent: true },
  { id: "c9", key: "reject_queue_full", title: "队列满拒绝次数", percent: false },
  { id: "c10", key: "reject_budget", title: "预算拒绝次数", percent: false },
  { id: "c11", key: "reject_sampling", title: "Sampling 拒绝次数", percent: false },
].filter((c) => payload.metrics[c.key]);

const chartScript = chartConfigs
  .map(
    (c, i) =>
      `draw('${c.id}','${c.key}',${c.percent});`
  )
  .join("\n    ");

const chartCards = chartConfigs
  .map(
    (c) =>
      `<div class="card"><div class="title">${c.title}</div><div class="box"><canvas id="${c.id}"></canvas></div></div>`
  )
  .join("\n    ");

const html = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>压测对比报告 - TRADITIONAL vs OBJECT_POOL</title>
  <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
  <style>
    * { box-sizing: border-box; }
    body { font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #e2e8f0; margin: 0; padding: 20px; }
    h1 { font-size: 1.5rem; margin: 0 0 8px 0; font-weight: 600; }
    .meta { color: #94a3b8; font-size: 0.8rem; margin-bottom: 16px; }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 16px; }
    .card { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 14px; }
    .title { font-size: 0.85rem; color: #94a3b8; margin-bottom: 8px; font-weight: 500; }
    .box { height: 220px; }
    .summary { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 14px; margin-bottom: 16px; overflow-x: auto; }
    .summary table { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
    .summary th, .summary td { padding: 6px 10px; text-align: left; border-bottom: 1px solid #334155; }
    .summary th { color: #94a3b8; font-weight: 500; }
  </style>
</head>
<body>
  <h1>TRADITIONAL vs OBJECT_POOL 压测对比</h1>
  <div class="meta">横轴 = 目标 RPS · 蓝色 = TRADITIONAL · 紫色 = OBJECT_POOL · 数据来源: ${path.basename(outDir)}/compare.csv · 生成时间: ${new Date().toLocaleString("zh-CN")}</div>
  <div class="summary">
    <table>
      <thead><tr><th>算法</th><th>目标 RPS</th><th>实际 RPS</th><th>错误率</th><th>P95 时延(ms)</th><th>成功率</th><th>吞吐</th><th>拒绝(队/预算/Sample)</th></tr></thead>
      <tbody>
        ${rows
          .map(
            (r) =>
              `<tr><td>${r.algo}</td><td>${r.target_rps}</td><td>${Number(r.actual_rps).toFixed(2)}</td><td>${(Number(r.error_rate) * 100).toFixed(2)}%</td><td>${Number(r.latency_p95_ms).toFixed(0)}</td><td>${(Number(r.success_rate_avg ?? 0) * 100).toFixed(1)}%</td><td>${Number(r.throughput_avg ?? 0).toFixed(2)}</td><td>${r.reject_queue_full ?? 0} / ${r.reject_budget ?? 0} / ${r.reject_sampling ?? 0}</td></tr>`
          )
          .join("")}
      </tbody>
    </table>
  </div>
  <div class="grid">
    ${chartCards}
  </div>
  <script>
    const data = ${JSON.stringify(payload)};
    function draw(id, metric, percent){
      if (!data.metrics[metric]) return;
      new Chart(document.getElementById(id), {
        type: 'line',
        data: {
          labels: data.labels,
          datasets: [
            { label: 'TRADITIONAL', data: data.metrics[metric].t, borderColor: '#3b82f6', backgroundColor: 'rgba(59,130,246,0.15)', tension: 0.25, pointRadius: 4 },
            { label: 'OBJECT_POOL', data: data.metrics[metric].o, borderColor: '#a78bfa', backgroundColor: 'rgba(167,139,250,0.15)', tension: 0.25, pointRadius: 4 }
          ]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          animation: false,
          plugins: { legend: { labels: { color: '#94a3b8' } } },
          scales: {
            x: { ticks: { color: '#94a3b8' }, grid: { color: '#334155' } },
            y: { ticks: { color: '#94a3b8', callback: (v) => percent ? (v * 100).toFixed(1) + '%' : v }, grid: { color: '#334155' } }
          }
        }
      });
    }
    ${chartScript}
  </script>
</body>
</html>`;

fs.writeFileSync(reportPath, html, "utf8");
console.log(`Report generated: ${reportPath}`);
