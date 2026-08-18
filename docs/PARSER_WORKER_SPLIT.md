# Parser Worker + Latest-Only Mailbox 拆分计划

> 对应 PR #35 (https://github.com/Gong-Mi/termux-app-beta/pull/35)
> 分支：`refactor/parser-worker-mailbox`

## 现状

- [x] Stage 1: `TerminalRenderMailbox<T>`（泛型、revision-only、单槽丢帧）
- [x] Stage 2: `TerminalSessionClientMainThreadWrapper`（所有 emulator 回调回归主线程）
- [x] Stage 3: parser worker 脚手架 + `TerminalModelFrame` + `TerminalFrameSink`
- [x] Stage 4: `TerminalSession` 只读 snapshot API（外部 `getEmulator()` 迁移）
- [x] Stage 5: 把 worker 和 mailbox 真正接入 `TerminalSession` / `TerminalView`
- [ ] Stage 6: CI stress 断言更新与验证

## 关键设计决策

### 模块依赖

- `terminal-emulator` 不能依赖 `terminal-view`。
- `TerminalRenderMailbox` / `RenderFrameMetrics` 继续留在 `terminal-view`。
- 通过 `com.termux.terminal.FrameRevision` 接口让 mailbox 泛型化，同时 `TerminalModelFrame` 与 `TerminalRenderFrame` 都暴露 `screenRevision`。
- `TerminalFrameSink` 接口在 `terminal-emulator` 中定义；`TerminalView` 实现 sink，把 model frame 转成 render frame 后丢进 mailbox。

这样 parser worker 可以放在 `terminal-emulator`，而 mailbox 留在 `terminal-view`，没有循环依赖。

### 线程边界

| 对象 | 所在线程 | 说明 |
|---|---|---|
| `TerminalEmulator` | parser worker | 所有 mutation（append/resize/reset/finish）串行化 |
| `TerminalModelFrame` | parser worker 产出，主线程消费 | 不可变快照 |
| `TerminalRenderFrame` | 主线程 | 包装 model frame + 当前文本选择 |
| `TerminalRenderMailbox<TerminalModelFrame>` | 跨线程 | 单槽 AtomicReference，producer 发布，render 获取 |
| `RenderFrameMetrics` | 主线程/跨线程 | 发布在 worker，ack/drop 在 render |
| `TerminalSessionClientMainThreadWrapper` | 跨线程 | worker 线程调用，post 到主线程执行 |

### 外部 `session.getEmulator()` 迁移

需要迁移的调用点（搜索 `.getEmulator()`）：

- `TerminalView.java`：渲染、尺寸、选择、鼠标状态等 → 改为从 mailbox model frame / session snapshot 读取。
- `TermuxTerminalViewClient.java`：`isMouseTrackingActive()`、`isAlternateBufferActive()` 等 → 使用 session snapshot 方法。
- `TermuxTerminalSessionActivityClient.java`：背景色、reset → 使用 session snapshot 方法。
- `ShellUtils.java`：transcript 文本 → 使用 session snapshot 方法或 frame 内的 `TerminalScreenSnapshot`。

## Stage 5 集成步骤

1. `TerminalSession` 创建 `TerminalParserWorker`（在 `initializeEmulator` 中）。
2. reader thread 有新输入时调用 `worker.requestAppend()`，不再发 `MSG_NEW_INPUT`。
3. `MainThreadHandler` 只负责进程退出协调；满足 `TerminalSessionExitCoordinator.shouldFinish(true)` 后，向 worker 发 `requestFinish(exitCode)`。
4. worker 的 finish 命令负责：
   - 调用 `cleanupResources(exitCode)`；
   - 向 emulator append 退出提示文本；
   - publish 最终 frame；
   - 通过 wrapper 调用 `onSessionFinished`。
5. `TerminalView` 在 `attachSession` 时设置 sink 并初始化 mailbox。
6. `TerminalView.onDraw` 从 mailbox `acquireLatest()` model frame，构造 `TerminalRenderFrame` 并渲染。
7. 外部调用者全部改为 session snapshot 方法；`TerminalView` 不再直接读 `mEmulator`。

## 不变量

- `published >= drawn + dropped`
- `acked <= published`
- `drawn == acked + dropped`
- 主线程不再调用 `TerminalEmulator.append/resize/reset`

## 测试策略

- 单元测试：`TerminalRenderMailboxTest`、`TerminalRenderFrameTest`、`RenderFrameMetricsTest`
- 编译：`:terminal-emulator:compileDebugJavaWithJavac`、`:terminal-view:compileDebugJavaWithJavac`
- 全量单测：`:terminal-emulator:testDebugUnitTest`、`:terminal-view:testDebugUnitTest`
- CI：触发 `pixel-loop-stress.yml` 并校验最终帧指标与 gfxinfo。


## Stage 6 剩余工作

- [ ] 在目标 Android 真机上做 parser / snapshot copy / Canvas / RenderThread / GPU 分层验收。
- [ ] 在 CI 中加入端到端 frame invariant（publish/draw/dropped 计数、revision 单调性、输入尾延迟）。
- [ ] 目前 `TerminalView.updateSize()` 仍保留 `mTermSession.getEmulator() == null` 这一处仅用于判断 emulator 是否已初始化的兼容性读取；不影响线程隔离。
