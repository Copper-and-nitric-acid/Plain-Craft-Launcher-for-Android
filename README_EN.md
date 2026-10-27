# PCLA (Plain Craft Launcher for Android)

**Status:** Alpha 0.1.0 (Early Development)
**License:** AGPL-3.0
**Core Languages:** Kotlin, Python

## 🌌 Prologue: A 13-Year-Old's Cyber Breakthrough

Hello, world. I am the developer of this project, a 13-year-old middle school student.

I don't have a high-end PC. I don't have an expensive server. I don't even have a physical keyboard. All I have is an Android smartphone (with a screen I had to replace myself). In an environment where traditional Android native development is deemed almost impossible, I utilized Termux, AI assistance, and the power of GitHub cloud computing to write these thousands of lines of code.

Why did I create PCLA? Because I was tired. I was tired of seeing players with budget phones blindly tweaking settings in the mobile Java Edition of Minecraft, and tired of so-called "one-click optimizations" that are nothing but marketing gimmicks. I wanted a launcher that truly understands the hardware. 

I am not here to teach you how to tweak settings blindly. I am here to let the launcher proactively adapt to your device. Through deep hardware detection and real-device performance baseline testing, we use data to tell you the truth: how fast can your phone actually run?

This project is not just a piece of software; it is a testament to the fact that hardware limitations cannot cage a curious and determined mind.

## 🛠️ Completed Hardcore Engineering (Real Code, Not Promises)

Although a complete client has not been officially released yet, the core underlying engine is already taking shape:

- **Intelligent Underlying Device Detection (Implemented):** Based on Kotlin and the EGL14 off-screen context technology, the launcher can silently read the device's GL_RENDERER, GPU model, and available memory within milliseconds in the background. This achieves undisturbed, highly accurate hardware detail extraction without requiring intrusive permissions. It is fast, precise, and invisible.
- **Real-Device Performance Baseline Library (Established):** I conducted rigorous stress tests specifically targeting budget devices, using the MediaTek MT6833V (Dimensity 700) as the primary test subject. By utilizing Python scripts to parse massive JVM GC logs and align frame rate valleys, I reached a hardcore conclusion: "Stuttering in some versions is not because the phone is inherently bad, but the fault of GC pauses and poorly optimized render distances."
- **Dynamic Scheduling Algorithm (In Development):** Based on the aforementioned real testing data, a rule engine is currently being built. For example, when a specific chip is detected, the launcher will automatically lock the recommended render distance and inject the optimal JVM parameters. This prevents the classic tragedy of "maxing out graphics and causing the phone to become a hand warmer."

## 🧩 The Confession and Promise of a "Frankenstein"

I must be extremely honest with the open-source community and all users: at this current stage, the native libraries (the .so files) of PCLA are somewhat of a "Frankenstein" (a patchwork).

In the mobile Minecraft launcher ecosystem, we all stand on the shoulders of giants. Because I currently only have a mobile phone, rewriting those native C/C++ libraries from scratch inside Termux is a nightmare. I cannot even properly inspect the native C/C++ source code on mobile, and the mobile environment simply does not support the toolchains required to compile them. Therefore, I have reused and referenced some underlying native libraries and rendering solutions from FCL (Fold Craft Launcher) and PojavLauncher.

I want to express my deepest gratitude to the FCL team and all predecessors in the mobile launcher community. Their groundbreaking work laid the foundation for projects like mine.

However, I hereby make a solemn promise: If one day, I have my own PC, or if I acquire sufficient technical capabilities, I will definitely refactor all these .so native libraries from scratch to fill this gap. I will make PCLA a truly pure, completely independent, and powerful project! I invite the entire open-source community to witness my growth. This patchwork is temporary, but my passion is permanent.

## 📥 Global Dual-Channel Download

To ensure everyone can access the latest builds regardless of their network location, I have prepared two download channels:

- **International Channel (GitHub):** [Download from GitHub Releases](https://github.com/copper-and-nitric-acid/plain-craft-launcher-for-android/releases)
- **Domestic Channel (Gitee):** [Download from Gitee Releases](https://gitee.com/copper-and-nitric-acid/plain-craft-launcher-for-android/releases)

Please note that because this is an early Alpha build, the APK is currently distributed within a ZIP archive. Simply extract it and install the APK on your Android device.

## ⚠️ Disclaimer and Open-Source License

### 1. Support the Authentic Original PCL
This project is a personal, unofficial open-source project. It is completely free. It has absolutely no affiliation with the original author "龙腾猫跃" (Longteng Maoyue) or the official PCL team. The desktop version of PCL is a masterpiece, and I strongly urge everyone to support the authentic original PCL.

### 2. Open-Source Shield: AGPL-3.0
This repository fully adopts the strictest AGPL-3.0 open-source license. You are free to use, modify, and distribute this software. However, if you modify this project and use it to provide any form of network services (including but not limited to cloud launching, online distribution, or SaaS platforms), you MUST disclose the complete source code in accordance with the AGPL terms. This prevents malicious closed-sourcing and illegal commercial exploitation. PCLA will always be free.

### 3. Risk Warning
This project is currently in the Alpha early testing phase. Due to the extreme complexity and fragmentation of the Android ecosystem, device chips, and OS versions, using this software may cause game crashes, device overheating, or severe compatibility issues. The developer is not responsible for any device damage or data loss caused by using this software. Please test at your own risk. Always back up your important data before installing.

## 🤝 Acknowledgements
- Thanks to the Fold Craft Launcher (FCL) team and PojavLauncher for the native library support.
- Thanks to the open-source community for providing such powerful tools like Termux, Gradle, and GitHub Actions.
- Thanks to my family for their support.

Keep coding, keep critical thinking. See you in the matrix.