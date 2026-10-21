# 桌宠

手机里的「大肥鱼」：App 内一只会动的小宠物，素材随 APK 一起安装，**不需要电脑、不需要网络**。

- 开关：设置 → 桌宠 → 显示桌宠
- 大小：96–200 dp 的滑块（默认 132 dp）
- 位置：右下角，浮在对话之外的所有界面上

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
| 点一下 / 长按 | `poke` / `head_pat` |
| 待机时每 14–30 秒 | 随机播 `idle_yawn` / `idle_stretch` / `tail` |

状态读的是 `UiState`（也就是屏幕上正在显示的那份快照），所以宠物不会在界面写着「未连接」
的时候还在演「工作中」。`Done` / `Failed` 是**事件不是状态**：`ChatState.status` 在一轮结束后
会停在 `Done`，把 `success` 当循环放就成了永远庆祝的宠物，所以它们只在状态变化的那一刻
放一遍。

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
| `PetOverlay.kt` | 状态 → clip 的映射、一次性反应、待机小动作、点击/长按 |

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

- **只在 App 内**：不申请悬浮窗权限，也不会浮到别的应用上。切到后台时宠物跟着界面一起不可见
  —— 这是有意的，Android 上「后台仍然显示」需要 `SYSTEM_ALERT_WINDOW` 加前台服务，代价是
  权限、耗电和各家 ROM 的后台限制，不适合一个装饰。
- **iOS 不适用**：这是 Android 应用。
- **不会自己换姿势以外的事**：没有好感度、没有语音、没有聊天；它只反映电脑的会话状态。
- **R8 会混淆类名**：抓堆栈时看到 `a.b.c` 是正常的，看 `pet-manifest.json` 之类的字符串
  反而更容易确认版本。
