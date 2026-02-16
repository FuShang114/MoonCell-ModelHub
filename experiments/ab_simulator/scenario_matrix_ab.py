import csv
import json
import random
from dataclasses import dataclass
from pathlib import Path

import ab_test_simulator as sim


OUTPUT_DIR = Path(__file__).parent / "outputs"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


@dataclass
class Scenario:
    name: str
    duration_sec: int
    base_lam: int
    burst_lam: int
    burst_ranges: list
    token_profile: str
    node_profile: str


class TunedAimdController:
    def __init__(self, params: dict):
        self.min_c = params.get("min_c", 8)
        self.max_c = params.get("max_c", 96)
        self.cwnd = float(params.get("init_c", 14))
        self.ssthresh = float(params.get("ssthresh", 24))
        self.decrease_factor = params.get("decrease_factor", 0.75)
        self.cooldown_windows = params.get("cooldown_windows", 1)
        self.rate_limit_threshold = params.get("rate_limit_threshold", 0.05)
        self.burst_threshold = params.get("burst_threshold", 0.05)
        self.p95_threshold_ms = params.get("p95_threshold_ms", 2200)
        self.gc_threshold = params.get("gc_threshold", 2.0)
        self.slow_start_factor = params.get("slow_start_factor", 1.8)
        self.ai_step = params.get("ai_step", 1.0)
        self.cooldown = 0

    def current_limit(self):
        return max(self.min_c, min(self.max_c, int(self.cwnd)))

    def update(self, _second: int, m: dict):
        if self.cooldown > 0:
            self.cooldown -= 1
            return
        congested = (
            m["rate_limit"] > self.rate_limit_threshold
            or m["burst"] > self.burst_threshold
            or m["p95_latency_ms"] > self.p95_threshold_ms
            or m["gc_freq"] > self.gc_threshold
        )
        if congested:
            self.ssthresh = max(self.min_c, self.cwnd * self.decrease_factor)
            self.cwnd = max(float(self.min_c), self.ssthresh)
            self.cooldown = self.cooldown_windows
            return
        if self.cwnd < self.ssthresh:
            self.cwnd = min(float(self.max_c), self.cwnd * self.slow_start_factor)
        else:
            self.cwnd = min(float(self.max_c), self.cwnd + self.ai_step)


def load_best_params():
    p = OUTPUT_DIR / "aimd_best_params.json"
    if p.exists():
        return json.loads(p.read_text(encoding="utf-8"))
    return {
        "min_c": 8,
        "max_c": 96,
        "init_c": 14,
        "ssthresh": 24,
        "decrease_factor": 0.75,
        "cooldown_windows": 1,
        "rate_limit_threshold": 0.05,
        "burst_threshold": 0.05,
        "p95_threshold_ms": 2200,
        "gc_threshold": 2.0,
        "slow_start_factor": 1.8,
        "ai_step": 1.0,
    }


def sample_tokens(profile: str, rng: random.Random):
    p = rng.random()
    if profile == "short":
        in_tok = rng.randint(30, 140)
        out_tok = rng.randint(80, 300)
    elif profile == "long":
        if p < 0.4:
            in_tok = rng.randint(260, 900)
            out_tok = rng.randint(700, 2400)
        else:
            in_tok = rng.randint(900, 1700)
            out_tok = rng.randint(1800, 4200)
    elif profile == "token_heavy":
        in_tok = rng.randint(180, 900)
        out_tok = rng.randint(700, 2800)
    else:  # mixed
        if p < 0.5:
            in_tok = rng.randint(40, 160)
            out_tok = rng.randint(80, 260)
        elif p < 0.85:
            in_tok = rng.randint(160, 420)
            out_tok = rng.randint(260, 900)
        else:
            in_tok = rng.randint(420, 1100)
            out_tok = rng.randint(900, 2600)
    return in_tok, out_tok


def generate_requests_for_scenario(s: Scenario, seed: int):
    rng = random.Random(seed)
    reqs = []
    rid = 0
    for sec in range(s.duration_sec):
        lam = s.base_lam
        for a, b in s.burst_ranges:
            if a <= sec < b:
                lam = s.burst_lam
                break
        n = max(1, int(rng.gauss(lam, lam * 0.12)))
        for _ in range(n):
            t = sec + rng.random()
            in_tok, out_tok = sample_tokens(s.token_profile, rng)
            chunks = max(4, out_tok // 32)
            ttft_delay = 0.035 + min(0.25, chunks * 0.0024) + rng.random() * 0.03
            service = max(ttft_delay + 0.02, 0.08 + chunks * 0.012 + rng.random() * 0.1)
            reqs.append(sim.Request(rid, t, in_tok, out_tok, chunks, ttft_delay, service))
            rid += 1
    reqs.sort(key=lambda r: r.arrival_ts)
    return reqs


def make_node_factory(profile: str):
    def factory():
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
                burst = max(7.0, (rpm / 60.0) * 0.9)
            nodes.append(
                sim.NodeState(
                    node_id=i,
                    rpm_limit=rpm,
                    tpm_limit=tpm,
                    max_physical_concurrency=14 + i,
                    burst_rps_limit=burst,
                )
            )
        return nodes

    return factory


def ratio(a, b):
    if a == 0:
        return 0.0
    return b / a


def classify(fixed: dict, aimd: dict):
    throughput = ratio(fixed["actual_rpm"], aimd["actual_rpm"])
    util = ratio(fixed["composite_util"], aimd["composite_util"])
    ttft = fixed["p95_ttft_ms"] / max(1e-9, aimd["p95_ttft_ms"])
    e2e = fixed["p95_latency_ms"] / max(1e-9, aimd["p95_latency_ms"])
    gc_gain = fixed["sim_gc_avg_freq"] / max(1e-9, aimd["sim_gc_avg_freq"])
    reject = fixed["reject_rate"] / max(1e-9, aimd["reject_rate"])

    score = 0.33 * throughput + 0.27 * util + 0.16 * ttft + 0.12 * e2e + 0.08 * gc_gain + 0.04 * reject

    if throughput >= 0.98 and util >= 0.98 and (ttft >= 1.0 or gc_gain >= 1.08):
        tag = "AIMD优秀"
    elif throughput < 0.9 or util < 0.9:
        tag = "AIMD不适合当前场景"
    else:
        tag = "AIMD中性/需进一步调优"
    return tag, score


def run_matrix():
    scenarios = [
        Scenario("steady_balanced", 180, 75, 75, [], "mixed", "balanced_high"),
        Scenario("steady_high_pressure", 180, 130, 130, [], "mixed", "balanced"),
        Scenario("bursty_traffic", 180, 85, 240, [(35, 80), (120, 155)], "mixed", "balanced"),
        Scenario("long_context_heavy", 180, 70, 140, [(70, 120)], "long", "tpm_tight"),
        Scenario("short_chat_realtime", 180, 110, 210, [(40, 70), (140, 165)], "short", "rpm_tight"),
        Scenario("token_quota_tight", 180, 95, 170, [(60, 95), (130, 160)], "token_heavy", "tpm_tight"),
    ]

    best_params = load_best_params()
    rows = []
    report_lines = []
    report_lines.append("# Multi-Scenario A/B Test (Full Dimensions)\n")
    report_lines.append("对比对象：Fixed 并发控制 vs Tuned AIMD。")
    report_lines.append("")

    for i, s in enumerate(scenarios, start=1):
        reqs = generate_requests_for_scenario(s, seed=100 + i)
        node_factory = make_node_factory(s.node_profile)
        fixed = sim.run_simulation(
            f"{s.name}_fixed",
            reqs,
            sim.FixedController(fixed_limit=95),
            node_factory=node_factory,
        )
        aimd = sim.run_simulation(
            f"{s.name}_aimd",
            reqs,
            TunedAimdController(best_params),
            node_factory=node_factory,
        )
        tag, score = classify(fixed, aimd)

        row = {
            "scenario": s.name,
            "token_profile": s.token_profile,
            "node_profile": s.node_profile,
            "tag": tag,
            "score": round(score, 4),
            "fixed_rpm": round(fixed["actual_rpm"], 2),
            "aimd_rpm": round(aimd["actual_rpm"], 2),
            "fixed_composite_util": round(fixed["composite_util"], 4),
            "aimd_composite_util": round(aimd["composite_util"], 4),
            "fixed_p95_ttft_ms": round(fixed["p95_ttft_ms"], 2),
            "aimd_p95_ttft_ms": round(aimd["p95_ttft_ms"], 2),
            "fixed_p95_e2e_ms": round(fixed["p95_latency_ms"], 2),
            "aimd_p95_e2e_ms": round(aimd["p95_latency_ms"], 2),
            "fixed_reject_rate": round(fixed["reject_rate"], 4),
            "aimd_reject_rate": round(aimd["reject_rate"], 4),
            "fixed_gc_avg": round(fixed["sim_gc_avg_freq"], 4),
            "aimd_gc_avg": round(aimd["sim_gc_avg_freq"], 4),
            "fixed_tokens_per_sec": round(fixed["tokens_per_sec"], 2),
            "aimd_tokens_per_sec": round(aimd["tokens_per_sec"], 2),
        }
        rows.append(row)

        report_lines.append(f"## {s.name}")
        report_lines.append(f"- 结论：**{tag}**（score={score:.3f}）")
        report_lines.append(f"- 吞吐RPM：Fixed={fixed['actual_rpm']:.2f}, AIMD={aimd['actual_rpm']:.2f}")
        report_lines.append(f"- 复合利用率：Fixed={fixed['composite_util']*100:.2f}%, AIMD={aimd['composite_util']*100:.2f}%")
        report_lines.append(f"- TTFT p95(ms)：Fixed={fixed['p95_ttft_ms']:.2f}, AIMD={aimd['p95_ttft_ms']:.2f}")
        report_lines.append(f"- E2E p95(ms)：Fixed={fixed['p95_latency_ms']:.2f}, AIMD={aimd['p95_latency_ms']:.2f}")
        report_lines.append(f"- Reject率：Fixed={fixed['reject_rate']*100:.2f}%, AIMD={aimd['reject_rate']*100:.2f}%")
        report_lines.append(f"- GC均值(/s)：Fixed={fixed['sim_gc_avg_freq']:.2f}, AIMD={aimd['sim_gc_avg_freq']:.2f}")
        report_lines.append("")

    with (OUTPUT_DIR / "scenario_matrix_results.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    report_lines.append("## Overall Summary")
    good = [r["scenario"] for r in rows if r["tag"] == "AIMD优秀"]
    neutral = [r["scenario"] for r in rows if r["tag"] == "AIMD中性/需进一步调优"]
    bad = [r["scenario"] for r in rows if r["tag"] == "AIMD不适合当前场景"]
    report_lines.append(f"- AIMD优秀场景：{', '.join(good) if good else '无'}")
    report_lines.append(f"- AIMD中性场景：{', '.join(neutral) if neutral else '无'}")
    report_lines.append(f"- AIMD不适合场景：{', '.join(bad) if bad else '无'}")
    report_lines.append("- 输出明细：`outputs/scenario_matrix_results.csv`")

    (OUTPUT_DIR / "scenario_matrix_report.md").write_text("\n".join(report_lines), encoding="utf-8")


if __name__ == "__main__":
    run_matrix()
