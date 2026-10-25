# PCLA 合规声明（可直接作为「关于」页首位内容）

> 本文件对应《PCL 存储库合理使用指南》中「重度使用」的 5 条要求。
> 每一条都标出了对应动作，未完成的标 ❌，完成后改 ✅。

---

## 一、第三方身份声明（对应第 1 条）✅

PCLA（Plain Craft Launcher for Android）是**第三方独立二次创作**产物，
由「铜与硝酸」个人开发与维护，**与原作者龙腾猫跃及 PCL 官方无关**，
未获得官方授权或认可。

---

## 二、名称（对应第 2 条）⚠️ 待改

原文要求：名称必须以 `Plain Craft Launcher (PCL)` 开头，其后添加足以表现
「由第三方修改」的后缀。

| 项 | 现状 | 判定 |
|---|---|---|
| 仓库名 | `Plain Craft Launcher for Android` | ⚠️ 缺 `(PCL)` |
| 建议改为 | `Plain Craft Launcher (PCL) for Android` | ✅ |

**禁止出现**的名称形态（会踩第 1、2 条）：
- PCL 手机版 / PCL 安卓版 —— 直接点名禁止
- PCL++、PCL Pro、PCL 3 —— 像官方后续作品
- 任何「官方」「授权」「合作」措辞

---

## 三、关于页首位（对应第 3 条）❌ 待实现

顺序不可颠倒：

```
1. 龙腾猫跃  [署名]
   赞助链接：https://meloong.com/afd/a/LTCat
   ↑ 必须排第一，不能放在自己爱发电后面

2. 铜与硝酸  [PCLA 开发者]
   赞助链接：<你的爱发电>
   源码地址：https://gitee.com/copper-and-nitric-acid/plain-craft-launcher-for-android

3. 本文件（NOTICE.md）可访问链接
```

公开源代码：✅ 已在 Gitee 公开。
许可证：仓库用 AGPL-3.0 ✅（但见下方「四」的坑）。

---

## 四、AGPL-3.0 的边界 ⚠️

- 你自己写的代码（DeviceProbe.kt、analyze_gc.py、device_profiles.json…）
  用 AGPL-3.0 发布：**没问题**，是作者的选择。
- PCL 的源码**不是** AGPL，而是《PCL 分发有限许可》。
  因此：**不要把 PCL 原码放进本仓库**，也不要把 PCL 代码改标成 AGPL。
- 把 PCL 的 VB.NET 逻辑翻译成 Kotlin 属于「重度使用」范畴，
  README 里需署名并说明「逻辑参考自 PCL」。
- 若未来 fork FCL / PojavLauncher（GPLv3），AGPL-3.0 与之兼容（GPLv3 §13），
  可以合并，但要保留各自的版权声明。

---

## 五、正版购买劝导（对应第 4 条）❌ 待实现

只要 PCLA 有「启动 Minecraft」功能，就必须实现与 PCL 的 `McLaunchPrecheck`
**一致或更激进**的正版劝导：检测到未登录 / 离线账户时，
引导用户前往正版购买渠道。

（PCL 中该函数位于 `Plain Craft Launcher 2/Modules/Minecraft/ModLaunch.vb`，
行为是弹出提示并跳转 Xbox 商店链接。）

---

## 六、界面主色（对应第 5 条）✅ 不受约束

原文：**「软件在 Windows 系统上运行时」**，不得有类似赞助解锁的功能，
例如主色相必须为蓝色。

PCLA 仅运行于 Android → **不受此条约束**。
但若未来推出 Windows 版，主色相须为蓝色。
