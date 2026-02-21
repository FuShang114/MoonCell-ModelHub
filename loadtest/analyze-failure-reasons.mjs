import fs from "node:fs";
import path from "node:path";

const batchDir = process.argv[2] || "loadtest/results/batch-006";

if (!fs.existsSync(batchDir)) {
  console.error(`批次目录不存在: ${batchDir}`);
  process.exit(1);
}

console.log(`分析批次: ${batchDir}\n`);

// 读取所有采样文件
const samplesDir = path.join(batchDir, "samples");
if (!fs.existsSync(samplesDir)) {
  console.error(`采样目录不存在: ${samplesDir}`);
  process.exit(1);
}

const sampleFiles = fs.readdirSync(samplesDir).filter(f => f.endsWith(".jsonl"));

const failureStats = {};

for (const file of sampleFiles) {
  const filePath = path.join(samplesDir, file);
  const lines = fs.readFileSync(filePath, "utf8").split("\n").filter(l => l.trim());
  
  for (const line of lines) {
    try {
      const data = JSON.parse(line);
      if (data.failureReasons) {
        for (const [reason, count] of Object.entries(data.failureReasons)) {
          if (!failureStats[reason]) {
            failureStats[reason] = { total: 0, byCase: {} };
          }
          const caseKey = `${data.case?.algo || "UNKNOWN"}-${data.case?.dataDistribution || "UNKNOWN"}-rps${data.case?.rps || "UNKNOWN"}`;
          if (!failureStats[reason].byCase[caseKey]) {
            failureStats[reason].byCase[caseKey] = 0;
          }
          failureStats[reason].total += count;
          failureStats[reason].byCase[caseKey] += count;
        }
      }
    } catch (e) {
      // 忽略解析错误
    }
  }
}

// 读取 progress.jsonl 获取最终统计
const progressPath = path.join(batchDir, "progress.jsonl");
if (fs.existsSync(progressPath)) {
  const progressLines = fs.readFileSync(progressPath, "utf8").split("\n").filter(l => l.trim());
  for (const line of progressLines) {
    try {
      const data = JSON.parse(line);
      if (data.type === "case_end" && data.failureReasons) {
        const caseKey = `${data.algo}-${data.dataDistribution}-rps${data.rps}`;
        for (const [reason, count] of Object.entries(data.failureReasons)) {
          if (!failureStats[reason]) {
            failureStats[reason] = { total: 0, byCase: {} };
          }
          failureStats[reason].total += count;
          if (!failureStats[reason].byCase[caseKey]) {
            failureStats[reason].byCase[caseKey] = 0;
          }
          failureStats[reason].byCase[caseKey] += count;
        }
      }
    } catch (e) {
      // 忽略解析错误
    }
  }
}

// 输出统计结果
const totalFailures = Object.values(failureStats).reduce((sum, stat) => sum + stat.total, 0);

console.log("=== 失败原因统计 ===\n");
console.log(`总失败数: ${totalFailures}\n`);

if (totalFailures === 0) {
  console.log("✅ 没有失败记录");
  process.exit(0);
}

// 按总数排序
const sortedReasons = Object.entries(failureStats)
  .sort((a, b) => b[1].total - a[1].total);

for (const [reason, stat] of sortedReasons) {
  const percentage = totalFailures > 0 ? ((stat.total / totalFailures) * 100).toFixed(2) : "0.00";
  console.log(`${reason}: ${stat.total} (${percentage}%)`);
  
  // 按用例分组显示
  const sortedCases = Object.entries(stat.byCase)
    .sort((a, b) => b[1] - a[1]);
  
  for (const [caseKey, count] of sortedCases) {
    const casePct = stat.total > 0 ? ((count / stat.total) * 100).toFixed(1) : "0.0";
    console.log(`  └─ ${caseKey}: ${count} (${casePct}%)`);
  }
  console.log();
}

// 输出失败原因说明
console.log("\n=== 失败原因说明 ===");
console.log("HTTP_XXX: HTTP 状态码错误（如 HTTP_400, HTTP_500, HTTP_503）");
console.log("EMPTY_RESPONSE: 响应状态 200 但响应体为空");
console.log("TIMEOUT: 请求超时（30秒）");
console.log("NETWORK_ERROR: 网络错误");
console.log("FETCH_ERROR: Fetch API 错误");
console.log("UNKNOWN: 未知错误");
