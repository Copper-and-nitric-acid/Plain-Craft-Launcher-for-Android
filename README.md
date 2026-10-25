# Plain Craft Launcher for Android (PCLA)

## 🚧 当前状态 / Current Status
> **早期开发阶段 (Early Development)**
> 底层硬件探测与性能调度逻辑已跑通，UI 界面与整合正在施工中。
> *Underlying hardware detection and performance scheduling logic are functional; UI integration is currently under construction.*

---

## 📖 关于 PCLA / About PCLA

**PCLA** 是一个致力于将桌面端神级启动器 PCL (Plain Craft Launcher) 的体验，移植到 Android 平台的非官方开源项目。
*PCLA is an unofficial open-source project dedicated to porting the experience of the legendary desktop launcher PCL (Plain Craft Launcher) to the Android platform.*

我们拒绝让玩家在手机上盲目摸索配置，而是让启动器主动去“适配”设备。通过底层硬件探测与真机性能基线测试，用数据告诉你：**你的手机到底能跑多快。**
*We refuse to let players blindly tweak settings on their phones. Instead, the launcher proactively "adapts" to the device. Through underlying hardware detection and real-device performance baseline tests, we use data to tell you: how fast can your phone actually run?*

---

## 🛠️ 已完成的硬核工作（不是画饼，是真实代码） / Completed Hardcore Work (Real Code, Not Just Promises)

项目虽然尚未发布完整的客户端，但核心底层“引擎”已现雏形：
*Although a complete client has not been released yet, the core underlying "engine" is taking shape:*

- **底层设备智能探测 (已实现)**：基于 Kotlin 与 EGL14 离屏上下文技术，启动器能在后台毫秒级静默读取设备的 GL_RENDERER、GPU 型号及可用内存，实现无打扰的硬件底细抓取。
  *Intelligent Underlying Device Detection (Implemented): Based on Kotlin and EGL14 off-screen context technology, the launcher can silently read the device's GL_RENDERER, GPU model, and available memory in milliseconds in the background, achieving undisturbed hardware detail extraction.*
- **真机性能基线库 (已建立)**：针对测试机（MT6833V / 天玑700）进行了严苛的压测。通过 Python 脚本解析 JVM GC 日志，对齐帧率谷底，得出了“某些版本卡顿不是手机烂，而是 GC 停顿与渲染距离的锅”的硬核结论。
  *Real-Device Performance Baseline Library (Established): Conducted rigorous stress tests on the test device (MT6833V / Dimensity 700). By using Python scripts to parse JVM GC logs and align frame rate valleys, we reached the hardcore conclusion that "stuttering in some versions is not because the phone is bad, but the fault of GC pauses and render distance."*
- **动态调度算法 (开发中)**：基于上述测试数据，正在构建规则引擎。例如：探测到特定芯片，自动锁定推荐渲染距离（RD），杜绝“拉满画质导致手机变暖手宝”的惨剧。
  *Dynamic Scheduling Algorithm (In Development): Based on the above test data, a rule engine is being built. For example: when a specific chip is detected, automatically lock the recommended render distance (RD) to prevent the tragedy of "maxing out graphics causing the phone to become a hand warmer."*
- **开源防护盾**：仓库已全面采用 AGPL-3.0 协议，确保代码自由，防范恶意闭源与非法商业牟利。
  *Open-Source Shield: The repository has fully adopted the AGPL-3.0 license to ensure code freedom and prevent malicious closed-sourcing and illegal commercial profit-making.*

---

## 🚀 当前开发进度 / Current Development Progress

- [x] 设备智能探测模块 (DeviceProbe.kt) / Device Intelligent Detection Module (DeviceProbe.kt)
- [x] 真机性能压测与 GC 日志分析脚本 / Real-Device Performance Stress Test & GC Log Analysis Scripts
- [ ] Android 端 UI 框架搭建 / Android UI Framework Setup
- [ ] JVM 启动参数动态生成与调度模块 / JVM Launch Parameter Dynamic Generation & Scheduling Module
- [ ] 首个 Alpha 测试包发布 / First Alpha Test Package Release

*(欢迎来 GitHub 仓库查看 git log 代码提交记录 / Welcome to visit the GitHub repository to check the git log commit records)*

---

## 💖 支持项目 / Support the Project

如果觉得 PCLA 有意思，欢迎来爱发电支持我：
[爱发电主页](https://afdian.com/a/L38324R14122)
*If you find PCLA interesting, feel free to support me on Afdian.*

---

## 📄 开源许可证 / License

本项目采用 **AGPL-3.0 License** 开源。
*This project is open-sourced under the AGPL-3.0 License.*

你可以自由地使用、修改和分发，但请务必保留原作者的署名。
*You are free to use, modify, and distribute, but you must retain the original author's attribution.*

> ⚠️ **特别声明**：任何将本项目修改后用于网络服务（包括但不限于云端启动、在线分发）的行为，必须依据 AGPL 条款公开完整的源代码！
> *⚠️ Special Statement: Any act of modifying this project for network services (including but not limited to cloud launching and online distribution) must disclose the complete source code in accordance with the AGPL terms!*

---

## ⚠️ 免责声明 / Disclaimer

本项目为个人非官方开源项目，完全免费，与原作者“龙腾猫跃”及 PCL 官方无关。
*This project is a personal, unofficial open-source project, completely free, and has nothing to do with the original author "龙腾猫跃" or PCL official.*

请支持正版原版 PCL。
*Please support the authentic original PCL.*
