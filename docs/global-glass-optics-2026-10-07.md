# 全局完整透镜光学的来源与限制

日期：2026-10-07。此文记录新增渲染方式，不把 2026-10-06 的旧包原生验收当作新版验证结果。新版构建、测试及原生验证以本轮实际执行记录为准。

## 背景来源

本应用内，`AppGlassBackdrop` 对本应用 Activity / Dialog 窗口做 PixelCopy。浮窗是另一窗口，所以可以取得本应用窗口内没有浮窗的实际背景，中心可直接放大和折射这些真实像素。

外部全局模式仍由用户明确授权的 MediaProjection 提供屏幕流。整屏捕获包含悬浮窗自身，公开 API 没有“捕获整屏但排除指定本应用悬浮层，并露出其下方内容”的选项。`FLAG_SECURE` 的作用是保护窗口不被截入，不保证露出其下方实时画面；不能用它取得被遮挡背景。Android 14 的单应用共享只分享用户选择的应用窗口，捕获源、尺寸和坐标也随选定内容变化，无法作为桌面及任意应用间切换的通用实时背景。

因此全局中心采用**外围真实像素的近似重建**：

1. 工作线程保持完整浮窗矩形及移动期间遮挡区的禁止读取策略。ROI 中禁止区对应像素是透明，未读取的中心不会偷偷进入纹理。
2. `GlobalEdgeLens` 的唯一纹理读取处是 `safeSample`，同时检查 ROI 范围、禁止区和额外双线性采样保护边界。合法的背景位置直接使用真实采样。
3. 禁止区内的光学采样由左、上、右、下四个可信边界像素按距离平方倒数混合。边界位于禁止区之外两个采样像素；超出屏幕裁切范围的边界权重为零，余下边界继续参与。没有合法来源时输出透明。
4. 中心颜色、色带和环境变化来自外围，但被浮窗完全遮住的文字、图标、细节不能由外围准确恢复。这不是任意应用的真实完整背景捕获，也不承诺与本应用内 PixelCopy 内容等价。

## 同一套光学

`GlassLensOptics` 被本应用内及全局 Shader 共用。整个圆角图标使用相同的中心放大、弯月面位移、边缘色散、环境反射及方向高光，中心不再由另一个透明环带 Shader 留空。边界输出按 alpha 预乘并做覆盖抗锯齿。

系统窗口模糊与此渲染方式是不同来源。全局近似中心不能描述为系统提供的真实折射；新版 host 的模糊开关行为以代码与本轮设备验证为准。

## 拖动坐标与分配

`GlassSampleRegion.roi`、`excluded` 使用捕获图像的绝对坐标；`bubble` 使用当前浮窗的屏幕绝对坐标；`scaleX/Y` 是每个屏幕像素对应的捕获像素数。每次绑定将当前目标转换为 `bubble.left/top * scale - roi.left/top`，然后以当前局部位置重投影旧 ROI；不把旧浮窗位置当作新窗口原点，也不把之前被遮住的像素重新标为可信。

本应用内与全局 renderer 都各保留一个 RuntimeShader，并为两个复用 Bitmap 缓存最多两个 BitmapShader。移动及光照更新只更新 uniforms，像素帧绑定复用已有输入 Shader。输入显式使用 API 33 的双线性过滤；RuntimeShader 的默认输入过滤是 nearest，不受 Paint 的过滤标记控制。

`GlassLensSamplingGeometryTest` 检查屏幕到 ROI 的坐标、拖动重投影、双轴比例、双线性 guard、屏幕角裁切和扩大的移动禁止区。这些 JVM 几何检查不代表 AGSL 已在手机 GPU 上编译通过，也不代表 120fps 的手机性能或功耗实测。

## 官方公开 API 依据

- [Media projection](https://developer.android.com/media/grow/media-projection)：捕获设备显示或用户选择的应用窗口、单应用共享内容边界及尺寸回调。
- [MediaProjection](https://developer.android.com/reference/android/media/projection/MediaProjection)：公开 virtual display、callback、stop API，不提供排除指定捕获窗口的参数。
- [MediaProjectionConfig](https://developer.android.com/reference/android/media/projection/MediaProjectionConfig)：本项目 API 35 范围内的整屏及用户选择配置；没有逐窗口排除配置。
- [WindowManager.LayoutParams.FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)：窗口内容禁止截入截图或非安全显示。
- [BitmapShader](https://developer.android.com/reference/android/graphics/BitmapShader#FILTER_MODE_LINEAR)：RuntimeShader 输入默认 nearest 及显式线性过滤 API。
