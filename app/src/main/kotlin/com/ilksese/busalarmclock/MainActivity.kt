package com.ilksese.busalarmclock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.ilksese.busalarmclock.ui.theme.Accent
import com.ilksese.busalarmclock.ui.theme.BodyText
import com.ilksese.busalarmclock.ui.theme.DeepBlue
import com.ilksese.busalarmclock.ui.theme.Hairline
import com.ilksese.busalarmclock.ui.theme.SoftSurface
import com.ilksese.busalarmclock.ui.theme.SubText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.ilksese.busalarmclock.data.*
import com.ilksese.busalarmclock.ui.theme.BusAlarmClockTheme
import com.ilksese.busalarmclock.wait.WaitNotifications
import com.ilksese.busalarmclock.wait.WaitRuntime
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private var cameraPermissionContinuation: ((Boolean) -> Unit)? = null
  /** 从提醒通知正文进来时要查看的那条提醒；只用于定位，不构成确认。 */
  private val pendingAlertTask = mutableStateOf<AlertOpenRequest?>(null)
  private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    val continuation = cameraPermissionContinuation
    cameraPermissionContinuation = null
    continuation?.invoke(granted)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    pendingAlertTask.value = alertRequestOf(intent)
    setContent {
      val applicationBehavior = WaitRuntime.application(this)
      val context = androidx.compose.ui.platform.LocalContext.current
      // 仅在用户进入相机扫码时申请 CAMERA；退出取景即释放，不启用相机前台服务。
      val access = CameraAccess(
        granted = android.content.pm.PackageManager.PERMISSION_GRANTED ==
          context.checkSelfPermission(android.Manifest.permission.CAMERA),
        request = { continuation ->
          cameraPermissionContinuation = continuation
          requestCamera.launch(android.Manifest.permission.CAMERA)
        },
      )
      BusAlarmClockTheme {
        WatchItemsScreen(
          applicationBehavior,
          actionScope = lifecycleScope,
          waitActionScope = WaitRuntime.scope,
          cameraAccess = access,
          viewfinder = { onDecoded -> QrViewfinder(onDecoded) },
          alertOpenRequest = pendingAlertTask.value,
        )
      }
    }
  }

  /** 页面已在后台时点通知正文：同样只定位查看，不确认、不停止输出。 */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    pendingAlertTask.value = alertRequestOf(intent)
  }

  private fun alertRequestOf(intent: Intent?): AlertOpenRequest? = intent?.let {
    if (it.action == WaitNotifications.OPEN_ALERT_LIST) AlertOpenRequest(null)
    else WaitNotifications.taskOf(it, WaitNotifications.OPEN_ALERT)?.let(::AlertOpenRequest)
  }
}

/** 一次通知正文点击；同一条通知再次点开也会产生一个新请求。 */
class AlertOpenRequest(val taskId: Long?)

/** 相机可用性由 Activity 注入；测试可控制授权结果而不触碰真实硬件。 */
class CameraAccess(val granted: Boolean, val request: ((Boolean) -> Unit) -> Unit = { it(granted) })

/** 进入扫码时可能处于“等待授权”，此时不能预览，也不能假装已在取景。 */
enum class CameraPhase { GRANTED, PENDING, DENIED }

/**
 * 两个主页与同一添加流程；UI 不直接发 HTTP 请求或写 Room。
 * 相机只在添加流程内出现，[viewfinder] 负责真实取景并把识别结果交回同一导入入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchItemsScreen(
  application: WatchItemApplication,
  actionScope: kotlinx.coroutines.CoroutineScope? = null,
  cameraAccess: CameraAccess = CameraAccess(granted = false),
  viewfinder: (@Composable (onDecoded: (String) -> Boolean) -> Unit)? = null,
  alertOpenRequest: AlertOpenRequest? = null,
  waitActionScope: kotlinx.coroutines.CoroutineScope? = null,
  notificationPermissionRequest: (((Boolean) -> Unit) -> Unit)? = null,
) {
  val state by application.state.collectAsStateWithLifecycle()
  val compositionScope = rememberCoroutineScope()
  val scope = actionScope ?: compositionScope
  val waitScope = waitActionScope ?: scope
  val context = androidx.compose.ui.platform.LocalContext.current
  var scanning by remember { mutableStateOf(false) }
  var scanGeneration by remember { mutableLongStateOf(0) }
  // 当前正在查看的提醒；null 表示跟随最新一条。
  var viewingAlertTask by remember { mutableStateOf<Long?>(null) }
  var viewingAlertList by remember { mutableStateOf(false) }
  LaunchedEffect(application) { application.open() }
  val incomingAlert = alertOpenRequest?.taskId
  val alertAvailable = state.wait.alerts.any { it.snapshot.taskId == incomingAlert }
  LaunchedEffect(alertOpenRequest, alertAvailable) {
    if (alertOpenRequest != null && incomingAlert == null) {
      application.showCurrent(true)
      viewingAlertTask = null
      viewingAlertList = true
    } else if (incomingAlert != null && alertAvailable && application.viewAlert(incomingAlert)) {
      viewingAlertTask = incomingAlert
      viewingAlertList = false
    }
  }
  val hasNotificationPermission = {
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  }
  var permissionPending by remember { mutableStateOf(false) }
  val permissionResult: (Boolean) -> Unit = { granted ->
    permissionPending = false
    if (granted) waitScope.launch { application.startWait() }
    else application.notificationPermissionDenied()
  }
  val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), permissionResult)
  val startMonitoring = {
    if (hasNotificationPermission()) {
      waitScope.launch { application.startWait() }
    } else if (!permissionPending) {
      permissionPending = true
      if (notificationPermissionRequest != null) notificationPermissionRequest(permissionResult)
      else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Unit
  }
  // 同步判定是否接管：不受支持的码仍交给应用层给出提示并保留取景，但不占用“一码只交付一次”的机会。
  fun importCamera(rawValue: String): Boolean {
    val accepted = application.isCameraCodeAccepted(rawValue)
    scope.launch { application.cameraDecoded(rawValue) }
    return accepted
  }

  var cameraPhase by remember(cameraAccess.granted) {
    mutableStateOf(if (cameraAccess.granted) CameraPhase.GRANTED else CameraPhase.PENDING)
  }

  fun startCamera() {
    application.openCamera()
    cameraPhase = if (cameraAccess.granted) CameraPhase.GRANTED else CameraPhase.PENDING
    if (cameraAccess.granted) return
    cameraAccess.request { granted ->
      // 拒绝或取消授权都只回到添加面板，相册入口与已有关注项不受影响。
      cameraPhase = if (granted) CameraPhase.GRANTED else CameraPhase.DENIED
      if (!granted) application.cameraPermissionDenied()
    }
  }

  fun scan(uri: Uri) {
    val generation = ++scanGeneration
    scanning = true
    val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrElse {
      scanning = false
      application.imageFailure("无法读取图片，请重新选择；没有保存照片")
      return
    }
    val scanner = BarcodeScanning.getClient()
    scanner.process(image).addOnSuccessListener { barcodes ->
      if (generation == scanGeneration) {
        scanning = false
        scope.launch { application.decoded(barcodes.filter { it.format == Barcode.FORMAT_QR_CODE }.mapNotNull { it.rawValue }) }
      }
    }.addOnFailureListener {
      if (generation == scanGeneration) {
        scanning = false
        application.imageFailure("二维码解析失败，请重新选图")
      }
    }.addOnCompleteListener { scanner.close() }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(::scan) }
  val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::scan) }
  fun cancel() { scanGeneration++; scanning = false; application.cancel() }
  fun leaveCamera() { scanGeneration++; scanning = false; application.closeCamera() }
  BackHandler(viewingAlertList && state.currentPage && !state.adding) { viewingAlertList = false; viewingAlertTask = null }
  BackHandler(viewingAlertTask != null && state.currentPage && !state.adding) { viewingAlertTask = null }
  BackHandler(state.adding && !state.saving) { if (state.cameraActive) leaveCamera() else cancel() }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(if (state.adding) "确认上车站台" else if (state.currentPage) "当前等车" else "关注项") },
        navigationIcon = {
          if (state.adding) IconButton(enabled = !state.saving, onClick = { if (state.cameraActive) leaveCamera() else cancel() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, if (state.cameraActive) "退出扫码" else "取消添加") }
        },
        actions = {
          if (!state.adding && !state.currentPage) IconButton(onClick = application::showAdd) { Icon(Icons.Default.Add, "添加站台") }
        },
      )
    },
    bottomBar = {
      if (!state.adding) {
        // D 原型底部导航：选中项是浅柠色胶囊 + 深石蓝图标，未选中为次文色。
        Row(Modifier.fillMaxWidth().background(DeepBlue).padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
          DNavigationItem("关注项", Icons.Default.Place, selected = !state.currentPage) { viewingAlertList = false; viewingAlertTask = null; application.showCurrent(false) }
          DNavigationItem("当前等车", Icons.Default.Schedule, selected = state.currentPage) { viewingAlertList = false; viewingAlertTask = null; application.showCurrent(true) }
        }
      }
    },
  ) { padding ->
    LazyColumn(
      Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      when {
        state.adding -> {
          val draft = state.confirmation
          if (state.cameraActive) {
            item {
              Text("扫描站台二维码", style = MaterialTheme.typography.headlineMedium)
              Text("将站台二维码放入框内，识别后进入同一站台与线路确认流程。", modifier = Modifier.padding(top = 12.dp))
              Text("不获取定位，不保存扫码照片。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            item {
              when (cameraPhase) {
                // 只有真正拿到授权才挂载取景；等待授权期间不能显示空画面冒充取景。
                CameraPhase.GRANTED -> viewfinder?.invoke(::importCamera) ?: Text("当前无法预览相机画面，可从相册选择。")
                CameraPhase.PENDING -> Text("等待相机授权…授权后自动开始取景；也可以返回改用相册。")
                CameraPhase.DENIED -> Text("相机未授权，仍可从相册选择。")
              }
            }
            item { Text("识别受支持的公交站台码后自动继续；其他码会被忽略。", style = MaterialTheme.typography.bodySmall) }
            item { SecondaryAction("取消扫码") { leaveCamera() } }
          } else if (draft == null) {
            item {
              Text("添加上车站台", style = MaterialTheme.typography.headlineMedium)
              Text("扫描公交站台二维码，随后确认具体站台与线路方向。", modifier = Modifier.padding(top = 12.dp))
              Text("不获取定位，不保存扫码照片。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            item { PrimaryAction("相机扫码", enabled = !scanning && !state.loading) { startCamera() } }
            item { SecondaryAction("从相册选择", enabled = !scanning && !state.loading) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
            item { TextButton(enabled = !scanning && !state.loading, onClick = { documentPicker.launch(arrayOf("image/*")) }) { Text("从系统文件选择") } }
          } else {
            item {
              Text(draft.station.stationName, style = MaterialTheme.typography.headlineSmall)
              Text("站台 ${draft.stopCode}${if (draft.editing) " · 编辑选线，换站台请另行添加" else if (draft.stopCode == draft.station.defaultStopCode) " · 二维码指向站台" else " · 同名另一站台"}")
            }
            if (draft.editing) {
              item { Text("编辑已有关注项：站台不变，只改选线路。", style = MaterialTheme.typography.bodySmall) }
            } else {
              item { Text("核对具体上车站台", style = MaterialTheme.typography.labelLarge) }
              draft.station.stops.forEach { stop ->
                item(key = "stop-${stop.stopCode}") {
                  val isSelected = draft.stopCode == stop.stopCode
                  val choose: () -> Unit = { scope.launch { application.chooseStop(stop.stopCode) }; Unit }
                  // D 原型整行可点；用 selectable 承担整行点击与单选语义，RadioButton 自身不再响应点击。
                  Row(
                    Modifier.fillMaxWidth()
                      .selectable(selected = isSelected, enabled = !state.saving, role = Role.RadioButton, onClick = choose)
                      .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    RadioButton(selected = isSelected, enabled = !state.saving, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text("站台 ${stop.stopCode}${if (stop.stopCode == draft.station.defaultStopCode) " · 二维码默认" else " · 同名另一站台"}")
                  }
                }
              }
              if (draft.isUpdate) item { Text("已关注此站台：保存会更新已有项，取消保持原选择。", color = MaterialTheme.colorScheme.primary) }
            }
            // 过时记录置灰且只能删除；即使全部过时也保留关注项本身。
            draft.obsoleteRecords.forEach { record ->
              item(key = "obsolete-${record.identity}") {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                  RouteTag(record.lineName, enabled = false)
                  Spacer(Modifier.width(11.dp))
                  Column(Modifier.weight(1f)) {
                    Text("开往${record.endStationName}", style = MaterialTheme.typography.bodyMedium, color = SubText)
                    Text("过时，仅可删除记录", style = MaterialTheme.typography.bodySmall, color = SubText)
                  }
                  TextButton(enabled = !state.saving, onClick = { scope.launch { application.removeRecord(record.identity) } }) { Text("删除过时记录") }
                }
              }
            }
            item { Text("选择你可乘坐的线路方向", style = MaterialTheme.typography.titleMedium) }
            draft.availableLines.forEach { line ->
              item(key = "line-${line.identity}") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Checkbox(checked = line.identity in draft.selected, enabled = !state.loading && !state.saving, onCheckedChange = { application.selectLine(line.identity, it) }, modifier = Modifier.semantics { contentDescription = "${line.lineName}，开往${line.endStationName}，方向${line.upDown}" })
                  Column {
                    Text("${line.lineName} · 开往${line.endStationName}")
                    Text("线路 ${line.lineNo} · 方向 ${line.upDown} · 目标站序 ${line.targetSequence}", style = MaterialTheme.typography.bodySmall)
                  }
                }
              }
            }
            draft.fetchedAt?.let { time -> item { Text("线路获取时间：${DateFormat.getTimeInstance().format(Date(time))}", style = MaterialTheme.typography.bodySmall) } }
            if (!state.loading && draft.availableLines.isEmpty()) item { Text("该站台暂无可选择线路，可切换站台或稍后重试；已有关注项不变。") }
            item { Text("暂时无预测仍可选择。切换站台须重新选线，保存不会开始等车。", style = MaterialTheme.typography.bodySmall) }
            item { PrimaryAction(if (state.saving) "正在保存…" else if (draft.isUpdate) "保存对已有项的修改" else "保存关注项", enabled = draft.selected.isNotEmpty() && !state.loading && !state.saving) { scope.launch { application.save() } } }
            item { TextButton(enabled = !state.loading && !state.saving, onClick = { scope.launch { application.retry() } }) { Text("重新核对线路") } }
          }
          if (state.loading || scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (scanning) "正在识别二维码…" else "正在核对站台线路…") }
          item { TextButton(enabled = !state.saving, onClick = { cancel() }) { Text("取消，保持原状") } }
        }
        state.currentPage -> {
          val preflight = state.preflight
          val listed = state.wait.alerts
          val viewingAlert = listed.firstOrNull { it.snapshot.taskId == viewingAlertTask }
          val currentFailure = state.wait.endedReason?.takeIf {
            it != WaitEndReason.ALERTED && it != WaitEndReason.ALERT_CONFIRMED
          }
          val ownAlert = listed.firstOrNull { it.snapshot.taskId == state.wait.taskId }
          // 当前故障不被历史提醒遮挡；用户仍可明确点进未确认快照。
          val alert = viewingAlert ?: if (!viewingAlertList && currentFailure == null && preflight == null) {
            ownAlert ?: listed.firstOrNull { !it.confirmed }?.takeIf { !state.wait.running }
          } else null
          item {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
              when {
                alert != null -> AlertPanel(alert, waitScope, application, showBack = viewingAlert != null) { viewingAlertTask = null }
                viewingAlertList -> {
                  Text("未确认提醒", style = MaterialTheme.typography.headlineSmall)
                  if (listed.none { !it.confirmed }) Text("没有未确认的出门提醒")
                  TextButton(onClick = { viewingAlertList = false }) { Text("返回当前等车") }
                }
                preflight != null -> WaitPreflightPanel(
                  preflight, application, permissionPending, startMonitoring,
                  onNotificationSettings = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                  },
                )
                currentFailure != null -> EmptyState("本次等车已结束", state.wait.message ?: currentFailure.message)
                state.wait.stopCode != null -> WaitingPanel(state.wait, waitScope, application)
                else -> {
                  EmptyState("还没开始等车", "去关注项，选一个具体站台开始。")
                  SecondaryAction("去关注项") { application.showCurrent(false) }
                }
              }
              val endedMessage = state.wait.message
              if (!viewingAlertList && alert == null && currentFailure == null && state.wait.endedReason != null && endedMessage != null && !state.wait.running) {
                Text(endedMessage, color = SubText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
              }
            }
          }
          val otherPending = listed.filter { !it.confirmed && it.snapshot.taskId != alert?.snapshot?.taskId }
          if ((viewingAlertList || preflight == null) && otherPending.isNotEmpty()) {
            if (!viewingAlertList) item { Text("其他未确认的出门提醒", style = MaterialTheme.typography.titleMedium) }
            otherPending.forEach { pending ->
              item(key = "alert-${pending.snapshot.taskId}") {
                TextButton(onClick = { viewingAlertTask = pending.snapshot.taskId }) {
                  Text("查看 ${pending.snapshot.main.lineName} 的提醒 · ${pending.snapshot.stationName}")
                }
              }
            }
          }
        }
        else -> {
          if (state.items.isEmpty()) item { EmptyState("下一站，\n从这里开始。", "点右上角加号，识别公交二维码并保存你的上车站台。") }
          if (state.items.isNotEmpty()) {
            item { SecondaryAction("核对线路最新状态", enabled = !state.loading && !state.saving) { scope.launch { application.refresh() } } }
          }
          state.items.forEach { item ->
            item(key = item.stopCode) {
              Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 19.dp, bottom = 23.dp)) {
                Text(item.stationName, style = MaterialTheme.typography.headlineSmall, color = BodyText)
                Text("站台 ${item.stopCode} · ${item.selectedLines.size} 条已选线路", style = MaterialTheme.typography.bodySmall, color = SubText)
                item.selectedLines.forEach { line ->
                  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 过时记录置灰且只允许删除，避免把已消失的方向继续当作可乘线路。
                    RouteTag(line.lineName, enabled = !line.obsolete)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                      Text("开往${line.endStationName}", style = MaterialTheme.typography.bodyMedium, color = if (line.obsolete) SubText else BodyText)
                      if (line.obsolete) Text("过时 · 不参与提醒", style = MaterialTheme.typography.bodySmall, color = SubText)
                    }
                    if (line.obsolete) TextButton(onClick = { scope.launch { application.removeStoredRecord(item.stopCode, line.identity) } }) { Text("删除记录") }
                  }
                }
                Text(
                  if (item.lastCheckedAt == null) "本机保存 · 尚未联网核对"
                  else "最后成功核对：${DateFormat.getTimeInstance().format(Date(item.lastCheckedAt))}",
                  style = MaterialTheme.typography.bodySmall,
                  color = SubText,
                )
                Button(
                  onClick = { viewingAlertList = false; viewingAlertTask = null; application.prepareWait(item.stopCode) },
                  enabled = !state.wait.running,
                  shape = RoundedCornerShape(17.dp),
                  contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                  modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (state.wait.running) "已有一轮等车在运行" else "开始等车", style = MaterialTheme.typography.labelLarge) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  TextButton(enabled = !state.saving, onClick = { scope.launch { application.edit(item.stopCode) } }) { Text("编辑选线") }
                  TextButton(enabled = !state.saving, onClick = { application.requestDelete(item.stopCode) }) { Text("删除关注项") }
                }
                HorizontalDivider(color = Hairline)
              }
            }
          }
        }
      }
      state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp)) } }
    }
  }
  if (state.codes.isNotEmpty()) AlertDialog(
    onDismissRequest = { cancel() }, title = { Text("图片里有多个站台二维码") },
    text = { Column { state.codes.forEach { code -> TextButton(onClick = { scope.launch { application.importCode(code) } }) { Text("站台码 $code") } } } },
    confirmButton = {}, dismissButton = { TextButton(onClick = { cancel() }) { Text("取消") } },
  )
  state.deleting?.let { stopCode ->
    val item = state.items.firstOrNull { it.stopCode == stopCode }
    AlertDialog(
      onDismissRequest = { application.cancelDelete() },
      title = { Text("删除关注项") },
      text = { Text("将删除站台 $stopCode 的关注项与已保存的 ${item?.selectedLines?.size ?: 0} 条线路记录，不会开始或结束任何等车。") },
      confirmButton = { TextButton(onClick = { scope.launch { application.confirmDelete() } }) { Text("确认删除") } },
      dismissButton = { TextButton(onClick = { application.cancelDelete() }) { Text("取消") } },
    )
  }
}

/** D 的 C 独立提醒面：已触发事实、触发时预计与同窗口线路；只有一个明确的确认动作。 */
@Composable
private fun AlertPanel(
  alert: OutingAlert,
  scope: kotlinx.coroutines.CoroutineScope,
  application: WatchItemApplication,
  showBack: Boolean,
  onBack: () -> Unit,
) {
  val snapshot = alert.snapshot
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(8.dp).background(Accent, CircleShape))
    Spacer(Modifier.width(8.dp))
    // 触发即停止取数：这一行是已触发事实，不随新预测变化。
    Text("出门提醒 · 已触发 · 取数已停止", style = MaterialTheme.typography.labelLarge, color = Accent)
  }
  Spacer(Modifier.size(18.dp))
  Column(Modifier.fillMaxWidth().background(SoftSurface, RoundedCornerShape(22.dp)).padding(20.dp)) {
    Text(
      when {
        alert.confirmed -> "提醒已确认"
        !alert.outputEnded -> "准备出门吧"
        else -> "提醒已结束输出"
      },
      style = MaterialTheme.typography.headlineSmall,
      color = BodyText,
    )
    Text(
      when {
        alert.confirmed -> "本次输出已停止"
        !alert.outputEnded -> "你的车进入提醒窗口了"
        else -> "尚未确认 · 不会自动重响"
      },
      style = MaterialTheme.typography.bodySmall,
      color = SubText,
      modifier = Modifier.padding(top = 6.dp),
    )
    Spacer(Modifier.size(18.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Row(verticalAlignment = Alignment.Bottom) {
        Text((snapshot.main.etaSeconds / 60L).toString(), style = MaterialTheme.typography.displayLarge, color = BodyText)
        Spacer(Modifier.width(4.dp))
        val seconds = snapshot.main.etaSeconds % 60
        Text(if (seconds == 0L) "分钟" else "分 $seconds 秒", style = MaterialTheme.typography.bodySmall, color = SubText, modifier = Modifier.padding(bottom = 10.dp))
      }
      Spacer(Modifier.weight(1f))
      Text("触发时预计\n不继续倒计时", style = MaterialTheme.typography.bodySmall, color = SubText)
    }
    Spacer(Modifier.size(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      RouteTag(snapshot.main.lineName)
      Spacer(Modifier.width(11.dp))
      Column {
        Text("开往${snapshot.main.endStationName}", style = MaterialTheme.typography.bodyMedium, color = BodyText)
        Text("${snapshot.stationName} · 站台 ${snapshot.stopCode}", style = MaterialTheme.typography.bodySmall, color = SubText)
      }
    }
    if (snapshot.others.isNotEmpty()) {
      HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 14.dp))
      Text(
        "同时在窗口内：" + snapshot.others.joinToString("、") { "${it.lineName} · 约 ${formatAlertEta(it.etaSeconds)}" },
        style = MaterialTheme.typography.bodySmall,
        color = SubText,
      )
    }
    HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 14.dp))
    Text(
      "本次预测获取时间：${DateFormat.getDateTimeInstance().format(Date(snapshot.fetchedAt))}",
      style = MaterialTheme.typography.bodySmall,
      color = SubText,
    )
  }
  Spacer(Modifier.size(16.dp))
  if (alert.submissionPending) Text("通知已提交，本机提交状态未能保存；不代表系统已展示。", color = SubText)
  if (alert.confirmationError) Text("输出已停止，但确认未能保存；请重试。", color = MaterialTheme.colorScheme.error)
  if (!alert.confirmed) {
    PrimaryAction("知道了，停止提醒") { scope.launch { application.confirmAlert(snapshot.taskId) } }
  }
  if (showBack) TextButton(onClick = onBack) { Text("返回") }
  Text(
    if (alert.confirmed) "已确认，本次已经结束；不会自动重响。"
    else "提示音和震动最多各一次，受系统设置限制；未确认的提醒仍可查看。",
    style = MaterialTheme.typography.bodySmall,
    color = SubText,
  )
}

/** 开始前同屏核对：站台线路、步行时间 W 与预留余量 B；不自动估算 W。 */
@Composable
private fun WaitPreflightPanel(
  draft: WaitPreflight,
  application: WatchItemApplication,
  permissionPending: Boolean,
  onStart: () -> Unit,
  onNotificationSettings: () -> Unit,
) {
  Text(draft.stationName, style = MaterialTheme.typography.headlineSmall, color = BodyText)
  Text("站台 ${draft.stopCode}", style = MaterialTheme.typography.bodySmall, color = SubText)
  Spacer(Modifier.size(16.dp))
  draft.lines.forEach { line ->
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      RouteTag(line.lineName)
      Spacer(Modifier.width(11.dp))
      Text("开往${line.endStationName}", style = MaterialTheme.typography.bodyMedium, color = BodyText)
    }
  }
  Spacer(Modifier.size(16.dp))
  Row(verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(
      value = draft.walkText,
      onValueChange = application::updateWalk,
      label = { Text("步行时间 W（分钟）") },
      singleLine = true,
      enabled = !draft.starting,
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(12.dp))
    OutlinedTextField(
      value = draft.bufferText,
      onValueChange = application::updateBuffer,
      label = { Text("预留余量 B（分钟）") },
      singleLine = true,
      enabled = !draft.starting,
      modifier = Modifier.weight(1f),
    )
  }
  val walk = draft.walkMinutes
  val buffer = draft.bufferMinutes
  if (walk != null && buffer != null) {
    Text(
      "预计进入 ${formatMinutes(walk)}–${formatMinutes(walk + buffer)} 分钟时提醒你",
      style = MaterialTheme.typography.bodySmall,
      color = SubText,
      modifier = Modifier.padding(top = 12.dp),
    )
  }
  draft.verifiedAt?.let { at ->
    Text(
      "线路核对时间：${DateFormat.getTimeInstance().format(Date(at))}",
      style = MaterialTheme.typography.bodySmall,
      color = SubText,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
  draft.error?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
  if (draft.notificationBlocked) TextButton(onClick = onNotificationSettings) { Text("打开通知设置") }
  Spacer(Modifier.size(16.dp))
  PrimaryAction(
    when {
      permissionPending -> "等待通知授权…"
      draft.starting -> "正在开始…"
      draft.verifying -> "正在核对站台与线路…"
      else -> "开始监测"
    },
    enabled = !draft.starting && !draft.verifying && !permissionPending,
    onClick = onStart,
  )
  TextButton(enabled = !draft.starting && !draft.verifying && !permissionPending, onClick = application::dismissPreflight) { Text("取消") }
  Text("等车提醒需要通知权限；开始前会联网核对站台与线路。", style = MaterialTheme.typography.bodySmall, color = SubText)
  Text("本次最多监测 30 分钟；连续 5 分钟没有成功响应会自动结束。", style = MaterialTheme.typography.bodySmall, color = SubText)
}

/** D 的 B 画面：状态胶囊、当前预计、标题与线路条；不含原型调试控件。 */
@Composable
private fun WaitingPanel(
  wait: WaitState,
  scope: kotlinx.coroutines.CoroutineScope,
  application: WatchItemApplication,
) {
  val phase = wait.phase
  val primary = wait.lines.firstOrNull { it.minutes != null }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(8.dp).background(if (wait.running) Accent else SubText, CircleShape))
    Spacer(Modifier.width(8.dp))
    Text(
      if (!wait.running) (wait.endedReason?.let { "本次等车已结束" } ?: "本次等车已结束") else (phase?.label ?: WaitPhase.WAITING_FIRST.label),
      style = MaterialTheme.typography.labelLarge,
      color = if (wait.running) Accent else SubText,
    )
  }
  Spacer(Modifier.size(18.dp))
  Text(
    primary?.minutes?.toString() ?: "–",
    style = MaterialTheme.typography.displayLarge,
    color = BodyText,
  )
  Text(
    if (primary == null) "暂无可用预测" else "分钟 · 当前预计 ${primary.lineName}",
    style = MaterialTheme.typography.bodySmall,
    color = SubText,
  )
  Spacer(Modifier.size(14.dp))
  Text(wait.stationName.orEmpty(), style = MaterialTheme.typography.headlineMedium, color = BodyText)
  val walk = wait.walkMinutes
  val buffer = wait.bufferMinutes
  if (walk != null && buffer != null) {
    Text(
      "预计进入 ${formatMinutes(walk)}–${formatMinutes(walk + buffer)} 分钟时提醒你",
      style = MaterialTheme.typography.bodySmall,
      color = SubText,
      modifier = Modifier.padding(top = 8.dp),
    )
  }
  Spacer(Modifier.size(16.dp))
  Column(Modifier.fillMaxWidth().background(SoftSurface, RoundedCornerShape(17.dp)).padding(16.dp)) {
    wait.lines.forEach { line ->
      Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RouteTag(line.lineName)
        Spacer(Modifier.width(11.dp))
        Text("开往${line.endStationName}", style = MaterialTheme.typography.bodyMedium, color = BodyText)
        Spacer(Modifier.weight(1f))
        Text(
          when {
            line.arriving -> "即将到站"
            line.minutes != null -> "${line.minutes} 分钟"
            else -> "暂无预测"
          },
          style = MaterialTheme.typography.bodyMedium,
          color = if (line.minutes != null) BodyText else SubText,
        )
      }
    }
  }
  Spacer(Modifier.size(12.dp))
  val lastSuccess = wait.lastSuccessAt
  Text(
    buildString {
      append("最后成功：")
      append(lastSuccess?.let { DateFormat.getTimeInstance().format(Date(it)) } ?: "尚未成功")
      append(" · 请求间隔至少 60 秒")
    },
    style = MaterialTheme.typography.bodySmall,
    color = SubText,
  )
  wait.message?.takeIf { wait.phase == WaitPhase.RETRYING }?.let {
    Text(it, style = MaterialTheme.typography.bodySmall, color = SubText, modifier = Modifier.padding(top = 8.dp))
  }
  Spacer(Modifier.size(16.dp))
  if (wait.running) {
    SecondaryAction("结束等车") { scope.launch { application.stopWait() } }
    Text("结束不会触发出门提醒，也不会另发异常通知。", style = MaterialTheme.typography.bodySmall, color = SubText)
  } else {
    SecondaryAction("去关注项") { application.showCurrent(false) }
  }
}

/** 首页留白与原生层级，不以规则文字堆叠替代主状态。 */@Composable
private fun EmptyState(title: String, body: String) {
  Column(Modifier.fillMaxWidth().padding(top = 65.dp, bottom = 35.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
    // D 原型空状态：66dp soft 色块 + 浅柠图标，22dp 圆角。
    Box(Modifier.size(66.dp).background(SoftSurface, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
      Icon(Icons.Default.Place, contentDescription = null, tint = Accent, modifier = Modifier.size(30.dp))
    }
    Spacer(Modifier.size(24.dp))
    Text(title, style = MaterialTheme.typography.headlineLarge, color = BodyText)
    Spacer(Modifier.size(15.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, color = SubText, modifier = Modifier.fillMaxWidth(0.68f))
  }
}

/** D 原型主操作：浅柠底、深石蓝文字、17dp 圆角、54dp 高度。 */
@Composable
private fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(17.dp),
    contentPadding = PaddingValues(vertical = 14.dp, horizontal = 16.dp),
    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
  ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** D 原型次要操作：soft 底、浅柠文字，与主操作同形状。 */
@Composable
private fun SecondaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    enabled = enabled,
    colors = ButtonDefaults.buttonColors(containerColor = SoftSurface, contentColor = Accent),
    shape = RoundedCornerShape(17.dp),
    contentPadding = PaddingValues(vertical = 14.dp, horizontal = 16.dp),
    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
  ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** D 原型线路标签：浅柠底、深石蓝、8dp 圆角，最小 43dp 宽。 */
@Composable
private fun RouteTag(lineName: String, enabled: Boolean = true) {
  Box(
    Modifier.heightIn(min = 29.dp).width(43.dp).background(if (enabled) Accent else SoftSurface, RoundedCornerShape(8.dp)),
    contentAlignment = Alignment.Center,
  ) {
    Text(lineName.removeSuffix("路"), style = MaterialTheme.typography.labelMedium, color = if (enabled) DeepBlue else SubText, maxLines = 1)
  }
}

/** D 原型底部导航项：选中态为浅柠胶囊，未选中为次文色文字与图标。 */
@Composable
private fun DNavigationItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
  val color = if (selected) Accent else SubText
  Column(
    Modifier.width(150.dp).clickable(onClick = onClick).padding(vertical = 7.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box(
      Modifier.size(width = 60.dp, height = 30.dp).background(if (selected) Accent else Color.Transparent, CircleShape),
      contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = if (selected) DeepBlue else SubText, modifier = Modifier.size(20.dp)) }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
  }
}
