# 视频分享平台数据库系统

[English](README.md) | **中文**

一个仿 Bilibili 的视频分享平台的 PostgreSQL 后端：用户、视频、弹幕、点赞 / 投币 / 收藏、关注关系、搜索与推荐。Java 17、Spring Boot 2.7，基于 JDBC 手写 SQL（不使用 ORM）。项目实现了一个课程框架的服务接口，框架自带的 23 步基准测试会校验每个结果，并为导入以及其余 22 步中的 21 步计时。

**结果**（同一台机器、同一个 PostgreSQL 16 服务器；原实现与本版本在同一次开机内交替运行，各 3 次，取中位数；见[测试方法](#测试方法)）：

- 通过全部 **5,622** 个测试用例；原实现通过 5,618 个。
- 10 个读取和推荐步骤：**20.0 s → 1.09 s（18.3×）**。其中相似视频和通用推荐超过 100×、个性化推荐 32×（主要来自没有好友的用户回退到通用推荐）、好友推荐 22×、搜索 4.6×。
- 11 个写入步骤：**3.29 s → 3.77 s，慢了 14.5%**（第二轮为 17.8%，不过原实现自身的合计在两组运行之间从 2.85 s 变成了 3.29 s）；[写入路径](#写入路径)一节测量了这些时间花在哪里。
- 全部 21 个计时步骤合计：**23.3 s → 4.9 s（4.8×）**。数据导入（约 820 万行）：**103.4 s → 9.6 s（10.7×）**。
- 并发下（[WriteBench](#写入路径) 的 stress 模式，每项 45 次试验）：原实现在 45 次试验中有 45 次超额扣币、45 次丢失点赞和关注切换，本版本均为 0/45；两个交叉的账号删除（相隔 0–8 ms）在第一轮代码上有 4/45 次因死锁失败，现在为 0/45。8 个客户端下删除不再失败：300 个账号删除和 24 个导入视频的删除全部成功，而第二轮分别有 79–83/100 和 4–6/8 因死锁失败。代价是账号删除现在逐个进行，8 个客户端下的吞吐量为原实现的 29%（见[已知局限](#已知局限)）。
- 144 个测试（JUnit 5；其中 110 个是在 Testcontainers 中针对 PostgreSQL 16 的集成测试），在一个全新的克隆中，于 JDK 21 上以及 `eclipse-temurin:17-jdk` 容器中的 JDK 17 上通过（[ci-proof.txt](docs/experiments/ci-proof.txt)）。CI 工作流在这两个版本上运行同样的构建，但还没有在 GitHub 上运行过。

按中位数，有 11 个步骤比原来慢，每步慢 2–147 ms：11 个写入步骤中的 9 个（投币和注册变快了），以及两个开销最小的读取（热点 13 → 23 ms，弹幕展示 46 → 89 ms）。其中热点、发布视频、更新视频、审核、删除视频、删除账号和关注，两个版本的范围是重叠的。

> `io.sustc.service` 中的接口、`io.sustc.dto` 中的 DTO 以及整个 `sustc-runner` 模块（命令行和基准测试）来自一个 MIT 许可的课程框架（见 [LICENSE](LICENSE)），基准测试的逻辑和数据都没有改动。本项目的工作是 `sustc-api/src/main/java/io/sustc/service/impl/`、`sql/` 下的 SQL、测试、脚本和文档。

## 目录

- [架构](#架构)
- [数据库设计](#数据库设计)
- [性能](#性能)
- [正确性](#正确性)
- [运行](#运行)
- [限制与安全](#限制与安全)
- [已知局限](#已知局限)

更详细的说明（每项改动一节，附关键的 EXPLAIN 计划）：[docs/ENGINEERING_NOTES.md](docs/ENGINEERING_NOTES.md)（英文）。

## 架构

```mermaid
flowchart LR
    subgraph runner["sustc-runner（课程框架）"]
        shell["Spring Shell 命令"]
        bench["BenchmarkService<br/>23 个校验的步骤：<br/>导入 + 21 个计时步骤"]
    end
    subgraph api["sustc-api（本项目）"]
        services["User / Video / Danmu /<br/>Recommender / Database 服务"]
        auth["Authenticator"]
        importer["BulkImporter<br/>COPY FREEZE、延后建索引"]
    end
    subgraph pg["PostgreSQL 16"]
        tables["9 张表<br/>外键 ON DELETE CASCADE"]
        derived["video_stats、video_coviews"]
        audit["log_users"]
    end
    shell --> services
    bench --> services
    bench --> importer
    services --> auth
    services -- "NamedParameterJdbcTemplate + HikariCP" --> tables
    importer -- "4 个连接并行 COPY" --> tables
    tables -. "语句级触发器" .-> derived
    tables -. "审计触发器" .-> audit
```

- **服务**都是通过构造器注入依赖（`NamedParameterJdbcTemplate`、`Authenticator`、`Clock`）的 Spring Bean；REST 层可以在 [HANDOFF.md](HANDOFF.md#adding-a-rest-layer) 所列的条件下复用它们。`Clock` 让"当前时间"可测试；基准测试把它固定在测试数据生成的时刻。
- **每次写入都是一次调用。** 投币、审核、编辑和级联删除都是单条语句（带数据修改的 CTE、`ON CONFLICT`、外键级联）；切换操作是先对当前用户加一把咨询锁（advisory lock），再执行切换语句，两条语句在一次往返中发送。每次调用不需要显式事务就是原子的。并发调用有测试覆盖：同一用户的切换操作串行执行，不会丢失；编辑会先锁住所在行，再报告它替换掉的审核状态；共同观看计数会锁住观看者；会改动派生表的删除先获取一把删除锁（一把咨询锁，与删除语句在同一次往返中发送），所以两个这样的删除不会相互冲突；仍然输掉死锁的调用会被重试（最多 3 次）。
- **派生数据保存在数据库里。** 每个视频的计数（`video_stats`）和共同观看次数（`video_coviews`）由语句级触发器实时维护，推荐查询只需读几十行，而不必每次聚合 220 万行互动记录。
- **导入器**用 `COPY` 批量加载，之后再根据系统目录重建索引和约束；任何一步失败时，被导入的表都是空的，schema 保持完整（`users` 的审计日志会保留这次尝试）。

## 数据库设计

表结构：[`sql/build.sql`](sql/build.sql)。审计日志：[`sql/audit.sql`](sql/audit.sql)。角色与权限：[`sql/usercontrol.sql`](sql/usercontrol.sql)。

```mermaid
erDiagram
    users ||--o{ videos : "拥有 (owner_mid)"
    users ||--o{ videos : "审核 (reviewer)"
    users ||--o{ followings : "关注"
    users ||--o{ viewers : "观看"
    users ||--o{ videolikes : "点赞"
    users ||--o{ coins : "投币"
    users ||--o{ favorites : "收藏"
    users ||--o{ danmus : "发送"
    users ||--o{ danmulikes : "点赞"
    videos ||--o{ viewers : ""
    videos ||--o{ videolikes : ""
    videos ||--o{ coins : ""
    videos ||--o{ favorites : ""
    videos ||--o{ danmus : ""
    danmus ||--o{ danmulikes : ""
    videos ||--|| video_stats : "计数"
    videos ||--o{ video_coviews : "共同观看"

    users {
        bigint mid PK "identity"
        varchar name
        smallint level
        integer coin "check >= 0"
        varchar identity "USER 或 SUPERUSER"
        varchar qq "唯一，没有时为 NULL"
        varchar wechat "唯一，没有时为 NULL"
    }
    videos {
        varchar bv PK
        bigint owner_mid FK
        varchar title "同一用户内唯一"
        timestamp review_time "审核前为 NULL"
        timestamp public_time "NULL 表示不设定时公开"
        double duration
    }
    viewers {
        varchar bv PK
        bigint mid PK
        double view_time
    }
    danmus {
        bigint id PK "identity"
        varchar bv FK
        bigint mid FK
        double time
        timestamp post_time
    }
    video_stats {
        varchar bv PK
        integer viewers
        double view_time_sum
        integer likes
        integer coins
        integer favorites
        integer danmus
    }
    video_coviews {
        varchar bv PK
        varchar other_bv PK
        integer viewers "两个视频都看过的用户数"
    }
```

**约定。** 每张关系表的主键都是 `(对象, 用户)`，所以"用户 U 是否赞过视频 V"就是一次主键查找。外键都设置了级联删除（派生表 `video_coviews` 除外，它的行由触发器删除），删除用户或视频时，所有引用它的数据在同一条语句里一并删除（原代码会遗留弹幕点赞和部分互动记录）。实数一律用 `double precision`，求和不会在 `float4` 中累积误差。ID 来自 identity 列，导入器会把序列移到已导入的最大 ID 之后。空的 QQ / 微信号存为 `NULL`，唯一约束只作用于真正有这些账号的用户。

**索引及其用途。** 下面每个索引都对应具体的查询；执行计划见 [docs/explain](docs/explain)。

| 索引 | 服务于 |
|---|---|
| `followings (follower_mid, followee_mid)` 主键 | 用户关注了谁；互关判断 |
| `followings (followee_mid, follower_mid)` | 用户的粉丝；好友推荐对每个被关注者做一次仅索引扫描（原计划要扫描全部 600 万行） |
| `viewers / videolikes / coins / favorites (bv, mid)` 主键 | "U 是否看过 / 赞过……V"，以及每个视频的列表 |
| 同样四张表上的 `(mid, bv)` | 用户做过的所有事情（用户信息、删除账号时的级联、好友看过的视频），均为仅索引扫描 |
| `videos (owner_mid, title)` 唯一 | 规范中"同一用户不能有同名视频"；也用于查询某用户的视频 |
| `danmus (bv, time)` | 按时间顺序取某视频一段时间内的弹幕 |
| `danmus (mid)`、`danmulikes (mid)`、`videos (reviewer)` | 删除用户时的级联 |
| `video_coviews (bv, other_bv)` 主键、`(other_bv)` | 相似视频：按索引取出该视频的共同观看行，再取前 5 个；删除视频时双向删除它的行 |

**派生表。** `video_stats` 保存每个视频的各项计数和观看时长之和；`video_coviews` 对每一对有序的视频保存同时看过两者的用户数。带转换表（transition table）的语句级 `AFTER` 触发器把一条语句造成的全部变化合并成一条 `INSERT … ON CONFLICT DO UPDATE`（共同观看则是一条 `DELETE`/`UPDATE`），并按主键顺序执行，因此同一个触发器的两次触发会以相同的顺序锁住它们共同涉及的行。这个顺序只在单个触发器内成立：级联删除会为每个被级联的表各触发一个触发器，所以两个并发的级联删除可能以交叉的顺序加锁并发生死锁。因此删除操作会先获取一把删除锁（咨询锁：删除有互动或有视频的账号时为排他模式，删除视频时为共享模式），并且 `video_coviews` 不设外键：`videos` 上的删除触发器按主键顺序一次性锁住并删除该视频两个方向上的共同观看行。导入器在 Java 中根据导入记录算出这两张表，并像其他表一样用 `COPY` 加载。有一个测试用带固定种子的随机负载（服务调用加多行 SQL）驱动这两张表，再与从头重新计算的结果逐行比较。

**审计与角色。** `audit.sql` 通过语句级触发器记录 `users` 上的每次插入、更新和删除（包括数据库角色和时间）。`usercontrol.sql` 创建三个权限递减的角色，并包含派生表触发器所需的权限，因为触发器以调用者的权限运行。

## 性能

### 结果

<!-- benchmark-table:start -->
| 步骤 | 基线 ms (n=3) | 优化后 ms (n=3) | 加速比 | 通过数（基线 → 优化后） |
|---|---:|---:|---:|---:|
| 1 数据导入 | 103,387 [101,273–106,993] | 9,648 [9,335–9,816] | **10.7×** | – |
| 2 搜索视频 | 313 [282–340] | 68 [56–72] | **4.6×** | 18 → 20 / 20 |
| 3 平均观看率 | 55 [52–59] | 17 [16–19] | **3.2×** | 60 → 61 / 61 |
| 4 弹幕热点 | 13 [12–20] | 23 [17–24] | 0.57× | 56 → 56 / 56 |
| 5 相似视频 | 2,444 [2,249–3,036] | 18 [15–21] | **135.8×** | 43 → 43 / 43 |
| 6 通用推荐 | 1,025 [1,023–1,621] | 6 [5–9] | **170.8×** | 11 → 11 / 11 |
| 7 个性化推荐 | 1,977 [1,949–2,474] | 62 [55–65] | **31.9×** | 27 → 27 / 27 |
| 8 好友推荐 | 13,694 [12,493–14,549] | 616 [609–661] | **22.2×** | 142 → 142 / 142 |
| 9 弹幕展示 | 46 [43–54] | 89 | 0.52× | 382 → 382 / 382 |
| 10 发送弹幕 | 228 [218–240] | 322 [299–387] | 0.71× | 1016 → 1016 / 1016 |
| 11 用户信息 | 330 [276–365] | 183 [129–202] | **1.8×** | 500 → 500 / 500 |
| 12 弹幕点赞 | 不计时 | 不计时 | – | 500 → 500 / 500 |
| 13 投币 | 636 [566–676] | 569 [560–724] | **1.1×** | 511 → 511 / 511 |
| 14 点赞 | 636 [534–657] | 743 [742–1,006] | 0.86× | 686 → 686 / 686 |
| 15 收藏 | 569 [539–584] | 716 [618–741] | 0.79× | 644 → 644 / 644 |
| 16 发布视频 | 58 [39–69] | 68 [58–71] | 0.85× | 184 → 184 / 184 |
| 17 注册 | 121 [90–124] | 112 [86–113] | **1.1×** | 273 → 273 / 273 |
| 18 更新视频 | 22 [22–30] | 30 [22–32] | 0.73× | 184 → 184 / 184 |
| 19 审核 | 23 [19–23] | 26 [20–38] | 0.88× | 35 → 35 / 35 |
| 20 更新后搜索 | 136 [105–173] | 11 [9–14] | **12.4×** | 5 → 6 / 6 |
| 21 删除视频 | 492 [444–586] | 635 [569–666] | 0.77× | 55 → 55 / 55 |
| 22 删除账号 | 451 [427–478] | 490 [409–491] | 0.92× | 199 → 199 / 199 |
| 23 关注 | 58 [51–59] | 60 [55–70] | 0.97× | 87 → 87 / 87 |
| **读步骤 2–9、11、20 合计** | **20,033** | **1,093** | **18.3×** | |
| **写步骤 10、13–19、21–23 合计** | **3,294** | **3,771** | 0.87× | |
| **全部 21 个计时步骤合计** | **23,327** | **4,864** | **4.8×** | |
| **全部测试用例** | | | | **5618 → 5622 / 5622** |
<!-- benchmark-table:end -->

由 `python3 scripts/bench_table.py --write README.md README.zh-CN.md` 根据 [docs/benchmark/round3/plain](docs/benchmark/round3/plain) 中的原始报告生成（`c6-B<i>.json`：原实现；`c6-O<i>.json`：当前版本；运行顺序 B1、O1、B2、O2、B3、O3）。时间是 3 次运行的中位数，方括号内为范围；加速比由两个中位数相除得到。框架不为第 12 步计时。测试脚本自带的 `compare.py` 得到相同的中位数（[compare.txt](docs/benchmark/round3/plain/compare.txt)），[report.txt](docs/benchmark/round3/plain/report.txt) 给出每次运行的合计、每个写入步骤的 WAL 以及前后的检查点。第二轮的运行（jar sha256 `42004a16…`）在 [docs/benchmark/round2/plain](docs/benchmark/round2/plain) 中（读取步骤 19,057 → 1,080 ms，写入步骤 2,846 → 3,353 ms，全部 21,903 → 4,433 ms）。

[docs/benchmark/round1](docs/benchmark/round1) 中较早的运行是两个版本先后运行、相隔数小时，这里不再引用。原实现一轮比一轮慢（它的导入在第一轮用了 57.7 s，第二轮 90.4–93.2 s，第三轮 101.3–107.0 s；本版本在第二轮为 8.2–8.9 s，这里为 9.3–9.8 s），原因没有测量，这也是两个版本交替运行的原因之一。[docs/benchmark](docs/benchmark) 中较早的 `original-result.json` 和 `reference-standard.json` 来自 2023 年和另一台机器，这里没有使用。

### 关键改动

改动前后的查询计划在 [docs/explain](docs/explain) 中（`EXPLAIN (ANALYZE, BUFFERS)`，各运行一次，可用 `scripts/explain_plans.sh` 重新生成）。它为每个计划节点计时，会夸大产生大量行的计划的时间（在本机对 63.8 万行做连接和聚合时，它报告 200–205 ms，而 `TIMING OFF` 时为 129–133 ms，直接运行为 122–124 ms，各运行三次：[docs/experiments/explain-overhead.txt](docs/experiments/explain-overhead.txt)），所以下表的行数取自这些计划，时间取自基准测试（步骤中位数，原实现 → 当前版本）。

| 改动 | 效果 |
|---|---|
| 在 `video_coviews` 中维护共同观看次数 | 相似视频：原实现每次调用都要对 87.5 万行的 `viewers` 表做自连接；现在按索引读出该视频的 43 行共同观看记录，排序后取前 5 个。第 5 步：2,444 → 18 ms。 |
| 在 `video_stats` 中维护每个视频的计数 | 通用推荐：原实现每次调用都要对五张互动表（共 220 万行）分别聚合；现在是两张 44 行的表的连接。第 6 步：1,025 → 6 ms。没有好友的用户会回退到通用推荐，所以个性化推荐也随之变快：第 7 步：1,977 → 62 ms。 |
| 覆盖索引 `followings (followee_mid, follower_mid)` 并重写好友查询 | 第 8 步：13,694 → 616 ms。第二轮中，原实现在导入后立即执行 `VACUUM ANALYZE` 时仍然需要 11.4–12.2 s，计划也相同（[analyze 运行](docs/benchmark/round2/analyze)），所以缺少统计信息并不能解释它在这里的耗时。 |
| 搜索用 `replace()` 统计不重叠的出现次数，并从 `video_stats` 读取播放量 | 第 2 步：313 → 68 ms。原实现会连接所有匹配视频的每一条观看记录，而且把关键词当成正则表达式。 |
| 用 `COPY … FREEZE` 批量导入，之后再建索引和约束、并行校验外键，最后执行 `VACUUM (ANALYZE)` | 第 1 步：103.4 s → 9.6 s；各阶段（3 次运行的中位数）：加载 2.8 s、建索引 5.1 s、约束 1.5 s、vacuum/analyze 0.2 s。 |

### 写入路径

11 个写入步骤在原实现中共需 3,294 ms，在本版本中需 3,771 ms（各步骤中位数之和；每次运行的合计分别为 3,025–3,451 ms 和 3,594–4,301 ms，[report.txt](docs/benchmark/round3/plain/report.txt)）。差距主要来自收藏（+147 ms）、删除视频（+143）、点赞（+107）和发送弹幕（+94）；投币（−67）和注册（−9）更快。第一轮把差距归结为导入之后的时机；第二轮对此做了测量（[第二轮报告](docs/benchmark/round2/plain/report.txt)、[settle 运行](docs/benchmark/round2/settle/report.txt)；详见[工程笔记](docs/ENGINEERING_NOTES.md#the-write-path)）：

- **不是写回造成的。** 第 10 步开始时，内核中的脏数据在本版本各次运行中为 0.6–1.6 MB，在原实现中为 0.6–35.7 MB（第二轮：1.1–2.0 MB 和 63–90 MB）。
- **导入之后的检查点：约 5 %（第二轮）。** 在导入后执行 `CHECKPOINT` 并等待（settle 变体）时，写入步骤在本版本中需 3,189 ms，在原实现中需 2,892 ms；每次运行的总和与普通运行的范围重叠。
- **整页镜像：两个版本大致相同**，只有发送弹幕例外（WAL 从 122 kB 增加到 2,307 kB）。它指向 `users` 的外键会锁住每个发送者所在的行，而检查点之后对某个页面的第一次加锁会把该页面的镜像写入 WAL（[fk-lock-wal.txt](docs/experiments/fk-lock-wal.txt)）。
- **其余是每次调用本身的开销。** WriteBench 通过任一 jar 的服务层运行每种写操作，使用基准测试数据，先执行 `CHECKPOINT` 并预热 300 次调用：每种操作 600 次调用，每个版本、每种客户端数各交替运行 3 次（[第三轮汇总](docs/benchmark/round3/writebench/summary.md)；第二轮的 5 次运行见[其汇总](docs/benchmark/round2/writebench/summary.md)）。下表为 p50 中位数（ms，原实现 → 本版本）、8 个客户端下本版本吞吐量与原实现之比，以及根据 JDBC 驱动发送的消息统计的每次调用语句数和往返次数：

| 操作 | 1 个客户端 | 8 个客户端 | 8 个客户端吞吐量之比（本版本 / 原实现） | 每次调用的语句数 / 往返次数 |
|---|---:|---:|---:|---:|
| 投币 | 1.40 → 1.03 | 2.47 → 2.47 | 0.96 | 7 / 7 → 2 / 2 |
| 点赞视频 | 0.82 → 0.98 | 1.54 → 2.22 | 0.78 | 5 / 5 → 3 / 2 |
| 收藏 | 0.87 → 0.93 | 1.78 → 2.20 | 0.85 | 5 / 5 → 3 / 2 |
| 发送弹幕 | 0.92 → 1.03 | 2.10 → 2.20 | 0.90 | 5.05 / 5.05 → 2 / 2 |
| 弹幕点赞 | 0.81 → 0.74 | 1.49 → 2.01 | 0.79 | 5 / 5 → 3 / 2 |
| 发布视频 | 0.80 → 0.91 | 1.41 → 1.78 | 0.90 | 4 / 4 → 2 / 2 |
| 注册 | 0.65 → 0.49 | 1.02 → 1.10 | 0.88 | 2 / 2 → 1 / 1 |
| 更新视频 | 0.64 → 0.93 | 1.28 → 1.48 | 0.94 | 4 / 4 → 2 / 2 |
| 审核 | 0.53 → 0.62 | 1.02 → 1.00 | 1.16 | 3 / 3 → 2 / 2 |
| 删除视频（无观看） | 1.15 → 1.33 | 2.67 → 2.17 | 1.30 | 9 / 9 → 3 / 2 |
| 删除视频（约 20,000 次观看） | 26.14 → 53.52 | 133 → 297 | 0.38 | 9 / 9 → 3 / 2 |
| 删除账号 | 5.93 → 6.92 | 29.35 → 108 | 0.29 | 13 / 13 → 3 / 2 |
| 关注 | 0.69 → 0.75 | 1.56 → 1.82 | 0.90 | 4 / 4 → 3 / 2 |

8 个客户端的账号删除是单独的运行（每个版本 3 次，每次 100 个调用）。这些运行中没有任何调用失败，服务器在运行期间也没有检测到死锁。本版本的切换操作把加锁语句和切换语句放在一次往返中发送，删除操作则把删除锁和删除语句放在一次往返中发送。

**删除。** 第二轮中，8 个客户端下 8 个导入视频的删除有 4–6 个、100 个账号删除有 79–83 个在 3 次尝试后以 `deadlock detected` 失败：删除会逐表级联，每张被级联的表上的派生表触发器都会锁住它自己涉及的视频的计数器，因此两个删除可能以交叉的顺序锁住共同的计数器。现在删除操作会先获取删除锁（一个持有到提交为止的 advisory lock），删除账号时为排他模式，删除视频时为共享模式；被删除视频的共同观看行按键的顺序加锁；没有任何播放、点赞、投币、收藏、弹幕或视频的账号跳过这把锁。代价是账号删除只能逐个进行：8 个客户端下，一次账号删除的中位数为 107.66 ms，原实现为 29.35 ms（p99 为 141.61 ms 对 47.79 ms；每秒 70.7 次对 244.4 次）；删除一个约有 20,000 次观看的视频为 296.92 ms 对 133.50 ms。在同一次开机中运行的第二轮 jar 仍有 87/100 个账号删除失败（p50 为 19,064 ms）。第三轮的第一个版本（`1717da9`）用 `bv in (…) or other_bv in (…)` 删除视频的共同观看行，PostgreSQL 把它执行成对全部 1,892 行顺序扫描再过滤，而且每次删除账号也要付出这一开销，因为级联会以零行触发视频上的触发器。`ac3a0dc` 改为把被删除的视频与两个索引连接（[coview-delete-plan.txt](docs/experiments/coview-delete-plan.txt)）：1 个客户端下，删除一个没有观看的视频在 `1717da9` 中需 1.89 ms（同一组运行中原实现为 1.25 ms），现在为 1.33 ms（原实现 1.15 ms）；删除一个账号从 7.03 ms（5.28 ms）变为 6.92 ms（5.93 ms）（[1717da9 的运行](docs/benchmark/round3/writebench/1717da9/summary.md)）。

**8 个客户端。** 两个版本都受限于 4 个 vCPU：数据库后端进程和 WriteBench JVM 每次调用的 CPU 时间乘以吞吐量，在两个版本中都占用 3.0–3.7 个 vCPU（第二轮的关注为 2.53），因此吞吐量之比跟随 CPU 之比。在对原实现和第二轮（这些操作的代码与本版本相同，只有发布视频触发的插入触发器多了一个分支）的采样运行中，点赞每次调用的 CPU 时间为 0.95 ms 对 1.58 ms（后端 0.30 → 0.58，JVM 0.65 → 1.00），吞吐量为原实现的 0.63，而 CPU 之比的倒数为 0.60；收藏为 0.55 对 0.60，发布视频 0.59 对 0.52，发送弹幕 0.62 对 0.63。每次采样中，等待行锁的后端最多占 8 个中的 0.42 个（收藏），等待 WAL 刷盘的在两个版本中这四种操作都为 2.7–4.9 个。后端多出的 CPU 来自触发器和外键检查；JVM 多出的部分没有归因（性能剖析记录的样本太少）。关注的情况没有得到解释（两个版本每次调用的 CPU 时间相同）。在这台共享机器上，这些比值在不同轮次之间会变化：点赞的吞吐量在第二轮的运行中为原实现的 0.65，在第三轮中为 0.78（[诊断运行](docs/benchmark/round3/writebench/summary.md#where-the-8-client-time-goes-diagnosis-runs)）。

**每次调用。** 变慢的写入都要检查外键并维护 `video_stats` 中的计数器。pgbench 测得一次点赞中触发器约占 0.13 ms，外键约 0.09 ms，后者在噪声范围内（同时有两者、去掉触发器、两者都去掉时分别为 1.019、0.887 和 0.794 ms；交替运行 5 轮，[write-latency.txt](docs/experiments/write-latency.txt)）。在第二轮的 SQL 上，派生表触发器占删除一个有 18,179 条播放记录的视频时服务器端 28.9–30.7 ms 中的 4.2–4.5 ms，以及删除一个有 23 条播放记录的账号时 11.7–15.1 ms 中的 10.0–13.2 ms；后者大部分花在 `viewers` 上的触发器里，它更新播放计数以及这些播放记录涉及的 506 行共同观看计数（EXPLAIN ANALYZE，事务均回滚，各取 4 次热执行；[delete-costs.after.txt](docs/explain/delete-costs.after.txt)；没有在本轮的触发器上重新测量）。投币是一条语句，每次调用更快；它的基准测试步骤在第二轮为 547 → 567 ms，在这里为 636 → 569 ms。

### 测试方法

- 一台机器（4 个 vCPU、15 GiB 内存），所有运行使用同一个 PostgreSQL 16 服务器和同一份配置（`shared_buffers` 1 GB、`synchronous_commit` on、`full_page_writes` on、`wal_compression` off、`max_wal_size` 1 GB、`checkpoint_timeout` 5 min、JIT on）。基线是原实现：`2e811bd` 的服务代码，由测试脚本从 `3796695`（分支 `bench/baseline-clock`）构建，该提交只增加了下文所述的时钟偏移（jar sha256 `13515c67…`）及其自己的表结构。当前版本运行的是从 `1717da9` 构建的 jar（sha256 `bcde4e16…`），配合 `ac3a0dc` 的 SQL（该提交只修改了 `sql/build.sql`）；从 `ac3a0dc` 起的每个提交中，`sustc-api`、`sustc-runner` 和 `sql/` 都相同。（第二轮运行的是 `d0e24e2` 的 jar，sha256 `42004a16…`。）
- 每次运行都从新建的 schema（当前版本为 `sql/build.sql` + `sql/audit.sql`）开始，运行未经修改的框架基准测试，并把服务时钟固定在 2023-12-30 12:00 +08:00（`-Dsustc.clock.start=…`，JVM 时区 Asia/Shanghai），即测试数据生成的时刻：有些用例会拿公开时间和"当前时间"比较。原实现读取 `Instant.now()`；测试脚本使用的原实现构建会给它加上一个固定偏移，得到相同的起始时间。它失败的 4 个用例源于代码本身：3 个搜索用例的关键词中有连续空格，1 个平均观看率因为观看时长以 `real` 类型求和，在第四位有效数字上出现偏差。
- 每个版本 3 次，顺序为 B1、O1、B2、O2、B3、O3（B：原实现，O：当前版本），全部在同一次开机内完成（每次运行前后都记录 `uptime -s`）。每次运行都持有一把排他锁，运行之间释放，因此机器上的其他基准测试可以排在两次运行之间，但不会与某次运行重叠。没有丢弃任何一次运行，计时步骤内部也没有额外执行任何东西。第三轮最初在 `1717da9` 上开始的一组运行在第一轮之后就停止了，因为发现它的账号删除会扫描整张共同观看表（见[工程笔记](docs/ENGINEERING_NOTES.md#the-write-path)）；那两次运行保存在 [docs/benchmark/round3/1717da9-series](docs/benchmark/round3/1717da9-series)，这里不使用。报告每一步的中位数，方括号内为范围；通过数取各次运行的最小值。
- 每次运行的日志末尾记录了提交、未提交的已跟踪文件数（每次都是 0）、jar 的 sha256 和开机时间。`scripts/bench_instrumented.py` 从外部观察每次运行：每一步开始和结束时的 WAL 位置（`pg_waldump` 据此得出每一步的 WAL 字节数和整页镜像字节数）、内核的脏页内存、写入步骤前后的 `pg_stat_wal` 和 `pg_stat_bgwriter`、服务器日志中的检查点记录，以及第 8 步之前 `followings` 的规划器统计信息。
- 第二轮还以同样的方式运行了两个诊断变体，各 3 次：*settle* 在导入后暂停 runner，执行 `CHECKPOINT` 并等待内核脏数据低于 64 MB（[docs/benchmark/round2/settle](docs/benchmark/round2/settle)；在 `c3-B1` 中暂停落在了第 2 步里，该步的时间包含了暂停）；*analyze* 只针对原实现，在这次暂停中执行 `VACUUM ANALYZE`（[docs/benchmark/round2/analyze](docs/benchmark/round2/analyze)）。
- 这些运行使用仓库之外的一个测试脚本，由 `scripts/bench_series.sh` 驱动；`scripts/benchmark.sh --label NAME` 执行相同的步骤、使用相同的 JVM 参数（默认只允许本机，见下文）。

## 正确性

原实现有 4 个基准用例没有通过，还有若干基准测试不检查的问题。本版本全部修复，并且每一项都有测试覆盖：

- `follow` 返回操作之后的状态；删除账号会删除该用户的视频及视频上的一切，以及该用户做过的一切；删除视频时也会删除它的弹幕点赞。
- 相似视频在相似度相同时按 `bv` 排序；搜索相关度统计不重叠、不区分大小写的出现次数（接口 Javadoc 中的例子都写成了测试；大小写折叠使用 ICU，因此在基准数据库的 C locale 下对非 ASCII 文本同样有效），相关度相同时依次按播放量和 `bv` 排序。`bv` 的并列排序按字节比较（`collate "C"`），与数据库的默认排序规则无关。
- 未审核的视频以及尚未到公开时间的视频，只有其所有者和超级用户可见，搜索以及点赞、投币、收藏都遵循这一规则；弹幕只能发送到或显示在已发布的视频上，`likeDanmu` 遵循它的 Javadoc（弹幕存在，且用户看过它所在的视频）。
- 投币是一条语句：只有插入了投币记录才会扣币（用 8 个并发线程测试）。切换操作、编辑和共同观看计数在并发下是安全的；会改动派生表的删除先获取删除锁，因此相互之间不会死锁（见[架构](#架构)）。`ConcurrencyTest` 覆盖了每一种情况：第二轮的 11 个测试中有 10 个在第二轮修复之前的代码上失败（[concurrency-tests-old-vs-new.txt](docs/experiments/concurrency-tests-old-vs-new.txt)），第三轮新增的 2 个测试（在调用方事务中同时执行两个级联删除）在第二轮的代码上都会死锁（[deadlock-tests-old-vs-new.txt](docs/experiments/deadlock-tests-old-vs-new.txt)）；现在 13 个全部通过。
- identity 序列从已导入的最大 ID 之后继续；任何代码路径都不会泄漏连接池中的连接（每个集成测试结束后都会检查）。

规范中有歧义之处的处理方式列在 [docs/ENGINEERING_NOTES.md](docs/ENGINEERING_NOTES.md#interpretations-of-the-specification)。

## 运行

需要 JDK 17 或 21，以及 Docker（用于测试，也可以用来运行数据库）。

```bash
# 构建并运行全部测试（需要 Docker：Testcontainers 会启动 PostgreSQL 16）
./gradlew build

# 为 runner 准备数据库：用户名、密码和数据库名都是 "sustc"
cd sustc-runner && docker compose up -d && cd ..

# 课程提供的测试数据（.ser 文件，不在本仓库中）放到
#   sustc-runner/data/import/  和  sustc-runner/data/test/

# 在新建的 schema 上运行一次基准测试，结果写入 docs/benchmark/<label>.json
scripts/benchmark.sh --label my-run

# 根据 docs/benchmark/round3/plain/c6-B*.json 和 c6-O*.json 重新生成 README 中的表格
python3 scripts/bench_table.py --write README.md README.zh-CN.md

# 写入操作的微基准和压力测试（见 scripts/writebench/run.sh）
scripts/writebench/run.sh --jar run/sustc-runner.jar --sql "sql/build.sql sql/audit.sql" \
    --out result.json -- --mode steady --clients 1 --ops 600 --warmup 300 --settle

# 交互式命令行
./gradlew :sustc-runner:bootRun
```

## 限制与安全

- `scripts/benchmark.sh`、`scripts/explain_plans.sh`、`scripts/write_latency.sh` 和 `scripts/writebench/run.sh` 会**删除并重建**目标数据库的 `public` schema。除非传入 `--allow-remote`，它们只接受本机回环地址（`localhost`、`127.*`、`::1`）。在共享机器上可以设置 `BENCH_LOCK=/path/to/lockfile`（或用 `flock` 包住命令），让计时运行串行执行。
- WriteBench 最多使用 16 个客户端线程，每种操作最多 5,000 次调用。`scripts/bench_instrumented.py`、WriteBench 的 `--settle` 和 `docs/experiments/fk-lock-wal.sh` 通过 `sudo -u postgres` 执行 `CHECKPOINT` 和 `pg_waldump`，因此需要在数据库主机上有这个权限。
- 对数据库的负载受连接池（HikariCP，默认 10 个连接；`spring.datasource.hikari.maximum-pool-size`）和导入并行度（`sustc.import.parallelism`，默认 4 个连接，每个连接最多一路 `COPY` 流）限制。除此之外没有其他网络流量，也没有带宽限速。
- 基准测试本身会在并行流上运行部分步骤，这属于未改动的框架。

## 已知局限

- 密码以明文存储，与提供的数据一致。`users` 的审计日志不再复制密码、`qq` 和 `wechat`（只带 `qq` 或 `wechat` 的 `AuthInfo` 不需要密码就能认证）；旧版本写下的日志中包含这些信息，必须清除。
- 账号删除只能逐个进行。删除会逐表级联，每张被级联的表上的派生表触发器都会锁住它自己涉及的视频的计数器，因此两个删除可能以交叉的顺序锁住共同的计数器；第二轮中，8 个客户端下 100 个账号删除有 79–83 个、8 个导入视频的删除有 4–6 个以 `deadlock detected` 失败。现在每个删除操作都先获取删除锁：删除有互动或视频的账号时为排他模式，删除视频时为共享模式。8 个客户端下，300 个账号删除和 24 个视频删除没有一个失败，但一次账号删除的中位数为 107.66 ms（p99 为 141.61 ms），原实现为 29.35 ms（47.79 ms），每秒 70.7 次对 244.4 次；删除一个约有 20,000 次观看的视频为 296.92 ms 对 133.50 ms（[WriteBench 汇总](docs/benchmark/round3/writebench/summary.md)）。在派生表中没有任何数据的账号（例如第 22 步删除的新注册账号）跳过这把锁。让账号删除重新并发进行是尚未解决的问题（[待解决的问题](docs/ENGINEERING_NOTES.md#open-questions)）。
- 单行写入每次调用的 CPU 开销更大。1 个客户端下，需要检查外键或维护计数器的写入每次调用最多比原实现多 0.29 ms（更新视频 0.64 → 0.93 ms，p50 中位数），删除一个账号多 0.99 ms（5.93 → 6.92 ms），删除一个约有 20,000 次观看的视频所需时间翻倍（26.14 → 53.52 ms）。8 个客户端下两个版本都占满了 4 个 vCPU，多出的 CPU 就变成了吞吐量的损失：11 种单行写入中有 9 种为原实现的 0.78–0.96（点赞 0.78、弹幕点赞 0.79、收藏 0.85），审核为 1.16，删除没有观看的视频为 1.30（见[写入路径](#写入路径)）。
- 对 N 个视频，`video_coviews` 最多有 N(N−1) 行（这里 44 个视频的 1,892 个视频对全部存在）；一个看过 k 个视频的用户每新增一次观看会改动其中 2(k−1) 行，删除该用户会改动 k(k−1) 行。对这个规模没有问题；大得多的视频库需要改为存储无序对，或者限制每个用户的视频对数。
- 弹幕展示在新启动的 JVM 中前几次调用的开销更大：对基准测试的 382 个用例，第一遍在本版本中需 114.6 ms，原实现需 71.6 ms（3 次重放的中位数），而从第四遍起两者的范围重叠（36.2 和 34.9 ms）；基准测试只计时第一遍（72 → 80 ms）。热点在基准测试中为 14 → 19 ms，但在重放中并不更慢（第一遍 30.3 对 36.2 ms）。详见[工程笔记](docs/ENGINEERING_NOTES.md#hotspots-and-danmu-display)。
- 推荐结果不按可见性过滤；规范没有这样要求。
- `TRUNCATE` 不会触发派生表的触发器；`DatabaseService.truncate()` 会把派生表和其他所有表一起清空。
- 每次服务调用在执行语句之前都要单独查询一次来认证；把认证并入语句可以每次调用省一次往返。
