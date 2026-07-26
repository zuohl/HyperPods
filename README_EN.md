<div align="center">

<img src="https://github.com/user-attachments/assets/e8a3df6b-6e67-485a-ae1c-018ac24e87d4" width="120" height="120" style="border-radius: 24px;" alt="HyperPods Icon"/>

# HyperPods

**System-level third-party earphone control for HyperOS devices**

[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![HyperOS](https://img.shields.io/badge/ROM-HyperOS%203-orange?style=flat-square)](https://hyperos.mi.com)

**English** | **[简体中文](README.md)**

</div>

HyperPods is an Xposed module for Xiaomi HyperOS devices that gives third-party Bluetooth earphones system-level capabilities such as popup, battery display, and earphone settings.

This project is a refactored fork of [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) with a multi-brand architecture. QCY earphones are fully supported, and vivo support is in progress.

### Supported Brands

- **QCY** (full support): battery, ANC, game mode, spatial audio, dual device, LDAC, dynamic EQ, sleep mode, adaptive volume, custom EQ
- **vivo** (in development): integrated via the multi-brand routing framework

### Features

- Battery display: left/right earbuds and charging case
- ANC control: off, noise cancellation, transparency mode switching
- Game mode: low-latency mode toggle
- Spatial audio: spatial audio control entry
- Dual device connection: toggle
- LDAC / dynamic EQ / sleep mode / adaptive volume: independent toggles
- Custom tuning: custom EQ with persistence across reconnections
- Custom images: import earphone image assets

### System Integration

- System Bluetooth UI integration
- HyperOS notification and popup battery display
- In-module earphone detail page control

### Usage

1. Install the APK
2. Enable the module in LSPosed and select the recommended scope
3. Use the in-app one-tap restart scope feature
4. Connect your earphones and control them in the module

### Acknowledgements

- [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) - base project for this adaptation
- [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) - design and interaction reference
- [Miuix](https://github.com/YuKongA/miuix) - HyperOS-style Compose UI components

### License

GPL-3.0