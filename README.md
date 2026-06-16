<div align="center">

<img src="https://github.com/user-attachments/assets/e8a3df6b-6e67-485a-ae1c-018ac24e87d4" width="120" height="120" style="border-radius: 24px;" alt="QCYpods Icon"/>

# QCYpods

**为 HyperOS 设备提供系统级 QCY 耳机控制**

[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![HyperOS](https://img.shields.io/badge/ROM-HyperOS%203-orange?style=flat-square)](https://hyperos.mi.com)

**[English](README_EN.md)** | **简体中文**

</div>

QCYpods 是一个面向小米 HyperOS 的 Xposed 模块，用于提供系统级 QCY 蓝牙耳机控制与状态显示。

本项目基于 [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) 二次修改，针对 QCY 系列耳机做了适配与扩展。

### 当前特性

- 电量显示：显示左右耳与充电盒电量
- 降噪控制：支持关闭、降噪、通透等模式切换
- 游戏模式：支持低延迟模式开关
- 空间音效：提供空间音效相关控制入口
- 双设备连接：提供双设备连接开关
- LDAC / 动态 EQ / 睡眠模式 / 自适应音量：独立开关入口
- 自定义调音：支持自定义 EQ，保存后可在重连后恢复
- 自定义图片：支持导入耳机图片资源

### 系统集成

- 系统蓝牙界面集成
- HyperOS 通知与弹窗电量展示
- 模块内耳机详情页控制

### 使用方式

1. 安装 APK
2. 在 LSPosed 中启用模块，并勾选推荐作用域
3. 使用应用内的一键重启作用域功能
4. 连接 QCY 耳机后进入模块进行控制

### 致谢

- [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) - 本项目的二改基础
- [Art-Chen/HyperPods](https://github.com/Art-Chen/HyperPods) - 设计与交互参考
- [Miuix](https://github.com/YuKongA/miuix) - HyperOS 风格 Compose UI 组件

### 许可证

GPL-3.0
