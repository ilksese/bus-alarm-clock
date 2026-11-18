package com.ilksese.busalarmclock

import android.app.Application
import android.net.Uri
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.ilksese.busalarmclock.data.*
import com.ilksese.busalarmclock.ui.theme.BusAlarmClockTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** 配置变化复用同一行为入口与请求状态，不因重建 Activity 绕过请求间隔。 */
class WatchItemsViewModel(application: Application) : AndroidViewModel(application) {
  private val database = DatabaseProvider.create(application)
  val applicationBehavior = WatchItemApplication(BusSourceClient(), RoomWatchItemStore(database))
  override fun onCleared() { database.close() }
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val model: WatchItemsViewModel = viewModel()
      BusAlarmClockTheme { WatchItemsScreen(model.applicationBehavior, model.viewModelScope) }
    }
  }
}

/** 两主页与同一添加流程；UI 不直接发 HTTP 请求或写 Room。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchItemsScreen(application: WatchItemApplication, actionScope: kotlinx.coroutines.CoroutineScope? = null) {
  val state by application.state.collectAsStateWithLifecycle()
  val compositionScope = rememberCoroutineScope()
  val scope = actionScope ?: compositionScope
  val context = androidx.compose.ui.platform.LocalContext.current
  var scanning by remember { mutableStateOf(false) }
  var scanGeneration by remember { mutableLongStateOf(0) }
  LaunchedEffect(application) { application.open() }

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
  BackHandler(state.adding && !state.saving) { cancel() }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(if (state.adding) "确认上车站台" else if (state.currentPage) "当前等车" else "关注项") },
        navigationIcon = {
          if (state.adding) IconButton(enabled = !state.saving, onClick = { cancel() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "取消添加") }
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
          DNavigationItem("关注项", Icons.Default.Place, selected = !state.currentPage) { application.showCurrent(false) }
          DNavigationItem("当前等车", Icons.Default.Schedule, selected = state.currentPage) { application.showCurrent(true) }
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
          if (draft == null) {
            item {
              Text("从相册识别", style = MaterialTheme.typography.headlineMedium)
              Text("选择含公交二维码的图片，随后确认具体站台与线路方向。", modifier = Modifier.padding(top = 12.dp))
              Text("不获取定位，不保存扫码照片。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            item { PrimaryAction("从相册选择", enabled = !scanning && !state.loading) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
            item { SecondaryAction("选图不可用？从系统文件选择", enabled = !scanning && !state.loading) { documentPicker.launch(arrayOf("image/*")) } }
          } else {
            item {
              Text(draft.station.stationName, style = MaterialTheme.typography.headlineSmall)
              Text("站台 ${draft.stopCode}${if (draft.stopCode == draft.station.defaultStopCode) " · 二维码指向站台" else " · 同名另一站台"}")
            }
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
          item {
            EmptyState("还没开始等车", "去关注项，选一个具体站台开始。")
            SecondaryAction("去关注项") { application.showCurrent(false) }
          }
        }
        else -> {
          if (state.items.isEmpty()) item { EmptyState("下一站，\n从这里开始。", "点右上角加号，识别公交二维码并保存你的上车站台。") }
          state.items.forEach { item ->
            item(key = item.stopCode) {
              Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 19.dp, bottom = 23.dp)) {
                Text(item.stationName, style = MaterialTheme.typography.headlineSmall, color = BodyText)
                Text("站台 ${item.stopCode} · ${item.selectedLines.size} 条已选线路", style = MaterialTheme.typography.bodySmall, color = SubText)
                item.selectedLines.forEach { line ->
                  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RouteTag(line.lineName)
                    Spacer(Modifier.width(11.dp))
                    Text("开往${line.endStationName}", style = MaterialTheme.typography.bodyMedium, color = BodyText)
                  }
                }
                Text("本机保存 · 尚未联网核对", style = MaterialTheme.typography.bodySmall, color = SubText)
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
}

/** 首页留白与原生层级，不以规则文字堆叠替代主状态。 */
@Composable
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
private fun RouteTag(lineName: String) {
  Box(
    Modifier.heightIn(min = 29.dp).width(43.dp).background(Accent, RoundedCornerShape(8.dp)),
    contentAlignment = Alignment.Center,
  ) {
    Text(lineName.removeSuffix("路"), style = MaterialTheme.typography.labelMedium, color = DeepBlue, maxLines = 1)
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
