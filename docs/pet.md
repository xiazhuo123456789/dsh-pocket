# 桌宠

手机里的「大肥鱼」：一只会动、能拖、能甩、能点、能走出应用的小宠物。素材随 APK 一起安装，
**不需要电脑、不需要网络**。

- 开关：设置 → 桌宠 → 显示桌宠；**还没配对时**从配对页右上角的齿轮进去也能开
- 大小：96–320 dp 的滑块（默认 132 dp）
- 位置：应用内默认右下角；悬浮模式下可拖到屏幕任意位置
- 悬浮：设置 → 桌宠 → 悬浮在其他应用上（需要一次系统授权）
- 断线不会关掉它：设置是本机的，与连的是哪台电脑无关

## 两种活法

| 模式 | 什么时候用 | 代价 |
| --- | --- | --- |
| 应用内 | 默认。只在本 App 界面里出现 | 没有；切走就看不见 |
| 悬浮窗 | 「退出应用也要看到它」 | `SYSTEM_ALERT_WINDOW` 授权 + 一条常驻通知 |

应用内和悬浮窗**不会同时画**：悬浮窗开着时，`MainActivity` 的 `PocketRoot` 会把应用内那只
关掉（`state.settings.petFloating && overlayGranted`），否则同一个屏幕上会出现两只一样的宠物。
从菜单里点「关闭悬浮」会先写回偏好、再 `stopSelf()`，应用内那只随即重新出现。

**打开设置时宠物要让开触摸**：宠物画在内容之上的一个图层，放大到 320 dp 时整张图（连透明
边框）会盖住底部卡片的开关 —— 用户点了半天「悬浮在其他应用上」，点到的是宠物。

- 全屏设置页（`showSettings`）直接不画应用内那只，它会把大半个界面挡住；
- 桌宠小卡片（`showPetSettings`）仍画，但 `PetSprite(interactive = false)` 不装任何指针
  输入，于是触摸直接穿透到下面的开关。这样拉「大小」滑杆时还能看着它实时变大，又不会
  点错；
- 悬浮窗是另一个窗口（盖在本 Activity 之上），走同一套规则：`PocketRoot` 在
  `modalOpen && Activity 处于 RESUMED` 时写 `PetTouches.setBlocked(true)`，
  `PetOverlayService` 把窗口标成 `FLAG_NOT_TOUCHABLE`。Activity `onPause`（退到桌面、
  进系统权限页）同步解除，桌面上那只照常能点、能拖。

这两个标志因此上提到 `PocketRoot`。

两种模式都**不看配对状态**：`PocketRoot` 的条件是 `petEnabled && !floating && !showSettings`，没有 `paired != null`。
素材在 APK 里，一只待机的宠物和"有没有连上电脑"无关。取消配对只清凭据，
`UiState(settings = ...)` 会把设置带过去 —— 早先那版整块 `UiState()` 会连 `petEnabled`
一起打回默认值，于是"一断开宠物就没了"。

## 它做什么

动画不按名字硬编码，而是走 manifest 的 `roles` 表（见「存储格式」），所以换素材不用改 Kotlin。

| 状态 | 角色 | 对应素材 |
| --- | --- | --- |
| 没连电脑（或断线） | `idle` | 待机呼吸休闲 |
| 任务排队中 | `workThinking` | 工作状态-思考冒泡 |
| 等你审批 | `workWaiting` | 工作状态-原地踱步张望 |
| 正在跑，最后的工具是读文件/搜索 | `workOrganize` | 工作状态-清点归档 |
| 正在跑，其它（含 shell 与纯文本生成） | `workBusy` | 工作状态-忙碌点按 |
| 这一轮成功结束 | `workSuccess` | 工作状态-雀跃庆祝（放一遍） |
| 失败或被取消 | `workError` | 工作状态-垂头叹气冒汗（放一遍） |
| 手指按着它 | `drag` | 被鼠标拖拽悬空反馈 |
| 被甩出去 | — | 不播走路线，直接带速度滑出去（见下节） |
| 跳向下一处 | — | 只靠身体的翻转与落地压扁表达方向，不播「走路」素材 |
| 点一下（应用内或悬浮窗） | `click` | 五段「点击回应」随机一段 |
| 待机时每 14–30 秒 | — | 「小动作」分组里 18 段随机一段 |
| 菜单里选任意动作 | — | 该 clip 放一遍 |

状态读的是 `UiState`（也就是屏幕上正在显示的那份快照），所以宠物不会在界面写着「未连接」
的时候还在演「工作中」。`Done` / `Failed` 是**事件不是状态**：`ChatState.status` 在一轮结束后
会停在 `Done`，把成功动画当循环放就成了永远庆祝的宠物，所以它们只在状态变化的那一刻放一遍。

## 拖动、甩动与菜单

- 应用内不做拖动：那里底下是会话列表和输入框，拖动会把滚动抢走。应用内只保留点击/长按。
- 悬浮窗的窗口本身就是拖拽目标：`PetSprite` 收到 `onDrag` 时改用单个手势识别器
  （`awaitEachGesture` + `touchSlop` 判定），手指移动超过阈值就调 `moveBy` 改
  `WindowManager.LayoutParams.x/y`；原地抬手则算点击，打开菜单。
- **甩出去会滑、会弹**：`moveBy` 用最近 40 ms 的位移做指数平滑得到速度，抬手时若速度
  仍大于 1.5 px/帧就进入 `flingIfNeeded()` —— 每 16 ms 位移一次、速度乘 0.94 的摩擦，
  撞到屏幕边把该轴速度取反并乘 0.62 的弹性系数，直到速度小于 0.6 px/帧才停。
  这就是"拎着他、甩出去、在屏幕上弹来弹去"。
- 菜单是**第二个全屏悬浮窗**，不是 Compose `Popup`：宠物窗口只有宠物那么大，Popup 会被
  裁成一条。全屏遮罩既能把卡片放到底部，也提供了「点空白处关闭」——在别的应用上面
  没有返回键可接。
- 卡片自己吞掉点击（`pointerInput { detectTapGestures { } }`），否则点动作按钮会先被遮罩
  当成「关闭」吃掉。
- 动作按桌面端的**十三个分组**排（可折叠，第一次进菜单展开「待机」）：
  待机 / 转向 / 拖拽 / 点击回应 / 移动 / 小动作 / 玩耍 / 吃什么 / 时节 / 文字 /
  余额档位 / 碎碎念 / 工作状态，共 106 段。分组与组内顺序都来自 manifest，不在 Kotlin 里。
- 碎碎念走 `PetSay(text, token)`：气泡默认停 4.5 秒，token 变了才算新的一句，旧的定时器
  不会把新句子清掉。

## 它怎么"走"

两套节奏，故意分开的：

| 动作 | 频率 | 做什么 |
|---|---|---|
| 左右漫步 | 每 40–90 秒 | 往左或往右走一小段（60–170 dp），播 `walk` 那段走动素材，朝走的方向左右翻转 |
| 跳台阶 | 每 20–30 分钟 | 一段抛物线跳到另一处（偏向下），落地压扁；rare event，久了才一次 |

两者都要求：偏好 `petRoam` 为真；没在拖、菜单没开、没在跳/走上一段；
`petClipFor(state, assets) == assets.roleClip("idle")` —— 有任务在跑时它不动，
那时候它该演「在干活」。

**漫步是"一步一步"挪的**：`walkSomewhere()` 每 60 ms 迈 5 dp（步数超过 60 时压到 40 ms），
同时把 `walking` 置真让精灵切到走动素材，`facingLeft` 按位移方向翻转整张图。
停下来时清 `walking`，回到待机呼吸。这样看起来像走，而不是贴着地滑。

**跳台阶是一段抛物线**：`hopSomewhere()` 随机往横向 56–150 dp、纵向 −20…64 dp（偏向下，
像跳下一级台阶）的地方跳，用时 360–620 ms，弧高 18–36 dp，位置按
`eased = t*t*(3-2t)` 水平推进、`y = startY + dy*eased - arc*sin(πt)` 竖直起落。
落地时把 `landing` 置真 130 ms，`PetSprite` 用 `animateFloatAsState` 做一次压扁
（宽 +6%、高 −10%），再恢复。

**四条边各有各的姿势，而且每条边都留一半在屏内**：`SIDE_KEEP` / `TOP_KEEP` /
`BOTTOM_KEEP` 都是 `0.5`，所以

| 贴边 | 位置范围 | 画法 |
| --- | --- | --- |
| 左 | `x = -petWidth/2` | 半个脑袋探出左边 |
| 右 | `x = width - petWidth/2` | 半个脑袋探出右边 |
| 下 | `y = height - petHeight/2` | 头搭在下边缘上 |
| 上 | `y = -petHeight/2` | 整张图 `rotationZ = 180` 倒挂 |

早先只按「留 64 dp 看得见」夹，320 dp 的宠物几乎整只挂到屏幕外，就是「半个身子
消失、有时候完全找不到」。现在四条边各留一半，任何屏幕比例下都至少有一半可抓。

`pose` 由 `clampEdges()` 唯一推导，拖动、甩动、漫步、跳跃、转屏都走它，所以不会出现
「只有上边界会弹、左右下边界半身消失」这种各算各的。

只改边界还不够：`WindowManager` 默认会把将要超出屏幕的窗口拉回屏内，负的 `x/y` 会被
悄悄夹成 0。所以宠物窗口带上 **`FLAG_LAYOUT_NO_LIMITS`**（见 `petWindowFlags()`），
负坐标才真正生效。

`onConfigurationChanged()` 会在旋转、分屏、导航栏高度变化后重新夹一次——否则窗口还拿着
旧屏幕的坐标，换到新屏幕上可能整只在屏幕之外。

有人一拖或一甩就立刻 `return`，把控制权交回去；甩动接在 `flingIfNeeded()` 里，
边界与漫步共用同一套 `xMin()/yMin()/xMax()/yMax()`，所以甩出去也能一路撞到屏幕边缘再弹回来。

**镜像会让拖拽反向**：`graphicsLayer(scaleX = -1)` 会连带把指针坐标按逆矩阵映射回来，
所以精灵朝左时每个水平 `delta.x` 都是反的，宠物就往反方向跑——这就是「有时候正常、
有时候反着走」。`PetSprite` 在 `onDrag` 前按 `flipHorizontally` 把 `dx` 正回来。
另一半原因是甩动还没停就按下手指：旧速度仍在叠加，现在 `onDragStart` 会先
`flingJob?.cancel()` 并把速度清零。

## 素材从哪来

**106 段动画 / 12,659 帧**，直接取自 `PC2005-cloud/dsh-pet` 的 `assets/webm`
（代码 MIT，美术素材禁止商用）。不再经过大肥鱼的 16 段转码版本。

- 源：640×360、24 fps、10 秒左右的透明 WebM。透明通道在单独的 alpha plane 里，
  **只有 `ffmpeg -c:v libvpx-vp9` 解码器会解出来**（原生 vp9 解码器给的是黑底）。
- 转码：`fps=12,scale=400:-1:flags=lanczos` → RGBA PNG → Pillow 编码 WebP
  `quality=68, method=4`。
- 产物：帧尺寸 400×225，逐个 clip 首尾相接成 `.bin`，合计 **112.59 MB**；
  APK 里压缩后约 108 MB。
- 为什么是 400 而不是源码的 640：640 全量要约 190 MB，APK 会到 200 MB 以上；
  400 已经是 0.17.0（200 px）的两倍边长，配上 `FilterQuality.High` 与最高 320 dp 的
  显示宽度，正常看不会有锯齿。

许可原文随素材打包在 `android-app/app/src/main/assets/pet/LICENSE.txt`，仓库侧见
[`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)。

## 存储格式

不希望上万个 zip 条目（安装慢、资源枚举慢，而且每帧都要走一次 `AssetManager.open`），
所以按 clip 打包：

```
assets/pet/
  m001.bin                  120 帧首尾相接
  m002.bin
  ...
  pet-manifest.json         {"version":2,"fps":12,"width":400,"height":225,
                             "clips":[{"id":"m001","label":"待机呼吸休闲","group":"待机",
                                       "frames":120,"width":400,"height":225,"sizes":[...]}],
                             "groups":[{"id":"待机","clips":["m001"]}, ...],
                             "roles":{"idle":["m001"],"click":["m004",...], ...}}
  LICENSE.txt
```

`sizes` 记录每帧的字节数，运行时做前缀和得到 `offsets`，播一帧就是 `bytes.slice(offset, length)`
加一次 `BitmapFactory.decodeByteArray`。整段 clip 约 1.06 MB，`PetPlayer` 最多常驻两段
（当前 + 刚离开的那段），所以内存里同时只有一两张位图。

代码在 `android-app/app/src/main/java/dev/dshpocket/pet/`：

| 文件 | 职责 |
| --- | --- |
| `PetAssets.kt` | 读 manifest（clips / groups / roles）、给每帧算偏移、解码单帧 |
| `PetPlayer.kt` | 按 manifest 的 fps 循环播放，`Dispatchers.Default` 解码、主线程赋值 |
| `PetSprite.kt` | 画一张位图 + 手势（点击/长按/拖动）+ 一次性反应 + 待机小动作 + 左右镜像 + 落地压扁 |
| `PetOverlay.kt` | 状态 → 角色的映射；应用内那只的入口 |
| `PetActionMenu.kt` | 悬浮窗的底部动作卡片（十三个可折叠分组 + 漫步 + 大小） |
| `PetOverlayService.kt` | 前台服务 + 悬浮窗 + 拖动 + 甩动 + 跳台阶 + 菜单窗口 |

## 悬浮窗的几个硬约束

- **权限**：`SYSTEM_ALERT_WINDOW` 是 special access，只能用
  `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` 打开系统页让用户手动给，不能用
  `requestPermissions`。代码在 `MainActivity.onResume()` 里读
  `Settings.canDrawOverlays`，所以「去授权，回来」能自己接上。
- **前台服务类型**：API 34+ 每个前台服务都要申报类型，这里用 `specialUse`，并在
  manifest 里写 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`；不申报的话 `startForeground` 直接抛。
- **划掉任务也不退**：`android:stopWithTask="false"`。这是需求本体 —— 用户从最近任务里
  划掉 App，宠物仍要在桌面上。
- **没有 Activity 的 Compose**：`ComposeView` 需要一个 `ViewTreeLifecycleOwner`（
  `rememberSaveable` 还要 `SavedStateRegistryOwner`），而 Service 两者都不是。所以
  `OverlayLifecycleOwner` 自己实现这两个接口：`attach()` 时 `performRestore(null)` 再直接
  推到 `RESUMED`，`detach()` 推 `DESTROYED` 让 composition 释放、协程停止。
- **后台启动 Activity 会被拒**：菜单里的「打开应用」在 Android 10+ 常常失败，所以
  `runCatching` 包起来，不崩服务。

## 重新打包素材

上游加了新动画时：

```bash
# 默认从本机 dsh-pet 插件的 assets/webm 与 assets/config.jsonc 读，
# 写到 android-app/app/src/main/assets/pet
python tools/pack_pet.py

# 指定源、输出、参数
python tools/pack_pet.py --webm <webm 目录> --config <config.jsonc> \
    --out android-app/app/src/main/assets/pet --fps 12 --width 400 --quality 68
```

脚本会先清掉输出目录里旧的 `*.bin`，再按 `config.jsonc` 的分组顺序逐 clip 重写；
分组与角色表也由它生成。体积吃紧时优先降 `--quality`，其次 `--fps`，最后 `--width`。
跑完重新 `assembleRelease` 即可。

## 已知限制

- **动作是"桌面端那些"，不是无限多**：106 段已经覆盖桌面插件全部分类；再加只能是上游
  新增素材，本项目不会凭空生成动画。
- **APK 因此变大**：112 MB 的帧素材让安装包从 44 MB 涨到 123 MB。这是画质的代价：
  0.17.0 用 200 px 宽换体积，放大就能看出糊；现在换成 400 px 宽。
- **国产 ROM 可能杀后台**：前台服务已经能挡住大部分回收，但小米/华为/OPPO 的省电策略
  仍需用户在系统设置里把本应用列为「不优化」，否则悬浮窗过一阵会消失。这不是代码能绕过的。
- **iOS 不适用**：这是 Android 应用。
- **没有好感度/语音/聊天**：它只反映电脑的会话状态，并播自己的动作。
- **R8 会混淆类名**：抓堆栈时看到 `a.b.c` 是正常的，看 `pet-manifest.json` 之类的字符串
  反而更容易确认版本。
- **没有做真机验证**：构建环境里没有连接设备，也没有装模拟器；这一版的结论来自编译通过
  与 APK 结构检查，交互手感（拖拽跟手程度、甩动弹性、漫步节奏）还需要在手机上实测。
