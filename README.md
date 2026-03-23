# ClaudeBle - Android BLE 蓝牙客户端

## 项目说明
本项目是 Python BLE 客户端的 Android 原生（Java）版本，具备完整的 BLE 功能。

## 功能列表
- ✅ 设备扫描（支持名称过滤 & 超时设置）
- ✅ 连接/断开 BLE 设备
- ✅ 数据特性 UUID 自定义
- ✅ 心跳特性 UUID 自定义
- ✅ 打开/关闭 清水泵（控制码 16/32）
- ✅ 打开/关闭 清水阀（控制码 1/2）
- ✅ 打开/关闭 污水泵（控制码 4/8）
- ✅ 基站充电功能 打开/关闭（控制码 512/1024）
- ✅ 心跳循环发送（每秒发送一次，值为"1"）
- ✅ 心跳通知接收 & 解析（标志位解析 + 流速/流量计算）
- ✅ 数据收发窗口 & 心跳收发窗口
- ✅ 适配 Android 6~14（权限动态申请）

## 导入步骤

### 方法一：Android Studio 导入（推荐）
1. 打开 Android Studio
2. File → Open → 选择 `E:\AndroidRel\OpenClawSpace\ClaudeBle` 文件夹
3. 等待 Gradle Sync 完成（会自动下载依赖）
4. 点击 Run 即可运行

### 方法二：命令行构建
```bash
cd E:\AndroidRel\OpenClawSpace\ClaudeBle
gradlew.bat assembleDebug
```

## 权限说明
- Android 12+：BLUETOOTH_SCAN、BLUETOOTH_CONNECT（运行时申请）
- Android 6~11：ACCESS_FINE_LOCATION、BLUETOOTH（运行时申请）

## 环境要求
- Android Studio Hedgehog (2023.1.1) 或更新版本
- JDK 17+
- Android SDK API 34
- Gradle 8.4

## 注意事项
- 第一次打开项目时，Android Studio 会自动下载 gradle-wrapper.jar 和所有依赖
- 请确保手机开启蓝牙，并授予所有请求的权限
- 默认过滤名称为 "mpy-temp"，可在界面上修改

## ClaudeBle 生成提示词
- 1.python代码为blue.txt文件（已上传），运行这个blue.txt文件后，代码UI界面为blueui.png
- 2.根据python版本代码，以及UI界面效果，生成一个Android原生项目(java)，具备python版本的全部功能，
- 3.一定要实现blueui.png所展示的全部功能.
- 4.要求适配，安卓最新的系统，适配绝大部分安卓手机，如果有权限请求，也要一并适配。
- 5.生成的项目名称为ClaudeBle， 放到E:\AndroidRel\OpenClawSpace文件夹下面.
- 6.确保项目可以正常构建，运行，开始吧。

## BUG 提示词
- 1.把心跳收发窗口和数据收发窗口的TextView换成EditText，其他功能维持不变，不要变动.
- 2.目前问题：控制按钮无法点击，主线程被阻塞。
- 3.需要优化部分：优化跳收发窗口和数据收发窗口EditText的实时显示功能，确保不会阻塞主线程

