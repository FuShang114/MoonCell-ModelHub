# MoonCell-ModelHub

MoonCell-ModelHub is a Spring Boot/WebFlux gateway for routing chat requests to multiple LLM providers with unified request/response handling.

## What Is Implemented

- RPM/TPM-aware admission control per model instance
- Two hot-swappable load balancing algorithms:
  - `TRADITIONAL`
  - `OBJECT_POOL`
- Runtime strategy switch via admin settings API/UI
- SSE response normalization and configurable field mapping
- Redis-based idempotency guard

## Load Balancing Design

### Core principle

Routing decisions are budget-aware:

- `RPM` controls request-rate budget
- `TPM` controls token-consumption budget

An instance can receive a request only when both budget checks pass.

### Algorithm: TRADITIONAL

- Randomly sample `N` healthy instances (`sampleCount`)
- Score sampled instances by:
  - current concurrency pressure
  - RPM headroom pressure
  - TPM headroom pressure
- Try acquire from lowest-score candidate first

This gives a stable baseline with low operational complexity.

### Algorithm: OBJECT_POOL

- Uses the same RPM/TPM gates as `TRADITIONAL`
- Adds per-instance pool slot control (`coreSize`, `maxSize`)
- Samples `N` instances and scores by:
  - pool pressure (active/allocated slots)
  - concurrency pressure
  - RPM/TPM pressure
- Prefers candidates with the lowest combined pressure

This improves behavior under heterogeneous traffic (especially when long requests dominate), and reduces allocation churn.

## How Instance Selection Works

For each request:

1. estimate tokens from prompt input
2. sample candidate instances
3. rank candidates by algorithm-specific pressure score
4. attempt acquire in sorted order
5. release after completion/error in `finally`

If no candidate can pass admission checks, gateway returns `503`.

## Admin Settings

Settings endpoint:

- `GET /admin/load-balancing/settings`
- `PUT /admin/load-balancing/settings`

Key fields:

- `algorithm`: `TRADITIONAL` or `OBJECT_POOL`
- `sampleCount`: random-sampling size
- `objectPoolCoreSize`: initial slot count per instance (OBJECT_POOL only)
- `objectPoolMaxSize`: max slot count per instance (OBJECT_POOL only)

## Notes

- `target/` contains build artifacts; do not rely on it as source of truth.
- Load-balancer simulation scripts are under `experiments/ab_simulator/`.