# Task-Graph Scheduling Optimizer and Execution-Stats Service

A Java 21 / Spring Boot service that fuses linear chains and sibling branches
of a task-graph DAG into fewer scheduling units, replays the fused plan
against a simulated worker pool, and exposes the chosen plan and its
savings over a REST API. Per-task cost percentiles come from
PostgreSQL's own `percentile_cont` over recorded execution history, not a
config file. Every number below was measured on this machine against a
real, running PostgreSQL 14 instance, not targeted.

## Why this exists

This is a small version of a pipeline scheduler that has already paid for
its own DAG once (the task graph, its dependencies, and real historical
timing per task type) and is deciding how to run it again faster without
changing what it computes. Linear-chain fusion removes per-task dispatch
overhead that a chain of single-successor tasks never needed to pay
separately. Sibling-branch fusion is the harder case: it can help (fewer
overhead payments) or hurt (less parallelism), so it is applied only when a
simulated replay shows it does not make the schedule worse.

## What this is, honestly

- **A simulated 16-worker pool, not a real cluster.** `WorkerPoolSimulator`
  is a greedy list scheduler (highest critical-path rank first, earliest
  free worker) over an in-memory model. Nothing in this repository actually
  dispatches a task to a real machine.
- **The per-unit scheduling overhead (2.0 ms) is a stated modeling
  assumption, not a measured system property.** It is the entire mechanism
  by which fusion can reduce makespan in this model: a fused unit of k
  tasks pays it once instead of k times. Changing it changes how much
  fusion helps; it is disclosed here rather than buried in a constant.
- **The recorded execution history is synthetic.** `ExecutionHistorySeeder`
  generates it from a seeded Gaussian per task type
  (`new Random(seed).nextGaussian()`); there is no real worker fleet behind
  any duration in this repository. What is real is the query: the
  percentiles are computed by PostgreSQL's `percentile_cont` over whatever
  rows are actually in the `task_executions` table, not by this service
  doing the math itself.
- **The fusion decision is a greedy local search, not an exact optimizer.**
  Linear-chain fusion is always applied (it provably cannot increase
  makespan in this cost model, see "Architecture"). Sibling-branch fusion
  is decided group by group, in a fixed order, by simulating the plan with
  and without each candidate group and keeping the fusion only if it ties
  or wins. A different candidate order could in principle reach a
  different local optimum; this is not claimed to be globally optimal.

**Machine and toolchain.** WSL2 Ubuntu 22.04 on Windows 11, OpenJDK
21.0.12 (Temurin), Apache Maven 3.6.3, Spring Boot 3.3.4, PostgreSQL 14.24
running as a native WSL2 service (not Docker; `pg_lsclusters` showed a
cluster already online on port 5432 on this machine, so that is what every
query below actually ran against). Spring Data JPA + Hibernate 6.5.3 for
persistence, `percentile_cont` is a native query, not JPQL.

## Architecture

```
src/main/java/com/manas/taskgraph/
  model/
    TaskNode.java        One DAG node: id, task type, dependency ids.
    TaskGraph.java        Immutable graph with precomputed children/roots/sinks.
  graph/
    GraphValidator.java   Kahn's algorithm: topological order and exact cycle
                          membership in one pass (whatever Kahn's cannot place
                          is, by construction, inside a cycle).
    BenchmarkDagGenerator.java  The 240-node benchmark: 30 repeated 8-node
                          blocks, each containing both fusion shapes (a
                          4-node linear chain feeding a 3-way sibling fan-out).
  persistence/
    ExecutionRecord.java  JPA entity: one recorded (task type, duration, time).
    ExecutionRecordRepository.java  `percentile_cont` as a native query.
  cost/
    CostPercentileService.java  Percentiles from Postgres, plus an
                          independent Java reference implementation of the
                          same percentile definition, used only in tests.
  optimize/
    FusionOptimizer.java  Linear-chain fusion (always) and sibling-branch
                          fusion (simulate, keep ties-or-better).
    FusedGroup.java        One applied fusion, for the REST response.
  sim/
    WorkerPoolSimulator.java  Greedy list scheduler over a fixed worker count.
    OutputOrderChecker.java   Checks the actual "output order unchanged"
                          claim: sorting completions by finish time must
                          still be a valid topological order of the
                          original, unfused graph.
  api/
    GraphOptimizationService.java  Orchestrates cost lookup, baseline sim,
                          fusion, fused sim, and the savings computation.
    GraphController.java   POST /api/graphs, GET /api/graphs/{id}/plan.
```

### Design deep-dives

**Why linear-chain fusion is unconditional but sibling-branch fusion is
not.** A linear chain (each node has exactly one dependency, and that
dependency has exactly one child) was never going to run next to anything
else; it is already a fully serial dependency path regardless of fusion.
Fusing it only removes `(chain length - 1)` overhead payments and changes
nothing about available parallelism, so in this additive cost model it can
only tie or improve the makespan. A sibling group (same single parent, same
single child, no edges between them) genuinely could have run in parallel
across separate workers; fusing it onto one worker trades removed overhead
for lost parallelism, and which one wins depends on worker availability at
that point in the schedule. The optimizer resolves this the way the
project's own design-first/compare principle resolves any such tradeoff:
simulate both ways, keep the fusion only if it does not make the measured
makespan worse.

**Why chain fusion can cross what looks like a block boundary.** The chain
rule is defined purely on fan-in/fan-out degree (one dependency, one
child), not on any notion of "block". In the 240-node benchmark, each
block's `merge` node has exactly one child (the next block's `entry`), and
that `entry` has exactly one dependency (the previous `merge`). That
satisfies the chain rule exactly, so the optimizer correctly fuses across
the boundary (see the live API response in `docs/api_demo_output.txt`,
where `unit_b0_merge` contains `b0_merge, b1_entry, b1_chainA, ...`). This
was not special-cased; it falls out of the same rule that fuses the
in-block chain.

**Why the percentile reference implementation has to match
`percentile_cont`'s exact interpolation, not just "a percentile".** See
"Findings": a nearest-rank percentile looks right and disagrees with
PostgreSQL's continuous (linearly interpolated) definition on most sample
sizes. `CostPercentileService.referencePercentile` interpolates between the
two bracketing order statistics at rank `p * (n - 1)`, the same definition
`percentile_cont` uses, so the test in `CostPercentileServiceTest` can
assert exact agreement rather than "close enough".

**Why the benchmark graph's task types repeat instead of each node having a
unique type.** "Per-task cost percentiles from recorded executions" only
means something if a task type has actually recurred enough times to have
a distribution. 240 nodes share exactly 8 task types (one per role in the
repeated block), each with 2,000 recorded executions in the benchmark run,
which is what makes `percentile_cont` meaningful rather than a percentile
of a sample of size 1.

## Validation

Unit and integration tests (raw summary in `docs/test_output.txt`):

```
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
```

- `GraphValidatorTest`: topological order correctness, and that cycle
  detection reports exactly the nodes inside a cycle, not the whole graph
  (a 2-node cycle attached to 2 acyclic nodes correctly isolates just the 2).
- `WorkerPoolSimulatorTest`: includes a deliberately naive brute-force
  reference (`bruteForceMinimalMakespan`, tries every worker assignment)
  that the greedy simulator is diffed against on a small, all-independent
  case, plus a hand-computed linear-chain case and a fusion-removes-overhead
  case.
- `FusionOptimizerTest`: chain fusion fires and preserves member order; a
  branching node is never swallowed into a chain that would hide a second
  dependent; a dedicated worker-scarce case proves sibling-branch fusion
  actually gets applied and wins (2 workers, 3 siblings; see "Findings" for
  why the 240-node benchmark itself applies zero sibling fusions); fusion
  never increases makespan on the full benchmark graph.
- `OutputOrderCheckerTest`: accepts a dependency-respecting completion
  order, rejects one where a dependent finishes before its dependency
  (the actual bug this check exists to catch), and is indifferent to the
  order of two tasks that do not depend on each other.
- `CostPercentileServiceTest`: seeds 1,000 synthetic durations into
  PostgreSQL, asserts the database's `percentile_cont` result matches the
  independent Java reference implementation to within `1e-9` at p50, p90
  and p99, and that an unrecorded task type fails loudly instead of
  returning a silent zero cost.
- `GraphControllerTest`: a real HTTP round trip (`TestRestTemplate` against
  a randomly-bound port, not `MockMvc`) submitting a graph, fetching its
  plan, and checking a 404 for an unknown graph id.
- `BenchmarkRunnerTest` (tagged `benchmark`, excluded from default
  `mvn test`; run with `-Dtest=BenchmarkRunnerTest -Dsurefire.excludedGroups=`):
  the 240-node measurement in "Measured results".

Every database-dependent test calls `repo.count()` first and aborts via
`Assumptions.abort` if PostgreSQL is unreachable, rather than failing; on
this machine PostgreSQL was reachable for every run below.

## Findings

**The first percentile test failed by a small but consistent amount, and
the bug was in the reference implementation, not the SQL.** The first
version of `referencePercentile` used nearest-rank (`sorted[round(p * (n -
1))]`). `CostPercentileServiceTest` immediately disagreed with
`percentile_cont` on p90 and p99 (p50 happened to agree on an even sample
size, which is what made this look like a p90/p99-only bug at first). The
discriminating fact: PostgreSQL's `percentile_cont` is explicitly
documented as a *continuous* (linearly interpolated) percentile, not a
nearest-rank one. Root cause was the Java side using the wrong definition
of "percentile", not a database query bug. Fixing `referencePercentile` to
interpolate between the two bracketing order statistics made every
percentile agree to floating-point precision.

**The 240-node benchmark applies linear-chain fusion 30 times and
sibling-branch fusion zero times, and that is a real result, not a bug.**
With 16 simulated workers and only 3-way sibling fan-out per block, there
is never enough worker contention for fusing a sibling group to pay off:
for k independent siblings with ample free workers, their parallel finish
time is `overhead + max(costs)`, while fusing them serially costs
`overhead + sum(costs)`, which is strictly worse for any k ≥ 2 and positive
costs. Sibling fusion can only win when workers are scarce enough that some
siblings would otherwise queue. `FusionOptimizerTest.
siblingFusionIsAppliedWhenWorkersAreScarceEnoughThatFusingWins` constructs
exactly that regime (2 workers, 3 siblings) and confirms the mechanism
fires and wins there. The 240-node benchmark's 16-worker, 3-way fan-out
shape is simply not in that regime, and the optimizer correctly recognizes
that rather than fusing anyway.

**The REST API's chain-fusion groups looked wrong at first because they
crossed block boundaries.** The first read of the live
`/api/graphs/benchmark/plan` response showed a fused group named
`unit_b0_merge` containing nodes from block 0 and block 1 together, which
looked like a bug (block boundaries should matter, surely). They do not, by
design: see "Architecture", "Why chain fusion can cross what looks like a
block boundary". Re-reading the fusion rule (fan-in/fan-out degree only)
confirmed this is correct, not a leak between blocks.

## Measured results

Machine: WSL2 Ubuntu 22.04 on Windows 11, OpenJDK 21.0.12, PostgreSQL 14.24
(native WSL2 service, already running), 16 simulated workers.

**240-node benchmark (30 blocks x 8 nodes), per-task-type cost percentiles
from `percentile_cont` over 2,000 recorded executions per type**
(`docs/benchmark_output.txt`):

| task type | n | p50 (ms) | p90 (ms) | p99 (ms) |
|---|---|---|---|---|
| entry | 2000 | 4.00 | 5.00 | 5.00 |
| transform_a | 2000 | 12.00 | 14.00 | 17.00 |
| transform_b | 2000 | 10.00 | 12.00 | 14.00 |
| transform_c | 2000 | 14.00 | 17.00 | 20.00 |
| fanout_x | 2000 | 20.00 | 24.00 | 27.00 |
| fanout_y | 2000 | 18.00 | 22.00 | 25.00 |
| fanout_z | 2000 | 22.00 | 27.00 | 31.00 |
| merge | 2000 | 6.00 | 7.00 | 8.00 |

**Replayed makespan, baseline (240 units, one per task) vs. fused (121
units) on a simulated 16-worker pool:**

- Baseline makespan: 2400.00 ms
- Fused makespan: 2162.00 ms
- **Makespan cut: 9.92%**
- Fusion plan: 30 linear-chain groups fused, 0 sibling-branch groups fused
  (see "Findings" for why 0 is the correct answer at this worker count and
  fan-out width, not a shortfall)
- Task output order unchanged: **true** (the fused schedule's finish-time
  order is a valid topological order of the original 240-node DAG; checked
  programmatically, not by inspection)

What this measures: makespan of one fixed 240-node DAG and one fixed p50
cost assignment, under one greedy list-scheduling heuristic, with a 2.0 ms
per-unit overhead assumption. What it does not measure: performance of a
real worker fleet, an exact-optimal schedule, or sensitivity to a
different overhead constant (a larger assumed overhead would make fusion
matter more; this was not tuned to produce the 9.92% figure, see "What
this is, honestly").

**REST API, against the real running service**
(`docs/api_demo_output.txt`, `java -jar target/task-graph-scheduling-optimizer.jar`,
started and stopped by this measurement):

```
GET /api/graphs/benchmark/plan -> 200, nodeCount=240, fusedUnitCount=121,
  baselineMakespanMs=2400.0, fusedMakespanMs=2162.0,
  savingsPercent=9.916666666666666, outputOrderUnchanged=true
POST /api/graphs {3-node custom graph} -> 200, {"graphId": "..."}
```

## Building and running

```bash
# Prerequisite: a reachable PostgreSQL with the database and role this
# project expects (see src/main/resources/application.yml), e.g.:
#   sudo -u postgres psql -c "CREATE ROLE taskgraph_app LOGIN PASSWORD 'taskgraph_app_local_only';"
#   sudo -u postgres psql -c "CREATE DATABASE task_graph_optimizer OWNER taskgraph_app;"
# or docker compose up -d (see docker-compose.yml). Hibernate creates the
# task_executions table on first run (ddl-auto: update).

# Tests (fast ones; the 240-node benchmark is excluded by default)
mvn test

# The 240-node benchmark measurement (writes docs/benchmark_output.txt)
mvn test -Dtest=BenchmarkRunnerTest -Dsurefire.excludedGroups=

# Run the service
mvn -DskipTests package
java -jar target/task-graph-scheduling-optimizer.jar

# Exercise the REST API
curl http://localhost:8080/api/graphs/benchmark/plan
curl -X POST http://localhost:8080/api/graphs -H "Content-Type: application/json" \
  -d '{"nodes":[{"id":"n1","taskType":"entry","dependsOn":[]},{"id":"n2","taskType":"transform_a","dependsOn":["n1"]}]}'
```

## Sibling comparison

[model-test-deploy-harness](https://github.com/Manas103/model-test-deploy-harness)
is also a Java 21, tick-based discrete-event scheduler over a fixed worker
pool, but it schedules independent per-user jobs for fairness (max-min
fair-share across 8 users on a 4-worker pool, with preemption), not a
dependency-constrained DAG. Its headline number is fairness deviation
(21.3% with preemption, against an unmet 4% target); this repo's headline
number is a makespan cut (9.92%) on a graph where task order is fixed by
real dependencies, not by a fairness policy. The two problems share almost
nothing once dependencies exist: this repo's scheduler has to respect a
topological order and its optimizer has to decide whether to collapse
nodes together before scheduling even starts, where the fair-share
scheduler's only decision is which ready, independent job to run next.

## Limitations

- The worker-pool simulator is a greedy heuristic (highest critical-path
  rank first, earliest-free worker), not an exact scheduler; it is used
  identically for baseline and fused plans so the makespan delta isolates
  what fusion changed, but neither number is a provable optimum.
- The 2.0 ms per-unit overhead is a stated modeling assumption, not a
  measurement from a real task-dispatch system; the 9.92% makespan cut is
  a property of this model at this overhead, not a universal constant.
- The 240-node benchmark's specific topology (16 workers, 3-way sibling
  fan-out) never triggers sibling-branch fusion; that mechanism is real and
  is proven to fire and win in a dedicated worker-scarce test case, but not
  on this benchmark's own numbers (see "Findings").
- `GraphOptimizationService` holds submitted graphs in an in-memory map
  with no eviction; this is a demo-scale REST surface, not a production
  store.
- The recorded execution history behind every percentile is synthetic
  (seeded Gaussian), stated as such throughout; the real, unmocked part of
  the "recorded executions in PostgreSQL" claim is the `percentile_cont`
  query against whatever rows exist, not the rows' provenance.
