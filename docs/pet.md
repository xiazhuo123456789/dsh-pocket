# 桌宠

手机里的「大肥鱼」：一只会动、能拖、能点、能走出应用的小宠物。素材随 APK 一起安装，
**不需要电脑、不需要网络**。

- 开关：设置 → 桌宠 → 显示桌宠
- 大小：96–200 dp 的滑块（默认 132 dp）
- 位置：应用内默认右下角；悬浮模式下可拖到屏幕任意位置，位置在服务存活期间保持
- 悬浮：设置 → 桌宠 → 悬浮在其他应用上（需要一次系统授权）

## 两种活法

| 模式 | 什么时候用 | 代价 |
| --- | --- | --- |
| 应用内 | 默认。只在本 App 界面里出现 | 没有；切走就看不见 |
| 悬浮窗 | 「退出应用也要看到它」 | `SYSTEM_ALERT_WINDOW` 授权 + 一条常驻通知 |

应用内和悬浮窗**不会同时画**：悬浮窗开着时，`MainActivity` 的 `PocketRoot` 会把应用内那只
关掉（`state.settings.petFloating && overlayGranted`），否则同一个屏幕上会出现两只一样的宠物。
从菜单里点「关闭悬浮」会先写回偏好、再 `stopSelf()`，应用内那只随即重新出现。

## 它做什么

| 状态 | 动画 |
| --- | --- |
| 没连电脑（或断线） | `idle`（待机循环） |
| 任务排队中 | `thinking` |
| 等你审批 | `waiting` |
| 正在跑，且最后的工具是 shell/命令 | `working_command` |
| 正在跑，且最后的工具是读文件/搜索 | `working_search` |
| 正在跑，其它（含纯文本生成） | `working` |
| 这一轮成功结束 | `success`（放一遍就回到常态） |
| 失败或被取消 | `error`（同上） |
| 点一下（应用内） | `poke` |
| 长按（应用内） | `head_pat` |
| 点一下（悬浮窗） | 打开动作菜单（十四个动作 + 大小 + 漫步 + 回到原位 + 打开应用 + 关闭） |
| 待机时每 14–30 秒 | 随机播 `idle_yawn` / `idle_stretch` / `tail` |

状态读的是 `UiState`（也就是屏幕上正在显示的那份快照），所以宠物不会在界面写着「未连接」
的时候还在演「工作中」。`Done` / `Failed` 是**事件不是状态**：`ChatState.status` 在一轮结束后
会停在 `Done`，把 `success` 当循环放就成了永远庆祝的宠物，所以它们只在状态变化的那一刻
放一遍。

## 拖动与菜单

- 应用内不做拖动：那里底下是会话列表和输入框，拖动会把滚动抢走。应用内只保留点击/长按。
- 悬浮窗的窗口本身就是拖拽目标：`PetSprite` 收到 `onDrag` 时改用单个手势识别器
  （`awaitEachGesture` + `touchSlop` 判定），手指移动超过阈值就调 `moveBy` 改
  `WindowManager.LayoutParams.x/y`；原地抬手则算点击，打开菜单。
- 菜单是**第二个全屏悬浮窗**，不是 Compose `Popup`：宠物窗口只有宠物那么大，Popup 会被
  裁成一条。全屏遮罩既能把卡片放到底部，也提供了「点空白处关闭」——在别的应用上面
  没有返回键可接。
- 卡片自己吞掉点击（`pointerInput { detectTapGestures { } }`），否则点动作按钮会先被遮罩
  当成「关闭」吃掉。

## 自动漫步

`PetOverlayService.startRoaming()` 每 12–26 秒掷一次骰子，同时满足才走：

1. 偏好 `petRoam` 为真；
2. 没在拖、菜单没开、没在走上一段；
3. `petClipFor(state) == "idle"` —— 有任务在跑时它不 wander，那时候它该演「在干活」。

一次漫步 30 步、每步 28 ms，位置用 smoothstep 插值，所以起步和停下都是缓的，看着像走
而不是瞬移。走着的时候用户一拖就 `break`，交还控制权。

## 素材从哪来

16 段动画、3444 帧，源素材是 `PC2005-cloud/dsh-pet`（MIT，Copyright (c) 2026 PC2005-cloud）
的 24 fps 透明 WebM，经 `QCYTSN/dsh-dafeiyu`（MIT，commit `549fff2ea33c2acfb16612c76912c6137da4a758`）
转成逐帧 WebP。本项目再缩到 0.7×、以 WebP quality 70 重新编码。

帧尺寸 288×241（源 412×344），合计 34.76 MB，APK 里压缩后约 32.6 MB。这也是 APK 从
11.64 MiB 涨到 44.24 MiB 的全部原因 —— 这些是功能本身，不是 `shrinkResources` 该压掉的东西。

许可原文随素材打包在 `android-app/app/src/main/assets/pet/LICENSE.txt`，仓库侧见
[`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)。

## 存储格式

不希望 3444 个 zip 条目（安装慢、资源枚举慢，而且每帧都要走一次 `AssetManager.open`），
所以按 clip 打包：

```
assets/pet/
  idle.bin                  241 帧首尾相接
  idle_yawn.bin
  ...
  pet-manifest.json         {"version":1,"fps":24,"width":288,"height":241,
                             "clips":[{"id":"idle","frames":241,...,"sizes":[...]}]}
  LICENSE.txt
```

`sizes` 记录每帧的字节数，运行时做前缀和得到 `offsets`，播一帧就是 `bytes.slice(offset, length)`
加一次 `BitmapFactory.decodeByteArray`。整段 clip 约 2.3 MB，`PetPlayer` 最多常驻两段
（当前 + 刚离开的那段），所以内存里同时只有一两张位图。

代码在 `android-app/app/src/main/java/dev/dshpocket/pet/`：

| 文件 | 职责 |
| --- | --- |
| `PetAssets.kt` | 读 manifest、给每帧算偏移、解码单帧 |
| `PetPlayer.kt` | 24 fps 循环播放，`Dispatchers.Default` 解码、主线程赋值 |
| `PetSprite.kt` | 画一张位图 + 手势（点击/长按/拖动）+ 一次性反应 + 待机小动作 |
| `PetOverlay.kt` | 状态 → clip 的映射；应用内那只的入口 |
| `PetActionMenu.kt` | 悬浮窗的底部动作卡片 |
| `PetOverlayService.kt` | 前台服务 + 悬浮窗 + 拖动 + 漫步 + 菜单窗口 |

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

素材更新时（比如上游加了新动画）：

```bash
# 默认从本机 dsh-dafeiyu 插件的素材目录读，写到 android-app/app/src/main/assets/pet
python tools/pack_pet.py

# 指定源与输出
python tools/pack_pet.py /path/to/pet-assets --out android-app/app/src/main/assets/pet
```

脚本会先清掉输出目录里旧的 `*.bin`，再按目录名逐 clip 重写。跑完重新 `assembleRelease`
即可。源目录里如果有 `LICENSE.txt`，会一起拷到输出目录。

## 已知限制

- **国产 ROM 可能杀后台**：前台服务已经能挡住大部分回收，但小米/华为/OPPO 的省电策略
  仍需用户在系统设置里把本应用列为「不优化」，否则悬浮窗过一阵会消失。这不是代码能绕过的。
- **iOS 不适用**：这是 Android 应用。
- **不会自己换姿势以外的事**：没有好感度、没有语音、没有聊天；它只反映电脑的会话状态。
- **R8 会混淆类名**：抓堆栈时看到 `a.b.c` 是正常的，看 `pet-manifest.json` 之类的字符串
  反而更容易确认版本。
- **没有做真机验证**：构建环境里没有连接设备，也没有装模拟器；这一版的结论来自编译通过
  与 APK 结构检查，交互手感（拖拽跟手程度、漫步节奏）还需要在手机上实测。
