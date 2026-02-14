# 天音壁纸

代码集成了蒲公英sdk，如果不需要，可以删除，如果需要，请申请自己的蒲公英sdk

## 功能特性

### 横向自动滚动轮播

动态壁纸现在支持横向自动滚动轮播功能，可以在壁纸上展示多张图片的连续滚动效果。

**特性说明：**
- 在动态壁纸的Engine类中实现了RecyclerView样式的横向滚动
- 使用Handler和Runnable实现定时自动滚动
- 支持最多同时显示5张静态壁纸的轮播
- 平滑的60 FPS滚动动画
- 自动循环播放，滚动到最后一张后会重新开始
- 仅在静态壁纸模式下工作（不支持视频壁纸）

**使用方法：**
1. 在主界面点击设置图标
2. 选择"壁纸通用设置"
3. 勾选"启用横向自动滚动轮播"选项
4. 重新应用壁纸即可生效

**技术实现：**
- 在`TianYinWallpaperService.Engine`中初始化RecyclerView样式的滚动容器
- 在`onCreate()`方法中初始化Handler和自动滚动Runnable
- 使用Canvas直接绘制实现横向图片展示
- 通过`onVisibilityChanged`控制滚动的启动和停止
- 实现了完整的内存管理，避免Bitmap泄漏

## 构建APK

### 方法一：使用命令行构建

1. 确保已安装 JDK 11 或更高版本
2. 在项目根目录执行以下命令：

```bash
# Linux/Mac
./gradlew assembleRelease

# Windows
gradlew.bat assembleRelease
```

3. 构建完成后，APK文件位于：`app/build/outputs/apk/release/`目录下

### 方法二：使用Android Studio构建

1. 用Android Studio打开项目
2. 选择菜单 Build -> Build Bundle(s) / APK(s) -> Build APK(s)
3. 等待构建完成，点击通知中的"locate"定位APK文件

### 方法三：自动构建（GitHub Actions）

项目已配置GitHub Actions工作流，每次推送到main/master分支时会自动构建APK。
你也可以在GitHub仓库的Actions标签页手动触发构建。

构建产物可在Actions运行页面的Artifacts部分下载。

## 签名配置

项目的release构建类型默认使用debug keystore进行签名（用于CI/CD自动构建）。

**注意：** 如果您需要发布生产版本的APK，请配置正式的签名密钥：

1. 创建或使用您的release keystore文件
2. 在`app/build.gradle`中的`signingConfigs.release`部分配置您的密钥信息
3. 或者通过环境变量传递签名信息

