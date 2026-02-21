import http from "k6/http";
import { check } from "k6";

const baseUrl = __ENV.BASE_URL || "http://127.0.0.1:9061";
const rps = Number(__ENV.RPS || 3);
const duration = __ENV.DURATION || "180s";

// 中等长度请求，控制 token 成本，默认约 100~180 token/req（取决于下游模型）
const prompt = __ENV.PROMPT || "请简要分析对象池负载均衡策略的优缺点，并给出三条优化建议，每条不超过20字。";

export const options = {
  scenarios: {
    steady_load: {
      executor: "constant-arrival-rate",
      rate: rps,
      timeUnit: "1s",
      duration,
      preAllocatedVUs: 20,
      maxVUs: 80,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.10"],
    http_req_duration: ["p(95)<5000"],
  },
};

export default function () {
  const url = `${baseUrl}/v1/chat/completions`;
  const idempotencyKey = `k6-${Date.now()}-${__VU}-${__ITER}`;
  const payload = JSON.stringify({
    message: prompt,
    idempotencyKey,
  });

  const res = http.post(url, payload, {
    headers: {
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    },
    timeout: "30s",
  });

  check(res, {
    "status is 200": (r) => r.status === 200,
  });
}
