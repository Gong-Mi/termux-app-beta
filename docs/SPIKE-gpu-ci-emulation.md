# Spike: GitHub CI 上模拟 GPU 验证渲染的代价与可行性

日期：2026-08-15
分支：spike/gpu-emulation-probe（探针 workflow：gpu-probe.yml）

## 问题

STRATEGY-gles.md 延期理由之一是"CI 验不了视觉正确性"。若 GitHub CI 能提供
可用的模拟 GPU（软件 GLES），渲染等价性验证（至少 GLSurfaceView 通路、
崩溃回归、像素 diff 级别的正确性）就有自动化基础。本 spike 测：能不能、
多快、验到什么程度。

## 候选路线（研究结论）

| 路线 | 机制 | GPU 来源 | 代价 | 可行性 |
|------|------|---------|------|--------|
| A. ubuntu-latest + SDK emulator + swiftshader_indirect | KVM 加速 x86_64 模拟器 | SwiftShader 软件 GLES（CPU 仿真 GPU 指令集） | 每次 SDK+镜像下载 + 启动 | 高（正在实测） |
| B. ubuntu-24.04-arm + Redroid 容器 | 真 ARM Android 直跑容器（无虚拟化） | SwiftShader/llvmpipe 或 host GPU 透传（hosted runner 无独显，实际=软件） | docker pull 镜像 + boot | 中：Redroid 官方支持 arm64，但 hosted runner 无 GPU 透传，渲染仍是软件 |
| C. self-hosted runner（rothko/pandora） | 真机 | Mali-G720 真驱动 | 需常驻在线+电池/网络 | 高但非 CI 托管；pandora 当前不可达 |

## 路线 A 探针设计（gpu-probe.yml）

ubuntu-latest，分相计时：
1. runner 规格 + KVM 权限
2. SDK 安装（emulator + platform-tools + system-image android-36 google_apis x86_64）
3. assembleDebug（复用现有 CI 构建）
4. 模拟器 boot（swiftshader_indirect，无窗口）
5. GPU 身份（getprop ro.hardware.egl、dumpsys SurfaceFlinger）
6. 装 APK、冷启动 TermuxActivity、logcat、screencap 截图证据

## 实测结果

### Run 1 (#31886756059, b20af612/17587d05)：infra 错误
`adb: command not found`（platform-tools 装了但不在 PATH）。runner 只把
cmdline-tools 放 PATH。修复：$GITHUB_PATH。

### Run 2 (#31886938170, e7501a2a)：关键失败模式
- SDK 安装 + APK 构建：success（build ~2-3 分钟）
- avdmanager create avd 返回 0，但 emulator 报 `Unknown AVD name [probe]`、
  `no file probe.ini in $HOME/.android/avd` → AVD 未落到 emulator 搜索路径
- `adb wait-for-device` 无限等待（没有设备可连），步骤卡 27 分钟直到
  job 30 分钟 timeout 杀掉整个 run（conclusion=cancelled）
- 教训1：wait-for-device 模式在"模拟器根本没起"时 = 烧满 timeout
- 教训2：avdmanager 静默失败（exit 0 + 无产物）必须显式验证 probe.ini

### Run 3 (#31888381472, 466b234b)：全绿，实测数字
显式 ANDROID_AVD_HOME + probe.ini 存在性检查 + 15 分钟 boot 硬超时。

| 阶段 | 实测耗时 |
|------|---------|
| SDK 安装（emulator+镜像 ~1.5GB） | 63s |
| assembleDebug（全 ABI APK） | 134s |
| 模拟器 boot（SwiftShader, API 36, KVM） | **60s** |
| APK install | 14s |
| 端到端合计 | ~5 分钟 |

GPU 身份（dumpsys SurfaceFlinger）：
`GLES: Google (Google Inc.), Android Emulator OpenGL ES Translator
(Google SwiftShader), OpenGL ES 3.0 (OpenGL ES 3.0 SwiftShader 4.0.0.1)`
ro.hardware.egl=emulation。扩展表完整（EGL_image、texture_float、
astc_ldr 等）+ ANDROID_EMU_vulkan 翻译标记。

渲染证据：com.termux 进程 R 状态存活；截图 1080x2340 解码后 82% 纯黑
（终端背景）+ 8.3% 白（字形）+ 内容 bbox (49,52)-(1051,2312)、334 行字形
--真实文本渲染，非黑屏。artifact: gpu-probe-evidence。

Run 2 的 27 分钟卡死复盘：不是 SwiftShader 慢，是 AVD 没建成 ->
emulator 立即退出 -> `adb wait-for-device` 无设备可等 -> 烧满 job timeout。
boot 本身只要 60 秒。

## 边界（模拟 GPU 能验什么/不能验什么）

能验：
- GLSurfaceView/EGL 通路是否建立（代码不崩、surface 生命周期）
- 渲染等价性（像素级）：同一输入 -> 软件渲染截图 vs 真机截图 diff
- 回归保护：渲染线改动后 CI 上自动验证"没崩、帧循环活着"

不能验：
- Mali/Adreno 真驱动行为（tiler 架构 vs SwiftShader immediate-mode 差异）
- 真机性能（SwiftShader 速度不代表任何真 GPU）
- 驱动 bug 兼容性（Mali 驱动栈的 quirk 只能真机验）

## Verdict: VALIDATED（代价可接受，路线 A 成立）

### 代价（真实数字）
- 端到端 ~5 分钟/次（SDK 63s + build 134s + boot 60s + install 14s）
- 公共仓库 Actions 分钟免费；KVM 在 ubuntu-latest 可用（已实测）
- 一次性踩坑成本已付：AVD 路径 + adb PATH + wait-for-device 反模式（3 轮 CI 换来，配方已记录在 gpu-probe.yml）

### 可行（已证明）
- SwiftShader GLES 3.0 全扩展 translator 在 CI 上活着
- TermuxActivity 冷启动 + 终端文本真实渲染（截图解码验证 334 行字形）
- 每次渲染改动可自动跑：boot -> 装新 APK -> 截图 -> 像素断言

### 边界（不能验的）
- Mali/Adreno 真驱动 quirk（SwiftShader 是 translator，非 tiler 架构）
- 真机性能数字
- 真机驱动栈崩溃（如 rothko 的 Mali kbase）

### 建议
1. gpu-probe.yml 从 spike 分支毕业成可复用渲染烟囱测试（render-smoke.yml）：
   每次渲染相关 PR 自动跑，产出截图 artifact + 基础像素断言（非黑屏、
   字形 bbox 非空）
2. 渲染等价性（软件 vs GLES renderer 像素 diff）= 下一步在 #10 线的
   MVP 里做，probe 已铺好底座
3. Redroid/ARM 路线：hosted ARM runner 无 GPU 透传也跑不了 SDK 模拟器
   （无嵌套虚拟化），维持软件渲染为主，ARM 真机验收不可替代

对 STRATEGY-gles.md 的影响："CI 验不了视觉正确性"这条延期理由需要
修正为"CI 可验渲染回归（SwiftShader），真机视觉正确性仍需设备验收"。

## ARM64 Android + 容器化路线（2026-08-19 增补）

这里的目标不是为每个 Android API 维护一个 Android 容器，而是把测试
runner 容器化，把 Android 版本作为可替换的 system image：

```text
containerized test runner
  -> Cuttlefish/emulator launcher + adb + verifier
  -> selected ARM64 Android system image
  -> ARM64 Android guest
  -> optional KVM + DRM/virgl/GLES backend
```

### 方案分层

| 方案 | Android 形态 | GPU 证据 | 维护边界 | 当前结论 |
|------|--------------|----------|----------|----------|
| ARM64 Cuttlefish + Docker | ARM64 Android guest | `drm_virgl` 可验 host GLES/Canvas/RenderThread/SF；不是 Mali/Adreno/Vulkan | runner 镜像、启动器、system-image manifest；不维护 Android framework | 首选 ARM 容器化路线 |
| ARM64 Android Emulator | ARM64 system image | ARM64 host 官方路径主要是 SwiftShader；不是真手机 GPU | emulator 工具 + image matrix | 可作 ARM ABI/framework 补充 |
| Firebase ARM virtual device | Google 托管 ARM guest | 适合测试矩阵；GPU/ADB 证据受服务边界限制 | 只维护 model/API/test 配置 | 云端 fallback |
| Redroid ARM64 | Android userspace in host container | 取决于 host GPU/kernel，不能自动得到真实手机 driver | kernel/binder/GPU/namespace 兼容层 | 不作为主路线 |
| 真实 Mali/Adreno 手机 | 物理 Android | 真 driver/HWC/RenderThread/GPU | 设备刷机、连接、电源、版本 | 最终 acceptance |

### 不应做的事情

- 不为 API 33/34/35/36 各写一个 Android Dockerfile；
- 不把 Cuttlefish virgl 结果写成 Mali/Adreno 或 Vulkan 结果；
- 不把 ARM64 guest 跑起来写成 ARM GPU 验收；
- 不把容器看成消除了 `/dev/kvm`、`/dev/dri/renderD*` 和 render group 依赖；
- 不在没有 ARM64 runner 和 capability evidence 时添加假绿色的 workflow。

### ARM64 Cuttlefish 的必要 host 能力

```text
/dev/kvm
/dev/dri/renderD*
render group access
network/TAP capability
```

Docker 只是固定测试 runner 和 host tools；KVM/DRM 仍然来自宿主机，
因此这不是完全隔离的 Android 容器。

### 当前本机 probe

当前 Android/Termux host 为 `aarch64`，但实测：

```text
/dev/kvm: absent
/dev/dri/card0: present
/dev/dri/renderD*: absent
```

所以当前设备不能被报告为 ARM64 Cuttlefish/KVM/virgl runner。它的 Android
GPU 属性存在，不等于容器可获得 Linux DRM render node。

### 分阶段施工

1. 先定义 system-image manifest、API/ABI matrix、SHA-256 和 evidence schema；
2. 在 ARM64 Linux runner 上只做 KVM/DRM/container capability probe；
3. capability 通过后再启动 ARM64 Cuttlefish，安装 exact APK，采集 logcat、
   SurfaceFlinger、gfxinfo、截图和 frame diagnostics；
4. 单独启用 virgl/GLES，声明为 virtual-GPU GLES evidence；
5. 最后用真实 Mali/Adreno 设备补 physical GPU acceptance。

官方依据：

- Cuttlefish: https://source.android.com/docs/devices/cuttlefish
- Cuttlefish Docker: https://source.android.com/docs/devices/cuttlefish/docker
- Cuttlefish GPU: https://source.android.com/docs/devices/cuttlefish/gpu
- Emulator ARM64 host: https://developer.android.com/studio/releases/emulator
- Firebase ARM virtual devices: https://firebase.google.com/docs/test-lab/android/avds
