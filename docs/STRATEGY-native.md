# STRATEGY: native 化线（perf/strategy-native）— C / Rust / C++

结论：**现阶段不做**。rust-integration 是路线级失败（证据见下）；perf 数据不支撑"解析需要 native"。第一步只做测量基建与约束文档，零引擎改动。

## 语言裁定（此工程语境）
- C++：不选（无既有基建、无收益、ABI/异常复杂度最高）
- C：可行，贴近 termux.c/Android.mk 基建，但 host 测试与 sanitizer 要自搭
- Rust：占优——cargo test host 直接跑（x86+ARM runner 双架构）、内存安全、fork 已有工程资产；但**小步增量 native 化 ≠ 引擎接管**

## 坑清单（带证据）
1. rust-integration 失败史：Rust CI 24h 挂起（无 timeout 杀日志）、skia API 级别问题、armv7/i686 砍半（64-bit-only）、clippy 165 项债、引擎从未真机验收——大爆炸路线在验证上整体失守
2. JNI 过界开销与引用管理：native 核心裸跑快不代表真实路径快，性能测试必须含 JNI 往返
3. native 状态与 Java 状态同步：TerminalEmulator 状态机跨边界后，差分测试是唯一可靠 oracle
4. ABI：NDK 宿主只有 x86（ARM runner 编不了 Android .so），4 ABI 交叉编译只能在 x86 CI

## 验证架构（出事怎么验证，本线基建第一提交）
- 层 0：4 ABI 交叉编译（x86 CI）
- 层 1：host 编译 native 核心，x86+ARM runner 双架构单测 + **差分测试**（Java 测试语料为 oracle，native vs Java 输出逐字节比对）+ ASan/UBSan + fuzz 短跑（60s/轮）
- 层 2：x86 CI 模拟器（KVM）+ instrumented 测试验 JNI 胶水
- 层 3：副用户真机 + logcat/dumpsys + tombstone 符号化（artifact 保留 unstripped .so）
- 纪律：任何 native 变更必须能在某一层复现问题；"只有真机能复现"的不许合并

## 第一步提交（零引擎改动）
1. 分配画像（现有代码的 GC/分配测量，证明 Java 侧真实瓶颈是否可消除）
2. JNI 边界/ABI 约束文档
3. rust-integration 失败史基线（上文证据落文档）

## 验收
本线基建（host 测试骨架 + 差分 harness + sanitizer 配置）在 CI 上绿 = 后续 native 迁移 PR 的准入条件。
