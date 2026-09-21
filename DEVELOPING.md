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
├── MainActivity.java              # 单页 UI，串起全流程
├── HandpanAccessibilityService.java  # 注入手势（核心能力）
├── OverlayController.java         # 悬浮校准层 + 悬浮停止按钮
├── Playback.java                  # 实时调度，按毫秒派发
├── TapPlanner.java                # 音符 → 琴键点击计划（含和弦分组）
├── MidiParser.java                # SMF 解析（tempo map / running status / SMPTE）
├── PitchDetector.java             # YIN 音高检测（音频 → 音符）
├── AudioDecoder.java              # MediaCodec 解码 / 手写 WAV 解析
├── JianpuParser.java              # 简谱文本解析
├── SongLoader.java                # 格式分发 + 单音旋律提取
├── SongLibrary.java               # 已导入曲目（含持久化 URI 权限）
├── KeyDetector.java               # 自动识调（12 个调）
├── ScaleMapper.java               # 音高 → 简谱音级
├── PadMapper.java                 # 音级 + 八度 → 9 个琴键中的哪一个
├── AppPrefs.java                  # 坐标与设置持久化
├── Playback.java / RawNote.java   # 数据类
```

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
| 和弦分组（do-mi-sol → 一次三指；同键不重复按；上限生效） | PASS |
| 点击计划（单个音也必须有输出——曾因整数溢出恒为空） | PASS |
| APK 打包合规（resources.arsc 不压缩 + 4 字节对齐） | PASS |

`SongLibrary` 依赖 Android 的 SharedPreferences 与 org.json，无法在纯 JVM 中测试，仅有编译与打包检查覆盖。
