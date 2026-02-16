import csv
import random
from collections import deque, defaultdict
from dataclasses import dataclass
from pathlib import Path
from statistics import mean

import ab_test_simulator as sim


OUTPUT_DIR = Path(__file__).parent / "outputs"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)


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


class StaticBucketAllocator:
    def __init__(self, total_rpm: int, total_tpm: int):
        self.boundaries = [256, 1024, 4096]
        self.shares = [0.35, 0.35, 0.20, 0.10]
        self.total_rpm = total_rpm
        self.total_tpm = total_tpm
        self.rpm_tokens = [0.0] * 4
        self.tpm_tokens = [0.0] * 4
        self.last_refill = 0.0

    def bucket_of(self, tokens: int) -> int:
        if tokens <= self.boundaries[0]:
            return 0
        if tokens <= self.boundaries[1]:
            return 1
        if tokens <= self.boundaries[2]:
            return 2
        return 3

    def refill(self, now: float):
        dt = max(0.0, now - self.last_refill)
        if dt <= 0:
            return
        for i in range(4):
            rpm_rate = (self.total_rpm * self.shares[i]) / 60.0
            tpm_rate = (self.total_tpm * self.shares[i]) / 60.0
            self.rpm_tokens[i] = min(rpm_rate * 3, self.rpm_tokens[i] + dt * rpm_rate)
            self.tpm_tokens[i] = min(tpm_rate * 3, self.tpm_tokens[i] + dt * tpm_rate)
        self.last_refill = now

    def can_admit(self, now: float, bucket: int, tokens: int) -> bool:
        self.refill(now)
        return self.rpm_tokens[bucket] >= 1.0 and self.tpm_tokens[bucket] >= tokens

    def consume(self, bucket: int, tokens: int):
        self.rpm_tokens[bucket] -= 1.0
        self.tpm_tokens[bucket] -= tokens

    def on_window_end(self):
        return


class AdaptiveBucketAllocator(StaticBucketAllocator):
    def __init__(self, total_rpm: int, total_tpm: int):
        super().__init__(total_rpm, total_tpm)
        self.token_hist = deque(maxlen=12000)
        self.win_arrival = [0, 0, 0, 0]
        self.win_accepted = [0, 0, 0, 0]
        self.win_tokens = [0, 0, 0, 0]
        self.win_reject = [0, 0, 0, 0]
        self.boundaries = [300, 1200, 3800]

    def record_arrival(self, tokens: int):
        b = self.bucket_of(tokens)
        self.win_arrival[b] += 1
        self.token_hist.append(tokens)

    def record_accept(self, tokens: int):
        b = self.bucket_of(tokens)
        self.win_accepted[b] += 1
        self.win_tokens[b] += tokens

    def record_reject(self, tokens: int):
        b = self.bucket_of(tokens)
        self.win_reject[b] += 1

    def _smooth_boundary(self, old_v: int, new_v: int, max_step_ratio: float = 0.15):
        raw = int(0.7 * old_v + 0.3 * new_v)
        step = int(old_v * max_step_ratio)
        lo = max(32, old_v - step)
        hi = old_v + step
        return max(lo, min(hi, raw))

    def _update_boundaries(self):
        data = list(self.token_hist)
        if len(data) < 200:
            return
        p40 = int(percentile(data, 40))
        p75 = int(percentile(data, 75))
        p92 = int(percentile(data, 92))
        b1 = self._smooth_boundary(self.boundaries[0], p40)
        b2 = self._smooth_boundary(self.boundaries[1], max(p40 + 64, p75))
        b3 = self._smooth_boundary(self.boundaries[2], max(p75 + 256, p92))
        if b1 < b2 < b3:
            self.boundaries = [b1, b2, b3]

    def _update_shares(self):
        eps = 1e-9
        a_sum = sum(self.win_arrival) + eps
        ok_sum = sum(self.win_accepted) + eps
        tok_sum = sum(self.win_tokens) + eps

        new_shares = []
        for i in range(4):
            req_share = self.win_arrival[i] / a_sum
            throughput_share = self.win_accepted[i] / ok_sum
            token_share = self.win_tokens[i] / tok_sum
            risk = self.win_reject[i] / max(1, self.win_arrival[i])
            score = 0.35 * req_share + 0.35 * throughput_share + 0.20 * token_share - 0.25 * risk
            new_shares.append(max(0.10, min(0.50, score)))

        s = sum(new_shares)
        target = [x / s for x in new_shares]

        # Anti-oscillation: max +-5pp per window.
        updated = []
        for old, tgt in zip(self.shares, target):
            delta = max(-0.05, min(0.05, tgt - old))
            updated.append(old + delta)
        s2 = sum(updated)
        self.shares = [x / s2 for x in updated]

    def on_window_end(self):
        self._update_boundaries()
        self._update_shares()
        self.win_arrival = [0, 0, 0, 0]
        self.win_accepted = [0, 0, 0, 0]
        self.win_tokens = [0, 0, 0, 0]
        self.win_reject = [0, 0, 0, 0]


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


def total_limits(nodes):
    return sum(n.rpm_limit for n in nodes), sum(n.tpm_limit for n in nodes)


def run_one(name: str, reqs, nodes, allocator, fixed_limit=95, timeout_sec=1.2, update_window_sec=5):
    queue = deque()
    inflight = []
    idx = 0
    now = 0.0
    dt = 0.02
    end_time = max(r.arrival_ts for r in reqs) + 20

    accepted = 0
    rejected = 0
    accepted_tokens = 0
    reason = defaultdict(int)
    ttft_list = []
    e2e_list = []
    queue_wait_list = []
    peak_concurrency = 0
    simulated_gc_counter = 0
    young_heap = 0
    alloc_units = 0

    per_sec = []
    sec_cursor = 0
    sec_acc = sec_rej = sec_gc = 0
    sec_ttft = []
    sec_e2e = []
    sec_queue = []
    window_tick = 0

    while now <= end_time:
        while idx < len(reqs) and reqs[idx].arrival_ts <= now:
            req = reqs[idx]
            queue.append(req)
            allocator.record_arrival(req.total_tokens) if isinstance(allocator, AdaptiveBucketAllocator) else None
            idx += 1

        completed = [x for x in inflight if x.finish_ts <= now]
        if completed:
            keep = []
            done = set()
            for c in completed:
                nodes[c.node_id].finish()
                done.add(c.req.id)
                ttft = (c.start_ts - c.req.arrival_ts + c.req.ttft_delay_sec) * 1000.0
                e2e = (c.finish_ts - c.req.arrival_ts) * 1000.0
                qw = (c.start_ts - c.req.arrival_ts) * 1000.0
                ttft_list.append(ttft)
                e2e_list.append(e2e)
                queue_wait_list.append(qw)
                sec_ttft.append(ttft)
                sec_e2e.append(e2e)
                sec_queue.append(qw)
            for x in inflight:
                if x.req.id not in done:
                    keep.append(x)
            inflight = keep

        while queue and (now - queue[0].arrival_ts) > timeout_sec:
            req = queue.popleft()
            rejected += 1
            sec_rej += 1
            reason["QUEUE_TIMEOUT"] += 1
            if isinstance(allocator, AdaptiveBucketAllocator):
                allocator.record_reject(req.total_tokens)

        total_inflight = sum(n.inflight for n in nodes)
        while queue and total_inflight < fixed_limit:
            req = queue[0]
            b = allocator.bucket_of(req.total_tokens)
            if not allocator.can_admit(now, b, req.total_tokens):
                reason["BUCKET_BUDGET"] += 1
                break
            sample = random.sample(nodes, k=min(3, len(nodes)))
            sample.sort(key=lambda n: n.inflight)
            started = False
            fail = "CONCURRENCY"
            for node in sample:
                ok, r = node.can_start(now, req.total_tokens, fixed_limit)
                if ok:
                    node.start(now, req.total_tokens)
                    allocator.consume(b, req.total_tokens)
                    inflight.append(Inflight(req, now, now + req.service_time_sec, node.node_id))
                    queue.popleft()
                    accepted += 1
                    sec_acc += 1
                    accepted_tokens += req.total_tokens
                    total_inflight += 1
                    started = True
                    alloc_units += req.stream_chunks * 3 + 8
                    young_heap += req.stream_chunks * 1024 + req.output_tokens * 16
                    if young_heap > 3_000_000:
                        simulated_gc_counter += 1
                        sec_gc += 1
                        young_heap = int(young_heap * 0.35)
                    if isinstance(allocator, AdaptiveBucketAllocator):
                        allocator.record_accept(req.total_tokens)
                    break
                fail = r
            if not started:
                if fail in ("RATE_RPM", "RATE_TPM", "BURST"):
                    reason[fail] += 1
                    if isinstance(allocator, AdaptiveBucketAllocator):
                        allocator.record_reject(req.total_tokens)
                break

        peak_concurrency = max(peak_concurrency, sum(n.inflight for n in nodes))

        while now >= sec_cursor + 1.0:
            per_sec.append(
                {
                    "second": sec_cursor,
                    "accepted": sec_acc,
                    "rejected": sec_rej,
                    "p95_ttft_ms": percentile(sec_ttft, 95),
                    "p95_e2e_ms": percentile(sec_e2e, 95),
                    "p95_queue_ms": percentile(sec_queue, 95),
                    "sim_gc_freq": sec_gc,
                }
            )
            sec_cursor += 1
            sec_acc = sec_rej = sec_gc = 0
            sec_ttft, sec_e2e, sec_queue = [], [], []
            window_tick += 1
            if window_tick % update_window_sec == 0:
                allocator.on_window_end()

        now += dt

    elapsed = max(1.0, end_time)
    total_rpm, total_tpm = total_limits(nodes)
    rpm_util = min(1.0, (accepted * 60.0 / elapsed) / total_rpm)
    tpm_util = min(1.0, (accepted_tokens * 60.0 / elapsed) / total_tpm)
    return {
        "name": name,
        "accepted": accepted,
        "rejected": rejected,
        "accept_rate": accepted / max(1, accepted + rejected),
        "reject_rate": rejected / max(1, accepted + rejected),
        "actual_rpm": accepted * 60.0 / elapsed,
        "actual_tpm": accepted_tokens * 60.0 / elapsed,
        "rpm_util": rpm_util,
        "tpm_util": tpm_util,
        "max_util": max(rpm_util, tpm_util),
        "p95_ttft_ms": percentile(ttft_list, 95),
        "p95_e2e_ms": percentile(e2e_list, 95),
        "p95_queue_ms": percentile(queue_wait_list, 95),
        "sim_gc_avg_freq": mean([x["sim_gc_freq"] for x in per_sec]) if per_sec else 0.0,
        "sim_gc_peak_freq": max([x["sim_gc_freq"] for x in per_sec]) if per_sec else 0.0,
        "allocation_units": alloc_units,
        "peak_concurrency": peak_concurrency,
        "reasons": dict(reason),
        "timeseries": per_sec,
        "allocator_shares": getattr(allocator, "shares", None),
        "allocator_boundaries": getattr(allocator, "boundaries", None),
    }


def generate_requests(duration_sec: int, seed: int, profile: str):
    rng = random.Random(seed)
    reqs = []
    rid = 0
    for sec in range(duration_sec):
        lam = 85
        if 35 <= sec < 80 or 115 <= sec < 155:
            lam = 190
        if 155 <= sec < 175:
            lam = 260
        n = max(1, int(rng.gauss(lam, lam * 0.12)))
        for _ in range(n):
            t = sec + rng.random()
            p = rng.random()
            if profile == "mixed":
                if p < 0.5:
                    in_tok = rng.randint(40, 160)
                    out_tok = rng.randint(80, 260)
                elif p < 0.85:
                    in_tok = rng.randint(160, 420)
                    out_tok = rng.randint(260, 900)
                else:
                    in_tok = rng.randint(420, 1100)
                    out_tok = rng.randint(900, 2600)
            else:
                in_tok = rng.randint(180, 820)
                out_tok = rng.randint(600, 2600)
            chunks = max(4, out_tok // 32)
            ttft_delay = 0.035 + min(0.25, chunks * 0.0024) + rng.random() * 0.03
            service = max(ttft_delay + 0.02, 0.08 + chunks * 0.012 + rng.random() * 0.1)
            reqs.append(sim.Request(rid, t, in_tok, out_tok, chunks, ttft_delay, service))
            rid += 1
    reqs.sort(key=lambda r: r.arrival_ts)
    return reqs


def draw_svg(path: Path, title: str, ys_a, ys_b, label_a, label_b):
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
    svg.append(f'<text x="{left+pw-210}" y="{top+16}" font-size="12" fill="#ef4444">{label_a}</text>')
    svg.append(f'<text x="{left+pw-120}" y="{top+16}" font-size="12" fill="#2563eb">{label_b}</text>')
    svg.append(f'<text x="{left}" y="{height-8}" font-size="11" fill="#666">seconds</text>')
    svg.append("</svg>")
    path.write_text("".join(svg), encoding="utf-8")


def run():
    scenarios = [
        ("mixed_bursty", "mixed", "balanced"),
        ("token_heavy", "token_heavy", "tpm_tight"),
    ]
    summary_rows = []
    report = ["# Static vs Adaptive Bucket A/B\n"]
    for idx, (name, profile, node_profile) in enumerate(scenarios, start=1):
        reqs = generate_requests(180, seed=500 + idx, profile=profile)
        nodes_a = make_nodes(node_profile)
        nodes_b = make_nodes(node_profile)
        total_rpm, total_tpm = total_limits(nodes_a)
        static_alloc = StaticBucketAllocator(total_rpm, total_tpm)
        adaptive_alloc = AdaptiveBucketAllocator(total_rpm, total_tpm)

        fixed = run_one(f"{name}_static", reqs, nodes_a, static_alloc)
        adaptive = run_one(f"{name}_adaptive", reqs, nodes_b, adaptive_alloc)

        summary_rows.append(
            {
                "scenario": name,
                "static_rpm": round(fixed["actual_rpm"], 2),
                "adaptive_rpm": round(adaptive["actual_rpm"], 2),
                "static_max_util": round(fixed["max_util"], 4),
                "adaptive_max_util": round(adaptive["max_util"], 4),
                "static_ttft_p95_ms": round(fixed["p95_ttft_ms"], 2),
                "adaptive_ttft_p95_ms": round(adaptive["p95_ttft_ms"], 2),
                "static_gc_avg": round(fixed["sim_gc_avg_freq"], 4),
                "adaptive_gc_avg": round(adaptive["sim_gc_avg_freq"], 4),
                "static_reject_rate": round(fixed["reject_rate"], 4),
                "adaptive_reject_rate": round(adaptive["reject_rate"], 4),
                "adaptive_boundaries": str(adaptive["allocator_boundaries"]),
                "adaptive_shares": str([round(x, 4) for x in adaptive["allocator_shares"]]),
            }
        )

        draw_svg(
            OUTPUT_DIR / f"bucket_{name}_ttft.svg",
            f"{name} - TTFT p95 per second",
            [x["p95_ttft_ms"] for x in fixed["timeseries"]],
            [x["p95_ttft_ms"] for x in adaptive["timeseries"]],
            "StaticBucket",
            "AdaptiveBucket",
        )
        draw_svg(
            OUTPUT_DIR / f"bucket_{name}_gc.svg",
            f"{name} - Simulated GC freq per second",
            [x["sim_gc_freq"] for x in fixed["timeseries"]],
            [x["sim_gc_freq"] for x in adaptive["timeseries"]],
            "StaticBucket",
            "AdaptiveBucket",
        )

        report.append(f"## {name}")
        report.append(f"- RPM: Static={fixed['actual_rpm']:.2f}, Adaptive={adaptive['actual_rpm']:.2f}")
        report.append(f"- max(rpm_util,tpm_util): Static={fixed['max_util']*100:.2f}%, Adaptive={adaptive['max_util']*100:.2f}%")
        report.append(f"- TTFT p95(ms): Static={fixed['p95_ttft_ms']:.2f}, Adaptive={adaptive['p95_ttft_ms']:.2f}")
        report.append(f"- E2E p95(ms): Static={fixed['p95_e2e_ms']:.2f}, Adaptive={adaptive['p95_e2e_ms']:.2f}")
        report.append(f"- Reject rate: Static={fixed['reject_rate']*100:.2f}%, Adaptive={adaptive['reject_rate']*100:.2f}%")
        report.append(f"- GC avg(/s): Static={fixed['sim_gc_avg_freq']:.2f}, Adaptive={adaptive['sim_gc_avg_freq']:.2f}")
        report.append(f"- Adaptive boundaries(end): {adaptive['allocator_boundaries']}")
        report.append(f"- Adaptive shares(end): {[round(x,4) for x in adaptive['allocator_shares']]}")
        report.append("")

    with (OUTPUT_DIR / "bucket_ab_summary.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(summary_rows[0].keys()))
        w.writeheader()
        w.writerows(summary_rows)
    (OUTPUT_DIR / "bucket_ab_report.md").write_text("\n".join(report), encoding="utf-8")


if __name__ == "__main__":
    run()
