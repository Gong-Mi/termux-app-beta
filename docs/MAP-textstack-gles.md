# 文本栈参考映射：CJK 宽字符 / BMP 外汉字 / ZWJ emoji

目的：GLES renderer（#10 线）的字形 atlas 设计不重新发明，直接镜像系统文本栈
（minikin + harfbuzz + skia）的已量产设计。参考仓库在 ~/textstack/（本地克隆，
2026-08-15 拉取）。

参考版本：
- minikin 05a978c（main, 2025-03-08）android.googlesource.com/platform/frameworks/minikin
- skia 22fdd3b（google/skia, 2026-08-15）
- harfbuzz google/harfbuzz main（2026-08-15）
- fonts.xml: frameworks/base/data/fonts/fonts.xml（322 family 条目）

## 1. CJK 宽字符

| 层 | 系统实现 | 函数 | termux 现状 |
|----|---------|------|------------|
| 列宽判定（终端语义） | 无（终端层职责） | - | WcWidth.java:368 WIDE_EASTASIAN 表（jquast/wcwidth 对齐）:553 判定 |
| 字体选择 | minikin FontCollection::itemize | FontCollection.cpp:719 | 无（依赖 Canvas.drawTextRun） |
| run 切分 sticky 规则 | itemize 内 | :758-775 shouldContinueRun | 无 |
| 字形宽度 | harfbuzz advance + minikin measure | - | TerminalRenderer:47 measureText("X") |

设计结论：GLES renderer 的 atlas slot 宽度 = WcWidth * cellW。CJK 一律
2*cellW，无需特殊字形逻辑；字体 fallback 交给 itemize 同款规则。

## 2. BMP 外汉字（U+2xxxx 等）

| 屓 | 系统实现 | 函数/位置 |
|----|---------|----------|
| UTF-16 代理对解码 | itemize U16_NEXT 循环 | FontCollection.cpp:740/750 U16_NEXT, :741 REPLACEMENT_CHARACTER |
| 字体覆盖查询 | FontFamily::getCoverage (SparseBitSet) | FontCollection.cpp:773/778 getFamilyForChar :463 |
| atlas 索引 | **SkPackedGlyphID（glyphID 键，非 codepoint）** | SkStrike.h:54/103 digestFor/prepareImages(SkPackedGlyphID) |

设计结论：atlas 必须以 shaping 后的 glyphID 索引（Skia 同款），codepoint 索引
在代理对/连字下必错。孤代理 → REPLACEMENT_CHARACTER U+FFFD（minikin 同款）。

## 3. ZWJ emoji 序列

| 层 | 系统实现 | 函数/位置 |
|----|---------|----------|
| 序列发现 | harfbuzz cluster（monotone levels） | hb-buffer-verify.cc:66 HB_BUFFER_CLUSTER_LEVEL_IS_MONOTONE |
| emoji 属性判定 | minikin Emoji.cpp | isEmoji:21 u_hasBinaryProperty(UCHAR_EMOJI), isEmojiModifier:25, isEmojiBase:31（+0x1F91D/0x1F93C 特例保留） |
| emoji run 聚合 | itemize isColorEmojiFamily 分支 | FontCollection.cpp:766-774（longest family）, :785-799 isEmojiBreak + intersection 聚合 |
| isEmojiBreak | FontCollection.cpp:51 | prevCh/ch 判定（VS16 延长 run 等） |
| 位图字形 | NotoColorEmoji CBDT/CBLC 格式 | fonts.xml und-Zsye family |
| atlas 上传 | 三态 MaskFormat：A8/565/ARGB | GrAtlasManager.cpp:164 addGlyphToAtlas, GrDrawOpAtlas.h:268-271 三 atlas 并行 |
| 淘汰 | plot 淘汰 + genID 失效 | GrDrawOpAtlas.h:54-56（<25% plot 使用则停用高 index page） |

设计结论：
- emoji 序列 = itemize 的 color-emoji family run，无需自研 cluster 判定
- 位图字形进 ARGB atlas（PNG 直接上传，不经灰度栅格化）
- 淘汰用 plot/genID 模型（GrDrawOpAtlas 同款），不用 LRU

## 规范上游（每层的正式标准，URL 已验证 200）

把系统工作迁到 APP 内 = 按 open spec 重新实现，不是逆向私有逻辑：

| 层 | 正式规范 | 数据文件 | 实现参考 |
|----|---------|---------|---------|
| CJK 列宽 | UAX #11 East Asian Width | unicode.org/Public/UCD/latest/ucd/EastAsianWidth.txt | WcWidth.java（表已对齐） |
| 代理对/U+FFFD | Unicode Standard ch.3 | - | FontCollection.cpp:740-755 |
| emoji 属性 | UTS #51 | /Public/emoji/16.0/emoji-zwj-sequences.txt（1489 条 RGI 序列） | Emoji.cpp u_hasBinaryProperty |
| emoji run 聚合 | UTS #51 §ZWJ | 同上 | FontCollection.cpp:766-799 |
| 位图字形 | OpenType CBDT/CBLC | - | GrAtlasManager ARGB atlas |
| atlas/EGL/纹理 | Khronos EGL 1.5 / GLES 3.0 | - | GrDrawOpAtlas plot/genID |

注意 emoji-zwj-sequences.txt 的正确路径是 /Public/emoji/<ver>/ 而非
/ucd/emoji/（后者 404）。ICU u_hasBinaryProperty 是 Emoji Property 的
权威实现（UTS #51 定义，UCD emoji-data.txt 数据）。

## 接口边界（renderer 只需实现这些）

```
输入: PTY 字节流 -> TerminalBuffer 行（含 style run）
     + WcWidth 列宽（已有）
渲染单元: draw run = (字体系列, style, 列区间, glyphID 列表)
     - shaping: 若自建, 用 harfbuzz + fonts.xml fallback 链
     - 若混合（方案 B）: Canvas.drawTextRun 保留系统 shaping, atlas 只缓存 cell 合成
atlas: glyphID -> (page, plot, rect), MaskFormat A8(文本)/ARGB(emoji)
淘汰: plot genID, evict 回调重排
```

## 对 #10 MVP 的修正

STRATEGY-gles.md 坑 3 原文："字形 atlas/fallback/emoji 需要自建缓存，系统引擎
优势丢失"。基于本映射：
- fallback 规则本身可从 minikin itemize（FontCollection.cpp:719-816）镜像，且
  Canvas/Paint 路径下系统已在做（drawTextRun）——不需要重写，只需在自建
  shaping 时复刻
- emoji 处理有量产参考（isColorEmojiFamily run + ARGB atlas），无未知设计风险
- 坑 3 剩余真实成本 = 实现工作量（atlas 上传/淘汰/内存预算），非设计不确定性

## 参考树位置

```
~/textstack/fwbase-minikin/libs/minikin/   # itemize/Emoji/CmapCoverage
~/textstack/skia/src/gpu/ganesh/text/      # GrAtlasManager, TextStrike, GlyphData
~/textstack/skia/src/gpu/ganesh/GrDrawOpAtlas.*  # plot/genID 淘汰模型
~/textstack/skia/src/core/SkStrike*        # glyphID 键 strike 缓存
~/textstack/harfbuzz/src/                  # cluster/shape
~/textstack/fwbase/data/fonts/fonts.xml    # 322 family fallback 链
```
