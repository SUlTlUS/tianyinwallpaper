# 天音壁纸

一款支持视频图片混合切换和光栅壁纸效果壁纸应用

## 功能特性

### 图集光栅壁纸
- 支持添加多张图片，通过倾斜手机切换显示不同图片
- 基于陀螺仪传感器的流畅图片过渡效果
- 支持不同过渡效果自定义

#### 效果参数自定义
- **传感器灵敏度**：调整图片切换对倾斜角度的响应程度
- **过渡区域宽度**：控制图片切换时的过渡区域大小
- **边缘柔化程度**：调整过渡边缘的柔和程度

### 视频光栅壁纸
- 支持添加视频，通过倾斜手机控制视频进度

### 界面特点
- Liquid Glass 风格
- 深色模式支持


## 构建APK

### 环境要求

- JDK 11 或更高版本
- Android SDK (推荐使用 Android Studio 自动安装)
- Gradle 8.0+ (项目已包含 Gradle Wrapper)

### 方法一：使用命令行构建

```bash
# Windows
gradlew.bat assembleRelease

# Linux/Mac
./gradlew assembleRelease
```

构建完成后，APK 文件位于：`app/build/outputs/apk/release/` 目录下

### 方法二：使用 Android Studio 构建

1. 用 Android Studio 打开项目
2. 选择菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**
3. 等待构建完成，点击通知中的 **locate** 定位 APK 文件

### 方法三：自动构建（GitHub Actions）

项目已配置 GitHub Actions 工作流，每次推送到 main/master 分支时会自动构建 APK。
你也可以在 GitHub 仓库的 Actions 标签页手动触发构建。

构建产物可在 Actions 运行页面的 Artifacts 部分下载。

## 技术栈

- **语言**：Kotlin
- **UI 框架**：Jetpack Compose
- **渲染引擎**：OpenGL ES 2.0
- **传感器**：Android Gyroscope API
- **架构**：MVVM

## 项目结构

```
app/src/main/java/com/zeaze/tianyinwallpaper/
├── backdrop/          # Liquid Glass 毛玻璃效果组件
├── model/             # 数据模型
├── renderer/          # OpenGL 渲染器
├── service/           # 壁纸服务
├── ui/                # UI 界面
└── MainActivity.kt    # 主活动
```

## 许可证

使用Apache协议开源