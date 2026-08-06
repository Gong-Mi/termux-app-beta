# STRATEGY: Java 优化线（perf/strategy-java）

结论：**做**，第一条线。C 化/异步解析均延后，先证明/消除 Java 侧可量化的瓶颈。

## 热点证据（file:line）
- TerminalSession.java:142 — PTY 每 read 一条 Handler 消息（无合并）；:343 每消息只 read 一次就 append → 主线程卡顿源头
- ByteQueue.java:20/59 — synchronized + wait/notify + 双 arraycopy，读写线程每 chunk 争锁
- TerminalEmulator.java:972/1011/1013 — OSC/APC 路径每 hex 字符 String.format("%02X") + StringBuilder 分配
- TerminalBuffer.java:236-238 — resize/reflow 逐行 new TerminalRow + 逐字符回填
- WcWidth.java:514-533 — 每字符最多两次二分查找，被 emitCodePoint 逐字符调用

## 关键坑
1. **perf 墙钟被测试脚手架主导**：TerminalTestCase.java:93-98 每次 enterString 后跑 assertInvariants（全屏逐字符 WcWidth + 每行 HashSet 分配）——ResizeTest 0.34s 大部分是断言开销，非引擎成本。没有"无断言 perf 变体 + 预热"前，任何优化验收都是假象。
2. **64KB 读缓冲（ef4775b6）不能单独 cherry-pick**：它与 drain 循环（fork ba9c5c35）配套；单拎出来单次解析量放大 16 倍。
3. 单测可能锁实现细节（读取字节数/调用次数断言），改读循环前先 grep 测试断言面。

## 计划（分三步，每步独立验收）
1. 读循环 drain+coalesce（多 chunk 合并一次解析）+ 64KB 缓冲配套
2. perf 测量基建：预热轮 + 关闭 assertInvariants 的 perf 变体 + 分配采样（/proc/self/stat 或 JFR）
3. OSC/APC 分配点消除（String.format 替换）

## 验收
- 全量单测 x86（./gradlew test）+ ARM 腿（:terminal-emulator:testDebugUnitTest）绿
- perf.yml 双架构数据：机制描述（减少了什么分配/锁/消息数），不编造百分比
