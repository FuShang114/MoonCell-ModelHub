import csv
import gc
import math
import random
from collections import deque, defaultdict
from dataclasses import dataclass
from pathlib import Path
from statistics import mean


OUTPUT_DIR = Path(__file__).parent / "outputs"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


@dataclass(frozen=True)
class Request:
    id: int
    arrival_ts: float
    input_tokens: int
    output_tokens: int
    stream_chunks: int
    ttft_delay_sec: float
    service_time_sec: float

    @property
    def total_tokens(self) -> int:
        return self.input_tokens + self.output_tokens


@dataclass
class Inflight:
    req: Request
    start_ts: float
    finish_ts: float
    node_id: int


@dataclass
class NodeState:
    node_id: int
    rpm_limit: int
    tpm_limit: int
    max_physical_concurrency: int
    burst_rps_limit: float
    inflight: int = 0
    rpm_tokens: float = 0.0
    tpm_tokens: float = 0.0
    last_refill_ts: float = 0.0
    starts_window: deque = None

    def __post_init__(self):
        self.rpm_tokens = float(self.rpm_limit)
        self.tpm_tokens = float(self.tpm_limit)
        self.starts_window = deque()

    def refill(self, now: float):
        dt = max(0.0, now - self.last_refill_ts)
        if dt <= 0:
            return
        self.rpm_tokens = min(self.rpm_limit, self.rpm_tokens + dt * (self.rpm_limit / 60.0))
        self.tpm_tokens = min(self.tpm_limit, self.tpm_tokens + dt * (self.tpm_limit / 60.0))
        self.last_refill_ts = now
        while self.starts_window and self.starts_window[0] < now - 1.0:
            self.starts_window.popleft()

    def can_start(self, now: float, token_cost: int, allowed_concurrency: int) -> (bool, str):
        self.refill(now)
        if self.inflight >= min(self.max_physical_concurrency, allowed_concurrency):
            return False, "CONCURRENCY"
        if len(self.starts_window) >= self.burst_rps_limit:
            return False, "BURST"
        if self.rpm_tokens < 1.0:
            return False, "RATE_RPM"
        if self.tpm_tokens < token_cost:
            return False, "RATE_TPM"
        return True, ""

    def start(self, now: float, token_cost: int):
        self.rpm_tokens -= 1.0
        self.tpm_tokens -= token_cost
        self.inflight += 1
        self.starts_window.append(now)

    def finish(self):
        if self.inflight > 0:
            self.inflight -= 1


class FixedController:
    def __init__(self, fixed_limit: int):
        self.limit = fixed_limit

    def current_limit(self):
        return self.limit

    def update(self, _second: int, _window_metrics: dict):
        pass


class AimdController:
    def __init__(self, min_c=4, max_c=80, init_c=8):
        self.min_c = min_c
        self.max_c = max_c
        self.cwnd = init_c
        self.ssthresh = 32
        self.cooldown = 0

    def current_limit(self):
        return max(self.min_c, min(self.max_c, int(self.cwnd)))

    def update(self, _second: int, m: dict):
        if self.cooldown > 0:
            self.cooldown -= 1
            return
        congested = (
            m["rate_limit"] > 0.06
            or m["burst"] > 0.03
            or m["p95_latency_ms"] > 2200
            or m["gc_freq"] > 4.0
        )
        if congested:
            self.ssthresh = max(self.min_c, int(self.cwnd * 0.7))
            self.cwnd = max(self.min_c, self.ssthresh)
            self.cooldown = 2
            return
        if self.cwnd < self.ssthresh:
            self.cwnd = min(self.max_c, self.cwnd * 1.5)
        else:
            self.cwnd = min(self.max_c, self.cwnd + 1)


def generate_requests(duration_sec: int, seed: int = 7):
    random.seed(seed)
    reqs = []
    rid = 0
    for sec in range(duration_sec):
        # Base arrivals + burst windows.
        lam = 85
        if 35 <= sec < 80 or 115 <= sec < 155:
            lam = 190
        if 155 <= sec < 175:
            lam = 260
        n = max(1, int(random.gauss(lam, lam * 0.1)))
        for _ in range(n):
            t = sec + random.random()
            p = random.random()
            if p < 0.5:
                in_tok = random.randint(40, 160)
                out_tok = random.randint(80, 260)
            elif p < 0.85:
                in_tok = random.randint(160, 420)
                out_tok = random.randint(260, 900)
            else:
                in_tok = random.randint(420, 1100)
                out_tok = random.randint(900, 2600)
            chunks = max(4, out_tok // 32)
            ttft_delay = 0.035 + min(0.22, chunks * 0.0022) + random.random() * 0.025
            service = max(ttft_delay + 0.02, 0.08 + chunks * 0.012 + random.random() * 0.08)
            reqs.append(Request(rid, t, in_tok, out_tok, chunks, ttft_delay, service))
            rid += 1
    reqs.sort(key=lambda r: r.arrival_ts)
    return reqs


def make_nodes():
    nodes = []
    for i in range(6):
        rpm = 1150 + i * 80
        tpm = 520_000 + i * 45_000
        nodes.append(
            NodeState(
                node_id=i,
                rpm_limit=rpm,
                tpm_limit=tpm,
                max_physical_concurrency=14 + i,
                burst_rps_limit=max(6.0, (rpm / 60.0) * 0.8),
            )
        )
    return nodes


def percentile(values, p):
    if not values:
        return 0.0
    arr = sorted(values)
    k = min(len(arr) - 1, max(0, int(math.ceil((p / 100.0) * len(arr)) - 1)))
    return float(arr[k])


def run_simulation(name: str, reqs, controller, node_factory=None):
    gc.collect()
    gc_before = [s["collections"] for s in gc.get_stats()]

    nodes = node_factory() if node_factory else make_nodes()
    queue = deque()
    inflight = []
    idx = 0
    now = 0.0
    dt = 0.02
    end_time = max(r.arrival_ts for r in reqs) + 20

    accepted = 0
    rejected = 0
    rejected_reason = defaultdict(int)
    accepted_tokens = 0
    latencies_ms = []
    ttft_ms = []
    queue_wait_ms = []
    peak_concurrency = 0
    allocation_units = 0

    per_sec = []
    sec_cursor = 0
    sec_accepted = 0
    sec_rejected = 0
    sec_burst = 0
    sec_ratelimit = 0
    sec_lat = []
    sec_ttft = []
    simulated_gc_counter = 0
    young_heap = 0

    while now <= end_time:
        while idx < len(reqs) and reqs[idx].arrival_ts <= now:
            queue.append(reqs[idx])
            idx += 1

        completed = [x for x in inflight if x.finish_ts <= now]
        if completed:
            still = []
            done_ids = set()
            for c in completed:
                nodes[c.node_id].finish()
                done_ids.add(c.req.id)
                lat = (c.finish_ts - c.req.arrival_ts) * 1000.0
                wait = (c.start_ts - c.req.arrival_ts) * 1000.0
                ttft = (c.start_ts - c.req.arrival_ts + c.req.ttft_delay_sec) * 1000.0
                latencies_ms.append(lat)
                ttft_ms.append(ttft)
                queue_wait_ms.append(wait)
                sec_lat.append(lat)
                sec_ttft.append(ttft)
            for x in inflight:
                if x.req.id not in done_ids:
                    still.append(x)
            inflight = still

        while queue and (now - queue[0].arrival_ts) > 1.2:
            queue.popleft()
            rejected += 1
            sec_rejected += 1
            rejected_reason["QUEUE_TIMEOUT"] += 1

        cap = controller.current_limit()
        total_inflight = sum(n.inflight for n in nodes)
        while queue and total_inflight < cap:
            req = queue[0]
            sample = random.sample(nodes, k=min(3, len(nodes)))
            sample.sort(key=lambda n: n.inflight)
            started = False
            fail_reason = "CONCURRENCY"
            for node in sample:
                ok, reason = node.can_start(now, req.total_tokens, cap)
                if ok:
                    node.start(now, req.total_tokens)
                    inflight.append(Inflight(req=req, start_ts=now, finish_ts=now + req.service_time_sec, node_id=node.node_id))
                    queue.popleft()
                    accepted += 1
                    sec_accepted += 1
                    accepted_tokens += req.total_tokens
                    total_inflight += 1
                    started = True
                    allocation_units += req.stream_chunks * 3 + 8
                    # Simulated GC model: crossing threshold creates one GC event.
                    young_heap += req.stream_chunks * 1024 + req.output_tokens * 16
                    if young_heap > 3_000_000:
                        simulated_gc_counter += 1
                        young_heap = int(young_heap * 0.35)
                    break
                fail_reason = reason
            if not started:
                if fail_reason in ("RATE_RPM", "RATE_TPM"):
                    sec_ratelimit += 1
                if fail_reason == "BURST":
                    sec_burst += 1
                break

        peak_concurrency = max(peak_concurrency, sum(n.inflight for n in nodes))

        while now >= sec_cursor + 1.0:
            p95 = percentile(sec_lat, 95)
            p95_ttft = percentile(sec_ttft, 95)
            total = max(1, sec_accepted + sec_rejected)
            window_metrics = {
                "rate_limit": sec_ratelimit / total,
                "burst": sec_burst / total,
                "p95_latency_ms": p95,
                "p95_ttft_ms": p95_ttft,
                "gc_freq": simulated_gc_counter,
            }
            controller.update(sec_cursor, window_metrics)
            per_sec.append(
                {
                    "second": sec_cursor,
                    "accepted": sec_accepted,
                    "rejected": sec_rejected,
                    "burst_reject": sec_burst,
                    "rate_reject": sec_ratelimit,
                    "p95_latency_ms": p95,
                    "p95_ttft_ms": p95_ttft,
                    "controller_limit": controller.current_limit(),
                    "sim_gc_freq": simulated_gc_counter,
                }
            )
            sec_cursor += 1
            sec_accepted = 0
            sec_rejected = 0
            sec_burst = 0
            sec_ratelimit = 0
            sec_lat = []
            sec_ttft = []
            simulated_gc_counter = 0

        now += dt

    gc.collect()
    gc_after = [s["collections"] for s in gc.get_stats()]
    py_gc_collections = max(0, sum(gc_after) - sum(gc_before))

    elapsed = max(1.0, end_time)
    total_rpm_cap = sum(n.rpm_limit for n in nodes)
    total_tpm_cap = sum(n.tpm_limit for n in nodes)
    total_concurrency_cap = sum(n.max_physical_concurrency for n in nodes)
    actual_rpm = accepted * 60.0 / elapsed
    actual_tpm = accepted_tokens * 60.0 / elapsed
    rpm_util = min(1.0, actual_rpm / total_rpm_cap)
    tpm_util = min(1.0, actual_tpm / total_tpm_cap)
    conc_util = min(1.0, peak_concurrency / total_concurrency_cap)

    return {
        "name": name,
        "accepted": accepted,
        "rejected": rejected,
        "accepted_tokens": accepted_tokens,
        "actual_rpm": actual_rpm,
        "actual_tpm": actual_tpm,
        "rpm_util": rpm_util,
        "tpm_util": tpm_util,
        "conc_util": conc_util,
        "composite_util": min(rpm_util, tpm_util, conc_util),
        "success_rate": accepted / max(1, accepted + rejected),
        "reject_rate": rejected / max(1, accepted + rejected),
        "p95_ttft_ms": percentile(ttft_ms, 95),
        "p95_latency_ms": percentile(latencies_ms, 95),
        "p99_latency_ms": percentile(latencies_ms, 99),
        "avg_latency_ms": mean(latencies_ms) if latencies_ms else 0.0,
        "avg_queue_wait_ms": mean(queue_wait_ms) if queue_wait_ms else 0.0,
        "p95_queue_wait_ms": percentile(queue_wait_ms, 95),
        "tokens_per_sec": actual_tpm / 60.0,
        "burst_reject": rejected_reason["BURST"],
        "rate_rpm_reject": rejected_reason["RATE_RPM"],
        "rate_tpm_reject": rejected_reason["RATE_TPM"],
        "timeout_reject": rejected_reason["QUEUE_TIMEOUT"],
        "allocation_units": allocation_units,
        "sim_gc_avg_freq": mean([x["sim_gc_freq"] for x in per_sec]) if per_sec else 0.0,
        "sim_gc_peak_freq": max([x["sim_gc_freq"] for x in per_sec]) if per_sec else 0.0,
        "py_gc_collections": py_gc_collections,
        "timeseries": per_sec,
    }


def write_timeseries_csv(path: Path, fixed_ts, aimd_ts):
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(
            [
                "second",
                "fixed_accepted",
                "aimd_accepted",
                "fixed_rejected",
                "aimd_rejected",
                "fixed_p95_ttft_ms",
                "aimd_p95_ttft_ms",
                "fixed_p95_ms",
                "aimd_p95_ms",
                "fixed_limit",
                "aimd_limit",
                "fixed_gc_freq",
                "aimd_gc_freq",
            ]
        )
        n = min(len(fixed_ts), len(aimd_ts))
        for i in range(n):
            a = fixed_ts[i]
            b = aimd_ts[i]
            w.writerow(
                [
                    i,
                    a["accepted"],
                    b["accepted"],
                    a["rejected"],
                    b["rejected"],
                    round(a.get("p95_ttft_ms", 0.0), 3),
                    round(b.get("p95_ttft_ms", 0.0), 3),
                    round(a["p95_latency_ms"], 3),
                    round(b["p95_latency_ms"], 3),
                    a["controller_limit"],
                    b["controller_limit"],
                    a["sim_gc_freq"],
                    b["sim_gc_freq"],
                ]
            )


def draw_svg(path: Path, title: str, ys_a, ys_b, label_a="Fixed", label_b="AIMD"):
    width, height = 1000, 320
    left, right, top, bottom = 55, 20, 30, 40
    pw, ph = width - left - right, height - top - bottom
    n = max(1, min(len(ys_a), len(ys_b)) - 1)
    max_y = max(1.0, max(ys_a[: n + 1]), max(ys_b[: n + 1]))

    def points(data):
        pts = []
        for i in range(n + 1):
            x = left + (i / n) * pw if n > 0 else left
            y = top + (1.0 - (data[i] / max_y)) * ph
            pts.append(f"{x:.2f},{y:.2f}")
        return " ".join(pts)

    svg = []
    svg.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">')
    svg.append(f'<rect x="0" y="0" width="{width}" height="{height}" fill="#fff"/>')
    svg.append(f'<text x="{left}" y="18" font-size="14" fill="#111">{title}</text>')
    svg.append(f'<line x1="{left}" y1="{top}" x2="{left}" y2="{top+ph}" stroke="#999"/>')
    svg.append(f'<line x1="{left}" y1="{top+ph}" x2="{left+pw}" y2="{top+ph}" stroke="#999"/>')
    svg.append(f'<polyline fill="none" stroke="#ef4444" stroke-width="2" points="{points(ys_a)}"/>')
    svg.append(f'<polyline fill="none" stroke="#2563eb" stroke-width="2" points="{points(ys_b)}"/>')
    svg.append(f'<text x="{left+pw-190}" y="{top+16}" font-size="12" fill="#ef4444">{label_a}</text>')
    svg.append(f'<text x="{left+pw-120}" y="{top+16}" font-size="12" fill="#2563eb">{label_b}</text>')
    svg.append(f'<text x="{left}" y="{height-8}" font-size="11" fill="#666">seconds</text>')
    svg.append("</svg>")
    path.write_text("".join(svg), encoding="utf-8")


def pct(v):
    return f"{v * 100:.2f}%"


def run_ab_test():
    reqs = generate_requests(duration_sec=180, seed=42)
    fixed = run_simulation("fixed", reqs, FixedController(fixed_limit=95))
    aimd = run_simulation("aimd", reqs, AimdController(min_c=6, max_c=95, init_c=10))

    write_timeseries_csv(OUTPUT_DIR / "ab_timeseries.csv", fixed["timeseries"], aimd["timeseries"])
    draw_svg(
        OUTPUT_DIR / "ab_gc_frequency.svg",
        "Simulated GC Frequency (events/sec)",
        [x["sim_gc_freq"] for x in fixed["timeseries"]],
        [x["sim_gc_freq"] for x in aimd["timeseries"]],
    )
    draw_svg(
        OUTPUT_DIR / "ab_throughput.svg",
        "Accepted Requests Per Second",
        [x["accepted"] for x in fixed["timeseries"]],
        [x["accepted"] for x in aimd["timeseries"]],
    )

    report = []
    report.append("# A/B Test Report (Simulated Streaming Model)\n")
    report.append("## Setup\n")
    report.append("- Model backend: simulated streaming responses (short/medium/long)\n")
    report.append("- Workload: mixed + burst traffic for 180s\n")
    report.append("- A: fixed concurrency controller\n")
    report.append("- B: AIMD congestion controller\n")
    report.append("")
    report.append("## Summary Metrics\n")
    report.append("| Metric | Fixed(A) | AIMD(B) | Delta(B-A) |")
    report.append("|---|---:|---:|---:|")

    def row(name, a, b, percent=False):
        if percent:
            aa, bb = pct(a), pct(b)
            delta = f"{(b-a)*100:+.2f}pp"
        else:
            aa, bb = f"{a:.2f}", f"{b:.2f}"
            delta = f"{(b-a):+.2f}"
        report.append(f"| {name} | {aa} | {bb} | {delta} |")

    row("Actual RPM", fixed["actual_rpm"], aimd["actual_rpm"])
    row("Actual TPM", fixed["actual_tpm"], aimd["actual_tpm"])
    row("RPM Utilization", fixed["rpm_util"], aimd["rpm_util"], percent=True)
    row("TPM Utilization", fixed["tpm_util"], aimd["tpm_util"], percent=True)
    row("Concurrency Utilization", fixed["conc_util"], aimd["conc_util"], percent=True)
    row("Composite Utilization", fixed["composite_util"], aimd["composite_util"], percent=True)
    row("P95 Latency (ms)", fixed["p95_latency_ms"], aimd["p95_latency_ms"])
    row("P99 Latency (ms)", fixed["p99_latency_ms"], aimd["p99_latency_ms"])
    row("Avg Queue Wait (ms)", fixed["avg_queue_wait_ms"], aimd["avg_queue_wait_ms"])
    row("Sim GC Avg Freq (/s)", fixed["sim_gc_avg_freq"], aimd["sim_gc_avg_freq"])
    row("Sim GC Peak Freq (/s)", fixed["sim_gc_peak_freq"], aimd["sim_gc_peak_freq"])
    row("Python GC Collections", fixed["py_gc_collections"], aimd["py_gc_collections"])

    report.append("")
    report.append("## Rejection Breakdown\n")
    report.append("| Type | Fixed(A) | AIMD(B) |")
    report.append("|---|---:|---:|")
    for k in ["burst_reject", "rate_rpm_reject", "rate_tpm_reject", "timeout_reject"]:
        report.append(f"| {k} | {fixed[k]} | {aimd[k]} |")
    report.append("")
    report.append("## Output Files\n")
    report.append("- `outputs/ab_report.md`")
    report.append("- `outputs/ab_timeseries.csv`")
    report.append("- `outputs/ab_gc_frequency.svg`")
    report.append("- `outputs/ab_throughput.svg`")

    (OUTPUT_DIR / "ab_report.md").write_text("\n".join(report), encoding="utf-8")


if __name__ == "__main__":
    run_ab_test()
