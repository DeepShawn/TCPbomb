# 直连投递（DirectTCP）

一台手机上有个 App 已经和另一台手机保持着 TCP 连接；这个 App 帮你**拿到那条连接的对端 IP:端口，然后自己直连过去发消息**。

不需要配对码、不需要扫码、不需要对方确认。装在同一边手机上即可。

---

## 先说清楚能做到什么、做不到什么

这是踩过的坑，写下来免得白折腾：

| 想法 | 结论 |
|---|---|
| 直接把数据写进「老应用那条已经建立的连接」 | **无 root 做不到**。socket fd 属于进程，`/proc/<pid>/fd` 跨 uid 是 `EPERM`，`pidfd_getfd` 需要 `CAP_SYS_PTRACE`。 |
| 读 `/proc/net/tcp` 找老应用的连接 | **Android 10+ 做不到**。AOSP `public/app.te` 有编译期 `neverallow { appdomain -shell } proc_net_tcp_udp:file *;`，所有 App 域被封，只有 `shell` 豁免。Android 9 及以下才可以（本 App 保留了这个入口，只在 API ≤ 28 显示）。 |
| 拿到的地址另开一条连接发消息 | **可以**，这就是本 App 的做法。对端会看到一条新连接（聊天类服务通常允许多连接）。 |
| 自动拿到对端地址 | **可以**，用 `VpnService` 只接管老应用的流量，读到它下一个 SYN 的目的地址就撤。免 root，代价是点一次「允许 VPN」。 |

所以：**「借用会话信息 + 另建连接」= 可行；「注入原会话」= 需要 root。**

---

## 两种地址来源

### 1. 抓取（推荐，自动）

1. 点「抓取对端」→ 选择老应用（只抓它的流量，别的 App 不受影响）。
2. 首次会弹 VPN 授权，点允许。
3. 让老应用重新连一次：切一下 Wi-Fi/流量、重开 App、或下拉刷新。
4. 抓到下一个 TCP `SYN` 的目的 IP:端口后，VPN 自动关闭，目标地址自动填好并连接。

为什么不会把老应用弄坏：我们只吞掉**一个 SYN 包**，随即关闭 VPN。IP 层看到没有回应会重传，此时流量已回到物理网卡，连接照常建立。抓取期间老应用的 DNS 也会进 tun，App 会用普通 UDP socket 代转（自身流量不在 VPN 白名单里，不会打环）。

### 2. 手动 / 历史

直接填 `IP:端口`，或用「历史」选最近 20 条。这条永远可用，不受系统版本影响。

---

## 发送

- 模板：文本 + `\n` / 文本 + `\r\n` / 无行尾 / JSON 行 / HEX 字节（如 `48 65 6c`）
- 未连接时点「发送」会自动先连接
- 对端有回包会实时显示在日志里（`← ...`）
- 支持多次发送，长连接不断开

---

## 构建

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleRelease        # 产物 app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest      # 单元测试（含真实回环 socket）
```

- Kotlin 2.1.20 / AGP 8.10.1 / Gradle 8.11.1
- minSdk 24，compileSdk 36
- 依赖全部来自 Google Maven，离线可用（加 `--offline` 前提是缓存齐了）
- `keystore.properties` 里是本地测试签名，正式发布请换自己的

---

## 代码结构

```
app/src/main/java/com/aicode/directtcp/
├── MainActivity.kt          界面：地址来源、连接、发送、日志
├── CaptureService.kt        VpnService：抓 SYN 目的地址 + DNS 代转
└── net/
    ├── PacketParser.kt      纯逻辑：IPv4/IPv6 + TCP/UDP 解析、DNS 应答构造、校验和
    ├── Sender.kt            连接 / 发送 / 收包，纯 Java Socket，可单测
    ├── CaptureBus.kt        服务与界面的事件通道
    ├── SessionScanner.kt    /proc/net/tcp 解析（仅 Android ≤ 9 可用）
    └── TcpSession.kt        会话模型 + TCP 状态枚举
```

测试覆盖：`/proc/net/tcp` 大小端解码（IPv4/IPv6/v4-mapped）、SYN 识别（排除 SYN-ACK/ACK）、IPv6 SYN、DNS 查询解析与应答构造（地址互换、IP 头校验和自检、UDP 校验和 0）、回环 socket 真实收发与回执。

---

## 排错

| 现象 | 原因 |
|---|---|
| 抓取一直超时 | 老应用没有发起新连接；让它重连一次。或它走的是已存在的长连接，必须触发重连。 |
| 抓到的端口是 443 | 对端是 TLS 服务，直接发明文没用；这玩意儿不是给 HTTPS 用的。 |
| 连接被拒绝 / 超时 | 对端不接受新连接，或端口只允许原客户端；也可能有 IP 白名单。 |
| Android 9 以下「扫描会话」为空 | 该 ROM 限制了 procfs；用抓取或手输。 |
| 抓取后老应用短暂卡顿 | 正常，SYN 被吞 + 重传，一般 1~2 秒恢复。 |

---

## 边界

这是一个**网络调试工具**：只发你自己写的内容，只连你自己的对端。别拿它去碰不属于你的服务和会话。
