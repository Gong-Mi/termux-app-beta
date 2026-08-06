# termux-app v0.119.0-beta.3 基线仓库方案

仓库: Gong-Mi/termux-app-beta
基线: termux/termux-app v0.119.0-beta.3 (e634d8f9) + 工具链现代化
文档日期: 2026-08-06

## 1. 现状

### 1.1 基线
- master = v0.119.0-beta.3 + 工具链现代化（Gradle 9.2.0 / AGP 8.4.2 / compileSdk 36 / NDK 29.0.14206865，fork 主线验证过的组合），全部为构建系统改动，源码与 tag 一致（除两处编译修复，见 1.4）。
- CI (ci.yml): x86 腿 `./gradlew test` 全量单测 + assembleDebug APK；ARM 腿（ubuntu-24.04-arm）跑 `:terminal-emulator:testDebugUnitTest`。**全部绿**。

### 1.2 PR 线
| PR | 线 | 内容 | 状态 |
|----|----|------|------|
| #1 | 验证 | fix-crash 分支两个线程修复（CalledFromWrongThreadException，UI 操作包 runOnUiThread）cherry-pick | CI 全绿 |
| #5 | 测试 | 多用户 UID 数据路径修复 + 抽取 TermuxDataPathUtils + 4 个单测 | 搁置（依赖上游 termux-packages bootstrap 改路径） |
| #6 | 性能 | async-terminal-parsing（HandlerThread 异步解析）+ perf.yml 双架构性能测试 | CI 全绿，perf 出数据 |

### 1.3 平台硬约束（已验证，非配置问题）
- **aapt2 无 linux-aarch64**：AGP 8.4.2/8.13.2 的 maven `-linux.jar` 均为 x86-64 ELF（实测 `file`），无 `-linux-aarch64` 变体（404），build-tools 亦无 aarch64 包 → ARM 上任何资源管线（R 类生成、AAR transform）跑不了。
- **NDK 无 linux-aarch64 宿主包**（repository2-3.xml 实测：ndk;29.0.14206865 仅 linux/darwin/windows x64）→ ARM 上无法编原生 .so。
- 推论：ARM 腿只能跑无资源依赖的纯 Java 测试。terminal-emulator 恰好无 res/，其解析/输入测试套件是性能线核心——选择合理而非妥协。

### 1.4 基线相对 tag 的源码级修复（仅两处，均因 compileSdk 36 / AGP 8）
- MessageDialogUtils: `R.style` → `androidx.appcompat.R.style`（AGP 8 非传递 R；另在 gradle.properties 设 `android.nonTransitiveRClass=false` 保持其余引用不变，fork 验证过的路线）。
- HelpActivity: 删 `setAppCacheEnabled`（API 33 移除；LOAD_NO_CACHE + clearCache 已覆盖语义）。

## 2. 应该要做什么

### 2.1 验证线（#1）收尾
- 查上游 master / 上游 PR 是否已吸收这两个崩溃修复（git log --grep + 文件对照）：若上游已吸收，记录吸收 commit 作为验证结论；若未吸收，此 PR 是有效补丁载体，保持挂着（fork 纪律：补丁 PR 挂到官方完成目标为止）。
- 合并 #1 进 master 或保持 PR 形式，取决于 2.4 的同步策略。

### 2.2 性能线（#6）推进
- 已出第一组双架构数据（墙钟，噪声大）：
  - ResizeTest: x86 0.351s / ARM 0.340s
  - TerminalTest: x86 0.096s / ARM 0.137s
  - CSI: x86 0.036s / ARM 0.046s
  - 其余 <0.01s 量级
- 下一步：
  1. perf.yml 加 baseline job（基线 master vs pr-03-perf 同跑对比，直接量化"异步解析"收益的机制证据：ByteQueue 排空是否不再阻塞 UI）。
  2. 接更多 perf 提交对比：4KB→64KB 读缓冲（ef4775b6）、modernized-gradle-9 的深优化提交——各自独立 PR，同 harness 出数。
  3. 重复跑 3 次取中位数降噪声（runner 共享，单次墙钟不可信）。
- 性能汇报纪律：只报机制 + 原始耗时，不编造百分比。

### 2.3 测试线（#5）——等上游
- 依赖：termux-packages bootstrap 硬编码 /data/data/com.termux 路径，需上游先支持动态 UID 路径。
- 分支 pr-02-test 保留（cherry-pick + TermuxDataPathUtils 抽取 + 4 单测已就绪），上游就绪后恢复 PR。

### 2.4 上游同步与工具链
- 上游 master（2026-04-07）已到 AGP 8.13.2 + Gradle 9.2.1 + compileSdk 36，比 tag 多 50 commits。定期 `git fetch upstream` + 分类（feat/fix/perf）后 cherry-pick 值得回移植的修复（如 termcap 修复、hiddenapibypass 后续）。
- 工具链升级路径：AGP 8.4.2 → 8.13.2 对齐上游（aapt2 仍是 x86-only，ARM 约束不变；但 R 类限定迁移——上游选限定而非 flag，长期可跟）。
- 上游 master 的 debug_build.yml 已现代化，可对照采纳。

### 2.5 更多 fork 分支按同一流水线验证
- 候选：feature/opengles-renderer（GLES 渲染器）、feature/termux-enhancements（64KB 读缓冲等）、feature/target-api-35、feature/modernized-gradle-9（perf 提交部分）。
- 每一条：cherry-pick 有效提交 → CI x86+ARM → 编译/单测/性能证据 → 分类（合并/搁置/废弃）。

### 2.6 真机验收（长期）
- UI 类问题（旋转、IME、抽屉）CI 覆盖不了。方案：Android 副用户安装 testkey APK + logcat/dumpsys 断言（fork 的 scripts/ui_smoke.sh 计划）。
- 需要设备端人工或后续自动化；不在本次 CI 范围。

## 3. 你能做什么（agent 能力边界）

- **CI 闭环**：推分支 → CI 自动跑（x86+ARM）→ 汇总结果与 URL。已闭环。
- **性能数据**：perf.yml 每次跑完出双架构对比表 + artifact，可下载分析。
- **分支审计流水线**：任意 fork 分支 → cherry-pick → CI 验证 → 证据化结论（能否编译/单测/性能），复用本次 3 子代理模式。
- **上游 diff 审计**：上游 master vs tag 的 50 commits 分类，挑可回移植项。
- **合并/关闭纪律**：挂起分支保留（#5），补丁 PR 挂到上游完成（#1 视上游吸收情况），perf PR 可反复迭代。
- **边界**：
  - aapt2/NDK 的 ARM 原生构建：Google 无 aarch64 包，做不了也不硬凑（与"Mac 不凑合"同理）。
  - 真机 UI 视觉验收：需设备端（副用户方案），CI 只能到单测+编译层。

## 4. 怎么测试（测试金字塔）

| 层 | 内容 | 平台 | 工具 |
|----|------|------|------|
| L1 配置/编译 | 每个 push/PR 必跑 | x86 + ARM | ci.yml |
| L2 单测 | x86: `./gradlew test` 全量；ARM: `:terminal-emulator:testDebugUnitTest`（无 aapt2 依赖） | x86 + ARM | ci.yml |
| L3 APK | `assembleDebug` + artifact | x86（NDK 约束） | ci.yml build-apk |
| L4 性能 | 8 个解析/输入测试类 JUnit XML 耗时提取 + 双架构对比表 | x86 + ARM | perf.yml |
| L5 真机 | 副用户安装 + UI smoke（旋转/IME/抽屉） | 设备 | scripts/ui_smoke.sh（待建） |

验收标准：施工（提交内容、diff 证据）与验收（CI 各腿绿、数据产物）严格分开汇报；绿 = 真实 run 的结论 + URL，不凭推断。

## 5. 假绿审计（2026-08-06）

审计目标：CI 绿是否真实等于"被测对象真的跑了"。

### 覆盖清单（当前 master）
| 模块 | 测试文件数 | CI 覆盖 | 备注 |
|------|-----------|---------|------|
| terminal-emulator | 19（78 用例） | x86 全量 + ARM 腿 + perf | 真实覆盖核心 |
| app | 2 | x86 全量 | 部分覆盖 UI 类 |
| terminal-view | 0 | 无（仅编译） | 空洞 |
| termux-shared | 0 | 无（仅编译） | 空洞；多用户/ShareUtils 改动无测试保护 |

### 已知空洞（诚实清单，非假绿但需记录）
1. **drain+coalesce/64KB（已合并）**：双架构编译通过、全量单测绿，但 TerminalSession 运行时路径（Handler 循环、ByteQueue 排空）依赖 Android 运行时，**CI 无运行时执行**。验证 = 真机（副用户方案）。
2. **PR #1 崩溃修复**：UI 线程包裹，JVM 单测不可断言；真机验证待 ui_smoke。
3. **terminal-view/termux-shared 零测试**：这些模块的改动（如剪贴板迁移）CI 只保编译。

### 已做的防假绿加固（本仓库 ci.yml/perf.yml）
- ci.yml 新增 verify_tests.py 步骤：**要求 app 与 terminal-emulator 模块测试数 > 0**，零测试即红（防"测试静默消失仍绿"）。
- perf_extract_timing.py 新增零测试守卫：JUnit XML 0 用例即失败。
- perf.yml 计时跑加入 TERMUX_TEST_PERF_MODE=1：跳过 assertInvariants 脚手架（TerminalTestCase sPerfMode），让耗时反映引擎成本而非测试自检。
- 完整 CI 日志流 + artifact 已归档至 ~/termux-app-beta-archive/（含 runs-manifest、每 run 完整日志、perf timing、分析报告、子代理记录，archive.sh 可重跑）。

