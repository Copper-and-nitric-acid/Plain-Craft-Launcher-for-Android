# 下载引擎规格（v0.1）

对应模块：`download/`（唯一第三方依赖 okhttp 4.12.0，`core/` 保持零依赖）。

## 1. 分档策略

| 文件大小 | 档位 | 默认线程 | 玩家可改 |
|---|---|---|---|
| `< 10MB` | SINGLE | 1 | 否（强制单连接） |
| `10MB – 60MB` | PARALLEL | 4 | 是，1–16 |
| `> 60MB` | TURBO | 8 | 是，1–16 |

- 阈值与线程数都是 `DownloadConfig` 的字段，不写死。
- 每片最小 1MB：`minSegmentBytes`，防止 11MB 文件被切成 16 片、每片 700KB。
- 玩家输入统一过 `DownloadConfig.sanitized()`：9999 → 16，0 / -3 → 1。
  `newFixedThreadPool(9999)` 会直接 OOM 闪退，夹取比崩溃好。

## 2. 落盘与续传

- 每个分片写 `dest.parts/seg-N.part`，**顺序拼接**成 `dest.tmp`，校验通过才 rename 成 `dest`。
- 不给多线程共用同一个 `RandomAccessFile`：/sdcard 是 FUSE，并发随机 seek 的写放大极高。
- 分片文件长度即进度，重跑时自动从 `range.start + have` 续传。
- 416 视为已下完，429/503 指数退避（1s→2s→4s… 上限 60s），不用固定 sleep。

## 3. 地址拦截（`NetworkGuard`）

| 命中 | 判定 | 文案 |
|---|---|---|
| `10.x` `172.16–31.x` `192.168.x` `127.x` | PrivateNetwork | Hey Bro，这是局域网 IP，外网连不上。如果你懂行在测试穿透请忽略，不懂的话请点开高级选项。 |
| `169.254.x.x` | LinkLocal | 🚨 兄弟，获取不到 DHCP，你家路由器/光猫/交换机大概率出现环路导致广播风暴了。如果是云服务器，立刻找服务商提工单！ |
| 域名 | Ok | 放行，交给 DNS（`192.168.example.com` 不会被误伤） |

下载前拦截，不等 30 秒超时再报错。

## 4. 校验与「不对劲」流程

1. 下载完成 → `Checksum.verify(file, expectedSha1, expectedSize)`，SHA-1 + 大小双校验。
2. 不匹配时**不覆盖已有文件**，坏文件留在 `*.tmp`，返回 `HashMismatch(tempFile, url, expected, actual)`。
3. 弹窗三个选项（`UserChoice`）：
   - `DELETE_BAD_FILE` — 删除坏文件
   - `SHOW_URL` — 查看真实 URL（二次确认）
   - `FORCE_CONTINUE` — 强制放行，文件改名保留，日志打印 `⚠️ 高风险`
4. 没有官方哈希时返回 `sha1 = null`，**不假称「已验证」**；大小不符直接失败。

文案：「⚠️ 下载不对劲！可能是文件损坏、被人中间件劫持，或官方更新。为了你的手机不中病毒，我先暂停了。」

## 5. 实测（本机 OPPO A58x / wifi）

目标：1.20.1 client.jar，23,028,853 字节，BMCLAPI 镜像，SHA-1 全部校验通过。

| 并发 | 耗时 | 吞吐 | 相对 1 线程 |
|---|---|---|---|
| 1 | 27.14s | 0.81 MB/s | — |
| **4（默认）** | **17.65s** | **1.24 MB/s** | **+53%** |
| 6 | 20.70s | 1.06 MB/s | +31%（比 4 线程更慢） |

- 4 线程是这台机的甜点，和桌面 PCL 默认 63 线程完全不是一回事。
- 6 线程时 BMCLAPI 返回 429，退避 32s 后仍完成——**改大不一定更快，CDN 会限速**。
  这也说明 `maxThreads = 16` 是保护，不是性能开关。
- 单文件分段的收益在 10MB 以上才明显；10MB 以下强制单连接是省事，不是省时。

## v0.2 多文件队列（2026-10）

- `DownloadQueue.run(tasks, onOverall, onTaskDone, isCancelled)`：多文件并发（默认 2，上限 8），
  每个任务按 `MirrorChain` 换源，`SHA-1` 不匹配时换下一个源再试。
- 并发不是常数：`HttpDownloader` 撞到 429/503 时回调 `onThrottled`，`AdaptiveLimiter` 把许可减半，
  连续 8 个文件成功再 +1，避免在阈值附近来回抖。
- 为什么并发对象是「多文件」：本机实测单连接 1.30 MB/s、4 分片 1.24 MB/s、8 分片 1.06 MB/s（开始吃 429）。
  一条链路切碎只会互抢带宽；下载一个实例要拿几百个小文件，排队和握手才是大头。
- 下载页支持多行输入（一行一个地址，空格后可选 SHA-1），显示总进度、瞬时速度、逐任务状态。
