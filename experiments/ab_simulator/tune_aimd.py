import csv
import json
import random
from dataclasses import dataclass, asdict
from pathlib import Path

import ab_test_simulator as sim


OUTPUT_DIR = Path(__file__).parent / "outputs"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


@dataclass
class AimdParams:
    min_c: int
    max_c: int
    init_c: int
    ssthresh: int
    decrease_factor: float
    cooldown_windows: int
    rate_limit_threshold: float
    burst_threshold: float
    p95_threshold_ms: float
    gc_threshold: float
    slow_start_factor: float
    ai_step: float


class TunableAimdController:
    def __init__(self, p: AimdParams):
        self.p = p
        self.cwnd = float(p.init_c)
        self.ssthresh = float(p.ssthresh)
        self.cooldown = 0

    def current_limit(self):
        return max(self.p.min_c, min(self.p.max_c, int(self.cwnd)))

    def update(self, _second: int, m: dict):
        if self.cooldown > 0:
            self.cooldown -= 1
            return
        congested = (
            m["rate_limit"] > self.p.rate_limit_threshold
            or m["burst"] > self.p.burst_threshold
            or m["p95_latency_ms"] > self.p.p95_threshold_ms
            or m["gc_freq"] > self.p.gc_threshold
        )
        if congested:
            self.ssthresh = max(self.p.min_c, self.cwnd * self.p.decrease_factor)
            self.cwnd = max(float(self.p.min_c), self.ssthresh)
            self.cooldown = self.p.cooldown_windows
            return
        if self.cwnd < self.ssthresh:
            self.cwnd = min(float(self.p.max_c), self.cwnd * self.p.slow_start_factor)
        else:
            self.cwnd = min(float(self.p.max_c), self.cwnd + self.p.ai_step)


def sample_params(rng: random.Random) -> AimdParams:
    max_c = rng.choice([72, 84, 96, 108])
    min_c = rng.choice([4, 6, 8, 10])
    init_c = rng.choice([6, 8, 10, 12, 14, 16])
    ssthresh = rng.choice([16, 24, 32, 40, 48])
    if init_c > max_c:
        init_c = max_c
    if min_c >= max_c:
        min_c = max(2, max_c // 2)
    return AimdParams(
        min_c=min_c,
        max_c=max_c,
        init_c=max(init_c, min_c),
        ssthresh=min(max(ssthresh, min_c), max_c),
        decrease_factor=rng.choice([0.55, 0.6, 0.65, 0.7, 0.75, 0.8]),
        cooldown_windows=rng.choice([1, 2, 3]),
        rate_limit_threshold=rng.choice([0.03, 0.05, 0.07, 0.09]),
        burst_threshold=rng.choice([0.02, 0.03, 0.05]),
        p95_threshold_ms=rng.choice([1600, 1800, 2000, 2200]),
        gc_threshold=rng.choice([1.0, 1.2, 1.5, 1.8, 2.0]),
        slow_start_factor=rng.choice([1.2, 1.35, 1.5, 1.65, 1.8]),
        ai_step=rng.choice([0.8, 1.0, 1.2, 1.5]),
    )


def score_result(base: dict, cand: dict) -> float:
    rpm_gain = cand["actual_rpm"] / max(1e-9, base["actual_rpm"])
    util_gain = cand["composite_util"] / max(1e-9, base["composite_util"])
    gc_gain = base["sim_gc_avg_freq"] / max(1e-9, cand["sim_gc_avg_freq"])
    p95_gain = base["p95_latency_ms"] / max(1e-9, cand["p95_latency_ms"])

    # Hard constraints: avoid pathological tuning.
    if cand["p95_latency_ms"] > base["p95_latency_ms"] * 1.15:
        return -1e9
    if cand["sim_gc_avg_freq"] > base["sim_gc_avg_freq"] * 1.3:
        return -1e9

    score = (
        0.45 * rpm_gain
        + 0.35 * util_gain
        + 0.10 * gc_gain
        + 0.10 * p95_gain
    )
    # Soft constraints: do not accept throughput/utilization collapse.
    if rpm_gain < 0.90:
        score -= (0.90 - rpm_gain) * 4.0
    if util_gain < 0.90:
        score -= (0.90 - util_gain) * 4.0
    return score


def to_row(trial_id: int, p: AimdParams, result: dict, score: float):
    row = {"trial_id": trial_id, "score": round(score, 6)}
    row.update(asdict(p))
    row.update(
        {
            "actual_rpm": round(result["actual_rpm"], 4),
            "actual_tpm": round(result["actual_tpm"], 4),
            "rpm_util": round(result["rpm_util"], 6),
            "tpm_util": round(result["tpm_util"], 6),
            "conc_util": round(result["conc_util"], 6),
            "composite_util": round(result["composite_util"], 6),
            "p95_latency_ms": round(result["p95_latency_ms"], 4),
            "p99_latency_ms": round(result["p99_latency_ms"], 4),
            "avg_queue_wait_ms": round(result["avg_queue_wait_ms"], 4),
            "sim_gc_avg_freq": round(result["sim_gc_avg_freq"], 4),
            "sim_gc_peak_freq": round(result["sim_gc_peak_freq"], 4),
            "accepted": result["accepted"],
            "rejected": result["rejected"],
        }
    )
    return row


def pct(v: float) -> str:
    return f"{v * 100:.2f}%"


def write_csv(path: Path, rows: list):
    if not rows:
        return
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)


def run_tuning():
    rng = random.Random(20260215)
    reqs = sim.generate_requests(duration_sec=180, seed=42)

    baseline = sim.run_simulation("fixed", reqs, sim.FixedController(fixed_limit=95))

    trials = 80
    rows = []
    best_score = -1e18
    best_params = None
    best_result = None

    for i in range(1, trials + 1):
        p = sample_params(rng)
        result = sim.run_simulation("aimd_tuned", reqs, TunableAimdController(p))
        score = score_result(baseline, result)
        rows.append(to_row(i, p, result, score))
        if score > best_score:
            best_score = score
            best_params = p
            best_result = result

    # Re-run best once for deterministic artifact generation.
    best_result = sim.run_simulation("aimd_best", reqs, TunableAimdController(best_params))
    best_score = score_result(baseline, best_result)

    write_csv(OUTPUT_DIR / "aimd_tuning_results.csv", rows)
    (OUTPUT_DIR / "aimd_best_params.json").write_text(
        json.dumps(asdict(best_params), ensure_ascii=False, indent=2), encoding="utf-8"
    )

    sim.write_timeseries_csv(
        OUTPUT_DIR / "ab_tuned_timeseries.csv",
        baseline["timeseries"],
        best_result["timeseries"],
    )
    sim.draw_svg(
        OUTPUT_DIR / "ab_tuned_gc_frequency.svg",
        "GC Frequency Comparison: Fixed vs Tuned AIMD",
        [x["sim_gc_freq"] for x in baseline["timeseries"]],
        [x["sim_gc_freq"] for x in best_result["timeseries"]],
        label_a="Fixed",
        label_b="Tuned AIMD",
    )
    sim.draw_svg(
        OUTPUT_DIR / "ab_tuned_throughput.svg",
        "Throughput Comparison: Fixed vs Tuned AIMD",
        [x["accepted"] for x in baseline["timeseries"]],
        [x["accepted"] for x in best_result["timeseries"]],
        label_a="Fixed",
        label_b="Tuned AIMD",
    )

    report = []
    report.append("# Tuned A/B Report (Fixed vs Best AIMD)\n")
    report.append("## Tuning Setup\n")
    report.append("- Search strategy: random search")
    report.append("- Trials: 80")
    report.append("- Workload: 180s mixed + burst simulated streaming")
    report.append("- Objective: throughput/composite-util up, GC and latency not degraded\n")
    report.append("## Best Parameters\n")
    for k, v in asdict(best_params).items():
        report.append(f"- `{k}`: {v}")
    report.append("")
    report.append("## Metrics Comparison\n")
    report.append("| Metric | Fixed | Tuned AIMD | Delta |")
    report.append("|---|---:|---:|---:|")

    def add_row(name, a, b, is_percent=False):
        if is_percent:
            aa, bb = pct(a), pct(b)
            delta = f"{(b-a)*100:+.2f}pp"
        else:
            aa, bb = f"{a:.2f}", f"{b:.2f}"
            delta = f"{(b-a):+.2f}"
        report.append(f"| {name} | {aa} | {bb} | {delta} |")

    add_row("Actual RPM", baseline["actual_rpm"], best_result["actual_rpm"])
    add_row("Actual TPM", baseline["actual_tpm"], best_result["actual_tpm"])
    add_row("RPM Utilization", baseline["rpm_util"], best_result["rpm_util"], True)
    add_row("TPM Utilization", baseline["tpm_util"], best_result["tpm_util"], True)
    add_row("Composite Utilization", baseline["composite_util"], best_result["composite_util"], True)
    add_row("P95 Latency (ms)", baseline["p95_latency_ms"], best_result["p95_latency_ms"])
    add_row("P99 Latency (ms)", baseline["p99_latency_ms"], best_result["p99_latency_ms"])
    add_row("Avg Queue Wait (ms)", baseline["avg_queue_wait_ms"], best_result["avg_queue_wait_ms"])
    add_row("Sim GC Avg Freq (/s)", baseline["sim_gc_avg_freq"], best_result["sim_gc_avg_freq"])
    add_row("Sim GC Peak Freq (/s)", baseline["sim_gc_peak_freq"], best_result["sim_gc_peak_freq"])
    add_row("Objective Score", score_result(baseline, baseline), best_score)

    report.append("")
    report.append("## Output Files\n")
    report.append("- `outputs/aimd_tuning_results.csv`")
    report.append("- `outputs/aimd_best_params.json`")
    report.append("- `outputs/ab_tuned_timeseries.csv`")
    report.append("- `outputs/ab_tuned_gc_frequency.svg`")
    report.append("- `outputs/ab_tuned_throughput.svg`")
    report.append("- `outputs/ab_tuned_report.md`")

    (OUTPUT_DIR / "ab_tuned_report.md").write_text("\n".join(report), encoding="utf-8")


if __name__ == "__main__":
    run_tuning()
