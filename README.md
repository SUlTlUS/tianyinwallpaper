# 天音壁纸

代码集成了蒲公英sdk，如果不需要，可以删除，如果需要，请申请自己的蒲公英sdk

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

