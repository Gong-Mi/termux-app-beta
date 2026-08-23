# Regression test matrix

This index classifies the repository's executable tests by the failure boundary they protect.
A test belongs to the layer where its evidence stops; a green JVM test is not Android UI or
physical-device acceptance.

## 1. Selection, cursor, and clipboard entrypoints

| Test | Module/layer | Contract |
|---|---|---|
| `TerminalSelectionCoordinatesTest` | `terminal-view` / JVM | Renderer baseline, floor-based pixel-to-row mapping, transcript viewport offset |
| `TerminalSelectionRangeTest` | `terminal-view` / JVM | Selection controller protocol `[y1,y2,x1,x2]` is converted to render-frame `[x1,y1,x2,y2]` |
| `TerminalActionModePolicyTest` | `terminal-view` / JVM | Xiaomi/Redmi/POCO primary action mode; other vendors floating action mode |
| `ClipboardPasteTargetTest` | `support_ui` / JVM | Explicit session wins; null requested session falls back to current session |
| `ClipboardAndStorageInstrumentedTest` | `app` / Android emulator instrumentation | API-scoped clipboard write/read boundary, storage capabilities, runtime permission state, MediaStore lifecycle |

Clipboard evidence is split deliberately:

- API 32 instrumentation proves `setPrimaryClip()` plus exact in-process read-back.
- API 33+ instrumentation proves the write call is accepted; Android clipboard privacy prevents
  a shell/instrumentation UID from using `getPrimaryClip()` as a foreground-app read-back oracle.
- `ClipboardPasteTargetTest` proves application callback routing only; it does not prove
  `ClipboardManager`, SystemUI, Gboard, MIUI overlay, or physical-device behavior.

## 2. Render-frame, mailbox, and lifecycle handoff

| Test | Module/layer | Contract |
|---|---|---|
| `TerminalRenderFrameTest` | `terminal-view` / JVM | Frame snapshot fields and selection/cursor projection invariants |
| `TerminalScreenSnapshotViewportTest` | `terminal-emulator` / JVM | A snapshot reports exactly the external rows it owns; an older viewport cannot be treated as covering a newer selection |
| `RenderFrameMetricsTest` | `terminal-view` / JVM | Published/drawn/acknowledged/dropped/coalesced frame accounting |
| `TerminalRenderMailboxTest` | `terminal-view` / JVM | Latest-value mailbox replacement and handoff behavior |
| `TerminalRenderSessionGateTest` | `terminal-view` / JVM | Stale session callbacks are rejected after detach/reattach |
| `TerminalParserWorkerTest` | `terminal-emulator` / JVM | Parser-worker command and frame publication behavior |
| `TerminalTextChangeCoalescerTest` | `terminal-emulator` / JVM | Text-change callback coalescing |
| `DirtyRowJournalTest` | `terminal-emulator` / JVM | Dirty-row mutation journal bookkeeping |
| `TerminalFeedDirtyJournalTest` | `terminal-emulator` / JVM | Feed/parser changes reach dirty-row accounting |
| `TerminalInputQueueDrainTest` | `terminal-emulator` / JVM | Input queue drain and burst handling |
| `TerminalSessionExitCoordinatorTest` | `terminal-emulator` / JVM | Exit, finish, and cleanup state transitions |

These tests do not by themselves prove Canvas, RenderThread, GPU, or Android View lifecycle
behavior. Those require emulator render smoke or physical-device evidence.

## 3. Terminal parser and screen semantics

| Test | Module/layer | Contract |
|---|---|---|
| `ByteQueueTest` | `terminal-emulator` / JVM | Byte queue operations and boundaries |
| `ControlSequenceIntroducerTest` | `terminal-emulator` / JVM | CSI parsing |
| `CursorAndScreenTest` | `terminal-emulator` / JVM | Cursor and screen state |
| `DecSetTest` | `terminal-emulator` / JVM | DEC private modes |
| `DeviceControlStringTest` | `terminal-emulator` / JVM | DCS handling |
| `ApcTest` | `terminal-emulator` / JVM | APC handling |
| `OperatingSystemControlTest` | `terminal-emulator` / JVM | OSC handling |
| `KeyHandlerTest` | `terminal-emulator` / JVM | Key translation and terminal input semantics |
| `UnicodeInputTest` | `terminal-emulator` / JVM | Unicode input handling |
| `HistoryTest` | `terminal-emulator` / JVM | Transcript/history behavior |
| `ScreenBufferTest` | `terminal-emulator` / JVM | Screen buffer operations |
| `ScrollRegionTest` | `terminal-emulator` / JVM | Scroll-region semantics |
| `RectangularAreasTest` | `terminal-emulator` / JVM | Rectangular area operations |
| `TerminalRowTest` | `terminal-emulator` / JVM | Row mutation and cell semantics |
| `TerminalTest` | `terminal-emulator` / JVM | General terminal behavior and invariants |
| `ResizeTest` | `terminal-emulator` / JVM | Resize behavior |
| `TextStyleTest` | `terminal-emulator` / JVM | Style encoding and decoding |
| `TerminalRowScanEquivalenceTest` | `terminal-emulator` / JVM | Row scan equivalence, including wide/combining text |
| `WcWidthTest` | `terminal-emulator` / JVM | Unicode cell-width semantics |

## 4. Storage, application, and data-path contracts

| Test | Module/layer | Contract |
|---|---|---|
| `StoragePermissionCapabilityTest` | `termux-shared` / JVM | Permission capability applicability and granted-state policy |
| `TermuxDataPathUtilsTest` | `termux-shared` / JVM | Termux data path construction and filtering |
| `TermuxConstantsTest` | `app` / JVM | Application constants and package-level contracts |
| `TermuxActivityTest` | `app` / JVM | Activity-side deterministic behavior |
| `FileReceiverActivityTest` | `app` / JVM | File receiver intent/data handling |
| `ExampleInstrumentedTest` | `termux-shared` / Android instrumentation | Basic Android instrumentation wiring only |

The API-specific permission and MediaStore runtime evidence is owned by
`ClipboardAndStorageInstrumentedTest`; the JVM capability test does not replace it.

## 5. Performance and benchmark-only tests

| Test | Module/layer | Contract |
|---|---|---|
| `PerfBenchmarkTest` | `terminal-emulator` / JVM benchmark | Parser/terminal throughput measurements |

Benchmark tests are not correctness gates and must not be used as evidence for Android main
thread, RenderThread, GPU, or physical-device performance.

## Evidence ladder

```text
JVM tests
  → deterministic protocol, parser, policy, and state-machine evidence

Android instrumentation/emulator
  → Android API, package, permission, MediaStore, clipboard write, View/runtime smoke evidence

SwiftShader/render smoke
  → packaged APK installation, emulator Canvas/render-frame evidence

Pandora/Xiaomi physical device
  → MIUI/HyperOS ActionMode, SystemUI/Gboard/universalClipboard, real layout and input evidence
```

A regression report must name the highest layer actually exercised. Passing a lower layer does
not upgrade the claim to the next layer.
