# Session Handoff

Project: `C:\IDE\Android\tianyinwallpaper`

Updated: 2026-06-07

Current goal: continue the depth wallpaper Gaussian native rewrite while keeping WebView as A/B comparison and fallback. The current user-facing bar is not "Vulkan can render something"; it is native matching the WebView/GLES visual behavior while meeting or beating GLES performance.

## Current Status - 2026-06-07

### SOG Directory Storage (2026-06-07)

SOG files are now copied to the app's internal files directory on import, with each SOG and its thumbnail stored together under `filesDir/sog/<modelId>/`:

- `DepthPrefs.sogDir(context, modelId)` → `filesDir/sog/<modelId>/`
- `DepthPrefs.sogSceneFile(context, modelId)` → `filesDir/sog/<modelId>/scene.sog`
- `DepthPrefs.sogThumbnailFile(context, modelId)` → `filesDir/sog/<modelId>/thumbnail.jpg`
- `DepthPrefs.copySogToAppDir(context, uri, modelId)` copies a content URI into the SOG dir, returns a `file://` URI
- `DepthPrefs.deleteSogDir(context, modelId)` removes the entire per-model directory

`addWallpaper()` in `DepthRouteScreen.kt` now calls `copySogToAppDir()` for both new wallpapers and replacement (preview) wallpapers. The stored `gaussianUri` is the local file URI, not the original content URI.

Thumbnail cache now uses `sogThumbnailFile()` — thumbnails live alongside their SOG in the same directory. `removeWallpaper()` and `removeSelectedWallpapers()` call `deleteSogDir()` to clean up the directory.

Unused imports and constants removed: `MessageDigest`, `GAUSSIAN_THUMBNAIL_CACHE_VERSION`.

### Vulkan / GLES Status

Vulkan refactor status: **not complete**.

What is done:

- `VulkanGaussianRenderer` exists and is selected by `NativeGaussianRendererFactory` on Vulkan-capable devices, with `DepthGLRenderer` retained as GLES fallback.
- Vulkan can create a surface-backed renderer, upload Gaussian buffers, and draw instanced Gaussian quads.
- Vulkan work was moved off the UI thread onto `HandlerThread("TianyinVulkanRenderer")` after an ANR log showed the app timing out shortly after `Vulkan scene uploaded count=494164`.
- Vulkan Y orientation was corrected after the preview appeared vertically flipped.
- Vulkan point scale was changed to match GLES semantics. The earlier Vulkan path multiplied `splatScale` by surface/image scale, which made splats far too large, blurry, and expensive.
- Preview parameter changes are persisted when exiting the preview page.
- The preview parameter formerly shown as wallpaper density was renamed to clarity, and the minimum splat budget was lowered to `0.1M`.

What is still not done:

- Vulkan visual quality is not yet proven equivalent to GLES/WebView across real SOG files. The user reported poor visual quality and worse performance than GLES before the latest orientation/scale fix.
- Vulkan performance is not yet proven to match or beat GLES. Treat Vulkan as an in-progress backend, not as a completed replacement.
- Vulkan still lacks the mature GLES hot-path optimizations documented later in this file, such as the compact/interleaved SOG path, CPU camera-frame precompute, alpha LUT, octagon/strip geometry, and the wallpaper-specific pacing/backpressure work.
- If Vulkan still performs worse after the latest scale/orientation fix, prefer making GLES the default stable native backend and keeping Vulkan behind an explicit experimental switch while continuing Vulkan optimization.

Latest relevant commits:

- `547a7f19 Add Vulkan Gaussian renderer surface backend`
- `9389b647 Upload Gaussian buffers to Vulkan backend`
- `b1ad8fb5 Render Gaussian points with Vulkan backend`
- `439da1fc Align Vulkan Gaussian splats with GLES quads`
- `714e7d21 Move Vulkan Gaussian rendering off UI thread`
- `a2894b84 Align Vulkan Gaussian scale and orientation`
- `82dd1579 Persist depth preview parameter edits`
- `6b6fc8e5 Rename depth clarity control`

Important correction: older sections below mention "WebView Route Deleted" and "native Gaussian only". That is stale for the current worktree. Current code still has `SuperSplatWebView`, `useWebGaussianRenderer()`, and the Native/WebView preview toggle. Keep WebView for comparison/fallback unless the user explicitly asks to delete it again.

## Hard Rules

- Do not reduce visual quality to gain performance: do not reduce resolution, scale, opacity, shader effect, or camera behavior unless the user explicitly asks. The user has explicitly allowed SuperSplat-style dynamic splat budget / LOD filtering for 60fps and service smoothness.
- Native Gaussian should preserve the current intended SuperSplat-aligned camera/parameter behavior. Do not silently switch back to bounds center or invent new camera defaults.
- Keep WebView available as A/B comparison and fallback for now.
- PLY route is removed from normal import; current Gaussian import is SOG.
- For thumbnails, do not parse SOG or generate a synthetic preview. Use persisted preview screenshots.

## Latest Changes

### WebView Route Deleted

- `DepthWallpaperModel.useWebGaussianRenderer()` was removed.
- `DepthRouteScreen.normalizedDepthParams()` forces `gaussianRenderMode = "native"`.
- The preview parameter panel no longer shows any WebView/native route toggle.
- `DepthWallpaperService.loadContent()` always uses native Gaussian for Gaussian models, even if old saved configs contain `gaussianRenderMode = "web"`.
- `SuperSplatWebView.kt`, `SuperSplatWebController.kt`, and `app/src/main/assets/supersplat-viewer/*` were removed.
- Native camera defaults moved to `app/src/main/assets/gaussian-camera-settings.json`.

### Thumbnail Flow

- Gaussian thumbnails no longer call `generateGaussianThumbnail()` and no longer parse SOG for thumbnail generation.
- Thumbnail cards read persisted JPEG screenshots from:
  - `context.filesDir/depth_preview_thumbnails_v2/<modelId>.jpg`
- Preview exit/apply captures the current native preview and persists it.
- Because native preview is rendered in a `SurfaceView`, screenshot capture now prefers `PixelCopy.request(surfaceView, bitmap, ...)`.
- Window `PixelCopy` remains only as a fallback, because window capture can miss `SurfaceView` contents and produce black thumbnails.
- `DepthPreviewView` exposes the native preview `SurfaceView` through `onCaptureViewChange`.
- Parameter changes mark `previewScreenshotDirty = true`; when leaving preview or applying wallpaper, a fresh screenshot is saved.

### SOG / Native Performance Work

- `GaussianSogLoader` now builds compact SOG scenes:
  - positions are decoded once on CPU into float32 xyz VBO data, avoiding per-frame vertex-shader symmetric unlog/`exp()` work
  - color channels are decoded from `sh0.webp` codebook once during load and stored as normalized RGBA bytes
  - scale channels and quaternion bytes are decoded once during load into 3D covariance half floats
  - final `GaussianScene.compact` avoids keeping full float VBO data for SOG
- `DepthGLRenderer` uploads compact VBOs and decodes only SOG quantized positions in the compact vertex path; compact covariance projection avoids per-vertex quaternion decode and quat-to-matrix math.
- `sh0` and scale codebook dynamic uniform lookups were removed from the vertex shader because 60fps still caused sustained launcher jank; this trades a tiny VBO increase for lower per-frame shader cost.
- Current compact GPU upload size is now 28 bytes per splat:
  - decoded position xyz: 12 bytes
  - decoded RGBA color: 4 bytes
  - covariance half floats A: 6 bytes
  - covariance half floats B: 6 bytes
- 2026-06-04 update: compact SOG data is now uploaded as one interleaved 28-byte-per-splat VBO instead of four separate VBO streams.
- 2026-06-04 update: the active quad shader is now SOG-only. The old PLY/float fallback branch, quaternion decode, rotation-matrix rebuild, `uCompactData`, and related uniforms were removed from the hot shader path.
- 2026-06-04 update: static quad uniforms now track only true static draw state. Camera movement and focus/distance changes use the dynamic camera-frame upload path without invalidating static projection uniforms.
- 2026-06-04 update: splat quad geometry now uses a circumscribed 8-vertex octagon triangle strip instead of a 4-vertex square. The octagon still contains the full unit-circle alpha mask, so it does not reduce splat count or alter the alpha curve, but trims square-corner fragments that would be discarded by the fragment shader.
- `DepthGLRenderer` logs whether a scene is compact and logs compact/float VBO upload byte size.
- `GaussianSceneLoader` duplicates compact buffers for consumers, so preview/service GL threads do not fight over buffer positions.
- `SogSplatAccumulator` was reduced to smaller arrays to lower Java heap pressure.
- 2026-06-05 update: `GaussianSogLoader` now exposes `PerformanceParams`, `highPerformanceParams()`, and `performanceParamsForRefreshFps()`, including dynamic `splatBudget`, `minContribution = 3f`, `minPixelSize = 2f`, LOD range 0-10, behind-camera LOD penalty 2f, and radial sorting metadata.
- 2026-06-05 update: Android Gaussian budgets are aligned to SuperSplat-style mobile defaults: high = 2,000,000 splats, low = 1,000,000 splats. `alphaClipForward` is recorded as 1/255f.
- 2026-06-05 update: SOG decode now filters by contribution/LOD and keeps the strongest splats inside a bounded priority queue instead of random sampling when a scene exceeds budget. Compact scenes also store per-splat priority aligned with the interleaved buffer.
- 2026-06-05 update: radial sorting is applied during SOG scene build with a small radial bucket inside the existing depth-bucket order.
- 2026-06-05 update: `GaussianSceneLoader` cache keys now include `PerformanceParams`. Preview loads high budget; service loads low budget for `nativeRefreshFps >= 55`, otherwise high.
- 2026-06-05 update: `DepthGLRenderer` now has separate `GaussianPerformanceParams`. Active budget changes schedule a serial background rebuild of the selected interleaved buffer, then the GL thread uploads only the newest completed generation. Current-camera radial resort uses a 10 degree threshold. Consecutive slow service frames can temporarily downgrade active budget to low; stable non-60fps rendering can recover to high.
- 2026-06-05 follow-up: `ENABLE_SOG_MIN_CONTRIBUTION_FILTER` was fixed to use `opacity * projected ellipse area` instead of a max-scale square estimate. `ENABLE_RENDERER_CAMERA_RADIAL_SORT` was fixed to sort by camera distance far-to-near instead of screen radial buckets, preserving translucent blend order more closely.
- 2026-06-05 follow-up: `ENABLE_SOG_MIN_CONTRIBUTION_FILTER` remains enabled, but it no longer rejects splats before the active budget is full and no longer rejects a splat that is stronger than the current weakest selected splat. This keeps the optimization from creating under-filled/holed selections.
- 2026-06-05 follow-up: `alphaClipForward` is now wired into the native Gaussian quad fragment shader as `uAlphaClip` instead of being a hardcoded GLSL constant. Active VBO upload/draw logs now include selected/source count, budget, filtered/dropped counts, radial sorting state, and alpha clip.
- 2026-06-05 follow-up: Service 60fps now disables runtime camera radial resort in renderer performance params, avoiding repeated background VBO rebuild/upload on the smoothness-critical path. Preview and non-60fps service can still use runtime distance sort.
- 2026-06-05 follow-up: sustained slow frames can temporarily drop below the nominal low budget to 65% of low budget for 60fps service. `DepthGLRenderer` now logs `gaussian perf` every 3s with active/source count, budget, fps, radial state, pressure-low state, and average draw/swap/total ms.
- 2026-06-05 follow-up: 24fps jank was traced to continuous wallpaper buffer submissions from sensor/tilt micro-motion rather than expensive Gaussian draw. Native Gaussian now uses higher tilt dispatch thresholds by fps, and renderer tilt target/stop epsilons are larger at low fps so it snaps to rest and stops submitting frames more aggressively.

### Service / Renderer Performance Work

- Native renderer input and frame pacing are decoupled from raw sensor event frequency.
- Renderer uses requested `nativeRefreshFps` pacing and stops drawing when tilt settles.
- Renderer frame pacing now uses nanosecond intervals instead of integer milliseconds, so 60fps targets `16.666ms` instead of accidentally tending toward `16ms` / `62.5fps`.
- Renderer pacing now uses the previous completed frame time, not the previous animation-step start time. Slow `draw+eglSwapBuffers` frames no longer cause immediate catch-up frames.
- Native Gaussian now has throttled composition backpressure detection: when consecutive frames exceed the frame budget or `eglSwapBuffers` blocks heavily, the renderer temporarily widens its pacing interval under pressure, then decays back when frames recover. This does not change splat count, shader output, camera logic, or quality parameters.
- Backpressure is now enforced inside the tilt animation step as well as the timed wait path, so high-frequency sensor wakeups cannot bypass pressure pacing.
- `DepthWallpaperService` enables wallpaper offset notifications and calls `DepthGLRenderer.notifyExternalCompositionPressure()` during launcher page offset changes. While the launcher is actively sliding pages, native Gaussian briefly yields to a 30fps pacing window for composition stability, without changing splat count, shader output, camera parameters, or model data.
- Per-frame main-thread `Choreographer` wakeups for the wallpaper render thread are disabled; the render thread now uses timed waits and `eglSwapBuffers` pacing.
- Wallpaper render thread priority is set slightly below normal (`THREAD_PRIORITY_DEFAULT + 2`) so launcher/system animations get scheduling preference under contention.
- EGL config no longer requests a depth buffer for the Gaussian renderer because depth testing is disabled for this path; this avoids unnecessary depth attachment memory/bandwidth.
- EGL config now prefers an opaque/no-alpha surface config first, matching the wallpaper `RGBX_8888` surface; alpha/no-depth remains as a fallback if opaque config is unavailable.
- Gaussian quad rendering now uses a 4-vertex `GL_TRIANGLE_STRIP` instead of 6 triangle-list vertices, reducing vertex shader invocations by one third with the same splat quad shape.
- Gaussian quad camera frame is computed once per frame on the GL thread CPU side and uploaded as `uCameraPosition/uCameraRight/uCameraUp/uCameraForward`, instead of recomputing camera frame per splat vertex in the vertex shader.
- Gaussian quad fragment alpha falloff now uses a 1024-sample GL alpha LUT texture (`uAlphaLut`) generated on the GL thread from the same curve, so the fragment shader no longer runs per-pixel `exp()`.
- Compact Gaussian shader now uses pre-baked 3D covariance (`aScale` + `aRotation.xyz`) instead of decoding SOG quaternion bytes and rebuilding rotation matrices per vertex.
- Compact Gaussian shader now receives decoded float32 positions from the VBO, removing the former per-vertex SOG position unlog/`exp()` path.
- Service unregisters sensors and pauses renderer when invisible/surface destroyed.
- Native Gaussian loading is serialized to avoid overlapping SOG decode tasks.
- `DepthWallpaperService` avoids restarting native GL renderer unnecessarily using `nativeRendererStarted`.
- `DepthGLRenderer.stopAsync()` uses retiring thread handling to avoid blocking UI while preventing unbounded thread overlap.
- Logs in hot render paths are debug-gated/throttled.

### Native Shader / Draw Path Optimizations

- Normal draw frames no longer call `GLES20.glGetError()` through `logGlError()` unless `CHECK_DRAW_GL_ERRORS` is explicitly enabled. Resource upload, shader compile/link, VAO creation, alpha LUT upload, and `eglSwapBuffers` failure checks still keep error logging.
- Gaussian quad shader now receives precomputed projection constants:
  - `uFocalPixels`
  - `uClipScale`
  - `uPixelToClip`
- The quad shader no longer recomputes focal pixels and pixel-to-clip scale per splat vertex. This reduces repeated division/ALU work without changing camera behavior or image quality.
- Unused quad shader uniforms and uploads were removed from the active quad path, including old `uFocal/uImageSize/uFillScale/uTilt/uStrength/uFocusDepth/uFarDepth/uDefaultTarget/uDefaultCamera/uSceneRadius/uTanHalfFov/uCameraZoom/uCenterOffset/uFocusDepthOffset`.
- Compact/SOG covariance projection was simplified:
  - `rawCovXX/rawCovYY` use a dedicated `covarianceQuadratic()`
  - `rawCovXY` uses `covarianceBilinear()`
  - float PLY fallback still keeps the rotation-matrix path
- Constant quad extent was removed from the shader uniform path. `uQuadExtent` and `GAUSSIAN_QUAD_EXTENT` were removed because the active value was always `1.0`; shader now uses `aCorner` directly and clips with `radius2 > 1.0`.
- These changes are shader/GL-call optimizations only. They do not reduce splat count, resolution, scale, opacity, blend behavior, camera defaults, or SuperSplat-aligned parameters.

## Important Log Findings

Log file inspected: `OnePlus-PLQ110-Android-16_2026-06-03_162352.logcat`

Key findings:

- Multiple `Throwing OutOfMemoryError` entries occurred around 4.5 MB allocations with heap limit 256 MB.
- `GaussianSogLoader` reported:
  - `built SOG scene count=1168470 ... heap=217MB`
  - later another build at `heap=233MB`
- This indicated Java heap pressure during SOG loading/building, and also suggested repeated scene construction.
- Thumbnail generation was one repeated-load source because it used to call `GaussianSceneLoader.loadScene()` just to create a thumbnail. This has now been removed.
- User reported 30fps is relatively smooth, while 60fps causes continuous launcher frame drops and occasional high-load drops. Latest renderer pacing changes target this without reducing splat count or visual parameters.

Perfetto traces captured:

- `perfetto_gaussian_wallpaper_20260603_221901.perfetto-trace`
- `perfetto_gaussian_wallpaper_20260603_224802.perfetto-trace`

Perfetto findings:

- The second trace had process/thread names and showed `com.zeaze.tianyinwallpaper` threads including `DepthGLRenderer`, but the wallpaper render thread had no meaningful sched runtime during the captured 35s window. This suggests the previous idle/no-input render suppression is working.
- `actual_frame_timeline_slice` and `expected_frame_timeline_slice` were empty despite the device reporting `android.surfaceflinger.frametimeline` support. Treat frame-timeline attribution from this capture as unavailable on this ROM/config.
- The captured heavy activity was mostly outside the wallpaper process:
  - `system_server` around 2092ms CPU in the trace
  - `com.taobao.taobao` around 1573ms CPU
  - SurfaceFlinger around 66.8ms CPU
- Negative-one/feed style content requested `RenderRate = 120` and emitted `Choreographer#doFrame - resynced ... in 22-24ms` slices.
- The user clarified that further work should focus on renderer/shader code optimization, not scene-specific or pressure-scenario handling.

## Current Verification Status

Last compile command:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Status: passed.

Known warnings are existing/deprecation style warnings; no compile errors after the latest thumbnail, WebView-route deletion, SOG-only shader, and interleaved VBO changes.

Latest compile after predecoded color/covariance/position compact VBO, 60fps pacing changes, compact covariance shader changes, alpha-LUT fragment shader optimization, completed-frame pacing, composition backpressure, launcher-offset yielding, opaque/no-depth EGL config, 2026-06-04 shader/GL-call optimizations, SOG-only shader, interleaved VBO, circumscribed octagon splat geometry, and WebView-route deletion also passed.

## Needs Real Device Testing

0. Re-test Vulkan after `a2894b84`:
   - preview should no longer be vertically flipped
   - splats should no longer appear over-enlarged/blurred from an extra texture-scale multiplier
   - compare Vulkan vs GLES at the same SOG and same clarity value
   - if Vulkan still loses to GLES on quality or FPS, make GLES the stable default and keep Vulkan experimental until optimized
1. Add/open a SOG Gaussian wallpaper, enter preview, wait for rendering, exit preview. Confirm the card thumbnail is not black.
2. Change preview parameters, exit preview, and confirm the thumbnail updates.
3. Apply as live wallpaper and confirm service uses native Gaussian only.
4. Check logcat for:
   - no `loadContent web gaussian`
   - `loadGaussians ... compact=true`
   - `upload gaussian interleaved compact VBO ...`
   - no `gaussian quad vertex shader compile error`
   - no `Program link error`
   - no runtime shader/link errors after the new `uFocalPixels/uClipScale/uPixelToClip` shader uniforms
   - reduced or absent `WaitForGcToComplete blocked Alloc`
   - no repeated SOG scene builds for thumbnail refresh
5. Test launcher sliding, lock/unlock, and background/foreground transitions for frame drops and sensor recovery.
6. Specifically compare native Gaussian at 30fps and 60fps after the nanosecond pacing change:
   - 60fps should no longer oversubmit frames at a 16ms cadence.
   - launcher sliding should have fewer sustained frame drops.
   - wallpaper may yield more often under launcher pressure because render thread priority is slightly lower.
7. Re-capture Perfetto after the 2026-06-04 shader optimizations. Since prior frame-timeline tables were empty, focus on:
   - `DepthGLRenderer` sched runtime
   - `eglSwapBuffers`/SurfaceFlinger slices if available
   - CPU/GPU contention during 60fps native Gaussian
   - whether the foreground app/system still dominates jank while wallpaper stays mostly idle

## Key Files

- `app/src/main/java/com/zeaze/tianyinwallpaper/model/DepthWallpaperModel.kt`
- `app/src/main/java/com/zeaze/tianyinwallpaper/ui/depth/DepthRouteScreen.kt`
- `app/src/main/java/com/zeaze/tianyinwallpaper/ui/depth/DepthPreviewView.kt`
- `app/src/main/java/com/zeaze/tianyinwallpaper/service/DepthWallpaperService.kt`
- `app/src/main/java/com/zeaze/tianyinwallpaper/renderer/DepthGLRenderer.kt`
- `app/src/main/java/com/zeaze/tianyinwallpaper/renderer/VulkanGaussianRenderer.kt`
- `app/src/main/java/com/zeaze/tianyinwallpaper/renderer/NativeGaussianRenderer.kt`
- `app/src/main/cpp/vulkan_gaussian_jni.cpp`
- `app/src/main/cpp/shaders/gaussian_quad.vert`
- `app/src/main/cpp/shaders/gaussian_quad.frag`
- `app/src/main/cpp/vulkan_gaussian_shaders.h`
- `app/src/main/java/com/zeaze/tianyinwallpaper/utils/GaussianSceneLoader.kt`
- `app/src/main/java/com/zeaze/tianyinwallpaper/utils/GaussianSogLoader.kt`
- `app/src/main/java/com/zeaze/tianyinwallpaper/utils/GaussianPlyLoader.kt`

## Cautions

- Do not bring back WebView as an active route unless the user explicitly reverses the decision.
- Do not restore thumbnail generation from SOG parsing; it caused unnecessary heavy loads and can pollute cache.
- If thumbnails are still black, investigate `PixelCopy.request(SurfaceView, ...)` timing and whether the preview has rendered a first frame before capture.
- If OOM/GC persists, next target is remaining SOG parse-time temporary data and duplicate preview/service loads, not lowering quality.
- If 60fps still causes sustained launcher jank after predecoded color/scale/position, alpha-LUT shader change, completed-frame pacing, composition backpressure, launcher-offset yielding, and opaque/no-depth EGL config, the remaining likely bottleneck is intrinsic transparent splat overdraw/blending or SurfaceFlinger composition pressure. Do not reduce splat count, camera behavior, or visual parameters without explicit approval.
- Retest 60fps on device. If it still drops frames even with backpressure, capture logcat with `DepthGLRenderer` DEBUG logs temporarily enabled or use Perfetto/GPU profiler to confirm whether time is in draw, `eglSwapBuffers`, or system composition.
