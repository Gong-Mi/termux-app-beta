# Parser worker / latest-only mailbox 拆分计划

> 目标：把 `TerminalSession` 的 drain+parse 从主线程拆到独立 parser worker，并通过 `TerminalRenderMailbox` 让 UI 只渲染最新帧。
> 依据：stress 日志确认主线程是瓶颈（Slow UI thread 619 / Slow issue draw commands 851），需要先把解析/模型构建拆出去。

## 已落地

- `TerminalRenderMailbox`（terminal-view）+ 单元测试（PR #35）。

## 待拆分工作

### Stage 2：callback 主线程化（可独立合并）

**文件**：`terminal-emulator/src/main/java/com/termux/terminal/TerminalSessionClientMainThreadWrapper.java`

- 包装任意 `TerminalSessionClient`，把所有回调（`onTextChanged`、`onTitleChanged`、…、`log*`）post 到构造时传入的 `Handler`（主线程 looper）。
- 这样 parser worker 可以直接调用原 client 方法，无需在每个回调点判断线程。
- 可先在 `TerminalSession` 里替换 client，但保持 drain+parse 仍在主线程；行为等价，单测不变。

### Stage 3：parser worker 脚手架（可独立合并）

**文件**：`terminal-emulator/src/main/java/com/termux/terminal/TerminalParserWorker.java`

- 使用 `HandlerThread` + `Handler` 做一条专用 parser 线程。
- 命令队列（`Message.what` 或显式 command 对象）：
  - `APPEND`：从 `ByteQueue` drain 并调用 `TerminalEmulator.append`。
  - `RESIZE(columns, rows, cellWidth, cellHeight)`。
  - `VIEWPORT(topRow, selX1, selY1, selX2, selY2)`。
  - `RESET`。
  - `FINISH(exitCode, exitDescriptionBytes)`：进程退出后把 `[Process completed...]` 写进 emulator。
  - `STOP`：清空队列并退出 looper。
- 每处理完一个 `APPEND` batch 后，**暂不做 mailbox 发布**，先保持原 `notifyScreenUpdate` 触发主线程 invalidate；这样 Stage 3 只搬了线程，不改渲染路径。
- 等价性：同一段字节序列仍按相同顺序进入 `TerminalEmulator.append`，只是线程变了。

### Stage 4：Session 只读快照 API（可独立合并，降低 Stage 5 风险）

**文件**：`TerminalSession.java` + 所有 `session.getEmulator()` 调用点

- 在 `TerminalSession` 上提供只读查询 API，外部调用者不再直接读 `TerminalEmulator`：
  - `isMouseTrackingActive()`、`isAlternateBufferActive()`、`isAutoScrollDisabled()`
  - `getActiveRows()`、`getActiveTranscriptRows()`、`getColumns()`
  - `getTitle()`
  - `getBackgroundColor()` / `getCurrentColors()` 副本
  - `getSelectedText(Rect)` / `getTranscriptText()`
  - `getCursorCol()`、`getCursorRow()`、`isCursorEnabled()`
- Stage 4 仍让 emulator 跑在主线程，只是 API 统一；为 Stage 5 做准备。
- 迁移清单（当前 5 个文件、约 40 处调用）：
  - `TerminalView.java`：读 screen rows/columns、mouse/alt buffer、scroll counter、selected text 等。
  - `TermuxTerminalViewClient.java`：读 mouse/alt buffer、rows、paste。
  - `TermuxTerminalSessionActivityClient.java`：读 colors。
  - `ShellUtils.java`：读 transcript。

### Stage 5：emulator 迁到 worker + mailbox 渲染（必须与 Stage 4 一起或紧随其后）

**文件**：`TerminalSession.java`、`TerminalView.java`

- `TerminalSession` 在 worker 线程创建并持有 `TerminalEmulator`。
- `TerminalView.onDraw` 从 `TerminalRenderMailbox` 取 `TerminalRenderFrame`，不再读 `mEmulator`。
- `TerminalView` 把 `topRow`/selection 变更作为 `VIEWPORT` 命令发给 worker。
- 输入事件（键盘、鼠标、粘贴）发给 `TerminalSession`，由 session 转成命令发给 worker 或 PTY。
- cursor blink 状态由 UI 线程命令控制。
- `notifyScreenUpdate` 机制替换为：worker 每处理完一个 batch，构建 `TerminalRenderFrame` 并 `mailbox.publish(frame)`；UI `onDraw` 取帧并 `metrics.ack(...)`。

### Stage 6：mailbox 跳帧验证 + CI stress

- 扩展 `RenderFrameMetrics` 断言：`published >= drawn + dropped`，`dropped` 在 parser 超过 renderer 时应大于 0（在 SwiftShader emulator 上应能复现）。
- 在 `pixel-loop-stress.yml` 里增加 `dropped > 0` 或 `coalesced > 0` 的合法断言，以及 janky 帧数占比的只记录不上限。
- 真机/ARM 上重跑 stress，确认跳帧后 `Slow UI thread` 下降、draw 命令数减少。

## 不能拆开的强耦合点

1. **emulator 迁到 worker 与 view 从 mailbox 渲染必须同 PR**：否则主线程仍读 `TerminalEmulator` 会产生数据竞争；不能靠锁解决（用户要求不采用 parser 直接修改共享 buffer + 锁）。
2. **Stage 4 的快照 API 必须在 Stage 5 之前完成**：否则 Stage 5 里外部调用者会出现悬空/并发访问。
3. **callback wrapper 必须在 Stage 3/5 之前完成**：否则 worker 线程回调会触发 UI 操作异常。

## 建议推进顺序

按 Stage 2 → Stage 3 → Stage 4 → Stage 5 → Stage 6 顺序做，每个 Stage 一个独立 PR，避免一次性大改。
