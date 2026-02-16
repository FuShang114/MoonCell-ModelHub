import csv
from pathlib import Path


BASE = Path(__file__).parent
INPUT = BASE / "outputs" / "scenario_matrix_results.csv"
OUT_DIR = BASE / "outputs"
OUT_DIR.mkdir(parents=True, exist_ok=True)


def read_rows():
    with INPUT.open(encoding="utf-8") as f:
        return list(csv.DictReader(f))


def _to_float(rows, key):
    return [float(r[key]) for r in rows]


def draw_line_chart_svg(path: Path, title: str, labels, y_fixed, y_aimd, y_label: str):
    width, height = 1200, 420
    left, right, top, bottom = 70, 20, 45, 75
    pw, ph = width - left - right, height - top - bottom
    n = len(labels)
    if n == 0:
        return
    max_y = max(max(y_fixed), max(y_aimd), 1.0)
    min_y = min(min(y_fixed), min(y_aimd), 0.0)
    y_span = max(1e-9, max_y - min_y)

    def point(i, v):
        x = left + (i / max(1, n - 1)) * pw
        y = top + (1 - (v - min_y) / y_span) * ph
        return x, y

    fixed_pts = []
    aimd_pts = []
    for i in range(n):
        x1, y1 = point(i, y_fixed[i])
        x2, y2 = point(i, y_aimd[i])
        fixed_pts.append(f"{x1:.2f},{y1:.2f}")
        aimd_pts.append(f"{x2:.2f},{y2:.2f}")

    parts = []
    parts.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">')
    parts.append(f'<rect x="0" y="0" width="{width}" height="{height}" fill="#ffffff"/>')
    parts.append(f'<text x="{left}" y="22" font-size="18" fill="#111827">{title}</text>')
    parts.append(f'<text x="{left}" y="40" font-size="12" fill="#6b7280">{y_label}</text>')

    # axes
    parts.append(f'<line x1="{left}" y1="{top}" x2="{left}" y2="{top+ph}" stroke="#9ca3af"/>')
    parts.append(f'<line x1="{left}" y1="{top+ph}" x2="{left+pw}" y2="{top+ph}" stroke="#9ca3af"/>')

    # y ticks
    for t in range(6):
        yy = top + (t / 5) * ph
        val = max_y - (t / 5) * y_span
        parts.append(f'<line x1="{left}" y1="{yy:.2f}" x2="{left+pw}" y2="{yy:.2f}" stroke="#f3f4f6"/>')
        parts.append(f'<text x="{left-8}" y="{yy+4:.2f}" text-anchor="end" font-size="11" fill="#6b7280">{val:.2f}</text>')

    # x labels
    for i, label in enumerate(labels):
        x, _ = point(i, min_y)
        parts.append(f'<text x="{x:.2f}" y="{top+ph+18}" text-anchor="middle" font-size="10" fill="#4b5563" transform="rotate(20 {x:.2f},{top+ph+18})">{label}</text>')

    # lines
    parts.append(f'<polyline fill="none" stroke="#ef4444" stroke-width="2.5" points="{" ".join(fixed_pts)}"/>')
    parts.append(f'<polyline fill="none" stroke="#2563eb" stroke-width="2.5" points="{" ".join(aimd_pts)}"/>')

    # dots
    for i in range(n):
        x1, y1 = point(i, y_fixed[i])
        x2, y2 = point(i, y_aimd[i])
        parts.append(f'<circle cx="{x1:.2f}" cy="{y1:.2f}" r="3" fill="#ef4444"/>')
        parts.append(f'<circle cx="{x2:.2f}" cy="{y2:.2f}" r="3" fill="#2563eb"/>')

    # legend
    lx, ly = left + pw - 170, top + 10
    parts.append(f'<line x1="{lx}" y1="{ly}" x2="{lx+22}" y2="{ly}" stroke="#ef4444" stroke-width="3"/>')
    parts.append(f'<text x="{lx+28}" y="{ly+4}" font-size="12" fill="#374151">Fixed</text>')
    parts.append(f'<line x1="{lx}" y1="{ly+20}" x2="{lx+22}" y2="{ly+20}" stroke="#2563eb" stroke-width="3"/>')
    parts.append(f'<text x="{lx+28}" y="{ly+24}" font-size="12" fill="#374151">AIMD</text>')

    parts.append("</svg>")
    path.write_text("".join(parts), encoding="utf-8")


def main():
    rows = read_rows()
    labels = [r["scenario"] for r in rows]

    draw_line_chart_svg(
        OUT_DIR / "scenario_line_rpm.svg",
        "Scenario Comparison - Actual RPM",
        labels,
        _to_float(rows, "fixed_rpm"),
        _to_float(rows, "aimd_rpm"),
        "RPM",
    )
    draw_line_chart_svg(
        OUT_DIR / "scenario_line_ttft.svg",
        "Scenario Comparison - P95 TTFT (ms)",
        labels,
        _to_float(rows, "fixed_p95_ttft_ms"),
        _to_float(rows, "aimd_p95_ttft_ms"),
        "Milliseconds",
    )
    draw_line_chart_svg(
        OUT_DIR / "scenario_line_composite_util.svg",
        "Scenario Comparison - Composite Utilization",
        labels,
        _to_float(rows, "fixed_composite_util"),
        _to_float(rows, "aimd_composite_util"),
        "Utilization (0-1)",
    )


if __name__ == "__main__":
    main()
