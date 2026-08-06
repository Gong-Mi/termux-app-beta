# STRATEGY: 主线程拆分线（perf/strategy-mainthread）

结论：**做**，低风险收益明确。与 PR #1（runOnUiThread 收敛 UI 操作，正确性）互补；与 PR #6（解析后台化）是同一方向但 #6 方案 fork 自回滚过（见下），本线从低风险项起步。

## 架构契约（基线事实）
TerminalSession.java:25 明示："All terminal emulation and callback methods will be performed on the main thread"。
管线：TermSessionInputReader 线程 (:133-148) → ByteQueue(4096B, :44) → MSG_NEW_INPUT (:142) → 主线程 handleMessage (:341-347) queue.read→mEmulator.append→notifyScreenUpdate → onTextChanged → TerminalView.onScreenUpdated (:453-499) → invalidate()

## 迁移候选（收益×风险排序）
- A. 剪贴板写（OSC 52）：TermuxTerminalSessionActivityClient.java:183-188 → ShareUtils.java:104-117（ClipboardManager binder 线程安全）——天然可后台，风险最低，**第一步做这个**
- B. 字体/颜色文件 I/O（读取侧）
- C. 会话列表 notifyDataSetChanged 合并（TermuxActivity 回调链）

## 坑清单
1. TerminalEmulator/ByteQueue/PTY fd 线程亲和——解析状态机不能跨线程，迁移必须限定在"不碰解析状态"的职责
2. activity 销毁竞态（TermuxActivity.java:399-410）——后台任务回调时 view 已 detach，需弱引用/生命周期守卫
3. 单测绕过 TerminalSession（测试直接驱动 Emulator，returnDefaultValues）——迁移后主线程契约变化测不到，需补并发回归测试
4. PR #6 方案（HandlerThread 异步解析）fork 自回滚（06f04d80 "restore 100% stability"）——解析后台化必须重做线程安全设计

## 第一步提交
剪贴板写迁移（OSC 52 拷贝 → 后台线程）+ 线程亲和约束文档（TerminalEmulator/ByteQueue/PTY 归属表）

## 验收
- 全量单测 x86 + ARM 腿绿
- 迁移项的行为不变式：OSC 52 拷贝时序、失败日志路径
- 真机验证项（副用户）：剪贴板粘贴可用性
