# Termux beta 认证与验收方案

本方案认证的是“哪个源码构建成哪个 APK，在什么 Android 环境中实际通过了哪些门”，不是账号登录认证。实现位于 `scripts/beta_*` 与 `.github/workflows/beta-certification.yml`。

## 1. 对象与不可跨越的证据边界

- 每次验收固定完整源码 SHA、工作流 run/attempt、APK SHA-256、签名证书 SHA-256、设备 serial、API/ABI/系统 fingerprint。
- 基础设施 PR 与功能修复 PR 分开。#88/#89/#90 的本地组合基线为 `c93d5b87dd26ace6170053e0645d9d89525e801e`，它不是远端 master，也不代表这些 PR 已合并。部署组合修复前，必须重新构建并验收组合候选自身，不能借用任一单独 PR 的绿灯。
- 当前环境自动门使用 release 派生的 `verification` 变体：`debuggable=false`、测试密钥签名、R8 关闭、生产包名 `com.termux`。这不是 minified release 的等价性认证。debug 构建的 JVM 单测可以继续使用，但 debug APK 不作为设备行为/性能验收包。
- public testkey 只用于测试签名和核对升级兼容性，不证明“官方发布”或生产信任。`build-head.txt` 是 CI 溯源侧车，不是 APK 源码身份的独立密码学证明，必须与可信 run/checkout/构建步骤共同使用。
- non-debuggable 不等于已经 AOT；compile 返回 `Success` 不等于读回为 speed；ART 可以正常混合 AOT/JIT/解释执行。不能要求零 JIT，也不能仅凭 JIT 线程存在或 CPU 高宣称“不断重新编译”。
- JVM、Android 模拟器、物理设备、性能是不同证据层。模拟器也可观察 Android/SurfaceFlinger，但不能冒充物理 GPU、OEM IME 或真实触控链路证据。

## 2. 门与完成条件

| 门 | 通过条件 | 不足时的判定 |
|---|---|---|
| G0 源码/CI | checkout 完整 SHA 与候选一致；逐工作流、逐 job 的预期清单完整；最新 run/attempt 全部终结成功 | 缺 workflow 为 NOT_RUN；运行中为 INCOMPLETE；失败、取消、预期 skipped 不通过 |
| G1 APK | 真实哈希与侧车一致；验签与预期证书一致；包名 com.termux、versionCode 1022、beta versionName、非 debuggable、含 arm64 ABI；DEX class_defs 确实定义 TermuxActivity | 任一错误 FAIL；工具不可执行/证据不可读不算通过 |
| G2 安装 | 安装后重新取得 pm path，对实际已装 APK 全量哈希，base 与 G1 候选相等；记录所有 split；物理设备另核对预选稳定文件和用户数据 | install 的 Success、相同版本号或更新时间都不能单独通过 |
| G3 ART | 明确非 debuggable；设备实际 dexopt primary code path/ISA 状态为 speed 或 everything；base 身份一致；代码 split 逐项验证 | verify、run-from-apk、speed-profile 不满足本轮强 AOT 基线；未知、缺项、来源冲突为 INCOMPLETE |
| G4 功能 | JVM XML 非空且无失败/错误；API32/36 各用同一非 debug universal APK，先请求 speed、读回 G3，再运行两个现有 instrumentation 类，分别执行 4 和 1 个测试且成功 | 空跑、计数不符、崩溃、runner 失败、输出截断或最终结果不成功均失败 |
| G5 物理设备 | 下面五个场景有独立运行证据；绑定同一候选 APK 和 G3 状态 | 未运行明确 NOT_RUN，不以 JVM/模拟器替代 |
| G6 性能 | 独立测量设计、原始数据和统计判决 | 环境或功能绿不能升级为性能改善 |

G3 证明的是设备报告的编译状态，不证明每个热方法当前都从新 oat 执行。编译前已运行的进程不能自动算作新编译产物的执行证据；真机复核需安排普通启动，并记录新 PID/时间与必要的 maps/运行观测。instrumentation 是测试运行模式，不是普通应用性能测试。

## 3. 已实现的自动环境

工作流 `.github/workflows/beta-certification.yml`：

1. 明确 checkout `pull_request.head.sha` 或 workflow_dispatch 的 SHA，拒绝把 synthetic merge SHA 当作候选。
2. 一个构建作业运行全部 beta Python 门的正负控，执行 `bundleVerification assembleVerificationAndroidTest`。
3. bundletool `--mode=universal` 融合 install-time `support_ui`，导出测试证书，运行 G1；只上传一份 APK 供两个 API 消费。
4. API32、API36 各自使用 KVM + SwiftShader emulator。显式固定 serial；每条设备调用有超时，启动有总截止与进程死亡检测。
5. driver 确认 `ro.kernel.qemu=1`、API 在 32/36、boot_completed=1 后才允许安装、授权、force-stop、compile 和 instrumentation。物理设备在 mutation 前拒绝。
6. 安装与设备侧哈希读回后，先请求 `cmd package compile -m speed -f com.termux`，调用只读 ART 门。必须读回新产生的 report.json、passed/status、serial/package/APK 身份；子进程 exit0 不能代替报告。
7. 普通 Activity 启动错误必须失败；instrumentation 逐类计数并核对最终 `INSTRUMENTATION_CODE: -1`，不能只看 adb exit0 或某一条 OK。
8. 失败日志与报告始终上传；APK 和小体积证据分别保管。不在手机大下载 SDK、系统镜像或 APK。

此工作流的实际 job 显示名：

- `Build verification APK + instrumentation APK (once)`
- `API 32 beta certification`
- `API 36 beta certification`

现有 CI、Perf、Render、Clipboard 工作流是否适用，由本次 PR 的累计 changed paths、base 分支与各自 on/paths 决定。预期清单在等待前固定，不能从“目前只看到的成功 job”反推完整集合；不能用固定 7/8/10 的总数代替清单。

## 4. 可复制的门命令

所有占位值先替换。`--build-head` 接受文件路径，不接受 SHA 本身。

```bash
python3 scripts/beta_artifact_gate.py \
  --apk /absolute/universal.apk \
  --expected-head FULL_40_HEX_SHA \
  --build-head /absolute/build-head.txt \
  --sha256-file /absolute/universal.apk.sha256 \
  --expected-cert-sha256 CERTIFICATE_SHA256 \
  --aapt /absolute/aapt2 --apksigner /absolute/apksigner \
  --output /absolute/evidence/artifact.json

# 宿主只读，不安装、不编译、不改权限、不杀应用
python3 scripts/beta_art_gate.py --transport local-root \
  --package com.termux --expected-apk-sha256 APK_SHA256 \
  --out-dir /absolute/evidence/device-art

# 远端只读；serial 必须显式，不回退到宿主
python3 scripts/beta_art_gate.py --transport adb --serial SERIAL \
  --package com.termux --expected-apk-sha256 APK_SHA256 \
  --out-dir /absolute/evidence/remote-art

# 以下为新环境工作流的三个必需 job；完整验收还须添加其它适用工作流。
python3 scripts/beta_ci_gate.py --repo Gong-Mi/termux-app-beta --sha FULL_40_HEX_SHA \
  --expect 'beta-certification.yml=Build verification APK + instrumentation APK (once)' \
  --expect 'beta-certification.yml=API 32 beta certification' \
  --expect 'beta-certification.yml=API 36 beta certification' \
  --output /absolute/evidence/ci.json
```

CI 门单次检查，不自行轮询。完整 SHA 先校验，不修复缩写/畸形值。逐页读取 run 和 job，核对最新 attempt；同一 SHA 上未列入清单的已出现 workflow 也不能藏住失败或在途状态。主调度者仍须负责预期但尚未注册的 workflow 清单。

CI 门退出：0 PASS；1 FAIL/INCOMPLETE/NOT_RUN；2 INVALID_INPUT；3 INFRA_RETRY。其它门使用各自 JSON 的 status/reasons 区分原因，任何非零都不通过。网络错误不得被部分 API 成功掩盖，重试也不得覆盖旧失败原始证据。

本地纯脚本回归：

```bash
PYTHONPATH=scripts python3 -m unittest discover -s scripts -p 'test_beta_*.py' -v
```

这些单测的模拟 APK/adb/GitHub/ART 数据仅证明解析器和控制流；不能冒充 CI 构建或真机结果。

## 5. 真机五个场景

1. idle attach：创建/切回一个已经初始化且不再输出的会话，绑定会话/PID/帧日志和截图，证明不依赖下一次 PTY 输出即可出现当前屏幕。
2. copy：跨软换行选区，含 CJK/宽字符；分别覆盖 frame 与 fallback，粘贴逐字核对。硬换行保留，软换行拼接，不以截图代替文本 oracle。
3. margin：偏好 off/on 各执行 IME 开合，采集同一时间窗的偏好、insets、margin 日志与画面。off 时没有 margin 更新；on 时保留既有行为。
4. fd：root 定位 com.termux 的实际 PID，数 ptmx 而非所有 fd；baseline→增加 N 个真实会话（应增加 3N 个 ptmx）→子进程退出后稳定回到 baseline。不要用 shell 的 PPID 猜应用 PID。该测试会增加抽屉会话项，执行前说明。
5. IME 过冲：键盘弹出/收起分别记录触发与时序；先核对屏幕解锁、前台焦点、IME 状态。静态截图只能证明现象，不能单独锁定根因。

`ime_probe.log` 若 init 因 EACCES 失败，会静默停用至下次初始化。先确认实际存储权限、appops 和日志持续追加，旧文件存在不代表采集通道可用。权限恢复与活动重建分别验证。

## 6. 宿主升级：必须单独择时

本项目的目标包就是当前 agent 宿主。替换安装和 force-stop 都会终止 Termux/agent；本自动环境不执行任何宿主写操作。

顺序：

1. 在 CI/远端取得候选，对 G0/G1 和候选/已装包完整证书指纹核验。签名不匹配就停，不卸载、不清数据。
2. 选定安装时点，落盘安装前稳定文件哈希、数据目录身份和复核脚本；避免把活跃日志/整个 home 的变化当成数据丢失。
3. 在已确认的目标上 `adb -s SERIAL install -r APK`；宿主本地则用明确的 local-root 安装流程。记录退出码，并读回 pm path/base APK 哈希。
4. 在同一目标请求 speed，再执行 G3 只读门。请求成功但仍 run-from-apk/verify 时保持失败，不靠更改状态标签收口。
5. 安排普通重新启动，记录新 PID、启动时间、ART 状态；再执行 G5。未安装/未重启/未验场景分别保留 NOT_RUN。

## 7. 性能单独验收

优先进程/线程 CPU 时间：目标进程使用 `/proc/PID/stat` 的 utime+stime 差值，Python 内部微基准才用其自身 `time.process_time()`，不要拿采集器自己的 CPU 时间代表 Android 应用。

保持同一设备/编译状态/负载，执行 ABBA + A/A 安慰剂 + bootstrap 置信区间；墙钟仅辅助。parser、snapshot、UI、RenderThread/GPU、GC 与输入尾延迟分层。SwiftShader、parser benchmark、普通交互、pixel-loop 真压力各自标注。没有物理设备数据，不声明流畅度或性能改善。

## 参考

- Discussion 34：管线与后端替换前置顺序：https://github.com/Gong-Mi/termux-app-beta/discussions/34
- Issue 36：parser/session 与分层验收：https://github.com/Gong-Mi/termux-app-beta/issues/36
- Issue 53：input→present 逐层证据：https://github.com/Gong-Mi/termux-app-beta/issues/53
