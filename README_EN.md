<div align="center">

<img src="https://github.com/user-attachments/assets/e8a3df6b-6e67-485a-ae1c-018ac24e87d4" width="120" height="120" style="border-radius: 24px;" alt="QCYpods Icon"/>

# QCYpods

**System-level QCY earbud control for HyperOS devices**

[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![HyperOS](https://img.shields.io/badge/ROM-HyperOS%203-orange?style=flat-square)](https://hyperos.mi.com)

**English** | **[Simplified Chinese](README.md)**

</div>

QCYpods is an Xposed module for Xiaomi HyperOS devices that provides system-level control and status display for QCY Bluetooth earbuds.

This project is a secondary adaptation based on [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods), with compatibility work and feature adjustments for QCY earbuds.

### Current Features

- Battery display for left earbud, right earbud, and charging case
- Noise control switching, including off, ANC, and transparency modes
- Game mode toggle for low-latency audio
- Spatial audio related controls
- Dual-device connection toggle
- Separate toggles for LDAC, Dynamic EQ, Sleep Mode, and Adaptive Volume
- Custom EQ with reconnect persistence after saving
- Custom earbud image import support

### System Integration

- System Bluetooth settings integration
- HyperOS notification/popup battery presentation
- In-app earbud detail page controls

### Usage

1. Install the APK
2. Enable the module in LSPosed and select the recommended scopes
3. Use the in-app one-tap restart scope action
4. Connect your QCY earbuds and open the module to control them

### Credits

- [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) - base project for this adaptation
- [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) - design and interaction reference
- [Miuix](https://github.com/YuKongA/miuix) - HyperOS-style Compose UI components

### License

GPL-3.0
