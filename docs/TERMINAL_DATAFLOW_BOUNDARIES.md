# Terminal 数据流交接与模块边界（render handoff + dirty-row journal）

> 本文记录 termux-app-beta 终端管线“拆碎点 / 数据流交接显式化”的第一阶段设计备注。
> 对应 PR：refactor(render): explicit TerminalRenderFrame handoff + dirty-row journal。
> 目的：让“解析改了哪几行 vs 渲染画了什么”成为可检查的数据，而不是靠人肉翻状态机。

## 1. 现状数据流（改动前，真实代码路径）

```
[reader thread]  read(pty, buf)
                 └─ ByteQueue.write(buf)                         TerminalSession.java:149-158
                    （mProcessToTerminalIOQueue, 64KB，synchronized 队列）

[main thread]    MSG_NEW_INPUT（coalesce，已有消息则不重复投递）
                 └─ TerminalInputQueueDrain → TerminalEmulator.append(byte[], len)
                                                               TerminalSession.java:358-362
                    └─ processChunk/processByte/processCodePoint   TerminalEmulator.java:500-580（2627 行状态机内联改屏）
                       └─ TerminalBuffer.setChar/blockCopy/blockSet/scroll/resize/...
                          （mScreen / mAltBuffer 直接变更，无锁 —— 同线程，无并发竞争）

[main thread]    TerminalSession → client.onScreenUpdated → TerminalView.onScreenUpdated
                                                               TerminalView.java:458-506
                 └─ invalidate()                       ← 全量重绘指令，无行级信息

[main thread]    onDraw → TerminalRenderer.render(emulator, canvas, topRow, sel…)
                                                               TerminalView.java:1034-1040
                 └─ 现场直读 god object：mRows/mColumns/mColors.mCurrentColors、
                    getCursorCol/Row/Style、shouldCursorBeVisible()、isReverseVideo()、
                    getScreen()  → 每行 allocateFullLineIfNecessary + 逐字符 measure 绘制
```

## 2. 泥点（改动动机，逐条注明）

| # | 泥点 | 位置 | 后果 |
|---|------|------|------|
| M1 | 渲染交接没有类型：“交接物”就是 god object 的 public 字段+getter 集合 | TerminalRenderer.java:57-71 | 改任意一侧都要翻 2627 行；无法单测渲染输入 |
| M2 | 变更集不存在：append 之后没人知道哪几行变了 | TerminalBuffer 全类 | 渲染 bug 无法归属：是解析改错了，还是渲染用了旧值/画错了行 |
| M3 | 读路径带写副作用 | TerminalRenderer.java:83 `allocateFullLineIfNecessary` | 渲染时可能分配行对象；渲染 bug 与模型 bug 边界被抹掉 |
| M4 | 解析状态机与屏幕模型熔合在 TerminalEmulator 一个类 | TerminalEmulator.java 全文 | 操作序列无法独立检查；单步调试 2627 行 |
| M5 | 通知链无归属：emulator 改完屏不发声，由 session 代发 | TerminalSession.java:358-412 | “谁负责画”没有明确的发出者；notify 只能全量 |

## 3. 本阶段改动（已提交）

1. **TerminalRenderFrame**（新增，terminal-view）—— 单帧渲染唯一交接对象：
   onDraw 入口一次性采集 cursor/screen/palette/selection/变更台账，渲染器只读该对象。
   `rowNeedsRedraw(externalRow)` 回答“这行有没有正当重绘理由（被改过/光标行/选中区）”。
2. **TerminalBuffer 变更台账** —— mutation count（批次）+ 内部行位图：
   setChar / blockCopy（重叠区并集、非重叠只记目标区）/ blockSet / scrollDownOneLine（可见行内部索引整体旋转，记所有可见行）/ resize（全量）/ setLineWrap / clearLineWrap / setOrClearEffect / clearTranscript / blockCopyLinesDown 全部按行记账。
   纯记账：不参与任何渲染/解析行为路径。
3. **DirtyRowJournalTest**（新增，12 用例）—— 锁定每个变更入口的记行语义；这组测试本身就是“交接规格”。
4. **TerminalFrameDiagnostics**（debug source set）——仅 Debug 变体提供每帧摘要日志；Release 变体使用空实现，不能通过 Intent 打开；
   `getLastRenderFrame()` 仍暴露上一帧交接快照供行级归属调试。

## 4. 等价性论证（行为保持）

- 渲染取值点从 render() 内部拉到 onDraw 入口，同一线程、渲染期间没有并发变更，值完全一致。
- palette 采集的是数组引用；调色板在渲染期间不会被替换（同线程）。
- 台账是附加记账（一个分支 + 位或 + 偶尔分配），不改变任何功能分支；CI Perf 负责确认 parser 不受影响。
- 唯一保留的“读带写副作用”（M3）明示不在此阶段动，原因见下。

## 5. 已知泥点 M3：为什么不改成只读访问器

render() 用 `screen.allocateFullLineIfNecessary(externalToInternalRow(row))` 取行；null 行会被分配一个
空白 TerminalRow（style 0）再绘制。改成“null 行跳过绘制”并非可证像素等价：
- 空白行当前会画出 style 0 的背景块；跳过则露出画布上的陈旧像素或兜底色；
- 滚动后新露出的行若恰好 null，行为差异取决于 View 背景与上一个帧的内容；
- 因此“绝对正确”的做法是保持现状，把该副作用与 M4 的解析器拆分一起处理（拆出后由模型层统一保证行非空）。

## 6. 下一阶段建议（拆碎点）

- **TerminalParser 抽取**：把 TerminalEmulator 的 escape 状态机（processByte/processCodePoint 及参数累积、
  模式位、title 栈）抽成纯函数式“字节 → 屏幕操作（ScreenOp）”，屏幕模型只消费 ScreenOp 流。
  台账就是 op 汇的天然基座（每个 op 记脏对应行）。
  保障：以既有 ControlSequenceIntroducerTest / CursorAndScreenTest / HistoryTest 为基线 + DirtyRowJournalTest
  校验 op 汇 === 直接改屏，两侧逐用例等价。
- **onScreenUpdated 归属**：让持有 ScreenOp 汇的解析器直接发出“rows X..Y changed”，替换现在的全量 invalidate
  （与终端渲染性能主线共用同一证据面）。
- **模块级拆碎候选**（评估后决定）：terminal-emulator 按 parser/model 分模块，terminal-view 的输入/选择/渲染
  三类职责拆分。

## 7. 验证记录

- 本地 `./gradlew :terminal-emulator:testDebugUnitTest`：BUILD SUCCESSFUL（12 新用例 + 既有全套件）
- 本地 `./gradlew :terminal-view:compileDebugJavaWithJavac`：BUILD SUCCESSFUL
- CI：Unit tests（ubuntu-latest / ubuntu-24.04-arm）、Perf（x86_64/aarch64）、Render smoke（SwiftShader）、
  Signed APKS experiment 全绿为合并前置条件