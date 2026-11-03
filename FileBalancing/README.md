# FileBalancing

Online placement of file replicas on servers. Files that are traversed together end up on the
same server, each file keeps `k` replicas while servers fail and come back, and no server holds
much more than its share.

The whole algorithm is in [`ReplicaPlacer.kt`](src/main/kotlin/ReplicaPlacer.kt);
[`Main.kt`](src/main/kotlin/Main.kt) holds a demo, correctness checks, a simulation and a benchmark.
The rest of the code runs the placer on real traces (Wikipedia clicks, Twitter interactions) as a
cache with different eviction policies (Wikipedia, Twitter, Linux kernel co-changes), and compares it with a distributed version of the same
algorithm; see [What we learned](#what-we-learned) for the short version.

| file | what it is |
|---|---|
| `ReplicaPlacer.kt` | the central placer: sees the whole graph and the true placement |
| `Placer.kt` | the interface both placers implement (add / remove file, traversal, stats) |
| `DistributedPlacer.kt` | the same algorithm run by the servers, each knowing only its own files and what it learnt from traffic |
| `PairCounts.kt` | what a distributed server remembers of traversed pairs: every pair, or a count-min sketch, behind one interface |
| `PairMemoryCheck.kt` | the memory estimate of the distributed placer against the real heap |
| `Eviction.kt` | cache eviction policies: LRU, FIFO, Random, CLOCK, LFU, SLRU, ARC, W-TinyLFU, Belady |
| `CacheSimulation.kt` | runs a placer as a cache of at most `maxFiles` files, each eviction policy in its own thread |
| `WikiPrep.kt`, `WikiSimulation.kt` | Wikipedia clickstream: conversion to a binary file, and the replay |
| `TwitterSimulation.kt` | SNAP Higgs Twitter interactions replay |
| `LinuxSimulation.kt` | Linux kernel history replay: files changed in one commit are traversed together |
| `DistributedSimulation.kt` | central vs distributed placer on the same traces |
| `CachePlot.kt`, `DistributedPlot.kt` | Kandy charts of the results, into `docs/` |
| `Scaling.kt` | time and memory as files and traversals grow |
| `Latency.kt` | the time of each traversal, to find the slowest ones |

## Model

- `n` servers, each with a capacity (the most replicas it can store), each up or down.
- Every file keeps `k` live replicas on distinct live servers (fewer if fewer than `k` servers are up).
- A traversal between two files costs **0** if they share a live server, the **distance**
  between their closest live replicas otherwise (1 on a uniform network), and is
  **unavailable** if one of them has no live replica. Distances are optional: an `n × n`
  symmetric matrix with a zero diagonal (racks, datacenters, link speeds). To not over prioritize near clusters, to not fail all at once
- Copying a replica to server `t` costs **α · distance** from the file's nearest live replica to
  `t` (just α on a uniform network), so α is measured in traversals over a link of distance 1.
- Balancing. A live server holds at most `(1 + slack)` times its share of the replicas, where
  shares are proportional to capacity. Without a cap, the cheapest placement is to put every
  file on the same `k` servers.
- A server never gets more than its capacity, and never more than one replica per file (so at
  most `F` replicas). The part of a share that a server can't use goes to the other servers
  (water-filling). This keeps the caps feasible whenever `Σ min(capacity, F) ≥ k·F`.

The goal is to keep `traversal distance + α · copy distance` low on a workload that is not known
in advance; on a uniform network that is `remote traversals + α · copies`.

## API

```kotlin
val p = ReplicaPlacer(servers = intArrayOf(500, 500, 1_000, 1_000, 1_000, 1_000, 2_000, 2_000), replicaCount = 2, alpha = 8.0, halfLife = 10_000.0, slack = 0.1, repairRate = 0.1)
p.onChange = { change -> /* Change.Copy(file, to) or Change.Delete(file, from): mirror it in storage */ }

// Optional: distances between servers, e.g. 1 within a rack and 4 across racks of 4.
val racks = Array(8) { a -> DoubleArray(8) { b -> if (a == b) 0.0 else if (a / 4 == b / 4) 1.0 else 4.0 } }
val q = ReplicaPlacer(servers = IntArray(8) { 1_000 }, replicaCount = 2, distances = racks)

p.addFile(42)                      // replicas go to the least loaded live servers
p.addEdge(42 to 7)                 // one traversal; returns LOCAL, REMOTE or UNAVAILABLE
p.toggleComponent(3)               // server 3 goes down (or comes back up)
p.replicasOf(42)                   // live replicas of file 42
p.pendingRepairs                   // files still waiting for a repair copy
```

`stats` counts local, remote and unavailable traversals, their total distance (`commCost`), and
copies split by cause (optimisation, swap, repair, rebalance) with their total distance
(`copyDistance`).

## Key ideas

### 1. Pair weights that decay

For each pair of files that has been traversed, the placer keeps one `Edge` holding a weight and
the time it was last updated. Each traversal adds 1, and every weight halves every `halfLife`
traversals:

```
w(now) = w(t) · exp(−λ · (now − t)),   λ = ln 2 / halfLife
```

The decay is applied lazily, only when an edge is read, so an update costs O(1) and weights
from different moments can still be compared. Recent traffic counts almost fully and old
patterns fade, so the placer follows the workload as it is now. Pairs whose weight drops below
`1e-4` are deleted, so memory grows with the number of recently active pairs, not with the
total number of traversals. A pair used once falls below that after about 13.3 half-lives.
Faded pairs are deleted when a file's evaluation reads them, and by an incremental sweep: each
traversal visits the next few files in turn (on average F / 2²⁰ of them), so every file is
visited once per 2²⁰ traversals and no traversal pays for a pass over the whole graph. See
[Incremental sweep](#incremental-sweep).

### 2. Rent-or-buy trigger

Each file keeps a **debt**: the remote cost its traversals have paid since it was last
evaluated. Once the debt reaches α (the traffic has cost as much as one copy), the file is
evaluated and its debt is reset. Most traversals only update counters; the expensive search
runs only after enough has been paid to justify it.

### 3. Exact gains, one move at a time

When a file triggers, the placer computes the exact change in weighted cost for moving each of
its replicas to every server:

```
gain(j → t) = Σ over neighbours g of  w(f, g) · (cost now − cost after replica j moves to t)
```

Only the edges of the file being moved change cost, so this needs just that file's neighbour
list. On a uniform network an edge changes only when it becomes local or stops being local,
which one pass over the neighbours' replicas covers; with distances, see
[Weighted server distances](#weighted-server-distances). Two moves are considered:

- **Relocate**: move one replica to a server that has room (1 copy).
- **Swap**: if the best target is full, also move a file from that server back the other way
  (2 copies). Up to 16 files on the full server are sampled as swap partners.

The best move is applied only if `gain ≥ α · copy distance` (`copies · α` on a uniform
network). A pattern therefore has to keep up
roughly α traversals per half-life before files move for it, so short bursts do not cause
moves that cost more than they save. `verifyGains` checks every predicted gain against a full
recomputation of the cost.

### 4. Failures and recovery

- **Server down**: each file that had a copy on it is queued for repair. The queue is kept in
  buckets by live replica count, and files with the fewest live replicas are repaired first:
  a file down to its last copy goes before one that is only one short of `k`. Repair is
  throttled to `repairRate` copies per traversal (a budget that refills with each `addEdge`,
  with no saving up while idle), so a failure does not flood the network. Each copy goes to
  the live server where it helps the file's neighbours most, minus what the copy itself costs
  (α · distance from the nearest live replica); ties go to the least loaded server. If every replica of a file is on dead servers, the file leaves the queue and is
  unavailable until one of them comes back. `repairRate = Double.POSITIVE_INFINITY` repairs
  everything immediately.
- **Grace period**: most outages are short (a restart, a network blip), and repairing for them
  wastes copies that are deleted again when the server returns. With `minReplicas < k` and
  `grace > 0`, a file that still has at least `minReplicas` live replicas is not repaired
  while the server holding its missing copy has been down for less than `grace` traversals.
  A file is repaired at once if it falls below `minReplicas`, or if it is short even counting
  the copies on servers still in grace (two overlapping outages). When the grace period runs
  out, the server's files are queued as usual. Ceph (`mon_osd_down_out_interval`, 10 minutes)
  and HDFS (about 10.5 minutes before a DataNode counts as dead) wait in the same way.
- **Server up**: files are assumed immutable (real one close to HDFS generation stamps), so the replicas on it are still valid. Its files
  are not visited; the clean-up is lazy:
  - A file that was repaired meanwhile now has a surplus replica (`k + 1` live). It drops the
    least useful one the next time it is traversed.
  - The caps shrink when a server returns, so some servers are now over theirs. They sample
    their files: surplus files among them are trimmed first (free deletes), otherwise the
    cheapest move to a server with room is made. Only if neither works are all files scanned
    for surplus, before the cap is exceeded.
  - A queued file whose live count went up stays in its old bucket and is moved to the right
    one (or off the queue) when the queue reaches it. Buckets can only understate the live
    count, so the most urgent files are still repaired first.
  - Files whose every copy was on a dead server could not be repaired, because there was no
    source to copy from. Those that now have a live copy are queued right away.
- If no placement fits the cap, the cap is exceeded and `stats.overflows` is incremented.

## Complexity

For the functions `Main.kt` calls. `n` servers, `k` replicas per file, `F` files, `d` active
neighbours of a file, `E` active pairs, `m` replicas on the toggled server (about `F·k/n`), `r`
copies a file has including those on dead servers (usually `k`). Hash-map operations are
counted as O(1) expected.

| function | time |
|---|---|
| `addFile` | O(n log n + n·k): a new file has no neighbours, so it takes the least loaded servers (by fraction of capacity). The caps are recomputed once, because the file count changed |
| `addEdge` | O(r + k²) for the traversal itself, plus O(d·k²) once for a file with a surplus replica, plus O(d·k² + n·k) when it triggers a file (up to 17× the `d·k²` term if a swap is tried). At most `2R/α` triggers over `R` remote traversals. While repairs are queued, up to ⌈`repairRate`⌉ repair copies at O(d·k² + n) each. When a server's grace period runs out, O(m·r) to queue its files. The sweep adds O(d) for each of ⌈F / 2²⁰⌉ files at most (O(E / 2²⁰) on average) |
| `toggleComponent` (down) | O(n log n + m·r) to recompute the caps and queue the files that had a copy on it. With a grace period (`minReplicas < k`, no other server in grace), a fully replicated file stays at or above `minReplicas`, so only already-queued files on the server are re-checked: O(n log n + min(Q, m)·r) for a queue of `Q`. The `m·r` scan then happens only if the grace period runs out. If fewer than `k` servers are left up, the whole queue is re-checked as well. The copies themselves happen later, in `addEdge` |
| `toggleComponent` (up) | O(n log n + s·r + X·(d·k² + n)): repair is not delayed until then: files below `k` were queued when the server went down. The only files queued here are the `s` files whose every copy was on a dead server; nothing could be copied from them before, and now there is a source; then `X` evictions (trims or moves of sampled files) bring servers back under the cap. The other files on the server are cleaned up later, in `addEdge`. If fewer than `k` servers were up, the target rises and all files are re-queued, adding O(F·r). If the cap can't be met otherwise, a scan for surplus adds O(F·r) |
| `pendingRepairs` | O(1). An upper bound: files a returning server already completed are counted until the queue reaches them |
| `replicasOf` | O(r) |
| `capacity`, `load` | O(1); the caps are cached until a file is added or a server is toggled |
| `isUp`, `fileCount`, `serverCount`, `stats` | O(1) |
| `checkInvariants` | O(F·k·r) |

The fallback when every server is full (`makeRoom`) can add O(n·(d·k² + n)) to a single
re-created replica. With `verifyGains` on, each optimisation move also costs O(E·k²) for the
full recomputation.

Space is O(F·k + E): replica lists and the server index take O(F·k), and each active pair is one
`Edge`. Pairs are dropped once their weight falls below `1e-4` (about 13 half-lives without
traffic), so `E` depends on recent traffic, not on the total number of traversals.

## Weighted server distances

Pass `distances` (an `n × n` symmetric matrix, zero on the diagonal, positive elsewhere) to model
racks, datacenters or link speeds. Without it the network is uniform and the placer uses a
faster formula built on costs being 0 or 1; with it, `gains` and `losses` use the general one
below. An all-ones matrix makes exactly the same moves as no matrix (checked: same copies and
same cost on the benchmark instance).

**Why 0/1 is cheap.** With 0/1 costs, moving replica `j` of a file to server `t` changes a
neighbour's edge in only two cases: the edge was remote and the neighbour has a replica on
`t`, so it becomes local, or it was local only through replica `j`, so it becomes remote. The
first case touches at most `k` targets per neighbour, so one pass over the neighbours'
replicas gives every gain: O(d·k² + n·k).

**With weights, every target matters.** Moving a replica to `t` changes the distance to every
neighbour. Per neighbour (`weightedGains`):

- The distance from each of the file's replicas to the neighbour, keeping the closest replica
  `j*` (the edge's current cost `c`) and the second closest (the cost without `j*`): O(k²).
- `toT[t]` = the distance from server `t` to the neighbour, for every live target: **O(n·k)**.
- `gain[j][t] += w · (c − min(c without j, toT[t]))`, where the cost without `j` is `c` unless
  `j = j*`: **O(n·k)**.

One evaluation becomes **O(d·k·(k + n))**. The `n` term used to be added once per evaluation,
and now it is multiplied by the number of neighbours `d`. Dropping a replica (`losses`) only
matters for each neighbour's closest replica, so trimming stays O(d·k²).

**Copies cost their distance.** A copy travels from the file's nearest live replica, so it costs
`α · distance`, and a move is made only if `gain ≥ α · copy distance`. Options are ranked by
net gain (gain minus copy cost), so a relocation inside a rack beats an equal gain across racks.
A repair copy is placed by net gain too. A new file's replicas are uploads and cost nothing, so
they still go to the least loaded servers. That matters: if they were placed by copy cost, both
replicas of a file would land in one rack, and every traversal to the other rack would pay the
long distance.

**Triggers.** A remote traversal adds its real distance to both files' debts, so long-distance
traffic reaches α sooner. The bound holds in cost units: at most `2·commCost/α` triggers.

| function | 0/1 costs | weighted costs |
|---|---|---|
| `addFile` | O(n log n + n·k) | unchanged: a new file has no neighbours to be close to |
| `addEdge`, the traversal itself | O(r + k²) | unchanged: `dist` already takes the minimum over all replica pairs |
| `addEdge`, a trigger | O(d·k² + n·k) | **O(d·k·(k + n))** |
| `addEdge`, the swap check | 16 · O(d·k²) | unchanged: one target per partner, O(k) per neighbour |
| `addEdge`, a repair copy | O(d·k² + n) | **O(d·n·k)**: the new replica is placed by net gain over all servers |
| `addEdge`, trimming a surplus replica | O(d·k²) | unchanged: only the cost with and without each replica is needed |
| `addEdge`, grace expiry and sweep | O(m·r), O(d) per swept file | unchanged |
| `toggleComponent` (down) | O(n log n + m·r) | unchanged: it only queues files |
| `toggleComponent` (up) | O(n log n + s·r + X·(d·k² + n)) | O(n log n + s·r + **X·d·n·k**): each eviction scores one replica of 16 sampled files against every server |
| `makeRoom` (fallback) | O(n·(d·k² + n)) | **O(n²·d·k)** |
| `weightedCost`, `verifyGains` | O(E·k²) | unchanged |

Space adds the O(n²) distance matrix.

**Measured cost.** On the benchmark instance (64 servers, `k = 3`, 50,000 files in groups of 20,
uniform group popularity, 3 million traversals): 1.45 µs per traversal without a matrix,
2.91 µs with an all-ones matrix (same moves, general formula), and 4.72 µs with 8 racks of 8 at
distance 1 within a rack and 4 across. The last one also triggers more often, because cross-rack
traffic fills the debt faster (22,083 copies against 13,297).

**A rack hierarchy would be cheaper (not implemented).** If the distance depends only on the
lowest level two servers share (server, rack, datacenter), every server in a rack has the same
`toT`, except one that already holds the neighbour's replica. `toT` could then be computed per
rack, so every `n` in the new terms would become `R`, the number of racks, and the matrix would
be replaced by O(n) rack IDs.

## Results

All numbers are the output of `Main.kt`. The seeds are fixed, so every run prints the same.

### Terms

- **Traversal**: one call to `addEdge(f to g)`, meaning a request that touches files `f` and `g`
  together. The workload is a stream of traversals.
- **Local / remote / unavailable**: a traversal is local if some live server holds both files,
  remote if both files are live but never on the same server, and unavailable if a file has
  no live replica.
- **Remote %**: the share of traversals in a window that were remote. Lower is better. The
  simulation reports it per window of 50,000 traversals, not cumulatively, so each row shows
  how well the placement fits the traffic at that time.
- **Copies**: replicas created in the window, for any reason: optimisation (relocate or swap),
  repair after a failure, or rebalancing after a server returns. Deletes are free.
- **Total cost**: `remote traversals + α · copies` over the whole run. This is the quantity the
  placer tries to keep low; with α = 8, one copy counts as much as eight remote traversals.
- **Adaptive**: the placer with the given α. **Static**: the same files on the same starting
  servers, but with α = ∞, so it never moves a file to optimise. It still repairs after a
  failure and rebalances after a return, so it is a fair baseline for "place once, then only
  react to failures".

### Simulation

8 servers, `k = 2`, 400 files in 20 groups of 20, α = 8. 90 % of traversals pick two files from
the same group, 10 % pick two random files. Server 0 fails at 200k traversals and returns at
300k. At 450k every file is moved to a new random group, so the old pattern becomes useless.

Each row is one window of 50,000 traversals, labelled by the traversal it ends at. An event
listed in a row happened at the start of that window.

| window ends at | event | remote % (adaptive) | remote % (static) | copies (adaptive) | copies (static) |
|---:|---|---:|---:|---:|---:|
| 50k | | 13.2 | 54.6 | 292 | 0 |
| 100k | | 5.5 | 54.1 | 0 | 0 |
| 150k | | 5.3 | 54.4 | 0 | 0 |
| 200k | | 5.4 | 54.6 | 0 | 0 |
| 250k | server 0 down | 5.0 | 42.3 | 116 | 100 |
| 300k | | 4.6 | 42.4 | 0 | 0 |
| 350k | server 0 up | 5.1 | 41.8 | 50 | 26 |
| 400k | | 5.0 | 41.7 | 0 | 0 |
| 450k | | 4.9 | 42.1 | 0 | 0 |
| 500k | groups reshuffled | 17.3 | 50.3 | 377 | 0 |
| 550k | | 5.1 | 50.2 | 8 | 0 |
| 600k | | 5.1 | 50.1 | 0 | 0 |
| 650k | | 5.0 | 50.5 | 0 | 0 |
| 700k | | 5.1 | 50.5 | 0 | 0 |
| **all** | | **6.55** | **48.54** | **843** | **126** |

The totals add up all 14 windows:

- **Remote traversals**: a window's remote count is `remote % × 50,000`. Summed, that is
  45,867 for adaptive and 339,774 for static, out of 700,000 traversals. Summing the rounded
  percentages instead (91.6 and 679.6, times 500) gives 45,800 and 339,800.
- **Copies**: the column sums. Adaptive is 292 + 116 + 50 + 377 + 8 = 843. Static is
  100 + 26 = 126: it only copies to repair the failure and to rebalance after the return.
- **Total cost** = remote + α · copies:
  - adaptive: 45,867 + 8 · 843 = 45,867 + 6,744 = **52,611**
  - static: 339,774 + 8 · 126 = 339,774 + 1,008 = **340,782**

  340,782 / 52,611 ≈ 6.5, so adaptive costs about 6.5 times less, even though it makes almost
  7 times as many copies.

How to read it:

- **Static is near random.** With 2 replicas each on 8 servers, two random files share a
  server with probability `1 − C(6,2)/C(8,2) ≈ 46 %`, so about 54 % of traversals are remote.
  That is what static shows.
- **About 5 % is the floor.** The 10 % random traversals are remote about half the time
  whatever the placement, which gives about 5 %. Adaptive is at 5.5 % by the second window
  (13.2 % is the first window's average, while it is still converging), so almost every group
  traversal is local. After convergence, the copies drop to 0: the gain threshold `gain ≥ copies · α` stops
  further moves once nothing pays off.
- **A failure doesn't hurt.** Server 0 held about `400 · 2 / 8 = 100` replicas. Repairs place each
  new copy where the file's neighbours are, so the remote share stays at 5 %. Static even improves (54 % → 42 %), because its repairs are also
  placed by gain: the failure acts as a partial optimisation for it.
- **A return costs little.** When server 0 comes back, the files that were repaired have 3
  live replicas. Servers over their cap trim the surplus files they sample (free deletes), and
  50 rebalance copies move replicas back onto server 0. The remaining surplus replicas are
  dropped when their files are next traversed.
- **A change in the workload is followed.** After the reshuffle, the window average rises to
  17.3 %, and 377 copies (about one per file) rebuild the placement. One window later it is
  back at 5 %. The decay (`halfLife`) is what lets the old pattern fade so the new one wins.
- **Copies are a small part of the cost.** 843 copies cost 6,744, against 45,867 for remote
  traversals; most of the adaptive cost is the unavoidable random traffic.

### Benchmark

64 servers, `k = 3`, 50,000 files in groups of 20, 3 million traversals. Groups are picked with
Zipf popularity, so a few groups get most of the traffic, as in real workloads as few files will be accessed the most.

- **0.71 µs per traversal** on average, including every trigger, move and decay sweep.
- Remote share falls from **49 %** in the first 10 % of traversals to **30 %** in the last 10 %.
- **6,797 copies** in total, about 0.14 per file.

The remote share stays higher than in the simulation. The likely reason is the Zipf tail:
rarely used groups are traversed too seldom for a move to pay off within a half-life, so the
gain threshold leaves them where they are. That is intended, since moving them would cost
more than it saves. (This explanation has not been measured per group.)

### Racks

8 servers in 2 racks of 4: distance 1 within a rack, 4 across. 400 files in 4 groups of 100,
the same 90 % / 10 % traffic as the simulation, α = 8, 400,000 traversals, server 0 down half
way. The same workload runs on a placer that is given the distances and on one that assumes a
uniform network. Both are charged the real distances, from a mirror of their placement that
`Main.kt` keeps from `onChange`. Cost is `traversal distance + α · copy distance`.

| `k` | placer | traversal distance | copy distance | total cost | copies | cross-rack traversals |
|---:|---|---:|---:|---:|---:|---:|
| 1 | aware | 249,826 | 1,763 | **263,930** | 1,235 | 5.2 % |
| 1 | uniform | 507,289 | 3,338 | **533,993** | 1,226 | 26.6 % |
| 2 | aware | 41,761 | 934 | **49,233** | 565 | 1.2 % |
| 2 | uniform | 42,938 | 1,255 | **52,978** | 619 | 1.3 % |

- **With `k = 1` the distances halve the cost.** A group of 100 files is bigger than one
  server's cap (55 replicas), so it has to span servers. The aware placer keeps each group
  inside one rack; the uniform one doesn't care which servers a group spans, and over a quarter
  of the traversals cross racks.
- **With `k = 2` the gain is small (7 %).** Each group fits on one server, so both placers make
  the group traffic local and the distances only matter for the 10 % random pairs and for the
  copies. Most of the saving is in copies: a similar number of copies travels a total distance
  of 934 instead of 1,255.
- In a wider sweep (far distance 2, 4 and 10; groups of 20, 100 and 200) the aware placer was
  cheaper in every case except two near-ties with `k = 2` and groups of 20 (1.6 % and 0.6 %
  more expensive), where almost all group traffic is local for both. The gap grows with the far
  distance: with `k = 1`, groups of 100 and distance 10 across racks it is 3.1 times cheaper.

### Correctness checks

- **Gain check**: on servers with uneven capacities `[20, 20, 30, 40, 50]`, 113 copies (48 of
  them in swaps) were made, and for each one the predicted gain matched a full recomputation
  of the cost. This tests that the one-pass `gains` is exact. The same check with a random
  symmetric distance matrix (distances between 1 and 5) made 98 copies (38 swaps), all exact,
  which tests `weightedGains`.
- **Fuzz test**: 30,000 random steps (files added, traversals, servers toggled; all servers
  were down in 731 of them). After every step `checkInvariants` held: at least `k` live
  replicas on distinct live servers (more until a surplus is trimmed) or a queued repair, queue
  buckets never above the live count, server index consistent, loads within the caps. It made
  1,995 copies, saw 5,971 unavailable traversals (only while no live replica
  existed) and 0 overflows.
- **Fuzz test with distances**: the grace-period fuzz test again with a random distance matrix.
  Invariants held after every step, every move's gain matched a full recomputation, 0 overflows.
- **Priority repair demo**: with `k = 3` and 2 of 6 servers down, files with one live replica
  are all repaired (after 16 traversals at `repairRate = 0.25`) before any file with two.
- **Grace period demo**: 6 servers, `k = 3`, `minReplicas = 2`, `grace = 1000`, 60 files. A
  500-traversal outage makes 0 repair copies. A 3,000-traversal outage makes 30, one for each
  of the 30 files on the server, once the grace period has run out. Two servers down together:
  9 copies right away, for the files that had a copy on both and were left with one.
- **Fuzz test with a grace period**: the same 30,000 steps with `k = 3`, `minReplicas = 2`,
  `grace = 300`. Invariants held after every step, including "every file that needs a repair
  is queued", which is what the shortcut in the down path relies on; 0 overflows.
- **Uneven capacities demo**: capacities `[20, 20, 40, 80]`, 60 files, `k = 2`. The loads are
  15/18, 15/18, 31/36 and 59/60, proportional to capacity. With server 0 down, the others fill
  to exactly 20/20, 40/40 and 60/60 (the last limited by one replica per file), with no
  overflow.

## Real traces: the placer as a cache

On real data there are far more files than the servers should hold, so the placer is used as a
cache: it holds at most `maxFiles` files, a traversal of a file it does not hold adds it, and when
it is over the limit an eviction policy picks a file to remove.

- **What the limit means**: the placer's graph holds at most `maxFiles` files; the files
  themselves stay stored on their servers whether or not the graph tracks them. Evicting a file
  only drops it (and its pairs) from the graph.
- **Load**: adding a file that is not in the graph (a miss). It costs nothing. A file seen for
  the first time is new and is placed freely (an upload; near placement applies here). A file
  that was evicted earlier is restored on the servers it is still stored on (`restoreFile`);
  only a replica whose server is now at its cap (or down) is copied elsewhere, and that copy
  costs α like any other move.
- **Miss %**: the share of touched files that had to be loaded.
- **Local %**: the share of traversals with both files on a common server, adaptive placer.
- **Saved %**: `1 − adaptive cost / static cost`, where cost is `remote traversals + α · copies`
  and the static placer (α = ∞) holds the same files but never moves them.

Every run uses 16 servers of equal capacity, `k = 2`, α = 8. Each eviction policy drives an
adaptive and a static placer at once, so both always hold the same files.

### Datasets

- **Wikipedia clickstream** (`enwiki`, September 2026): 21.2 million (article → article) link
  pairs with 2.0 billion clicks, over 3,267,066 articles. Traversals are drawn independently
  from the click counts (alias sampling, `Random(17)`), so the workload is stationary.
  Half-life 1 million traversals. `./gradlew wikiPrep`, then `./gradlew wiki -Ptraversals=…
  -PmaxFiles=… -Ppolicies=…`.
- **Twitter Higgs** ([SNAP](https://snap.stanford.edu/data/higgs-twitter.html)): 563,069
  retweets, mentions and replies around the 2012 Higgs boson announcement, in time order. A user
  is a file, an interaction between two users a traversal; 7,588 self-interactions are skipped,
  leaving 555,481 traversals over 304,198 users. Half-life 20,000. Unlike Wikipedia it is bursty
  and changes over time: only 29 % of interactions repeat an earlier pair, and one account (the
  announcement's) is in 36,000 of them. `./gradlew twitter -PmaxFiles=…`.
- **Linux kernel history** (`torvalds/linux`, 2005-04-17 to 2026-10-27): the files changed by
  each commit, oldest first, from a blobless clone (`git clone --bare --filter=blob:none`, 2.1 GB,
  then `./gradlew linuxLog`). Of 1,388,953 non-merge commits, 908,454 change one file (no pair) and
  4,356 change more than 32 (tree-wide sweeps, skipped). A commit of `m` files becomes `m − 1`
  traversals between neighbours in path order, so 476,143 commits give 1,413,078 traversals over
  156,764 files. Half-life 100,000. The topology differs from both others: dense clusters per
  subsystem or driver, few hubs (`MAINTAINERS`, the i915 headers), slow drift over 21 years, and
  62 % of traversals repeat an earlier pair (Twitter 29 %). `./gradlew linux -PmaxFiles=…`.

### Eviction policies

| policy | evicts |
|---|---|
| weight | the file whose pairs weigh least in total (`ReplicaPlacer.lightestFile`) |
| LRU | least recently used |
| FIFO | oldest added |
| Random | a random file |
| CLOCK | second-chance approximation of LRU |
| LFU | fewest hits since it was added (ties: least recently used) |
| SLRU | LRU with a 20 % probation and an 80 % protected segment |
| ARC | adaptive mix of recency and frequency, with ghost lists |
| W-TinyLFU | 1 % LRU window + SLRU main area; a count-min sketch decides admission |
| Belady | the file next used furthest in the future: offline, the fewest misses possible |
| all | nothing: no limit, every file stays (the reference) |

`cacheCheck()` in `Main.kt` checks each policy against hand-computed eviction sequences, that
W-TinyLFU resists a scan, and that Belady has the fewest misses on a Zipf trace.

### Wikipedia, 50 million traversals, 100,000 files

| policy | miss % | local % | adaptive cost | static cost | saved % |
|---|---:|---:|---:|---:|---:|
| LFU | 40.9 | 58.3 | 21,093,856 | 38,117,931 | **44.7** |
| weight | 39.5 | 44.8 | 27,847,779 | 37,189,280 | 25.1 |
| all (no limit) | 2.5 | 44.1 | 28,259,550 | 38,499,327 | 26.6 |
| W-TinyLFU | 39.4 | 43.2 | 28,709,261 | 37,464,802 | 23.4 |
| SLRU | 41.3 | 41.0 | 29,969,053 | 37,421,110 | 19.9 |
| Belady | 28.5 | 38.7 | 31,266,910 | 37,818,172 | 17.3 |
| ARC | 41.8 | 38.0 | 31,630,761 | 37,440,207 | 15.5 |
| CLOCK | 49.8 | 31.9 | 34,784,276 | 37,082,914 | 6.2 |
| LRU | 51.6 | 30.6 | 35,359,695 | 37,094,340 | 4.7 |
| FIFO | 57.2 | 26.6 | 36,890,684 | 36,837,395 | −0.1 |
| Random | 57.2 | 26.3 | 37,112,079 | 37,021,736 | −0.2 |

(`data/wikipedia/caches-50M.txt`.)

### Linux, the whole history, 10,000 files

| policy | miss % | local % | adaptive cost | static cost | saved % | loads + cost |
|---|---:|---:|---:|---:|---:|---:|
| LFU | 83.0 | 85.3 | 218,840 | 1,202,052 | 81.8 | 2,563,254 |
| weight | 44.1 | 40.4 | 890,347 | 1,087,523 | 18.1 | 2,137,829 |
| W-TinyLFU | 19.7 | 41.3 | 904,128 | 1,073,448 | 15.8 | 1,461,284 |
| Belady | 11.4 | 41.7 | 911,604 | 1,079,906 | 15.6 | 1,232,250 |
| all (no limit) | 5.5 | 41.6 | 920,904 | 1,155,899 | 20.3 | 1,077,668 |
| SLRU | 20.6 | 34.5 | 1,007,217 | 1,075,222 | 6.3 | 1,589,648 |
| ARC | 20.8 | 34.4 | 1,008,570 | 1,071,958 | 5.9 | 1,596,639 |
| LRU | 21.1 | 32.8 | 1,026,417 | 1,070,992 | 4.2 | 1,621,333 |
| CLOCK | 21.4 | 32.2 | 1,034,521 | 1,077,571 | 4.0 | 1,639,083 |
| FIFO | 23.5 | 27.4 | 1,080,286 | 1,085,723 | 0.5 | 1,743,402 |
| Random | 24.0 | 26.5 | 1,087,395 | 1,072,211 | −1.4 | 1,765,456 |

(`data/linux/caches-10k.txt`.)

- **Adapting pays here without any trick.** With no limit the adaptive placer saves 20 %, and
  W-TinyLFU and Belady come close to that at 10k files. Pairs repeat, so moves are repaid.
- **LFU's 82 % is the freed-slot effect again.** LFU misses 83 % of the time: a newly loaded file
  has one hit, so it is the next one evicted, and the following newcomer (often its neighbour in
  the same commit) lands on the slot it freed. Counting the loads, LFU is the second worst policy
  (2.56M against 1.08M without a limit), and at 1,000 files it thrashes so hard that adapting
  costs 140 % more than the static placer.
- Recency policies (LRU, CLOCK, ARC, SLRU) again leave little to optimise at small caches; they
  reach the no-limit saving from about 50,000 files.

- **Fewest misses is not the best placement.** Belady has by far the fewest misses (28.5 %) but
  ranks sixth on placement. LFU misses more than W-TinyLFU yet makes the most traversals local,
  more than holding every file (58 % against 44 %).
- **Why a cache can beat no limit.** A smaller set of files fits on fewer servers per group, and
  LFU keeps the files with many hits, which are the ends of heavy pairs. The placer then only has
  to place the dense core of the graph, and the misses go to one-off articles that would have
  been remote anyway.
- **Recency-only policies leave nothing to optimise.** With LRU, FIFO, Random or CLOCK a file
  rarely stays long enough for its pairs to build up the weight that pays for a move (the
  rent-or-buy trigger needs α of remote traffic first), so adapting saves 0–6 %.
- **weight** (evict the lightest file by pair weight) is second, and it is the only policy that
  uses the graph itself.

### Cache-size sweeps

Every policy at 10k–1M files on Wikipedia (50M traversals), 1k–200k on Twitter and 1k–100k on
Linux. Charts in
`docs/caches/` (`./gradlew plotCaches`), one panel per policy, with Belady dotted and "all"
dashed; raw rows in `data/*/sweep.csv`.

| saved % (Wikipedia) | 10k | 20k | 50k | 100k | 200k | 500k | 1M |
|---|---:|---:|---:|---:|---:|---:|---:|
| LFU | 59.8 | 62.7 | 52.3 | 44.7 | 36.6 | 28.9 | 26.6 |
| weight | 28.2 | 30.2 | 23.2 | 25.1 | 25.8 | 26.3 | 26.6 |
| W-TinyLFU | 6.7 | 10.5 | 18.2 | 23.4 | 25.6 | 26.5 | 26.4 |
| Belady | 2.0 | 4.3 | 10.5 | 17.3 | 23.5 | 26.5 | 26.6 |
| LRU | 0.0 | 0.3 | 1.4 | 4.7 | 12.2 | 24.2 | 26.6 |

- All policies converge to the no-limit value (26.6 %) once the cache holds most of the active
  articles.
- The smaller the cache, the more the policy matters: LFU gains most at 10k–20k files, where
  LRU gains nothing.

**Twitter: adapting does not pay.** With every policy except LFU, and without a limit, the
adaptive placer is 0–2 % *worse* than the static one at every cache size (local ≈ 25 % for
both). Most interactions are between pairs that never meet again, so a move is mostly paid for
by traffic that does not come back. LFU looks good at some sizes (47.5 % at 10k) and bad at
others (−21.7 % at 5k). The gain is an artefact: LFU misses 74 % of the time, and the central
placer puts each newly loaded user on the least loaded servers, which are the ones that just
freed a slot by evicting a neighbour of that user.

## Distributed placement

`DistributedPlacer` runs the same algorithm without a global view. Each server knows:

- the files it stores, with the pairs it has seen traversed and their decayed weights (a file's
  pairs are spread over its replicas, each seeing the traversals it served);
- a location cache of other files, learnt from traffic (each remote traversal tells both ends
  where the other file is) and corrected by a bounce when stale. Each server sweeps its own
  state incrementally, as the central placer does: it deletes faded pairs, and a cached location
  that no remaining pair has named for two sweep passes;
- the servers' loads, from gossip every 1,000 traversals.

Protocol:

1. A file's **home** (file mod n) is its directory entry. A server without a location for a file
   asks the home (2 messages).
2. Each replica keeps its own **debt**; when it reaches α / k, its server **coordinates**: it
   pulls the pairs the file's other replicas have seen (2 messages each), merges them, and
   evaluates relocate and swap moves with the gains of `ReplicaPlacer`, using the locations it
   knows.
3. A move is **proposed** to the target (2 messages), which refuses if it has no room by its own
   count. A swap partner is chosen by the full target from its own files.
4. Options: `notify` (a moved file tells the servers of its N heaviest neighbours where it went),
   `verify` (ask the directory for the locations a move depends on first), `oracle` (exact
   locations at no cost, for diagnosis only), `placeNear` (see below).
5. New files go to the less loaded of two random servers (by the gossiped loads). Picking the
   least loaded of all made every home send a gossip round's new files to the same servers.
6. A removed file tells its neighbours' servers, so they drop its pairs.

`./gradlew distributed -Pdataset=wiki|twitter -Ppolicy=LRU|LFU`, charts with
`./gradlew plotDistributed` into `docs/distributed/`.

### Near placement

A file is loaded because a traversal reached it from a file already in the placer. With
`placeNear`, its replicas go on the servers of that file, where there is room, so the traversal
that brought it in is already local. Both placers have it: `cent-near` is the central placer
with it, `dist-near` the distributed one (with `notify = 8`). Static placers never place near, so
the saving includes what near placement brings.

The central placer leaves one slot free below the cap when it places near. Without that margin,
servers filled exactly to the cap went over it when the cache evicted (caps follow the file
count), and every such eviction cost a rebalancing copy; on Twitter LRU that brought saved from
84 % down to 51 %. The distributed placer has no rebalancing and just refuses placements on a
full server.

### Results

Wikipedia: 10 million traversals, 100,000 files. Twitter: the whole trace, 10,000 files.

| saved % | central | **cent-near** | distrib | notify8 | verify | both | oracle | **dist-near** |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Wikipedia, LRU | 4.4 | **82.2** | 3.6 | 4.4 | 4.4 | 4.4 | 4.4 | **81.9** |
| Wikipedia, LFU | 31.0 | **79.8** | 17.2 | 17.7 | 17.7 | 17.6 | 17.7 | **79.4** |
| Twitter, LRU | −2.1 | **84.1** | −2.9 | −2.8 | −2.5 | −2.6 | −2.6 | **82.5** |
| Twitter, LFU | 47.5 | **89.8** | −1.5 | −2.3 | −1.4 | −2.1 | −1.7 | **92.5** |
| Linux, LRU | 4.2 | **75.3** | 1.2 | 3.8 | 3.5 | 3.8 | 4.0 | **73.7** |
| Linux, LFU | 81.8 | **96.2** | 8.0 | 8.3 | 8.4 | 8.4 | 8.4 | **94.5** |

| protocol, per traversal | Wikipedia LRU | Wikipedia LFU | Twitter LRU | Twitter LFU |
|---|---:|---:|---:|---:|
| messages, notify8 | 7.3 | 5.8 | 5.9 | 9.1 |
| messages, dist-near | 6.1 | 4.9 | 5.0 | 8.0 |
| bad moves (true gain < copy cost), distrib | 28.2 % | 24.1 % | 20.2 % | 24.2 % |
| bad moves, notify8 | 3.3 % | 0.8 % | 11.7 % | 3.0 % |
| max / average load, dist-near | 1.006 | 1.014 | 1.101 | 1.099 |

(`data/wikipedia/distributed-10M-*.txt`, `data/twitter/distributed-10k-*.txt`,
`data/linux/distributed-10k-*.txt`; Linux 10,000 files, the whole history.)

**Without a cache limit** (every file stays, so each file is loaded and placed once; LRU never
evicts):

| saved % | central | **cent-near** | distrib | notify8 | oracle | **dist-near** |
|---|---:|---:|---:|---:|---:|---:|
| Linux, 156,764 files | 20.3 | **76.0** | 19.0 | 20.8 | 21.6 | **72.5** |
| Twitter, 304,198 users | −1.1 | **66.6** | −1.7 | −1.7 | −1.6 | **63.6** |

(`data/linux/distributed-all.txt`, `data/twitter/distributed-all.txt`. Wikipedia without a limit,
3.3M files, has not been run.) Without misses the distributed protocol needs only 0.8–1.5 control
messages per traversal on Linux.

- **Local knowledge is enough for the optimisation.** With notify8 the distributed placer
  matches the central one on Wikipedia LRU (4.4 % both), and `oracle` (exact locations)
  is no better than notify8, so stale locations cost nothing more once moves are announced.
- **Stale locations cause bad moves.** Without notify, about a quarter of the moves are made on
  locations that had changed, predicting more gain than they get. Telling the 8 heaviest
  neighbours brings that to 1–3 % on Wikipedia. Before a server used the exact locations of
  the neighbours it holds itself, 73 % of moves were bad.
- **The remaining gaps to central were about new files, not knowledge.** Wikipedia LFU (31 %
  against 17.7 % even with the oracle) and Twitter LFU (47.5 % against −2 %) come from the
  central placer putting a newly loaded file on the servers that just freed a slot. Placing new
  files on purpose (near) closes the gap and goes far beyond it.
- **Near placement is what matters.** It turns 4 % into 82 % on Wikipedia LRU, and makes
  adapting worthwhile on Twitter. With near placement central and distributed are within 3
  points of each other, and the optimiser has little left to do: cent-near makes 116–289 copies
  on Twitter, against 232–1,365 for central.
- **It does not rely on misses.** Every load is a free re-placement (an upload costs the same on
  any server), so a high miss rate could have explained the gain. Without a cache limit each file
  is placed once, and near placement still saves 76 % on Linux and 67 % on Twitter, where the plain
  placers save 20 % and nothing. The file a new file is first reached from is usually one it
  keeps being traversed with: a header with its source file, a user with the account they
  answer.

## Rack spreading

With `racks` (a rack id per server), the central placer keeps each file's live replicas on at
least two racks whenever two racks have a live server:

- a new or repair replica avoids the rack the file is already in, if another rack has room;
- relocations, swaps (for both files) and rebalancing moves may not put a spread file back into
  one rack, and trimming a surplus replica keeps one that preserves the spread;
- a file left in one rack (its other rack was down when it was repaired, or no other rack had
  room) is moved out when it is next traversed, to the best server in another rack with room, or
  by a swap with a file there if those racks are full. These copies count as repair copies.

The rule is kept "if possible": with only one live rack, or no room and no swap partner, a file
stays in one rack until it can move. `rackFuzz` in `Main.kt` checks it: 30,000 random steps with
failures on 6 servers in 3 racks, invariants after each, and no file left in one rack once
every server is back and every file has been traversed.

`DistributedPlacer` takes the same `racks` option (variant `dist-near-rack`: dist-near with
racks). Every server knows the rack map, a new file's random choices skip the rack its other
replicas are in, and the coordinator (which knows its own file's replicas exactly) and a swap
partner's server reject moves that would put a file back into one rack. The rule never needs
another file's location, so it adds no messages. It has no server failures, so a file can never
end up in one rack, and `checkInvariants` checks that.

**Results** (`cent-rack`, `near-rack` and `dist-near-rack` in the distributed runs: 16 servers in
4 racks of 4, `k = 2`). Availability is the share of files whose live replicas are all in one rack, so a
failure of that rack would make them unavailable; cost is the adaptive placer's
`remote + α · copies`, compared directly since the baselines differ.

| | lost if the worst rack fails, without racks | with racks | cost change, central | cost change, cent-near | cost change, dist-near |
|---|---:|---:|---:|---:|---:|
| Wikipedia LRU, 100k | 5.8 % | 0 | +0.4 % | +0.7 % | +0.4 % |
| Wikipedia LFU, 100k | 6.0 % | 0 | +1.7 % | +1.8 % | +1.6 % |
| Twitter LRU, 10k | 5.8 % | 0 | +0.3 % | −0.6 % | 0.0 % |
| Twitter LFU, 10k | 6.4 % | 0 | +4.4 % | −33.6 % | −9.5 % |
| Twitter, no limit | 7.0 % | 0 | 0.0 % | +1.6 % | −0.8 % |
| Linux LRU, 10k | 5.8 % | 0 | +0.8 % | +1.4 % | +0.9 % |
| Linux LFU, 10k | 6.8 % | 0 | +3.1 % | +2.4 % | +2.8 % |
| Linux, no limit | 6.8 % | 0 | +0.8 % | +1.1 % | +0.7 % |

("Without racks" is the worst of the central variants; the distributed ones lose 4.9–7.4 %.)
The distributed placer keeps the same messages per traversal with racks (e.g. 3.128 against 3.125
on Linux LRU) and its load stays within 10 % of the average.

- Without racks, about 6 % of the files sit entirely in any one rack (random placement gives
  C(4,2) / C(16,2) = 5 %), and 20–28 % of all files would be lost by one of the four rack
  failures. With racks, none.
- It costs 0–4 % more. With `k = 2` and uniform costs, a traversal is local if the two files
  share one server, so one replica can follow the neighbours while the other sits in another
  rack, where it costs nothing. Twitter LFU with near placement got cheaper (central and
  distributed), but that run is dominated by the freed-slot effect, so the change is noise from
  where newcomers land.
- Not measured: racks together with `distances` where crossing racks costs more. There, the
  second replica's rack would also matter for traversals.

## Pair memory: a count-min sketch

A distributed server keeps every pair it has seen for as long as the pair has weight, about 13.3
half-lives, so its memory grows with the pairs in use. `PairCounts` hides how they are kept, with
`pairCounts` as an option of `DistributedPlacer`:

- `ExactCounts`: a `HashMap` of pairs per replica, as before. About 117 bytes per pair, of which
  16 are data. The results are the same as before the interface: 660 of 660 comparable rows of
  `data/distributed.csv` matched exactly.
- `SketchCounts`: one count-min sketch per server (4 rows of 32-bit counters), with forward decay
  and conservative update. A sketch can say how heavy a pair is, but cannot list a file's pairs,
  which evaluations, moves and removals need. So each replica also keeps the ids of up to 16 heavy
  neighbours; a new neighbour replaces the lightest one if the sketch says it weighs more.

The `*-cms` variants use 4 cells per row per replica a server holds, rounded up to a power of 2.
That sets the size from `maxFiles`, not from the files actually present. A moved replica adds its
weights to the new server's sketch. Its old weights stay in the old sketch until they fade, as a
sketch cannot forget one pair.

Memory below is an estimate from object sizes, at the peak window, over all 16 servers. It counts
pairs and location caches, not what both versions need anyway (which files a server holds). Static
placers keep exact pairs, so all variants of a dataset share a baseline.

| dist-near, LRU | files | pairs, exact | pairs, sketch | + locations, exact | + locations, sketch | saved %, exact | saved %, sketch |
|---|---:|---:|---:|---:|---:|---:|---:|
| Twitter (whole trace) | 10,000 | 3.9 MB | 3.1 MB | 25.5 MB | 24.5 MB | 50.2 | 50.9 |
| | 304,198 | 126 MB | 99 MB | 148 MB | 117 MB | 63.6 | 62.9 |
| Linux (whole history) | 10,000 | 4.8 MB | 3.2 MB | 19.9 MB | 18.0 MB | 58.5 | 60.9 |
| | 156,764 | 129 MB | 53 MB | 155 MB | 78 MB | 72.5 | 72.0 |
| Wikipedia (50M traversals) | 100,000 | 38.5 MB | 27.3 MB | 96 MB | 84 MB | 37.1 | 42.2 |
| | 1,000,000 | 864 MB | 267 MB | 1,178 MB | 555 MB | 48.9 | 48.6 |
| | 2,000,000 | 1,314 MB | 526 MB | 1,758 MB | 922 MB | 49.1 | 48.8 |
| | 3,267,066 | 1,378 MB | 843 MB | 1,822 MB | 1,236 MB | 49.1 | 48.9 |

(`data/distributed-memory.csv`. Charts in `docs/distributed/`: `memory.png`, `memory-scaling.png`,
`memory-tradeoff.png`; the `*-cms` variants are also in the existing ones.)

- **The saving grows with the pairs per file.** Up to 3.2 times less for pairs on Wikipedia (1M
  files) and 2.4 on Linux, for 0.5 points of saved cost. Twitter has few pairs per file, so most of
  its 99 MB is the sketch itself.
- **Memory still grows with files.** It no longer depends on the pairs per file, but the sketch and
  the neighbour ids are per replica. The last Wikipedia row is oversized: 50M traversals bring in
  no more pairs than at 2M files, while the sketch follows `maxFiles`.
- **The location cache is untouched,** and it is often the larger part: 22 of 25 MB on Twitter at
  10,000 files, and over half of the total on Wikipedia at 1M files with the sketch.
- **A sketch can be smaller, up to a point.** On the sweep in `data/distributed-sweep.csv`, 2 cells
  per row per replica keep the saving: 41.4 % on Wikipedia at 100,000 files (19 MB), 72.0 on Linux
  (37 MB), 62.9 on Twitter (65 MB). At 1 cell, Wikipedia falls to 35.4 %. Linux and Twitter do not
  mind, but the default stays at 4 for the margin.
- **Neighbours matter more than cells on sparse traces.** On Linux, 4 neighbours lose 2 points and
  32 neighbours match exact pairs, or slightly beat them; on Twitter, 32 get within 0.1 point.
- **The sketch beats exact pairs on small caches, partly by accident.** It saves 2.4 points more on
  Linux and 2.5 on Wikipedia at 10,000 and 100,000 files (10M traversals; 5.1 at 50M). An evicted
  file's weights survive in the sketch, while exact pairs drop them. With a salted hash that makes
  those weights unreachable, the lead falls to 1.7 and 1.0 points, and on Twitter from +0.7 to −0.2.
  So remembering pairs across evictions has value of its own; the rest of the lead is not
  explained.
- **The estimate against the heap** (`./gradlew pairMemoryCheck`, Parallel GC, every file in the
  placer): Twitter exact 148 MB estimated, 227 MB of heap; sketch 117 and 175. Linux exact 155 and
  163; sketch 78 and 96–106 (two runs). The heap also holds the file lists the estimate leaves out. With G1, the
  default, large arrays waste regions: the Twitter sketch took 239 MB against 204 MB for exact pairs,
  so at that size it saves nothing in practice unless made smaller.

## What we learned

1. **The cost model decides what helps.** Loads are free to place, copies cost α. Whatever can
   be decided at load time (near placement) beats what has to be paid for by copies later, even
   when each file is loaded only once.
2. **For a cache, keep files with many hits.** LFU gives the placer the stable core of the
   graph and saves up to 63 % of the cost; LRU-like policies leave nothing to optimise.
   The hit rate alone (Belady) is a poor guide.
3. **Adapting needs repeated pairs.** On Wikipedia and Linux, where pairs repeat, the adaptive placer
   saves 15–45 % with a good eviction policy. On Twitter, where 71 % of interactions are the first
   between their pair, it saves nothing (and loses
   1–2 %) unless new files are placed near.
4. **The algorithm does not need the whole graph.** Each server needs the pairs of its own
   files, a location cache fed by traffic, gossiped loads and a few notifications per move:
   5–9 control messages per traversal, load within 10 % of the average.
5. **Check the baseline before believing a gain.** LFU's 47.5 % on Twitter and 82 % on Linux, and
   the central placer's lead over the distributed one, were all side effects of where new files
   landed. "Saved" leaves out the loads; counted with them, LFU is one of the worst caches.

### Performance work

#### Incremental sweep

Faded pairs used to be deleted by a full sweep every 2²⁰ traversals (2¹⁸ in the distributed
placer). The sweep itself was cheap on average, but the traversal that ran it stalled until
every pair was visited. Now the sweep keeps a cursor over the files and visits a few per
traversal; with fractional credit (F / 2²⁰ files per traversal), the total work is the same
and every file is still visited once per period. In the distributed placer each server sweeps
its own files and cache when its turn comes (one server per traversal). A cached location is
needed only while one of the server's pairs names it; the sweep marks the locations it finds
named, and forgets a location left unmarked for two passes. That costs nothing on the
traversals themselves, as reference counts would have (they added 15 % to the mean). Random
sampling as in Redis was not used: our maps cannot pick a random entry cheaply, and sampling
gives no bound on how long a faded pair survives.

Measured on the Wikipedia stream with no file limit (`Latency.kt`: 16 servers, k = 2,
half-life 1M, every file added on first traversal), the time of each `addEdge` call:

| placer | traversals (files) | sweep | mean | p99 | p99.99 | max |
|---|---|---|---:|---:|---:|---:|
| central | 10M (1.57M) | full, every 2²⁰ | 1.10 µs | 11.4 µs | 89 µs | 367 ms |
| central | 10M (1.57M) | incremental | 1.04 µs | 11.0 µs | 85 µs | 73 ms |
| distributed (dist-near) | 3M (0.97M) | full, every 2¹⁸ | 3.28 µs | 27.2 µs | 188 µs | 582 ms |
| distributed (dist-near) | 3M (0.97M) | incremental | 3.61 µs | 30.4 µs | 198 µs | 80 ms |

- **The periodic stalls are gone.** The full sweeps were the slowest calls, and they grew with
  the graph: in the central placer 108 ms at 1M traversals and 367 ms at 9.4M; in the
  distributed one 86 ms at 262k and 612 ms at 2.6M. The remaining worst calls (20–80 ms) are
  rare and happen in both versions, at the same points for the central placer: hash tables
  growing, the heap rebuild when forward-decay units are rebased, and garbage collection.
- **The mean is unchanged for the central placer and 10 % higher for the distributed one.**
  The work per pass is the same, but visiting files in small steps among the traversals is
  less cache-friendly than one pass over everything. With the sweep off, the distributed mean
  is 2.08 µs, so the sweep at this period is a large part of its cost; at a period of 2²⁰ it
  adds 0.58 µs, at 2²² 0.17 µs. A pair takes 13.3 half-lives to fade, so a period of about one
  half-life would keep memory almost as low.
- **Results:** the central placer's results are identical in all eight distributed runs. In
  the distributed placer the timing of pruning shifts the random draws: costs moved by at most
  ±1.1 % and saved % by at most 0.3 points (the tables above are from the rerun). One side
  effect: unneeded cached locations now live up to two passes instead of until the next sweep,
  so more of them are stale when a new pair starts using them. notify8's bad moves on Wikipedia
  LRU rose from 0.6 % to 3.3 %, without a visible cost change (4.5 → 4.4 % saved).

#### Other changes

- Each file's total pair weight (`heat`) is kept in forward-decay units, so a traversal adds to
  it without reading the clock of other pairs. A lazy min-heap of heats gives `lightestFile()`
  (for weight eviction): a heap entry can only understate a file's heat, and it is fixed when it
  reaches the top. Forgotten pairs' weights are tracked per file (`residue`) so the invariant
  check is exact.
- Neighbour maps use `EdgeMap`, an open-addressing map from `Int` to `Edge` (linear probing,
  backward-shift deletion). Together with the heap this took the Wikipedia replay from about
  14.0 to 8.4 µs per traversal (four placers) and the benchmark from about 0.85 to 0.53 µs.
- The eviction policies and the runs per policy are independent, so each runs in its own
  thread; the placer itself stays single-threaded. A concurrent placer with atomics was tried
  and reverted.

## Related systems

Systems that partition a changing graph so that neighbours share a server. None was copied here;
the distributed placer is assembled from standard pieces (a home directory as in DHTs, location
caches corrected by bounces, gossiped loads, power-of-two choices, a propose/accept handshake),
and these are what it should be compared against. **None of them has been implemented or run
here**: the "where it loses" parts below are reasoning from our cost model and our measurements,
not results.

Our cost model is what decides the comparison: a remote traversal costs 1, a copy costs α = 8, a
file has exactly `k` replicas, servers have caps, and loading a file into the cache is free to
place. Most of these systems optimise edge cut (the share of remote traversals) and treat
migration as free or as a side cost, so under our model they mainly lose by moving too much.

### SPAR (Pujol et al., *The Little Engine(s) That Could*, SIGCOMM 2010)

Online placement for social networks. Each user has a master replica, and SPAR adds replicas of
neighbours to a server until every user's neighbours are local to its master. A new user goes to
the server with the fewest masters; on a new edge, it considers moving either master to the
other's server, picking whichever needs the fewest new replicas.

- **Where it loses with us:** its locality comes from extra replicas, and our `k` is fixed. With
  only `k` replicas it reduces to greedy master moves on every new edge, which have no rent-or-buy
  threshold, so it would copy for pairs that never repeat. On Twitter, where 71 % of interactions
  are the first between their pair, the central placer without near placement already loses 2 %
  to the static one, and moving on every new edge would lose more.
- **Where it wins:** with unlimited replicas (storage not charged), every traversal is local; our
  model does not allow that. Its rule for new users is our near placement, the single biggest
  gain we measured (4 % → 82 % on Wikipedia LRU).

### Hermes (Nicoara et al., EDBT 2015)

Lightweight repartitioning inside a graph database (Neo4j). Each vertex counts its neighbours per
partition; vertices whose gain from moving is positive migrate in rounds, under a balance bound.

- **Where it loses with us:** it migrates as soon as the gain is positive, with no wait until the
  traffic has paid for the copy. On Wikipedia the decayed pair weights are stable, so it should
  come close to our placer, with more copies. On Twitter the gains come from one-off bursts, so it
  would pay α for moves that no later traffic repays. It also counts each neighbour once, not by
  decayed traversal weight, so a pair seen once weighs as much as one seen a thousand times.
- **Where it wins:** it moves in batches of rounds, so a whole group can move at once; our placer
  makes one move per trigger and converges over several.

### Ja-Be-Ja (Rahimian et al., SASO 2013)

Fully decentralised partitioning: each vertex periodically picks a neighbour or a random vertex
in another partition and they swap partitions if that raises the number of same-coloured
neighbours, with simulated annealing to escape local optima. Swaps keep partitions exactly
balanced.

- **Where it loses with us:**
  - every swap costs 2 copies, and swaps run on a schedule, not when traffic has paid for them;
    annealing even accepts worsening swaps on purpose, each one costing 2α;
  - it is designed to run to convergence on a static graph. Our graphs change: Wikipedia's cache
    replaces 40–50 % of the files it touches, and on Twitter most pairs appear once, so it would
    keep swapping for a graph that is gone;
  - each swap needs both vertices' neighbour colours, the same stale-location problem we measured:
    without notifications 20–27 % of our distributed moves were bad.
- **Where it wins:** no directory, no gossip of loads (balance is kept by swapping only), and
  very few messages per round.

### Spinner (Martella et al., ICDE 2017)

Label propagation on Pregel/Giraph: in each iteration every vertex takes the partition most of
its neighbours are in, damped by a penalty for full partitions. Adapts to graph changes by
restarting from the previous labels.

- **Where it loses with us:** it is a batch job over the whole graph. Run every window, it would
  relabel many vertices at once and pay α for each; its objective has no term for the cost of
  moving, so after a burst like the Higgs announcement it would move most of the graph and then
  move it back. It also has a central view (the whole graph in Giraph), which our distributed
  placer does without.
- **Where it wins:** a better partition of a stable graph than one-move-at-a-time refinement. It is
  the natural offline reference: repartition per window and charge the copies, like Belady for
  caching.

### Facebook Social Hash (Kabiljo et al., VLDB 2017)

Offline, two-level: a graph partitioner (balanced label propagation) assigns users to buckets
every few days, and a hash maps buckets to servers, so servers can be added without
repartitioning.

- **Where it loses with us:** it reacts in days, not traversals. A cache that replaces tens of
  percent of its files per window cannot wait for the next batch, and new files would be placed by
  the hash, not by their neighbours.
- **Where it wins:** it separates "who goes together" from "which machine", so failures and new
  servers move whole buckets cheaply; our placer handles a returning server with sampled moves.

### What would be worth measuring

Hermes-style greedy migration (our placer with the threshold at zero) would show directly how
much the rent-or-buy trigger saves. Ja-Be-Ja is the right rival for the distributed placer, also
in messages per traversal. A Spinner-style per-window repartition would be the offline reference.
SPAR's fixed-`k` form is our near placement, and Social Hash is offline, so neither needs its
own run.

## Parameters

| parameter | default | effect |
|---|---|---|
| `servers` | (required) | Capacity of each server, in replicas. The number of servers is its size. |
| `alpha` | 8 | Cost of one copy. Higher means fewer, more selective moves. |
| `halfLife` | 10,000 | How fast old traffic fades. Too short and the placer chases noise; too long and it adapts slowly. A bit longer than a typical access pattern lasts works well. |
| `slack` | 0.1 | How far above its capacity-proportional share a server may go. A very large `slack` turns the caps into the physical capacities alone. |
| `repairRate` | 0.1 | Repair copies per traversal after a failure. Lower spreads the copying out but leaves files short of `k` copies for longer. `Double.POSITIVE_INFINITY` repairs immediately. |
| `minReplicas` | `k` | Fewest live replicas a file may have while it waits out a grace period. Below it, repair starts at once. `k` turns the grace period off. |
| `grace` | 0 | Traversals a server may stay down before its files are repaired (when `minReplicas < k`). A bit longer than a typical restart. |
| `distances` | `null` | `n × n` symmetric distances between servers, zero on the diagonal. `null` is a uniform network (distance 1) and uses the faster 0/1 formula. |
| `placeNear` | false | `addFile(file, near)` puts the new file's replicas on `near`'s servers that are at least two below their cap; the rest go to the least loaded servers. |
| `racks` | `null` | Rack id of each server. When two racks have a live server, every file keeps live replicas on at least two racks, so one rack failing cannot take all copies (see [Rack spreading](#rack-spreading)). |
| `seed` | 1 | Tie-breaking and sampling; runs are deterministic. |

## Limitations

- It is a heuristic with no proven competitive bound.
- One move per trigger: a group spread over several servers converges over several triggers,
  not in one step.
- Swaps and evictions sample 16 files on a server, so they can miss the best partner.
- With a distance matrix, a trigger costs O(d·k·(k + n)) instead of O(d·k² + n·k), about 2 to 3
  times slower per traversal on the benchmark; a rack hierarchy could bring `n` down to the
  number of racks, but is not implemented. See [Weighted server distances](#weighted-server-distances).
- Distances alone do not make placement rack-aware for fault tolerance; `racks` does (in both
  placers).

## References

- The rent-or-buy trigger comes from Avin, Bienkowski, Loukas, Pacut, Schmid, *Dynamic Balanced
  Graph Partitioning*, SIAM J. Discrete Math. 2020 (first published as *Online Balanced
  Repartitioning*, DISC 2016). Only that idea is used. Their algorithm, CREP, counts paid
  requests per pair of nodes, merges and moves whole components, has no replicas or failures,
  and needs clusters with room for (2 + ε) · k nodes. Its O((1 + 1/ε) · k log k) competitive
  ratio does not apply here.
- Relocate and swap are Kernighan–Lin / Fiduccia–Mattheyses-style refinement moves. Selvitopi,
  Turk and Aykanat extend this kind of iterative improvement to replication.
- Exponentially decayed counts are a standard technique for streams: Cohen and Strauss,
  *Maintaining Time-Decaying Stream Aggregates*, PODS 2003 (J. Algorithms 2006); Cormode,
  Shkapenyuk, Srivastava and Xu,
- Placing re-created replicas by gain is this project's own choice.
