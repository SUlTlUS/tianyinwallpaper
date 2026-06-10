# Liquid Glass 同步策略

`tianyinwallpaper` 的液态玻璃组件来源于 Kyant0/AndroidLiquidGlass 的 catalog 示例，但项目内已经加入了 Tianyin 自己的交互和视觉策略，所以不要再直接整文件覆盖。

本次对比的上游位置：

- `Kyant0/AndroidLiquidGlass`，默认分支 `kmp`
- `app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidButton.kt`
- `app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt`

上游 README 也明确说明库本身不包含高层组件，`LiquidButton`、`LiquidBottomTabs` 只是示例组件。因此本项目应该把它们当“参考实现”，而不是当可直接覆盖的依赖代码。

## 分层约定

### 1. 上游/底层层

这些文件尽量保持接近 AndroidLiquidGlass/Backdrop 的底层实现：

- `app/src/main/java/com/zeaze/tianyinwallpaper/backdrop/**`
- `app/src/main/java/com/zeaze/tianyinwallpaper/backdrop/backdrops/**`
- `app/src/main/java/com/zeaze/tianyinwallpaper/backdrop/effects/**`
- `app/src/main/java/com/zeaze/tianyinwallpaper/backdrop/highlight/**`
- `app/src/main/java/com/zeaze/tianyinwallpaper/backdrop/shadow/**`

后续同步上游 2.x 时，优先对比这些目录。AndroidLiquidGlass 2.0.0 的主要方向是 Compose Multiplatform 和 RuntimeShader 扩展入口；如果底层 API 变化，优先在这里做适配。

### 2. Tianyin 组件层

这些是项目自己的产品组件，不能直接用上游 catalog 覆盖：

- `catalog/components/LiquidButton.kt`
- `catalog/components/LiquidBottomTab.kt`
- `catalog/components/LiquidBottomTabs.kt`

它们保留了：

- `backdrop == null` 时的普通半透明降级样式；
- 外部传入 `isLightTheme` 的浅深色适配；
- Tianyin 顶部栏和底部栏的固定尺寸；
- 选中 Tab 的弹性缩放、速度形变和隐藏参考层合成；
- `AdaptiveLuminanceGlassState` 联动的按钮亮度/对比度/饱和度；
- 景深分支的 `LiquidButton` 图标参数；
- 景深分支的 `LiquidBottomTabs` 选择同步修复。

### 3. 样式 token 层

新增的 `catalog/components/LiquidGlassStyles.kt` 用来集中维护项目自定义参数。

- 修改颜色、尺寸、模糊半径、透镜半径时，优先改这里。
- 同步上游 catalog 示例时，尽量只迁移结构/bugfix，不要把 Tianyin 的参数重新写死进组件。
- 需要局部特殊样式时，给组件传入新的 `LiquidButtonStyle` 或 `LiquidBottomTabsStyle`，不要新增一堆魔法数字。

## 本次组件更新结果

### LiquidButton

上游 `LiquidButton` 当前仍是基础示例：`backdrop` 必传、固定 `48.dp` 高度、固定 `16.dp` padding、固定 blur/lens 参数、无图标参数、无 luminance 联动。

本项目版本保留上游结构，但额外支持：

- `LiquidButtonStyle` 外置样式参数；
- `AdaptiveLuminanceGlassState`；
- 图标按钮参数；
- 自定义高度和 padding；
- 原有 tint/surfaceColor 行为。

### LiquidBottomTabs

上游 `LiquidBottomTabs` 当前仍是基础示例：`backdrop` 必传，内部通过 `isSystemInDarkTheme()` 判定主题，没有低版本 fallback。

本项目版本保留上游核心结构，但额外支持：

- `backdrop: Backdrop?`，无玻璃时走普通半透明 fallback；
- 外部传入 `isLightTheme`，避免组件自己读取系统主题；
- `LiquidBottomTabsStyle` 外置颜色、尺寸、blur/lens 参数；
- 景深分支的 `pendingSelectionIndex`，避免拖动 Tab 与 Pager 状态回写打架；
- `safeTabsCount` 和 `constraints.maxWidth == 0` 保护，避免极端空 Tab/未布局时除零。

## 上游同步步骤

1. 先升级/对比 `backdrop` 底层实现。
2. 再查看上游 catalog 示例是否修复了交互 bug。
3. 把有价值的结构性变更手动移植到 Tianyin 组件层。
4. 保留 `LiquidGlassStyles.kt` 中的项目参数。
5. 在 Android 13+ 和低版本设备各测一次：
   - Android 13+：液态玻璃、RuntimeShader 高光、Backdrop 生效；
   - 低版本：不崩溃，走普通半透明 fallback。

## 关于融球效果

当前组件还不是完整 metaball 融球。若要做 iOS 26 风格的按钮靠近黏连，建议新增独立组件或 shader：

- 简化方案：用动态 `Path` 或额外背景胶囊模拟连接；
- 完整方案：基于 Android 13+ `RuntimeShader` 写 SDF/metaball，并在低版本 fallback 到普通胶囊。

不要把融球逻辑直接塞进 `LiquidButton`，否则按钮、Tab、菜单都会被耦合到同一种形变策略。
