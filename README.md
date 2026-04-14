# ClaudeBle

Android 原生 Java BLE 客户端，用于扫描、连接并控制低功耗蓝牙设备，同时支持心跳收发、数据收发与状态解析。

## 项目简介

`ClaudeBle` 是基于 Python 版 BLE 客户端迁移而来的 Android 原生实现，目标是提供更方便的移动端调试与设备控制能力。

当前项目包含以下核心能力：

- BLE 设备扫描，支持名称过滤和超时停止
- 设备连接 / 断开
- 自定义数据特性 UUID 与心跳特性 UUID
- 设备控制指令发送
- 心跳循环发送与通知接收
- 心跳返回数据解析（标志位、流速、本周期流量、总流量）
- 数据收发窗口与心跳收发窗口实时显示
- Android 6.0 ~ Android 14 权限适配

## 功能概览

### 设备扫描
- 支持按设备名称关键字过滤
- 支持自定义扫描超时时间
- 列表中显示设备名称、MAC 地址和 RSSI

### BLE 连接与通信
- 连接指定 BLE 设备
- 自定义数据/心跳 Characteristic UUID
- 支持断开连接
- 支持心跳定时写入
- 支持心跳通知接收与解析

### 控制功能
- 打开 / 关闭清水泵（16 / 32）
- 打开 / 关闭清水阀（1 / 2）
- 打开 / 关闭污水泵与阀（4 / 8）
- 打开 / 关闭基站充电（512 / 1024）

## 快速开始

### 方式一：使用 Android Studio（推荐）

1. 打开 Android Studio
2. 选择 **File -> Open**
3. 打开项目目录：`E:\AndroidRel\OpenClawSpace\ClaudeBle`
4. 等待 Gradle Sync 完成
5. 连接 Android 设备或启动模拟器后运行项目

### 方式二：命令行构建

```bash
cd E:\AndroidRel\OpenClawSpace\ClaudeBle
gradlew.bat assembleDebug
```

构建成功后，调试 APK 通常位于：

`app\build\outputs\apk\debug\app-debug.apk`

## 使用流程

1. 打开 App 并授予蓝牙相关权限
2. 确认手机蓝牙已开启
3. 在过滤框中输入目标设备名称关键字（默认可使用 `mpy-temp`）
4. 设置扫描超时时间并点击“扫描设备”
5. 从列表中选择目标 BLE 设备
6. 检查或填写数据 UUID 与心跳 UUID
7. 点击“连接设备”建立 BLE 连接
8. 连接成功后可进行：
   - 发送控制指令
   - 观察数据收发窗口
   - 观察心跳收发窗口与解析结果

## 权限说明

### Android 12 及以上
运行时会请求：

- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `BLUETOOTH_ADVERTISE`

### Android 6 ~ 11
BLE 扫描通常需要位置权限，项目中声明了：

- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`
- `BLUETOOTH`
- `BLUETOOTH_ADMIN`

> 说明：不同 Android 版本对 BLE 扫描与连接的权限要求不同，首次运行时请按系统提示授权。

## 环境要求

- Android Studio Hedgehog（2023.1.1）或更高版本
- JDK 17+（推荐）
- Android SDK 34
- Gradle Wrapper（项目已包含）
- 最低支持 Android 5.0（`minSdk 21`）

## 项目结构

```text
ClaudeBle/
├─ app/
│  ├─ build.gradle
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/com/claude/ble/MainActivity.java
│     └─ res/
├─ CodeAndUi/
│  ├─ blue.txt
│  └─ blueui.png
├─ build.gradle
├─ settings.gradle
└─ README.md
```

## BLE 使用注意事项

- 本项目面向 **BLE（低功耗蓝牙 / GATT）** 通信，不是传统经典蓝牙串口通信
- BLE 设备是否需要系统配对，取决于设备固件和特性权限设计；多数纯 GATT 数据通信场景可直接连接
- 若扫描不到设备，请确认：
  - 手机蓝牙已开启
  - 已授予必要权限
  - 设备处于可广播状态
  - 名称过滤条件填写正确
- 若连接后无法正常收发数据，请优先检查数据 UUID 与心跳 UUID 是否正确

## 已实现内容

- 原生 Java BLE 扫描与连接
- BLE 服务发现与 Characteristic 读写/通知
- 心跳定时发送
- 心跳数据解析与流量统计
- 实时日志窗口显示
- 基础权限适配与运行时请求

## 开发信息

- 应用包名：`com.claude.ble`
- `compileSdk`：34
- `targetSdk`：34
- `minSdk`：21
- UI 使用 ViewBinding

## 常见问题

### 1. 扫描不到设备怎么办？

请依次检查：

- 手机蓝牙是否已开启
- App 权限是否已全部授予
- 设备是否正在广播
- 名称过滤条件是否过于严格

### 2. 连接后没有数据返回怎么办？

建议确认：

- 目标设备支持 BLE GATT
- 数据特性 UUID 是否填写正确
- 心跳特性 UUID 是否填写正确
- 设备端是否开启通知/返回机制

### 3. 构建失败怎么办？

可优先检查：

- 本机 JDK 版本是否满足要求
- Android SDK 34 是否已安装
- Gradle 依赖是否下载完成

## 项目来源

项目参考了 `CodeAndUi/blue.txt` 与 `CodeAndUi/blueui.png` 中的 Python 版本逻辑与界面设计，并迁移为 Android 原生 Java 实现。


