# STRATEGY: 渲染框架线（render/strategy-gles）

结论：**短期延期/不做**。软件渲染现状有免费优势（系统字体引擎），先期工作（opengles-renderer）0 次 CI 且运行期回归明显。若做，MVP 边界明确如下。

> **2026-08-15 更新**：坑 5（"CI 验不了视觉正确性"）已被实测推翻一半。
> spike（docs/SPIKE-gpu-ci-emulation.md，run #31888381472）+ 毕业后的
> render-smoke CI（PR #25，master f51ae075）证明：GitHub CI 的
> KVM+SwiftShader 可跑模拟器渲染烟囱（boot 60s、端到端 ~5min、含像素
> 断言）。渲染回归（崩溃/黑屏/像素 diff）可自动验证；"视觉正确性"
> 中只剩真机驱动 quirk 与性能仍需设备验收。原延期理由更新为：
> **"CI 可验渲染回归，真机视觉正确性仍需设备验收；字形 atlas/
> fallback/emoji 自建成本仍是主要障碍（坑 3 未变）"。**
> 若启动 MVP，第一步即：软件渲染 vs GLES renderer 的像素 diff 测试
> 跑在 render-smoke CI 上（基建已就位）。

## 现状（file:line）
- TerminalView.java:46 extends View（纯软件渲染）；onDraw :1007 → mRenderer.render :1017
- TerminalRenderer.java（249 行）：:47 measureText 定字宽、:57-157 逐行逐 run 切分、:159-240 drawTextRun（背景 :202、光标 :208、bold/underline 等 :165-227、canvas.drawTextRun :236 走系统字体引擎——fallback/emoji/组合字符免费）
- 滚动：mTopRow 状态机 TerminalView.java:450-492、fling :207

## 先期工作
- fork feature/opengles-renderer：GLSurfaceView 改造 + 渲染循环 + "fix GLES renderer crashes" 提交；**0 次 CI 运行**；静态字段引用一致（推断可编译）但运行期回归明显
- vulkan 相关工作与 rust 引擎耦合（不搬 rust 就没法搬）

## 坑清单
1. GLSurfaceView/EGL 生命周期 vs Activity 重建、surface destroyed 竞态（GLES 崩溃史）
2. 旋转/preTransform（MIUI 修复史）——MVP 明确不碰
3. 字形 atlas/fallback/emoji 需要自建缓存，系统引擎优势丢失
4. 颜色管理（P3/HDR）复杂度
5. CI 验不了视觉正确性——真机副用户方案成本高，无法自动化验收

## 若做（MVP 定义）
GLSurfaceView + 背景色 + 全 BMP 字形 atlas + RENDERMODE_WHEN_DIRTY；不碰旋转/IME/文本选择手柄。真机验收清单另行定义。

## 验收
MVP 之前：无代码提交。本线 PR 仅作跟踪与决策记录。
