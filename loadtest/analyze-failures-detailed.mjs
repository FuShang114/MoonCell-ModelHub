import fs from "node:fs";
import path from "node:path";

const batchDir = process.argv[2] || "loadtest/results/batch-006";

if (!fs.existsSync(batchDir)) {
  console.error(`批次目录不存在: ${batchDir}`);
  process.exit(1);
}

console.log(`=== 详细失败原因分析 ===\n`);
console.log(`批次目录: ${batchDir}\n`);

const samplesDir = path.join(batchDir, "samples");
if (!fs.existsSync(samplesDir)) {
  console.error(`采样目录不存在: ${samplesDir}`);
  process.exit(1);
}

const sampleFiles = fs.readdirSync(samplesDir).filter(f => f.endsWith(".jsonl"));

const failureAnalysis = {
  byReason: {},
  byStatus: {},
  byCase: {},
  latencyAnalysis: { failed: [], success: [] }
};

for (const file of sampleFiles) {
  const filePath = path.join(samplesDir, file);
  const lines = fs.readFileSync(filePath, "utf8").split("\n").filter(l => l.trim());
  
  for (const line of lines) {
    try {
      const data = JSON.parse(line);
      const caseKey = `${data.case?.algo || "UNKNOWN"}-${data.case?.dataDistribution || "UNKNOWN"}-rps${data.case?.rps || "UNKNOWN"}`;
      
      // 分析失败原因统计
      if (data.failureReasons) {
        if (!failureAnalysis.byCase[caseKey]) {
          failureAnalysis.byCase[caseKey] = { byReason: {}, byStatus: {}, totalFailed: 0, totalSuccess: 0 };
        }
        for (const [reason, count] of Object.entries(data.failureReasons)) {
          failureAnalysis.byReason[reason] = (failureAnalysis.byReason[reason] || 0) + count;
          failureAnalysis.byCase[caseKey].byReason[reason] = (failureAnalysis.byCase[caseKey].byReason[reason] || 0) + count;
          failureAnalysis.byCase[caseKey].totalFailed += count;
        }
      }
      
      // 分析请求详情
      if (data.requestDetails && Array.isArray(data.requestDetails)) {
        for (const req of data.requestDetails) {
          if (!req.ok) {
            const reason = req.failureReason || "UNKNOWN";
            failureAnalysis.byReason[reason] = (failureAnalysis.byReason[reason] || 0) + 1;
            
            const status = req.status || 0;
            failureAnalysis.byStatus[status] = (failureAnalysis.byStatus[status] || 0) + 1;
            
            if (!failureAnalysis.byCase[caseKey]) {
              failureAnalysis.byCase[caseKey] = { byReason: {}, byStatus: {}, totalFailed: 0, totalSuccess: 0 };
            }
            failureAnalysis.byCase[caseKey].byReason[reason] = (failureAnalysis.byCase[caseKey].byReason[reason] || 0) + 1;
            failureAnalysis.byCase[caseKey].byStatus[status] = (failureAnalysis.byCase[caseKey].byStatus[status] || 0) + 1;
            failureAnalysis.byCase[caseKey].totalFailed++;
            
            if (req.latency) {
              failureAnalysis.latencyAnalysis.failed.push(req.latency);
            }
          } else {
            if (!failureAnalysis.byCase[caseKey]) {
              failureAnalysis.byCase[caseKey] = { byReason: {}, byStatus: {}, totalFailed: 0, totalSuccess: 0 };
            }
            failureAnalysis.byCase[caseKey].totalSuccess++;
            if (req.latency) {
              failureAnalysis.latencyAnalysis.success.push(req.latency);
            }
          }
        }
      }
    } catch (e) {
      // 忽略解析错误
    }
  }
}

// 输出统计结果
const totalFailures = Object.values(failureAnalysis.byReason).reduce((sum, count) => sum + count, 0);

console.log("=== 失败原因统计（按原因类型）===\n");
if (totalFailures === 0) {
  console.log("✅ 没有失败记录\n");
} else {
  const sortedReasons = Object.entries(failureAnalysis.byReason)
    .sort((a, b) => b[1] - a[1]);
  
  for (const [reason, count] of sortedReasons) {
    const percentage = ((count / totalFailures) * 100).toFixed(2);
    console.log(`${reason}: ${count} (${percentage}%)`);
  }
  console.log(`\n总失败数: ${totalFailures}\n`);
}

console.log("=== 失败原因统计（按HTTP状态码）===\n");
const sortedStatuses = Object.entries(failureAnalysis.byStatus)
  .sort((a, b) => b[1] - a[1]);
if (sortedStatuses.length === 0) {
  console.log("无HTTP状态码记录\n");
} else {
  for (const [status, count] of sortedStatuses) {
    const percentage = totalFailures > 0 ? ((count / totalFailures) * 100).toFixed(2) : "0.00";
    console.log(`HTTP ${status}: ${count} (${percentage}%)`);
  }
  console.log();
}

console.log("=== 失败原因统计（按用例）===\n");
const sortedCases = Object.entries(failureAnalysis.byCase)
  .sort((a, b) => (b[1].totalFailed + b[1].totalSuccess) - (a[1].totalFailed + a[1].totalSuccess));
  
for (const [caseKey, stats] of sortedCases) {
  const total = stats.totalFailed + stats.totalSuccess;
  const failRate = total > 0 ? ((stats.totalFailed / total) * 100).toFixed(2) : "0.00";
  console.log(`${caseKey}:`);
  console.log(`  总请求: ${total}, 成功: ${stats.totalSuccess}, 失败: ${stats.totalFailed} (${failRate}%)`);
  
  if (stats.totalFailed > 0) {
    console.log(`  失败原因分布:`);
    const sortedReasons = Object.entries(stats.byReason)
      .sort((a, b) => b[1] - a[1]);
    for (const [reason, count] of sortedReasons) {
      const pct = ((count / stats.totalFailed) * 100).toFixed(1);
      console.log(`    ${reason}: ${count} (${pct}%)`);
    }
    
    if (Object.keys(stats.byStatus).length > 0) {
      console.log(`  HTTP状态码分布:`);
      const sortedStatuses = Object.entries(stats.byStatus)
        .sort((a, b) => b[1] - a[1]);
      for (const [status, count] of sortedStatuses) {
        const pct = ((count / stats.totalFailed) * 100).toFixed(1);
        console.log(`    ${status}: ${count} (${pct}%)`);
      }
    }
  }
  console.log();
}

// 延迟分析
if (failureAnalysis.latencyAnalysis.failed.length > 0 || failureAnalysis.latencyAnalysis.success.length > 0) {
  console.log("=== 延迟分析 ===\n");
  
  function percentile(arr, p) {
    if (!arr.length) return 0;
    const sorted = [...arr].sort((a, b) => a - b);
    const idx = Math.max(0, Math.min(sorted.length - 1, Math.ceil(sorted.length * p) - 1));
    return sorted[idx];
  }
  
  if (failureAnalysis.latencyAnalysis.success.length > 0) {
    const success = failureAnalysis.latencyAnalysis.success;
    console.log(`成功请求延迟:`);
    console.log(`  数量: ${success.length}`);
    console.log(`  P50: ${percentile(success, 0.5).toFixed(0)}ms`);
    console.log(`  P95: ${percentile(success, 0.95).toFixed(0)}ms`);
    console.log(`  P99: ${percentile(success, 0.99).toFixed(0)}ms`);
    console.log(`  平均: ${(success.reduce((a, b) => a + b, 0) / success.length).toFixed(0)}ms`);
    console.log();
  }
  
  if (failureAnalysis.latencyAnalysis.failed.length > 0) {
    const failed = failureAnalysis.latencyAnalysis.failed;
    console.log(`失败请求延迟:`);
    console.log(`  数量: ${failed.length}`);
    console.log(`  P50: ${percentile(failed, 0.5).toFixed(0)}ms`);
    console.log(`  P95: ${percentile(failed, 0.95).toFixed(0)}ms`);
    console.log(`  P99: ${percentile(failed, 0.99).toFixed(0)}ms`);
    console.log(`  平均: ${(failed.reduce((a, b) => a + b, 0) / failed.length).toFixed(0)}ms`);
    console.log();
    
    // 分析超时情况
    const timeouts = failed.filter(l => l >= 29000); // 接近30秒超时的
    if (timeouts.length > 0) {
      console.log(`⚠️  疑似超时失败: ${timeouts.length} 个 (${((timeouts.length / failed.length) * 100).toFixed(1)}%)`);
      console.log(`  这些请求延迟接近30秒超时阈值，可能是网络延迟或下游服务响应慢\n`);
    }
  }
}

console.log("=== 失败原因说明 ===");
console.log("HTTP_XXX: HTTP 状态码错误");
console.log("  - HTTP_400: 请求参数错误");
console.log("  - HTTP_403: 权限拒绝");
console.log("  - HTTP_404: 资源不存在");
console.log("  - HTTP_500: 服务器内部错误");
console.log("  - HTTP_503: 服务不可用（可能是负载均衡拒绝）");
console.log("EMPTY_RESPONSE: 响应状态200但响应体为空");
console.log("TIMEOUT: 请求超时（30秒）");
console.log("NETWORK_ERROR: 网络连接错误");
console.log("FETCH_ERROR: Fetch API 错误");
console.log("UNKNOWN: 未知错误");
