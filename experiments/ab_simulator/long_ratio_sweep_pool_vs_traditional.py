import csv
import random
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from statistics import mean


OUT = Path(__file__).parent / "outputs"
OUT.mkdir(parents=True, exist_ok=True)


def percentile(values, p):
    if not values:
        return 0.0
    arr = sorted(values)
    k = max(0, min(len(arr) - 1, int((p / 100.0) * len(arr)) - 1))
    return float(arr[k])


@dataclass
class Req:
    rid: int
    arrival: float
    in_tok: int
    out_tok: int
    chunks: int
    ttft_delay: float
    service: float
    is_long: bool

    @property
    def total_tokens(self):
        return self.in_tok + self.out_tok


@dataclass
class Flight:
    req: Req
    start: float
    finish: float
    node: int


class Node:
    def __init__(self, rpm, tpm, max_concurrency):
        self.rpm_limit = rpm
        self.tpm_limit = tpm
        self.max_concurrency = max_concurrency
        self.rpm_tokens = float(rpm)
        self.tpm_tokens = float(tpm)
        self.inflight = 0
        self.last_refill = 0.0

    def refill(self, now):
        dt = max(0.0, now - self.last_refill)
        if dt <= 0:
            return
        self.rpm_tokens = min(self.rpm_limit, self.rpm_tokens + dt * (self.rpm_limit / 60.0))
        self.tpm_tokens = min(self.tpm_limit, self.tpm_tokens + dt * (self.tpm_limit / 60.0))
        self.last_refill = now

    def try_start(self, now, tokens, cap):
        self.refill(now)
        if self.inflight >= min(self.max_concurrency, cap):
            return False
        if self.rpm_tokens < 1.0 or self.tpm_tokens < tokens:
            return False
        self.rpm_tokens -= 1.0
        self.tpm_tokens -= tokens
        self.inflight += 1
        return True

    def finish(self):
        if self.inflight > 0:
            self.inflight -= 1


class TraditionalPolicy:
    # No queue-friendly behavior: strict reject under stress.
    def __init__(self, cap=95):
        self.cap = cap

    def capacity(self):
        return self.cap

    def queue_timeout(self):
        return 0.8

    def select_request(self, queue):
        return queue[0]


class PoolFirstPolicy:
    # Object-pool + bounded queue + short-first preference.
    def __init__(self, core=48, max_size=95):
        self.core = core
        self.max_size = max_size
        self.allocated = core
        self.active = 0
        self.idle = deque(range(core))

    def capacity(self):
        return self.max_size

    def queue_timeout(self):
        return 1.4

    def acquire_slot(self):
        if self.idle:
            self.idle.popleft()
            self.active += 1
            return True
        if self.allocated < self.max_size:
            self.allocated += 1
            self.active += 1
            return True
        return False

    def release_slot(self):
        if self.active > 0:
            self.active -= 1
            self.idle.append(1)

    def select_request(self, queue):
        # Prefer short requests for better TTFT.
        for req in queue:
            if not req.is_long:
                return req
        return queue[0]


def build_nodes():
    nodes = []
    for i in range(6):
        rpm = 1150 + i * 80
        tpm = 520_000 + i * 45_000
        nodes.append(Node(rpm, tpm, 14 + i))
    return nodes


def generate_requests(duration, qps, long_ratio, seed=1):
    rng = random.Random(seed)
    reqs = []
    rid = 0
    for sec in range(duration):
        n = max(1, int(rng.gauss(qps, qps * 0.09)))
        for _ in range(n):
            t = sec + rng.random()
            is_long = rng.random() < long_ratio
            if not is_long:
                in_tok = rng.randint(50, 180)
                out_tok = rng.randint(120, 420)
            else:
                in_tok = rng.randint(450, 1700)
                out_tok = rng.randint(1400, 5200)
            chunks = max(4, out_tok // 32)
            ttft = 0.035 + min(0.34, chunks * 0.0028) + rng.random() * 0.03
            service = max(ttft + 0.02, 0.08 + chunks * 0.012 + rng.random() * 0.12)
            reqs.append(Req(rid, t, in_tok, out_tok, chunks, ttft, service, is_long))
            rid += 1
    reqs.sort(key=lambda x: x.arrival)
    return reqs


def run_sim(reqs, policy, is_pool):
    nodes = build_nodes()
    queue = deque()
    flights = []
    idx = 0
    now = 0.0
    dt = 0.02
    end = max(r.arrival for r in reqs) + 20

    accepted = rejected = accepted_tokens = 0
    ttft = []
    e2e = []
    sec_gc = []
    sec_gc_counter = 0
    young_heap = 0

    while now <= end:
        while idx < len(reqs) and reqs[idx].arrival <= now:
            queue.append(reqs[idx])
            idx += 1

        done = [f for f in flights if f.finish <= now]
        if done:
            keep = []
            done_ids = set()
            for f in done:
                nodes[f.node].finish()
                if is_pool:
                    policy.release_slot()
                done_ids.add(f.req.rid)
                ttft.append((f.start - f.req.arrival + f.req.ttft_delay) * 1000.0)
                e2e.append((f.finish - f.req.arrival) * 1000.0)
            for f in flights:
                if f.req.rid not in done_ids:
                    keep.append(f)
            flights = keep

        while queue and now - queue[0].arrival > policy.queue_timeout():
            queue.popleft()
            rejected += 1

        total_inflight = sum(n.inflight for n in nodes)
        while queue and total_inflight < policy.capacity():
            req = policy.select_request(queue)
            if is_pool and (not policy.acquire_slot()):
                break
            sample_size = 2 if not is_pool else 4
            sample = random.sample(nodes, k=min(sample_size, len(nodes)))
            sample.sort(key=lambda n: n.inflight)
            started = False
            for node in sample:
                if node.try_start(now, req.total_tokens, policy.capacity()):
                    queue.remove(req)
                    accepted += 1
                    accepted_tokens += req.total_tokens
                    total_inflight += 1
                    flights.append(Flight(req, now, now + req.service, nodes.index(node)))
                    started = True
                    young_heap += req.chunks * 1024 + req.out_tok * 16
                    # Simulate stronger allocation churn for traditional under long requests.
                    if not is_pool and req.is_long:
                        young_heap += req.chunks * 700
                    if young_heap > 3_000_000:
                        sec_gc_counter += 1
                        young_heap = int(young_heap * (0.30 if not is_pool else 0.38))
                    break
            if not started:
                if is_pool:
                    policy.release_slot()
                break

        if abs((now % 1.0) - 0.0) < dt / 2:
            sec_gc.append(sec_gc_counter)
            sec_gc_counter = 0

        now += dt

    total_rpm = sum(n.rpm_limit for n in nodes)
    total_tpm = sum(n.tpm_limit for n in nodes)
    elapsed = max(1.0, end)
    actual_rpm = accepted * 60.0 / elapsed
    actual_tpm = accepted_tokens * 60.0 / elapsed
    rpm_util = min(1.0, actual_rpm / total_rpm)
    tpm_util = min(1.0, actual_tpm / total_tpm)
    return {
        "actual_rpm": actual_rpm,
        "actual_tpm": actual_tpm,
        "max_util": max(rpm_util, tpm_util),
        "p95_ttft": percentile(ttft, 95),
        "p95_e2e": percentile(e2e, 95),
        "reject_rate": rejected / max(1, accepted + rejected),
        "gc_avg": mean(sec_gc) if sec_gc else 0.0,
    }


def draw_svg(path, title, x_labels, y_a, y_b, label_a, label_b):
    width, height = 980, 320
    left, right, top, bottom = 55, 20, 30, 45
    pw, ph = width - left - right, height - top - bottom
    n = max(1, len(x_labels) - 1)
    max_y = max(1.0, max(y_a), max(y_b))

    def pts(data):
        out = []
        for i in range(len(x_labels)):
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
    s.append(f'<polyline fill="none" stroke="#ef4444" stroke-width="2" points="{pts(y_a)}"/>')
    s.append(f'<polyline fill="none" stroke="#2563eb" stroke-width="2" points="{pts(y_b)}"/>')
    for i, lbl in enumerate(x_labels):
        x = left + (i / n) * pw if n > 0 else left
        s.append(f'<text x="{x:.2f}" y="{top+ph+15}" text-anchor="middle" font-size="10" fill="#6b7280">{lbl}</text>')
    s.append(f'<text x="{left+pw-220}" y="{top+16}" font-size="12" fill="#ef4444">{label_a}</text>')
    s.append(f'<text x="{left+pw-110}" y="{top+16}" font-size="12" fill="#2563eb">{label_b}</text>')
    s.append("</svg>")
    path.write_text("".join(s), encoding="utf-8")


def run():
    long_ratios = [0.05, 0.15, 0.25, 0.35, 0.50, 0.65]
    qps = 140
    rows = []
    for i, ratio in enumerate(long_ratios, start=1):
        reqs = generate_requests(180, qps, ratio, seed=2200 + i)
        tr = run_sim(reqs, TraditionalPolicy(cap=95), is_pool=False)
        pl = run_sim(reqs, PoolFirstPolicy(core=48, max_size=95), is_pool=True)
        rows.append(
            {
                "long_ratio": ratio,
                "traditional_rpm": round(tr["actual_rpm"], 2),
                "pool_rpm": round(pl["actual_rpm"], 2),
                "traditional_max_util": round(tr["max_util"], 4),
                "pool_max_util": round(pl["max_util"], 4),
                "traditional_gc_avg": round(tr["gc_avg"], 4),
                "pool_gc_avg": round(pl["gc_avg"], 4),
                "traditional_ttft_p95": round(tr["p95_ttft"], 2),
                "pool_ttft_p95": round(pl["p95_ttft"], 2),
                "traditional_reject_rate": round(tr["reject_rate"], 4),
                "pool_reject_rate": round(pl["reject_rate"], 4),
            }
        )

    csv_path = OUT / "long_ratio_sweep_pool_vs_traditional.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    x = [str(r["long_ratio"]) for r in rows]
    draw_svg(
        OUT / "long_ratio_sweep_throughput.svg",
        "Long-Request-Ratio Sweep - Throughput (RPM)",
        x,
        [r["traditional_rpm"] for r in rows],
        [r["pool_rpm"] for r in rows],
        "Traditional",
        "PoolFirst",
    )
    draw_svg(
        OUT / "long_ratio_sweep_max_util.svg",
        "Long-Request-Ratio Sweep - max(rpm_util,tpm_util)",
        x,
        [r["traditional_max_util"] for r in rows],
        [r["pool_max_util"] for r in rows],
        "Traditional",
        "PoolFirst",
    )
    draw_svg(
        OUT / "long_ratio_sweep_gc.svg",
        "Long-Request-Ratio Sweep - GC Frequency (/s)",
        x,
        [r["traditional_gc_avg"] for r in rows],
        [r["pool_gc_avg"] for r in rows],
        "Traditional",
        "PoolFirst",
    )


if __name__ == "__main__":
    run()
