package com.ilksese.busalarmclock

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 相机取景 + 二维码识别。
 *
 * 只在取景这一屏存在：离开组合即解绑用例、关闭 ML Kit 客户端并停止分析线程，
 * 相机归系统回收，不留下任何后台取景或前台服务。
 *
 * [onDecoded] 返回是否已接管该码：返回 false（不接受、不进入确认流程）时取景继续可用。
 */
@Composable
fun QrViewfinder(onDecoded: (String) -> Boolean, modifier: Modifier = Modifier.fillMaxWidth().height(240.dp)) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val hasPermission = remember(context) {
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
  }
  val previewView = remember { PreviewView(context) }
  // 一帧只交付一次；应用层不接管的码由 DecodeGate 释放，取景可继续扫。
  val gate = remember { DecodeGate() }

  DisposableEffect(lifecycleOwner) {
    val executor: ExecutorService = Executors.newSingleThreadExecutor()
    val scanner: BarcodeScanner = BarcodeScanning.getClient()
    val providerFuture = if (hasPermission) ProcessCameraProvider.getInstance(context) else null
    // 分析器需要回读取景是否已退出，而 session 又需要分析器所在的生命周期，故先声明后赋值。
    lateinit var session: ViewfinderSession
    session = ViewfinderSession(
      // 回调只在 provider 就绪时投递；是否仍允许绑定由 ViewfinderSession 的 disposed 守卫决定。
      requestProvider = { onReady ->
        val future = providerFuture
        if (future == null) Unit else future.addListener(onReady, ContextCompat.getMainExecutor(context))
      },
      bindCamera = {
        val provider = runCatching { providerFuture?.get() }.getOrNull()
        if (provider == null) {
          false
        } else {
          val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
          val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
          analysis.setAnalyzer(executor) { proxy -> analyzeFrame(proxy, scanner, session, gate, onDecoded) }
          // 权限可能在进入取景后被系统撤销，bindToLifecycle 会抛 SecurityException；取景失败不崩溃。
          runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
          }.isSuccess
        }
      },
      // 退出时不阻塞主线程等 provider：未就绪就说明还没绑定过，后面的就绪通知会被 disposed 拦住。
      unbindAll = {
        val future = providerFuture
        if (future != null && future.isDone) runCatching { future.get().unbindAll() }
      },
    )
    session.start()

    onDispose {
      session.dispose()
      scanner.close()
      executor.shutdown()
    }
  }

  AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * 取景的生命周期守卫：provider 回调与识别回调都可能晚于退出到达。
 *
 * [requestProvider] 只负责“provider 就绪时调用我这一个回调”，真正的绑定决定在 [onProviderReady]，
 * 它同时被 [dispose] 后的状态拦住，因此退出之后不可能再打开相机。
 */
internal class ViewfinderSession(
  private val requestProvider: (() -> Unit) -> Unit,
  private val bindCamera: () -> Boolean,
  private val unbindAll: () -> Unit,
) {
  private val disposed = AtomicBoolean(false)
  private val bound = AtomicBoolean(false)

  val isDisposed: Boolean get() = disposed.get()

  fun start() { requestProvider(::onProviderReady) }

  private fun onProviderReady() {
    // 退出取景后 provider 才就绪时，绑定会重新占用相机直到 Activity 销毁；
    // 这里直接丢弃这次就绪通知，把“退出即释放”变成硬约束。
    if (disposed.get()) return
    // 重复就绪通知不能导致重复绑定；bindCamera 失败时不占用“已绑定”名额，便于重试。
    if (!bound.compareAndSet(false, true)) return
    if (!bindCamera()) bound.set(false)
  }

  fun dispose() {
    disposed.set(true)
    bound.set(false)
    unbindAll()
  }
}

/** 单帧分析入口：退出后到达的帧、已关闭识别器抛出的异常都只能被丢弃。 */
internal fun analyzeFrame(
  proxy: ImageProxy,
  scanner: BarcodeScanner,
  session: ViewfinderSession,
  gate: DecodeGate,
  onDecoded: (String) -> Boolean,
) {
  FrameSink(
    isDisposed = session::isDisposed,
    closeFrame = { runCatching { proxy.close() } },
    deliver = { value -> gate.deliver(value) { onDecoded(it) } },
  ).handle {
    // 识别器可能已被 onDispose 关闭，scanner.process 会同步抛 IllegalStateException；
    // FrameSink.handle 负责把这类异常当作“本帧无结果”，不崩溃、不上报成功。
    val mediaImage = proxy.image ?: return@handle null
    val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
    MlKitScanTask(scanner.process(image))
  }
}

/**
 * 识别闸门：同一次取景里连续取景时，一帧只交付一次，但应用层不接管的码不占用这次机会。
 *
 * 应用层拒绝（非公交码、来源不符）时回调返回 false，闸门立即释放，
 * 用户继续把站台码放入取景仍能被交付；一旦被接管就保持关闭，避免同一码重复导入。
 */
internal class DecodeGate(private val accepted: AtomicBoolean = AtomicBoolean(false)) {
  /** 返回本次是否真正交付给了应用层（false 表示被闸门挡住或应用层未接管）。 */
  fun deliver(value: String, onAccepted: (String) -> Boolean): Boolean {
    if (!accepted.compareAndSet(false, true)) return false
    // 应用层未接管（例如判定为不受支持的码）时必须释放闸门，否则后续真站台码永远送不进去。
    if (!onAccepted(value)) { accepted.set(false); return false }
    return true
  }
}

/** 识别任务的最小抽象：生产实现包装 ML Kit Task，测试可同步或延迟完成。 */
internal interface ScanTask {
  fun onSuccess(listener: (List<String>) -> Unit)
  fun onFailure(listener: () -> Unit)
  fun onComplete(listener: () -> Unit)
}

/**
 * 单帧决策点：退出后到达的帧直接丢弃，异常帧按无结果处理。
 *
 * 三个回调都可能晚于退出到达（识别是异步的），所以每次交付都在交付前复查 disposed。
 */
internal class FrameSink(
  private val isDisposed: () -> Boolean,
  private val closeFrame: () -> Unit,
  private val deliver: (String) -> Unit,
) {
  fun handle(scan: () -> ScanTask?) {
    if (isDisposed()) { closeFrame(); return }
    // [HACK] Reason: onDispose 关闭 ML Kit 客户端时，分析线程里已在执行的帧会立刻失效并同步抛
    // IllegalStateException；该异常出现在分析线程上，没人接就会结束线程并可能崩溃进程。
    // Solution: 在此统一捕获，异常帧按“无结果”关闭，绝不上报为识别成功。
    // Risk: 真正的识别故障也被吞掉，表现为该帧无结果；取景仍在，可继续扫或改用相册。
    // Track: Issue #17 用相机扫码添加关注项。
    val task = try { scan() } catch (error: Exception) { null }
    if (task == null) { closeFrame(); return }
    task.onSuccess { values ->
      if (!isDisposed()) runCatching { values.firstOrNull()?.let(deliver) }
    }
    task.onFailure { }
    task.onComplete { closeFrame() }
  }
}

/** ML Kit Task 到 [ScanTask] 的适配；只取 QR 格式的原始值，来源校验仍由统一入口完成。 */
private class MlKitScanTask(private val task: com.google.android.gms.tasks.Task<List<Barcode>>) : ScanTask {
  override fun onSuccess(listener: (List<String>) -> Unit) {
    task.addOnSuccessListener { barcodes ->
      listener(barcodes.filter { it.format == Barcode.FORMAT_QR_CODE }.mapNotNull { it.rawValue })
    }
  }

  override fun onFailure(listener: () -> Unit) { task.addOnFailureListener { listener() } }

  override fun onComplete(listener: () -> Unit) { task.addOnCompleteListener { listener() } }
}
