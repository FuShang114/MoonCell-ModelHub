import csv
import random
from collections import deque, defaultdict
from dataclasses import dataclass
from pathlib import Path
from statistics import mean

import ab_test_simulator as sim


OUT = Path(__file__).parent / "outputs"
OUT.mkdir(parents=True, exist_ok=True)


def percentile(values, p):
    if not values:
        return 0.0
    arr = sorted(values)
    k = max(0, min(len(arr) - 1, int((p / 100.0) * len(arr)) - 1))
    return float(arr[k])


@dataclass
class Inflight:
    req: sim.Request
    start_ts: float
    finish_ts: float
    node_id: int


@dataclass
class Scenario:
    name: str
    duration_sec: int
    base_lam: int
    burst_lam: int
    bursts: list
    token_profile: str
    node_profile: str


def make_nodes(profile: str):
    nodes = []
    for i in range(6):
        rpm = 1150 + i * 80
        tpm = 520_000 + i * 45_000
        burst = max(6.0, (rpm / 60.0) * 0.8)
        if profile == "rpm_tight":
            rpm = int(rpm * 0.7)
            burst = max(5.0, (rpm / 60.0) * 0.7)
        elif profile == "tpm_tight":
            tpm = int(tpm * 0.6)
        elif profile == "balanced_high":
            rpm = int(rpm * 1.1)
            tpm = int(tpm * 1.1)
        nodes.append(sim.NodeState(i, rpm, tpm, 14 + i, burst))
    return nodes


def generate_requests(s: Scenario, seed: int):
    rng = random.Random(seed)
    reqs = []
    rid = 0
    for sec in range(s.duration_sec):
        lam = s.base_lam
        for a, b in s.bursts:
            if a <= sec < b:
                lam = s.burst_lam
                break
        n = max(1, int(rng.gauss(lam, lam * 0.12)))
        for _ in range(n):
            t = sec + rng.random()
            p = rng.random()
            if s.token_profile == "short":
                in_tok = rng.randint(30, 140)
                out_tok = rng.randint(80, 320)
            elif s.token_profile == "long":
                if p < 0.45:
                    in_tok = rng.randint(260, 800)
                    out_tok = rng.randint(700, 2200)
                else:
                    in_tok = rng.randint(800, 1600)
                    out_tok = rng.randint(1700, 4200)
            elif s.token_profile == "token_heavy":
                in_tok = rng.randint(180, 920)
                out_tok = rng.randint(700, 2800)
            else:
                if p < 0.5:
                    in_tok = rng.randint(40, 160)
                    out_tok = rng.randint(80, 260)
                elif p < 0.85:
                    in_tok = rng.randint(160, 420)
                    out_tok = rng.randint(260, 900)
                else:
                    in_tok = rng.randint(420, 1100)
                    out_tok = rng.randint(900, 2600)
            chunks = max(4, out_tok // 32)
            ttft_delay = 0.035 + min(0.25, chunks * 0.0024) + rng.random() * 0.03
            service = max(ttft_delay + 0.02, 0.08 + chunks * 0.012 + rng.random() * 0.1)
            reqs.append(sim.Request(rid, t, in_tok, out_tok, chunks, ttft_delay, service))
            rid += 1
    reqs.sort(key=lambda x: x.arrival_ts)
    return reqs


class TraditionalPolicy:
    # 传统：随机采样 + 最小并发 + 固定并发阈值
    def __init__(self, fixed_limit=95):
        self.fixed_limit = fixed_limit

    def estimate_tokens(self, req):
        return req.total_tokens

    def on_window(self, _metrics):
        return

    def allowed_concurrency(self):
        return self.fixed_limit

    def admit(self, _now, _est_tokens):
        return True

    def on_result(self, _req, _accepted):
        return


class FullPipelinePolicy:
    # 完整方案：token估算 + 动态分桶 + 预算抑制alpha + 固定并发护栏
    def __init__(self, total_rpm, total_tpm, fixed_limit=95):
        self.fixed_limit = fixed_limit
        self.total_rpm = total_rpm
        self.total_tpm = total_tpm
        self.alpha = 1.0
        self.boundaries = [300, 1200, 3800]
        self.shares = [0.35, 0.35, 0.20, 0.10]
        self.rpm_tokens = [0.0] * 4
        self.tpm_tokens = [0.0] * 4
        self.last_refill = 0.0

        self.hist_tokens = deque(maxlen=12000)
        self.win_arrival = [0, 0, 0, 0]
        self.win_accept = [0, 0, 0, 0]
        self.win_tokens = [0, 0, 0, 0]
        self.win_reject = [0, 0, 0, 0]
        self.est_ratio_ema = 1.0

    def bucket_of(self, tokens):
        if tokens <= self.boundaries[0]:
            return 0
        if tokens <= self.boundaries[1]:
            return 1
        if tokens <= self.boundaries[2]:
            return 2
        return 3

    def estimate_tokens(self, req):
        # 简化估算：输入+预估输出，再加EMA修正
        base = req.input_tokens + int(req.output_tokens * 0.85)
        est = int(max(1, base * self.est_ratio_ema * 1.08))
        self.hist_tokens.append(est)
        b = self.bucket_of(est)
        self.win_arrival[b] += 1
        return est

    def _refill(self, now):
        dt = max(0.0, now - self.last_refill)
        if dt <= 0:
            return
        eff_rpm = self.total_rpm * self.alpha
        eff_tpm = self.total_tpm * self.alpha
        for i in range(4):
            rr = (eff_rpm * self.shares[i]) / 60.0
            tr = (eff_tpm * self.shares[i]) / 60.0
            self.rpm_tokens[i] = min(rr * 3, self.rpm_tokens[i] + rr * dt)
            self.tpm_tokens[i] = min(tr * 3, self.tpm_tokens[i] + tr * dt)
        self.last_refill = now

    def admit(self, now, est_tokens):
        self._refill(now)
        b = self.bucket_of(est_tokens)
        if self.rpm_tokens[b] >= 1.0 and self.tpm_tokens[b] >= est_tokens:
            self.rpm_tokens[b] -= 1.0
            self.tpm_tokens[b] -= est_tokens
            self.win_accept[b] += 1
            self.win_tokens[b] += est_tokens
            return True
        self.win_reject[b] += 1
        return False

    def on_result(self, req, accepted):
        if not accepted:
            return
        est = max(1, req.input_tokens + int(req.output_tokens * 0.85))
        ratio = req.total_tokens / est
        self.est_ratio_ema = 0.9 * self.est_ratio_ema + 0.1 * ratio

    def _smooth_boundary(self, old, new):
        raw = int(0.7 * old + 0.3 * new)
        step = int(old * 0.15)
        return max(old - step, min(old + step, raw))

    def _rebalance_buckets(self):
        if len(self.hist_tokens) < 200:
            return
        arr = list(self.hist_tokens)
        p40 = int(percentile(arr, 40))
        p75 = int(percentile(arr, 75))
        p92 = int(percentile(arr, 92))
        b1 = self._smooth_boundary(self.boundaries[0], p40)
        b2 = self._smooth_boundary(self.boundaries[1], max(p40 + 64, p75))
        b3 = self._smooth_boundary(self.boundaries[2], max(p75 + 256, p92))
        if b1 < b2 < b3:
            self.boundaries = [b1, b2, b3]

        eps = 1e-9
        a_sum = sum(self.win_arrival) + eps
        ok_sum = sum(self.win_accept) + eps
        tok_sum = sum(self.win_tokens) + eps
        target = []
        for i in range(4):
            req_share = self.win_arrival[i] / a_sum
            ok_share = self.win_accept[i] / ok_sum
            tok_share = self.win_tokens[i] / tok_sum
            risk = self.win_reject[i] / max(1, self.win_arrival[i])
            score = 0.35 * req_share + 0.35 * ok_share + 0.20 * tok_share - 0.25 * risk
            target.append(max(0.10, min(0.50, score)))
        s = sum(target)
        target = [x / s for x in target]
        new_shares = []
        for old, tgt in zip(self.shares, target):
            d = max(-0.05, min(0.05, tgt - old))
            new_shares.append(old + d)
        s2 = sum(new_shares)
        self.shares = [x / s2 for x in new_shares]

        self.win_arrival = [0, 0, 0, 0]
        self.win_accept = [0, 0, 0, 0]
        self.win_tokens = [0, 0, 0, 0]
        self.win_reject = [0, 0, 0, 0]

    def on_window(self, metrics):
        # 预算抑制：围绕吞吐/GC/TTFT折中
        if metrics["p95_ttft_ms"] > 1400 or metrics["sim_gc_freq"] > 1.8 or metrics["reject_rate"] > 0.50:
            self.alpha = max(0.65, self.alpha * 0.92)
        elif metrics["p95_ttft_ms"] < 1150 and metrics["sim_gc_freq"] < 1.2 and metrics["reject_rate"] < 0.40:
            self.alpha = min(1.0, self.alpha + 0.02)
        self._rebalance_buckets()

    def allowed_concurrency(self):
        return self.fixed_limit


def run_engine(reqs, nodes, policy, timeout_sec=1.2, window_sec=5):
    queue = deque()
    inflight = []
    idx = 0
    now = 0.0
    dt = 0.02
    end = max(r.arrival_ts for r in reqs) + 20

    accepted = rejected = accepted_tokens = 0
    ttft_ms, e2e_ms, queue_wait_ms = [], [], []
    reason = defaultdict(int)
    peak_concurrency = 0
    alloc_units = 0
    young_heap = 0

    series = []
    sec = 0
    s_acc = s_rej = s_gc = 0
    s_ttft = []
    s_e2e = []
    s_q = []
    tick = 0

    while now <= end:
        while idx < len(reqs) and reqs[idx].arrival_ts <= now:
            queue.append(reqs[idx])
            idx += 1

        completed = [x for x in inflight if x.finish_ts <= now]
        if completed:
            left = []
            done = set()
            for c in completed:
                nodes[c.node_id].finish()
                done.add(c.req.id)
                ttft = (c.start_ts - c.req.arrival_ts + c.req.ttft_delay_sec) * 1000
                e2e = (c.finish_ts - c.req.arrival_ts) * 1000
                qw = (c.start_ts - c.req.arrival_ts) * 1000
                ttft_ms.append(ttft)
                e2e_ms.append(e2e)
                queue_wait_ms.append(qw)
                s_ttft.append(ttft)
                s_e2e.append(e2e)
                s_q.append(qw)
            for x in inflight:
                if x.req.id not in done:
                    left.append(x)
            inflight = left

        while queue and (now - queue[0].arrival_ts) > timeout_sec:
            queue.popleft()
            rejected += 1
            s_rej += 1
            reason["QUEUE_TIMEOUT"] += 1

        total_inflight = sum(n.inflight for n in nodes)
        while queue and total_inflight < policy.allowed_concurrency():
            req = queue[0]
            est_tokens = policy.estimate_tokens(req)
            if not policy.admit(now, est_tokens):
                reason["BUDGET_BLOCK"] += 1
                break

            sample = random.sample(nodes, k=min(3, len(nodes)))
            sample.sort(key=lambda n: n.inflight)
            started = False
            for node in sample:
                ok, why = node.can_start(now, est_tokens, policy.allowed_concurrency())
                if ok:
                    node.start(now, est_tokens)
                    inflight.append(Inflight(req, now, now + req.service_time_sec, node.node_id))
                    queue.popleft()
                    accepted += 1
                    s_acc += 1
                    accepted_tokens += req.total_tokens
                    total_inflight += 1
                    started = True
                    policy.on_result(req, True)
                    alloc_units += req.stream_chunks * 3 + 8
                    young_heap += req.stream_chunks * 1024 + req.output_tokens * 16
                    if young_heap > 3_000_000:
                        s_gc += 1
                        young_heap = int(young_heap * 0.35)
                    break
                reason[why] += 1
            if not started:
                policy.on_result(req, False)
                break

        peak_concurrency = max(peak_concurrency, sum(n.inflight for n in nodes))

        while now >= sec + 1.0:
            reject_rate = s_rej / max(1, (s_acc + s_rej))
            metrics = {
                "p95_ttft_ms": percentile(s_ttft, 95),
                "p95_e2e_ms": percentile(s_e2e, 95),
                "sim_gc_freq": s_gc,
                "reject_rate": reject_rate,
            }
            if tick % window_sec == 0:
                policy.on_window(metrics)
            series.append(
                {
                    "second": sec,
                    "accepted": s_acc,
                    "rejected": s_rej,
                    "p95_ttft_ms": metrics["p95_ttft_ms"],
                    "p95_e2e_ms": metrics["p95_e2e_ms"],
                    "sim_gc_freq": s_gc,
                }
            )
            sec += 1
            tick += 1
            s_acc = s_rej = s_gc = 0
            s_ttft, s_e2e, s_q = [], [], []
        now += dt

    total_rpm = sum(n.rpm_limit for n in nodes)
    total_tpm = sum(n.tpm_limit for n in nodes)
    elapsed = max(1.0, end)
    rpm_util = min(1.0, (accepted * 60.0 / elapsed) / total_rpm)
    tpm_util = min(1.0, (accepted_tokens * 60.0 / elapsed) / total_tpm)
    return {
        "accepted": accepted,
        "rejected": rejected,
        "reject_rate": rejected / max(1, (accepted + rejected)),
        "actual_rpm": accepted * 60.0 / elapsed,
        "actual_tpm": accepted_tokens * 60.0 / elapsed,
        "rpm_util": rpm_util,
        "tpm_util": tpm_util,
        "max_util": max(rpm_util, tpm_util),
        "p95_ttft_ms": percentile(ttft_ms, 95),
        "p95_e2e_ms": percentile(e2e_ms, 95),
        "p95_queue_ms": percentile(queue_wait_ms, 95),
        "sim_gc_avg_freq": mean([x["sim_gc_freq"] for x in series]) if series else 0.0,
        "sim_gc_peak_freq": max([x["sim_gc_freq"] for x in series]) if series else 0.0,
        "peak_concurrency": peak_concurrency,
        "allocation_units": alloc_units,
        "reasons": dict(reason),
        "series": series,
        "policy_state": {
            "alpha": getattr(policy, "alpha", None),
            "boundaries": getattr(policy, "boundaries", None),
            "shares": getattr(policy, "shares", None),
        },
    }


def draw_svg(path: Path, title: str, a, b, la, lb):
    width, height = 1000, 320
    left, right, top, bottom = 55, 20, 30, 40
    pw, ph = width - left - right, height - top - bottom
    n = max(1, min(len(a), len(b)) - 1)
    max_y = max(1.0, max(a[: n + 1]), max(b[: n + 1]))

    def pts(data):
        out = []
        for i in range(n + 1):
            x = left + (i / n) * pw if n > 0 else left
            y = top + (1.0 - (data[i] / max_y)) * ph
            out.append(f"{x:.2f},{y:.2f}")
        return " ".join(out)

    s = []
    s.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">')
    s.append(f'<rect x="0" y="0" width="{width}" height="{height}" fill="#fff"/>')
    s.append(f'<text x="{left}" y="18" font-size="14" fill="#111">{title}</text>')
    s.append(f'<line x1="{left}" y1="{top}" x2="{left}" y2="{top+ph}" stroke="#999"/>')
    s.append(f'<line x1="{left}" y1="{top+ph}" x2="{left+pw}" y2="{top+ph}" stroke="#999"/>')
    s.append(f'<polyline fill="none" stroke="#ef4444" stroke-width="2" points="{pts(a)}"/>')
    s.append(f'<polyline fill="none" stroke="#2563eb" stroke-width="2" points="{pts(b)}"/>')
    s.append(f'<text x="{left+pw-220}" y="{top+16}" font-size="12" fill="#ef4444">{la}</text>')
    s.append(f'<text x="{left+pw-120}" y="{top+16}" font-size="12" fill="#2563eb">{lb}</text>')
    s.append("</svg>")
    path.write_text("".join(s), encoding="utf-8")


def run():
    scenarios = [
        Scenario("balanced_steady", 180, 78, 78, [], "mixed", "balanced_high"),
        Scenario("mixed_bursty", 180, 85, 240, [(35, 80), (120, 155)], "mixed", "balanced"),
        Scenario("token_heavy", 180, 95, 170, [(60, 95), (130, 160)], "token_heavy", "tpm_tight"),
        Scenario("long_context", 180, 70, 145, [(70, 120)], "long", "tpm_tight"),
    ]

    rows = []
    report = ["# Full Pipeline vs Traditional LB\n", "对比：完整负载均衡链路 vs 传统随机采样+最小并发。\n"]

    for i, sc in enumerate(scenarios, start=1):
        reqs = generate_requests(sc, seed=900 + i)
        nodes_a = make_nodes(sc.node_profile)
        nodes_b = make_nodes(sc.node_profile)
        total_rpm = sum(n.rpm_limit for n in nodes_a)
        total_tpm = sum(n.tpm_limit for n in nodes_a)

        traditional = run_engine(reqs, nodes_a, TraditionalPolicy(fixed_limit=95))
        full = run_engine(reqs, nodes_b, FullPipelinePolicy(total_rpm, total_tpm, fixed_limit=95))

        rows.append(
            {
                "scenario": sc.name,
                "traditional_rpm": round(traditional["actual_rpm"], 2),
                "full_rpm": round(full["actual_rpm"], 2),
                "traditional_max_util": round(traditional["max_util"], 4),
                "full_max_util": round(full["max_util"], 4),
                "traditional_ttft_p95_ms": round(traditional["p95_ttft_ms"], 2),
                "full_ttft_p95_ms": round(full["p95_ttft_ms"], 2),
                "traditional_e2e_p95_ms": round(traditional["p95_e2e_ms"], 2),
                "full_e2e_p95_ms": round(full["p95_e2e_ms"], 2),
                "traditional_reject_rate": round(traditional["reject_rate"], 4),
                "full_reject_rate": round(full["reject_rate"], 4),
                "traditional_gc_avg": round(traditional["sim_gc_avg_freq"], 4),
                "full_gc_avg": round(full["sim_gc_avg_freq"], 4),
                "full_alpha_final": round(full["policy_state"]["alpha"] or 0.0, 4),
                "full_boundaries_final": str(full["policy_state"]["boundaries"]),
                "full_shares_final": str([round(x, 4) for x in (full["policy_state"]["shares"] or [])]),
            }
        )

        draw_svg(
            OUT / f"full_vs_traditional_{sc.name}_ttft.svg",
            f"{sc.name}: TTFT p95 per second",
            [x["p95_ttft_ms"] for x in traditional["series"]],
            [x["p95_ttft_ms"] for x in full["series"]],
            "Traditional",
            "FullPipeline",
        )
        draw_svg(
            OUT / f"full_vs_traditional_{sc.name}_gc.svg",
            f"{sc.name}: GC freq per second",
            [x["sim_gc_freq"] for x in traditional["series"]],
            [x["sim_gc_freq"] for x in full["series"]],
            "Traditional",
            "FullPipeline",
        )

        report.append(f"## {sc.name}")
        report.append(f"- RPM: Traditional={traditional['actual_rpm']:.2f}, Full={full['actual_rpm']:.2f}")
        report.append(f"- max(rpm_util,tpm_util): Traditional={traditional['max_util']*100:.2f}%, Full={full['max_util']*100:.2f}%")
        report.append(f"- TTFT p95(ms): Traditional={traditional['p95_ttft_ms']:.2f}, Full={full['p95_ttft_ms']:.2f}")
        report.append(f"- E2E p95(ms): Traditional={traditional['p95_e2e_ms']:.2f}, Full={full['p95_e2e_ms']:.2f}")
        report.append(f"- Reject rate: Traditional={traditional['reject_rate']*100:.2f}%, Full={full['reject_rate']*100:.2f}%")
        report.append(f"- GC avg(/s): Traditional={traditional['sim_gc_avg_freq']:.2f}, Full={full['sim_gc_avg_freq']:.2f}")
        report.append(f"- Full final alpha: {full['policy_state']['alpha']:.3f}")
        report.append(f"- Full boundaries: {full['policy_state']['boundaries']}")
        report.append(f"- Full shares: {[round(x,4) for x in full['policy_state']['shares']]}")
        report.append("")

    with (OUT / "full_vs_traditional_summary.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    (OUT / "full_vs_traditional_report.md").write_text("\n".join(report), encoding="utf-8")


if __name__ == "__main__":
    run()
