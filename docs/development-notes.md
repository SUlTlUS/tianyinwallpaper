# 开发注意事项

本文档记录天音壁纸项目在后台服务、OpenGL 渲染、Bitmap 处理和性能优化中需要特别注意的事项。目标是让后续开发有一份稳定的检查清单，避免重复踩坑。

## 后台服务与可见性

- `WallpaperService.Engine` 中的重活必须跟随可见性变化启停。`onVisibilityChanged(false)` 时应暂停播放器、传感器监听、定时渲染循环和不必要的回调。
- 视频帧回调、传感器回调和定时任务在不可见状态下不要继续触发渲染。即使播放器已经暂停，也要在回调入口加可见性保护。
- `onSurfaceDestroyed()` 必须释放或停止 GL 线程、播放器 Surface、传感器监听和 Handler 回调，避免壁纸被系统卸载后后台线程继续运行。
- 自动切换壁纸这类定时逻辑应使用明确的下一次触发时间，不要用短间隔轮询。
- 不要在不可见时为了“预热”主动解码图片、seek 视频或触发 GL 绘制。壁纸服务在后台很容易被系统功耗策略盯上。

## 渲染线程

- GL 渲染线程应尽量事件驱动。没有新帧、参数变化或明确 `requestRender()` 时，不要固定 `Thread.sleep(16)` 循环绘制。
- 合并重复 `requestRender()` 请求，避免 UI/传感器高频事件把渲染队列塞满。
- GL 线程只做 GL 相关工作。图片解码、Bitmap 格式转换、视频转码、文件读取等 CPU/IO 工作应在进入 GL 线程前完成。
- 不要在每帧绘制、过渡绘制、`uploadToTexA/B()` 或 `draw()` 中创建大对象，尤其是 `Bitmap`、大数组和频繁字符串格式化。
- GL 资源释放要在拥有 EGL context 的线程中执行，避免跨线程销毁纹理、program 或 surface。
- 日志要节流。渲染循环里不要逐帧 `Log.d/Log.w`，需要排查时也应按帧数取模输出。

## Bitmap 与 OpenGL 上传

- `GLUtils.texImage2D()` 对 Bitmap 的底层格式比较敏感，尤其在部分厂商 ROM 上，`bitmap.config == ARGB_8888` 也不一定代表 native 像素格式可上传。
- Bitmap 格式归一化应在加载阶段完成，并且只做一次。不要在 GL 上传路径里临时 `copy()` 或 `Canvas.drawBitmap()` 生成大图。
- 推荐加载流程：
  - `BitmapFactory.Options.inPreferredConfig = Bitmap.Config.ARGB_8888`
  - 解码后统一生成项目可控的 software `ARGB_8888` Bitmap
  - 将归一化后的 Bitmap 放入渲染列表或缓存
  - GL 线程只上传已经归一化好的 Bitmap
- 从缓存取出的 Bitmap 不要被调用方误回收。需要给渲染器独占使用时，应复制一份或建立明确的所有权约定。
- 如果图片尺寸过大，应在解码阶段采样缩放，不要先解完整大图再缩放。壁纸场景通常不需要超过目标屏幕太多的纹理尺寸。
- 回收 Bitmap 时要确认它不再被渲染线程、缓存或过渡纹理使用。错误回收会导致偶发崩溃或黑屏。

## 传感器

- 壁纸场景优先使用 `SensorManager.SENSOR_DELAY_UI`。除非确实需要游戏级响应，不要使用 `SENSOR_DELAY_GAME`。
- 传感器注册只应发生在可见状态，隐藏或销毁时必须注销。
- 传感器数据进入渲染前应有变化阈值或节流逻辑，避免细微抖动导致持续重绘。
- 使用陀螺仪时要注意屏幕旋转方向映射，并保留 `else` 分支，避免 `when` 表达式不完整导致编译错误。

## 视频壁纸

- 视频壁纸需要区分“播放状态”和“渲染状态”。播放器有新帧时再触发渲染，静止或不可见时应暂停。
- 固定帧率轮询要谨慎。如果必须轮询，至少区分活跃和静止状态，静止时降低检查频率。
- `SurfaceTexture.OnFrameAvailableListener` 中不要做重活，只标记新帧并请求渲染。
- `MediaPlayer` 的 `reset/release` 必须与当前 Surface 生命周期配合，避免 Surface 已销毁后继续回调。
- 视频转码、关键帧缓存等耗时任务不能阻塞主线程或 EGL 线程。

## SharedPreferences 与配置更新

- 配置变化监听中只做轻量判断，重载图片或视频时要避免重复触发。
- 如果配置变化只影响参数，不要重新解码图片或重建播放器。优先走参数更新路径。
- 当前壁纸列表重新加载后，应尽量通过 `uuid` 或 uri 映射回旧索引，避免用户正在显示的壁纸突然跳变。

## 崩溃排查要点

- `FATAL EXCEPTION: RasterGLRenderer` 通常优先检查 GL 线程中的 Bitmap 上传、纹理生命周期和渲染循环。
- `java.lang.IllegalArgumentException: invalid Bitmap format` 通常是 Bitmap native 格式不被 `GLUtils.texImage2D()` 接受。优先检查加载阶段是否完成了标准化。
- `Fatal signal 6 (SIGABRT)` 且栈在 Bitmap 创建或上传附近时，常见原因是 GL 线程中频繁分配大图或内存压力过高。
- 只看应用自身日志还不够，系统服务日志里出现 `Wallpaper uninstalled`、进程被杀、传感器或热管理错误时，也要结合生命周期检查。
- 厂商 ROM 会输出大量系统级 `E` 日志，不一定都是应用错误。判断优先级时看是否有本应用进程的 `FATAL EXCEPTION`、`AndroidRuntime` 或 native tombstone。

## 验证清单

- 编译：至少运行 `./gradlew :app:compileDebugKotlin`。
- 静态壁纸：进入预览、设置为壁纸、切后台、回桌面、熄屏再亮屏。
- 多图光栅：快速切换、多次触发扫描线过渡、触发淡出过渡。
- 视频壁纸：播放、暂停、不可见、恢复、切换视频、循环播放结束。
- 传感器：可见时响应正常，不可见时确认没有持续高频回调和重绘。
- 性能：观察 CPU、内存、线程数量和日志频率，确认静止状态没有持续 60fps 渲染。
- 稳定性：连续进入/退出预览页，确认没有 Surface 销毁后的回调崩溃。

## 修改原则

- 优先在生命周期边界停掉资源消耗，而不是只在内部加更多判断。
- 优先把昂贵工作前移到加载阶段，避免进入渲染循环。
- 优先复用现有 renderer/service 结构，避免为单个问题引入新的全局状态。
- 每次性能优化后都要复测崩溃路径，因为降低资源消耗的改动也可能改变线程时序。
- 遇到厂商 ROM 相关问题时，修复应尽量基于 Android 通用 API 的保守路径，避免依赖某台设备上的偶然行为。

## Gaussian Native/WebView Rule

- 所有 native 效果保持和 WebView 对齐，不要擅自修改。
