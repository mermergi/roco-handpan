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
├── PracticeActivity.java          # 练习 / 录制控制面板
├── GameOverlay.java               # 游戏上的悬浮层：转发触摸 + 计时圈 + 判分
├── PadHitTester.java              # 坐标 → 哪个琴键（纯 Java，可测）
├── PadGeometry.java               # 从校准间距推算琴键半径（纯 Java，可测）
├── SpeedClock.java                # 练习倍速的虚拟时钟（纯 Java，可测）
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

**坐标空间**：校准存的是**屏幕坐标**（`getRawX/getRawY`），悬浮层的 `View` 坐标只在窗口原点等于屏幕
原点时才一致。所以触摸一律用 `getRawX/getRawY` 匹配，绘制前用 `getLocationOnScreen()` 把屏幕坐标
换算到视图坐标——否则悬浮层一旦被系统内缩，圈就会整体画偏。

**圈的尺寸**：不写死。`PadGeometry` 用校准出的**最近两个键的间距**推算半径（琴键不会重叠，
所以间距的一半是半径上界），再夹在 12~34dp 之间。横向 56px 间距算出约 21px 半径，正好贴着游戏里的键。

**练习/录制为什么是悬浮层，以及它的硬伤**：游戏的声音出不来，用户必须在游戏里弹，所以 APP 只能
盖一层透明悬浮窗。但 **`dispatchGesture` 注入的触摸会送到最上层窗口**——也就是悬浮层自己。
不处理的话，转发出去的点击会被自己接住，游戏一个都收不到（v2.8 就是这么坏的）。

拦截与放行在 Android 上互斥，唯一手法是**转发的一瞬间把悬浮层设成 `FLAG_NOT_TOUCHABLE`**，
让注入了的触摸落到底下的游戏，几十毫秒后再恢复。代价是让开那一瞬的按键记不到。
所以练习模式提供"只提示"选项：完全不接管触摸，游戏收到的输入和没装 APP 时一模一样。

**两个窗口，不是一个**：提示层（全屏、可能被设成不可触摸）和控制条（小窗、**永远可触摸**）必须
分开。第一版把结束按钮和提示层放在同一个窗口里，于是"只提示"模式下整窗不可触摸 →
按钮一起失灵。现在按钮独享一个小窗，不受提示层让路的影响。

**音符列表的排布规则**（`LaneLayout`，纯 Java，13 项测试）有两条：
**不许重叠**（相邻音只差 250ms，在窄条里只映射出 14dp，方块却有 30dp），
**不许摊开**（纯按时间摆，音一稀疏就中间一大片空白，浪费本来就不长的条）。
所以每个方块被夹在 `[前一个右边+gap, 前一个右边+maxGap]` 之间——既压不叠，也散不开。
代价是横向位置不再严格等于时间，计时由琴键上的圈负责。

**音符列表的参数是分开的**：列表用 `LANE_LEAD_MS=2800`、最多 12 个，圈用 `RING_LEAD_MS=1100`、
最多 6 个。列表窗口宽度是在悬浮条布局完成后**实测它的宽度再对齐**的，所以两者一样长。
第一版图省事共用了圈的提前量，结果列表又长又快又几乎看不到后面的音。

**列表要"点一个消一个"就必须接管触摸**：不接管时 APP 看不到任何按压，条目只能等超时消失，
所以练习模式的接管默认是开的。

**倍速放在面板而不是悬浮条**：悬浮条上那个循环按钮只能往上翻（1→1.25→1.5→2→0.5），
要放慢得转一圈，而且练习时人在游戏里根本看不清。现在改成面板上的 Spinner，直接选，
**能调慢**；并与主页面「速度」共用 `AppPrefs`，避免两套速度。倍速在开始前生效。

`SpeedClock` 仍然只缩放"虚拟时间"、不改谱面时刻——这个设计保留了，将来要做中途变速也不用重建会话。

**显示约定**：中音区（3 4 5 6 7）用普通数字，中音区以外的键（高音 1 2 3、低音 6）用带圈数字
①②③⑥ —— 对应游戏里数字上下的那一点，但只占一个字符。

**编号与颜色**：`PracticeSession.upcomingGroups()` 按时间把待按的音分组（30ms 内算同时），
从待判队列头开始编 1、2、3…，同一组的所有琴键共用同一个编号和颜色（调色板 8 色循环）。
编号跟着队列走而不是整首歌，所以一直在按的都是 1 号。

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
TestHitTester   10 项   触摸坐标 → 琴键匹配（范围、取最近、未校准、边界）
TestPadGeometry  8 项   琴键半径推算（过密/过疏被夹住、只有一个键、完全重合、真实布局）
TestGroups      15 项   待按分组（同时按合并、编号跟队列、同键重复、过期丢弃）
TestSpeed       14 项   倍速虚拟时钟（变速/循环切换/时间倒退/超长间隔截断）
```

MIDI 固件由 `tests/gen_fixtures.py` 逐字节生成，不联网、不依赖库。需要 Android 的部分
（Context / Handler / MediaCodec / 无障碍注入）只能在设备上验证，不在覆盖范围内。

## 发版

Release 说明是**写给下载的人看的**，不是开发日志。

- 只说「用户拿到什么变化」，三五行以内
- 不写实现细节、不写测量数据、不写代码路径、不写排查过程
- 技术细节留在 commit message 和本文档里

改完之后：

```bash
./tests/run.sh && ./build.sh
# 提交源码 → 建 Release → 传 handpan-autoplay.apk
```

APK 只放 Release，不放代码树。

## 验证

算法模块（`MidiParser` / `PitchDetector` / `PadMapper` / `KeyDetector` / `TapPlanner` /
`LaneLayout` / `ScoreLink` 等）由 `./tests/run.sh` 验证，**242 项断言**全部通过。

| 项目 | 结果 |
|---|---|
| MIDI 音阶音高/时间（C 大调 7 音，500ms/音） | PASS |
| MIDI tempo 变化（960tick 处 500000→250000） | PASS（第 2 音 750ms 起，250ms 长） |
| MIDI running status / format 1 双轨 | PASS |
| 截断、非法变长量 → 抛 IOException 不崩溃 | PASS |
| YIN 检出 A4/C5/E5（含静音前导） | PASS（69/72/76，时间误差 <80ms） |
| 负例：纯静音 / 白噪声 → 0 个音 | PASS |
| 九键音位表（do=C4 时 = C4 D4 E4 F3 G3 A3 B3 A2） | PASS |
| 弹不到的音的替换阈值（A#3→低音6，B3 保 1 级） | PASS |
| 音区选择（三度以内不挪、宽音域取偏移最小） | PASS |
| 自动识调（含五声性旋律必须看整首编曲） | PASS |
| 自动识速（500/666/400/250ms 间隔分别还原为 120/90/150/120 BPM；无节奏信息时返回 0） | PASS |
| 和弦分组（上限 1/4/6/9 均生效；0 或负数按 1 处理） | PASS |
| 点击计划（单个音也必须有输出——曾因整数溢出恒为空） | PASS |
| 存档格式（往返一致、版本不符/缺头/空内容返回 null、坏行跳过） | PASS |
| 播放计时（暂停冻结、继续接续、连续 5 次不漂移） | PASS |
| 曲目切换索引（双向环绕、越界、空列表、随机不返回自己） | PASS |
| 练习判定 / 键位命中 / 半径估算 / 倍速时钟 / 音符列表排布 | PASS |
| 链接导入（分享链接→直链、歌名、按文件头认类型） | PASS |
| APK 打包合规（resources.arsc 不压缩 + 4 字节对齐） | PASS |

`SongLibrary` 依赖 Android 的 SharedPreferences 与 org.json，`UrlImporter` 需要网络与 `Context`，
无法在纯 JVM 中测试，仅有编译与打包检查覆盖。
