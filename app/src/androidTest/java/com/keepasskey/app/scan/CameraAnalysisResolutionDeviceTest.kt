package com.keepasskey.app.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Point
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机回读分析帧尺寸（`ISSUE-P3-337` 口径 10 / AC⑤′②：改前 / 改后的 `ImageProxy` **实测读数**）。
 *
 * ## 为什么这一层只能真机证
 *
 * 分辨率由相机框架按设备能力挑选，宿主侧无从构造 `ImageProxy`；而「提了分辨率是否真的拿到更高帧」
 * 直接决定 AC⑧ 的声称范围是「相机 + 相册」还是「相册为主」。**禁止**以「已设 1280×960」
 * 推定「已拿到 1280×960」（AC⑤′③），故本用例把两个配置都绑上去各取一帧读数。
 *
 * 锁两条不变量（都是**只在真机上才可能被证伪**的）：
 * ① 默认配置（不设 `setResolutionSelector`，即官方 bound 640×480）确实交付短边 ≥ 480 的帧；
 * ② 请求 1280×960 后交付的帧**不得低于**默认配置的帧——即「往上请求不会被静默降级」。
 * 设备无相机时整例 `Assume` 跳过（ChromeOS 大屏一类无相机形态按安装期已允许的降级路径处理）。
 */
@RunWith(AndroidJUnit4::class)
class CameraAnalysisResolutionDeviceTest {

    /**
     * `LifecycleRegistry.createUnsafe` 而非普通构造：Instrumentation 线程上跑，
     * 而普通 registry 对主线程有断言（实测报 `Method setCurrentState must be called on the main thread`）；
     * 本 owner 只为满足 `bindToLifecycle` 的解绑契约，不参与任何 UI 生命周期语义。
     */
    private class TestOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry

        fun start() {
            registry.currentState = Lifecycle.State.STARTED
        }
    }

    /** 取一帧并回报其实际尺寸；拿不到帧即失败（不得把「没跑起来」当成「分辨率够用」的证据）。 */
    private fun sample(config: String, analysis: ImageAnalysis, owner: LifecycleOwner): Point {
        val latch = CountDownLatch(1)
        var size = Point(0, 0)
        val executor = Executors.newSingleThreadExecutor()
        analysis.setAnalyzer(executor) { image: ImageProxy ->
            if (latch.count > 0L) {
                size = Point(image.width, image.height)
                Log.i(
                    TAG,
                    "$config ImageProxy=${image.width}x${image.height} format=${image.format} " +
                        "planes=${image.planes.size} rowStride0=${image.planes.first().rowStride}"
                )
                latch.countDown()
            }
            image.close()
        }
        val provider = ProcessCameraProvider.getInstance(context).get(20, TimeUnit.SECONDS)
        // bind / unbind 必须在应用主线程（实测报 `Not in application's main thread`），
        // 取帧回调跑在自有 executor 上，故绑定走 runOnMainSync、等帧在 Instrumentation 线程
        instrumentation.runOnMainSync {
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }
        val got = latch.await(20, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { provider.unbindAll() }
        analysis.clearAnalyzer()
        executor.shutdown()
        assertTrue("$config 未拿到任何分析帧（CAMERA 权限未授 / 相机被占用？）", got)
        return size
    }

    /**
     * 环境自备（`ISSUE-P2-494`）：`CAMERA` 是**运行时**权限，`connectedDebugAndroidTest` 的安装
     * **不会**默认授予（2026-10-05 实测：AVD `kp-256` 上未授即整例红；CI `device-gate` 的模拟器
     * 同样不授 ⇒ 该层在 CI 上恒红）。而「权限未授」与「相机真的取不到帧」在断言层**外观一致**
     * ——这正是 `ISSUE-P2-192` 余量第 8 项要消除的形态（环境不满足被误读为产品缺陷）。
     * 此处经 `UiAutomation`（shell 身份，持 `GRANT_RUNTIME_PERMISSIONS`）**就地自授**，
     * 使本用例不再依赖带外 `adb shell pm grant`（§447 §2.9 的手工前置）。
     * 自授失败（平台拒绝）时 `Assume` 跳过，**不得**以「没跑到」冒充「分辨率够用」。
     */
    @Before
    fun grantCameraPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        if (instrumentation.targetContext.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val granted = runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(
                packageName,
                Manifest.permission.CAMERA
            )
            instrumentation.targetContext.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        Assume.assumeTrue(
            "无法自授 CAMERA 运行时权限（平台拒绝），本用例不适用；" +
                "不得据此判定「分辨率请求无效」",
            granted
        )
    }

    @Test
    fun raisedResolutionIsActuallyDelivered() {
        Assume.assumeTrue(
            "设备无相机，本用例不适用",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        )
        val owner = TestOwner().apply { start() }

        // 与改动前的生产配置逐字同构：只设背压、不设分辨率
        val defaultFrame = sample(
            "A_默认配置(官方bound640x480)",
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build(),
            owner
        )
        // 与 TotpScanDialog 现配置同构（口径 10）
        val raisedFrame = sample(
            "B_口径10配置(bound1280x960)",
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(RAISED_WIDTH, RAISED_HEIGHT),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .build(),
            owner
        )
        Log.i(
            TAG,
            "SUMMARY default=${defaultFrame.x}x${defaultFrame.y} raised=${raisedFrame.x}x${raisedFrame.y} " +
                "device=${android.os.Build.MODEL} api=${android.os.Build.VERSION.SDK_INT}"
        )
        assertTrue(
            "默认配置交付的帧短边须 ≥ 480（官方 bound 640×480），实际 ${defaultFrame.y}",
            minOf(defaultFrame.x, defaultFrame.y) >= DEFAULT_MIN_SHORT_SIDE
        )
        assertTrue(
            "请求 1280×960 后交付的帧不得比默认配置更小（实际 ${raisedFrame.x}x${raisedFrame.y} vs " +
                "${defaultFrame.x}x${defaultFrame.y}）：静默降级会让口径 10 的改动变成无收益的像素负担",
            minOf(raisedFrame.x, raisedFrame.y) >= minOf(defaultFrame.x, defaultFrame.y)
        )
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private companion object {
        const val TAG = "QrProbe"
        const val RAISED_WIDTH = 1280
        const val RAISED_HEIGHT = 960
        const val DEFAULT_MIN_SHORT_SIDE = 480
    }
}
