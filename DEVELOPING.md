# 开发说明

面向改代码的人。用户请看 [README.md](README.md)。

## 构建

全过程在这台 Android 手机上的 Termux 里完成，不需要 Android Studio。

```bash
./build.sh
```

首次会自动下载 `android.jar`（约 64MB）并生成签名密钥。流程：

```
aapt2 compile → aapt2 link → javac → d8 → pack.py 打包 → apksigner 签名 → 合规校验
```

### pack.py 为什么必要

本机没有 `zipalign`。Android 11+ 要求 `resources.arsc` **不压缩**且 **4 字节对齐**、
`AndroidManifest.xml` 也不压缩——用普通 zip 工具重打包会把它们压掉，装上直接报
「安装包与系统不兼容」（MIUI 显示为 `安装失败(-124)`）。

`pack.py` 按 zipalign 的规则对齐，`pack.py verify` 在每次构建末尾强制检查这条不变量。
单独校验已有 APK：

```bash
python3 pack.py verify --apk handpan-autoplay.apk
```

## 源码结构

```
app/src/com/handpan/autoplay/
├── MainActivity.java              # 演奏页（曲目 + 全部演奏参数 + 解析/保存）
├── SongsActivity.java             # 曲目库页（导入 / 列表 / 清空）
├── SettingsActivity.java          # 设置页（权限 / 校准 / 试弹）
├── PracticeActivity.java          # 练习 / 录制页
├── PadBoardView.java              # 九键演奏板（计时圈、命中反馈）
├── PracticeSession.java           # 练习判分（纯 Java，可测）
├── RecordingStore.java            # 录音存取（Android 存储层）
├── RecordingCodec.java            # 录音文件格式（纯 Java，可测）
├── Session.java                   # 三页共享的当前曲目
├── Loader.java                    # 后台解析 + 回主线程回调
├── Ui.java                        # 共用小工具
├── HandpanAccessibilityService.java  # 注入手势（核心能力）
├── OverlayController.java         # 悬浮校准层 + 悬浮控制条（上一首/暂停/下一首/停止）
├── Playback.java                  # 实时调度，按毫秒派发（暂停/继续）
├── ScheduleClock.java             # 播放计时（纯 Java，可测）
├── PlaylistNavigator.java         # 上/下一首索引（纯 Java，可测）
├── TapPlanner.java                # 音符 → 琴键点击计划（含和弦分组）
├── MidiParser.java                # SMF 解析（tempo map / running status / SMPTE）
├── PitchDetector.java             # YIN 音高检测（音频 → 音符）
├── AudioDecoder.java              # MediaCodec 解码 / 手写 WAV 解析
├── JianpuParser.java              # 简谱文本解析
├── SongLoader.java                # 格式分发 + 单音旋律提取
├── SongLibrary.java               # 已导入曲目（含持久化 URI 权限）
├── SongCache.java                 # 解析结果存档（Android 存储层）
├── SnapshotCodec.java             # 存档格式读写（纯 Java，可测）
├── AutoDetect.java                # 自动模式：识调 + 识速并写回设定
├── KeyDetector.java               # 自动识调（12 个调）
├── TempoEstimator.java            # 自动识速（从起音间隔推断 BPM）
├── ScaleMapper.java               # 音高 → 简谱音级
├── PadMapper.java                 # 音级 + 八度 → 9 个琴键中的哪一个
├── AppPrefs.java                  # 坐标与设置持久化
└── RawNote.java                   # 音符数据类
```

## 页面之间的状态

三个 Activity 之间没有直接引用：当前曲目放在 `Session`（进程内单例，`version()` 自增供各页判断是否要刷新），
演奏参数放在 `AppPrefs`（演奏页控件改动即写回，并立即重画预览）。演奏页 `onResume` 比对 `Session.version()`，
变了才同步控件并重画。任一页都可以独立改动，不牵动其它页。

**两种解析模式**：`AutoDetect.apply()` 是"自动模式"的唯一入口（导入文件与【自动解析】按钮都走它），
识别调性与曲速并覆盖设定。【手动解析】刻意不调用它——它不碰 `AppPrefs`，只把文件按当前 BPM 重新解析一遍，
因此你的设定一定留得住。对简谱文本来说 BPM 直接决定时值，所以两种模式的结果确实不同；
对 MIDI / 音频来说差别在于"调性有没有被识别结果覆盖"。

**参数为什么放在演奏页**：调性和弦上限这些是会边调边看的，放在曲目名下面改动即时反映到预览，
比"跳到设置页改完再退回来"直接得多。设置页只留一次配好就不再动的东西（权限、校准）。

顺序 / 随机演奏的衔接在演奏页：一次演奏完成且模式不是"单曲"时，从 `SongLibrary` 取下一首，
优先读存档（秒切），没有存档才回到解析流程，然后重新走倒计时。

## 关键设计

### 九个键是带八度的

| 位置 | 键 | 含义 |
|---|---|---|
| 上排 3 个 | `1̇ 2̇ 3̇` | 上方带点 = 高八度 |
| 中排 5 个 | `3 4 5 6 7` | 不带点 = 中音区 |
| 下排 1 个 | `6̣` | 下方带点 = 低八度 |

中音区**没有 1 和 2**，所以 do、re 只能落在高八度键上；`3` 和 `6` 各有两个八度可选。
选键必须同时用**音级和八度**，只按音级映射会把所有 do 点到同一个键，旋律轮廓就没了。

`PadMapper` 以旋律最低音为锚算出 do 音高，把每个音吸附到最近的全音阶级数并得到八度偏移，
再在候选键里选八度最接近的。

> 无法回避的限制：中音区没有 do、re，这两个音会被抬到高八度键。每个音的**音高本身是对的**，
> 只是落到了上面一排。换任何映射方案都一样，因为乐器只有一个 do 和一个 re。

### 和弦

乐器支持同时按多个键。`TapPlanner` 把起音时间落在 50ms 内的音符分成一组，
`HandpanAccessibilityService.tapAll()` 用**一次多 stroke 手势**同时按下（真多点触控，不是快速连点）。
一组最多 4 个键，再多会糊。

### 音频转谱的定位

YIN 是**单音**检测器，喂整首混音会锁到贝斯和底鼓。分析频段收窄到 **120–1000 Hz** 后
（实测一首中文流行歌：65–2100Hz 得到 157 个音、平均 G2 的贝斯线；120–1000Hz 得到 35 个音、
落在 B2–F#4 的人声区）。即便如此，整首流行歌也只能抓到旋律的一部分——**要弹完整的歌请用 MIDI**。

### 校准为什么用悬浮窗

直接盖在游戏上点，拿到的就是屏幕真实坐标，不存在横竖屏、状态栏、导航栏造成的偏移。

## 已知限制

- 只支持单音旋律输入；和弦会被分组，但复调音乐仍会失真。
- 音频最多分析 5 分钟。
- `dispatchGesture` 每次点击有几十毫秒系统开销，极快的十六分音符不稳。
- 目标游戏若带内核级反作弊或无障碍监测，可能收不到注入的点击。

## 测试

`tests/run.sh` 编译并运行纯 JVM 测试（不需要设备）：

```
TestCore        51 项   MIDI 解析 / 音高检测 / 识调 / 识速 / 九键映射 / 和弦 / 计时 / 切换索引 / 存档
TestPractice    29 项   判定窗口 / 空按不计分 / 漏拍自动 Miss / 最近目标选择 / 准确率
TestRecording   14 项   录音格式往返 / 坏行与越界跳过 / 版本校验
TestRoundTrip    4 项   琴键→音符→琴键 精确往返（12 调 × 7 八度 × 9 键）
```

MIDI 固件由 `tests/gen_fixtures.py` 逐字节生成，不联网、不依赖库。需要 Android 的部分
（Context / Handler / MediaCodec / 无障碍注入）只能在设备上验证，不在覆盖范围内。

## 验证

算法模块（`MidiParser` / `PitchDetector` / `PadMapper` / `KeyDetector` / `TapPlanner`）
由独立测试脚本验证，**74 项断言**全部通过：

| 项目 | 结果 |
|---|---|
| MIDI 音阶音高/时间（C 大调 7 音，500ms/音） | PASS |
| MIDI tempo 变化（960tick 处 500000→250000） | PASS（第 2 音 750ms 起，250ms 长） |
| MIDI running status / format 1 双轨 | PASS |
| 截断、非法变长量 → 抛 IOException 不崩溃 | PASS |
| YIN 检出 A4/C5/E5（含静音前导） | PASS（69/72/76，时间误差 <80ms） |
| 负例：纯静音 / 白噪声 → 0 个音 | PASS |
| 九键八度映射（E4→中音3、E5→高音3、A3→低音6、9 键全可达） | PASS 26/26 |
| 自动识调（12 个调全对） | PASS |
| 自动识速（500/666/400/250ms 间隔分别还原为 120/90/150/120 BPM；无节奏信息时返回 0） | PASS |
| 和弦分组（do-mi-sol → 一次三指；同键不重复按；上限 1/4/6/9 均生效；0 或负数按 1 处理） | PASS 8/8 |
| 点击计划（单个音也必须有输出——曾因整数溢出恒为空） | PASS |
| 存档格式（500/3000 音符往返一致、版本不符/缺头/空内容返回 null、坏行跳过、换行清洗） | PASS 11/11 |
| 播放计时（暂停期间时间冻结、继续从冻结点接续、连续 5 次暂停/继续不漂移、reset 清零） | PASS 16/16 |
| 曲目切换索引（双向环绕、当前曲目不在列表、越界 index、空列表、随机不返回自己） | PASS 17/17 |
| APK 打包合规（resources.arsc 不压缩 + 4 字节对齐） | PASS |

`SongLibrary` 依赖 Android 的 SharedPreferences 与 org.json，无法在纯 JVM 中测试，仅有编译与打包检查覆盖。
