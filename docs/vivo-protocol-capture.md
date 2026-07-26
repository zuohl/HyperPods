# vivo TWS Air3 Pro 协议抓包指南

本指南帮助你抓取 vivo TWS Air3 Pro 的 BLE 交互数据，用于逆向协议并在 HyperPods 中实现原生支持。

## 前置准备

### 方案 A：nRF Connect（推荐，最简单）

1. 从 Google Play 下载 **nRF Connect for Mobile**（by Nordic Semiconductor）
2. 手机连上 vivo TWS Air3 Pro（先在系统蓝牙里配对连接一次）
3. 打开 nRF Connect，找到你的耳机设备

### 方案 B：HCI snoop log（需要电脑 + adb）

适合抓取 vivo 官方 App 和耳机之间的完整交互。

1. 手机开启开发者选项
2. 开启「蓝牙 HCI 信息收集日志」(Bluetooth HCI snoop log)
3. 安装 vivo 耳机官方 App（「vivo 耳机」或「iQOO 耳机」）
4. 在 App 里操作耳机（查电量、切 ANC、开游戏模式等）
5. 关闭 snoop log，导出 btsnoop_hci.log

---

## 抓包步骤（方案 A：nRF Connect）

### 第 1 步：扫描并连接

1. 打开 nRF Connect → Scanner 标签页
2. 找到名称类似 `vivo TWS Air3 Pro` 或 `TWS Air3 Pro` 的设备
3. 点 CONNECT 连接

### 第 2 步：记录 Service 和 Characteristic

连接后你会看到一组 GATT Service。**截图或抄录所有 Service UUID**，重点关注：

- 以 `0000ff` 开头的自定义服务（vivo/BBK 常用 `0000ffb0`、`0000ffe0` 系列）
- 带 `Notify` 或 `Write` 属性的 Characteristic

对每个 Service，展开后记录：
- Service UUID
- 每个 Characteristic 的 UUID + 属性（Read/Write/Notify）
- 每个 Characteristic 的值（如果是 Read，点 READ 读一次）

### 第 3 步：开启 Notify 订阅

对带 Notify 属性的 Characteristic（通常有 2-3 个）：
1. 点击 Characteristic 旁边的「三个向下箭头」图标
2. 开启 Notify（订阅通知）
3. 这时耳机会开始推送状态数据（电量、ANC 模式等）

### 第 4 步：触发并记录交互

在 nRF Connect 里对 Write Characteristic 发送数据，同时在耳机上操作：

1. **记录初始状态**：把所有 Read Characteristic 读一遍，截图记录原始值
2. **电量查询**：等待耳机主动推送电量 Notify，记录 notify 数据
3. **ANC 切换**：在耳机上触摸切换 ANC（关→降噪→通透），记录每次 Notify 推送的数据
4. **游戏模式**：在耳机 App 里开关游戏模式，记录 Write 数据和 Notify 回包
5. **佩戴检测**：摘下/戴上耳机，记录 Notify 推送的数据变化

### 第 5 步：导出数据

nRF Connect 的每个操作都有时间戳和十六进制数据。你可以：
- 截图所有交互记录
- 或者用 nRF Connect 的「Share」功能导出完整 log

---

## 抓包步骤（方案 B：HCI snoop log）

### 第 1 步：开启 snoop log

```bash
# 在电脑上执行（需要 adb）
adb shell setprop persist.bluetooth.btsnooplog true
```

或者在手机上：
1. 设置 → 开发者选项 → 蓝牙 HCI 信息收集日志 → 开启
2. **重启蓝牙**（关闭再打开）

### 第 2 步：操作耳机

打开 vivo 耳机官方 App，依次操作：
1. 进入耳机详情页（触发电量查询）
2. 切换 ANC 模式（关/降噪/通透各一次）
3. 开关游戏模式
4. 开关双设备连接
5. 切换 EQ 预设（如果有）
6. 摘下/戴上耳机（触发佩戴检测）

### 第 3 步：导出 log

```bash
# 导出 snoop log 到电脑
adb bugreport bugreport.zip
# 解压后在 FS/data/misc/bluetooth/logs/ 下找 btsnoop_hci.log
# 或者直接 pull（部分设备）
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log .
```

### 第 4 步：用 Wireshark 分析

1. 用 Wireshark 打开 `btsnoop_hci.log`
2. 过滤 BLE 数据：`btatt` 或 `btle`
3. 重点看 `ATT Protocol > Write Request` 和 `ATT Protocol > Handle Value Notification`
4. 记录每个操作的 Handle（对应 Characteristic）和 Value（十六进制）

---

## 需要记录的关键数据

抓包完成后，请提供以下信息（截图或文字均可）：

### 1. GATT 服务结构
```
Service UUID: ?
  ├─ Characteristic UUID: ?  (属性: Read/Write/Notify)
  ├─ Characteristic UUID: ?  (属性: ?)
  └─ Characteristic UUID: ?  (属性: ?)
```

### 2. 命令格式
```
电量查询 → Write: ??
电量推送 ← Notify: ??
ANC 关   → Write: ??
ANC 降噪 → Write: ??
ANC 通透 → Write: ??
ANC 推送 ← Notify: ??
游戏模式开 → Write: ??
游戏模式关 → Write: ??
```

### 3. 广播数据（可选）
如果耳机有 manufacturer-specific data 广播：
```
Company ID: ?
Manufacturer Data: ??
```

---

## 抓包脚本

项目根目录 `docs/` 下有两个辅助脚本：

- `capture_ble.sh` — 在手机上自动开启 snoop log + 引导操作
- `parse_btsnoop.py` — 解析 btsnoop_hci.log，提取 ATT 交互

把抓到的数据和上面的格式发给我，我来写 VivoProtocol/VivoController/VivoPod。