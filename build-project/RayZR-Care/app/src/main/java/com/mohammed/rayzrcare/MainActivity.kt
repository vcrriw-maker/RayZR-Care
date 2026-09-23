package com.mohammed.rayzrcare

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

data class ServiceRecord(val id: Long, val type: String, val date: String, val dueDate: String?, val notes: String = "", val imageUri: String? = null)
data class FuelRecord(val id: Long, val date: String, val amount: String, val notes: String = "", val imageUri: String? = null)
data class Profile(val name: String = "", val motorcycle: String = "", val imageUri: String? = null)
enum class Tab { HOME, SERVICE, HISTORY, FUEL, SETTINGS }

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("rayzr", Context.MODE_PRIVATE)
    private fun array(key: String) = JSONArray(prefs.getString(key, "[]") ?: "[]")

    fun services(): MutableList<ServiceRecord> {
        val values = array("services")
        return MutableList(values.length()) { i -> values.getJSONObject(i).let { value ->
            ServiceRecord(value.getLong("id"), value.getString("type"), value.getString("date"), if (value.isNull("due")) null else value.getString("due"), value.optString("notes"), value.optString("image").ifBlank { null })
        } }
    }

    fun saveServices(list: List<ServiceRecord>) { prefs.edit().putString("services", JSONArray().apply { list.forEach { record -> put(JSONObject().apply { put("id", record.id); put("type", record.type); put("date", record.date); put("due", record.dueDate ?: JSONObject.NULL); put("notes", record.notes); put("image", record.imageUri ?: JSONObject.NULL) }) } }.toString()).apply() }

    fun fuels(): MutableList<FuelRecord> {
        val values = array("fuels")
        return MutableList(values.length()) { i -> values.getJSONObject(i).let { value -> FuelRecord(value.getLong("id"), value.getString("date"), value.getString("amount"), value.optString("notes"), value.optString("image").ifBlank { null }) } }
    }

    fun saveFuels(list: List<FuelRecord>) { prefs.edit().putString("fuels", JSONArray().apply { list.forEach { record -> put(JSONObject().apply { put("id", record.id); put("date", record.date); put("amount", record.amount); put("notes", record.notes); put("image", record.imageUri ?: JSONObject.NULL) }) } }.toString()).apply() }
    fun profile() = Profile(prefs.getString("name", "") ?: "", prefs.getString("motorcycle", "") ?: "", prefs.getString("profile_image", null))
    fun saveProfile(value: Profile) { prefs.edit().putString("name", value.name).putString("motorcycle", value.motorcycle).putString("profile_image", value.imageUri).apply() }

    fun background(): String? {
        prefs.getString("background", null)?.let { return it }
        val values = JSONObject(prefs.getString("backgrounds", "{}") ?: "{}")
        return Tab.values().firstNotNullOfOrNull { tab -> if (values.has(tab.name)) values.getString(tab.name) else null }
    }

    fun saveBackground(uri: String?) { prefs.edit().putString("background", uri).remove("backgrounds").apply() }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val due = Store(applicationContext).services().filter { it.dueDate != null }.map { it to ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(it.dueDate)) }.filter { it.second in 0..3 }
        if (due.isNotEmpty()) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("maintenance", "مواعيد الصيانة", NotificationManager.IMPORTANCE_DEFAULT))
            due.forEachIndexed { index, (service, days) -> manager.notify(100 + index, NotificationCompat.Builder(applicationContext, "maintenance").setSmallIcon(R.drawable.ic_stat_rayzr).setContentTitle("RayZR Care").setContentText(if (days == 0L) "اليوم موعد ${service.type}" else "باقي $days يوم على ${service.type}").setAutoCancel(true).build()) }
        }
        return Result.success()
    }
}

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("rayzr-reminders", ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build())
        setContent { RayZRApp(Store(this)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RayZRApp(store: Store) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Tab.HOME) }
    var services by remember { mutableStateOf(store.services()) }
    var fuels by remember { mutableStateOf(store.fuels()) }
    var profile by remember { mutableStateOf(store.profile()) }
    var backgroundUri by remember { mutableStateOf(store.background()) }
    var editing by remember { mutableStateOf<ServiceRecord?>(null) }
    var editingFuel by remember { mutableStateOf<FuelRecord?>(null) }
    var serviceEditor by remember { mutableStateOf(false) }
    var fuelEditor by remember { mutableStateOf(false) }

    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            store.saveBackground(uri.toString())
            backgroundUri = uri.toString()
        }
    }
    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            profile = profile.copy(imageUri = uri.toString()); store.saveProfile(profile)
        }
    }
    val chooseBackground = { backgroundPicker.launch(arrayOf("image/*")) }
    val resetBackground = { store.saveBackground(null); backgroundUri = null }

    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF006B9A), onPrimary = Color.White, secondary = Color(0xFF2E7650), background = Color(0xFFF5F8FA), surface = Color.White, surfaceVariant = Color(0xFFE8F0F4)), shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp))) {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
            Box(Modifier.fillMaxSize().background(if (backgroundUri == null) Color(0xFFF5F8FA) else Color.Transparent)) {
                backgroundUri?.let { BackgroundImage(Uri.parse(it), Modifier.fillMaxSize()) }
                val navigationContent = if (backgroundUri == null) Color(0xFF18323D) else Color.White
                val navigationContainer = if (backgroundUri == null) Color.White else Color(0xFF18252B)
                val chromeContent = if (backgroundUri == null) Color(0xFF18323D) else Color.White
                val chromeSurface = if (backgroundUri == null) Color.White else Color(0xFF18252B)
                Scaffold(containerColor = Color.Transparent, topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = chromeSurface, titleContentColor = chromeContent, navigationIconContentColor = chromeContent, actionIconContentColor = chromeContent), title = { Text("RayZR Care", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }, actions = { Icon(Icons.Outlined.TwoWheeler, "RayZR Care", Modifier.padding(horizontal = 16.dp)) }) }, bottomBar = { NavigationBar(windowInsets = NavigationBarDefaults.windowInsets, containerColor = navigationContainer, contentColor = navigationContent, tonalElevation = 0.dp) { listOf(Tab.HOME to (Icons.Outlined.Home to "الرئيسية"), Tab.SERVICE to (Icons.Outlined.Build to "الصيانة"), Tab.HISTORY to (Icons.Outlined.History to "السجل"), Tab.FUEL to (Icons.Outlined.LocalGasStation to "البنزين"), Tab.SETTINGS to (Icons.Outlined.Settings to "الإعدادات")).forEach { (item, label) -> NavigationBarItem(selected = tab == item, onClick = { if (tab != item) tab = item }, icon = { Icon(label.first, label.second, Modifier.size(22.dp)) }, label = { Text(label.second, style = MaterialTheme.typography.labelMedium, fontWeight = if (tab == item) FontWeight.Bold else FontWeight.Medium) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = navigationContent, selectedTextColor = navigationContent, indicatorColor = if (backgroundUri == null) Color(0xFFD9EDF2) else Color(0xFF426D78), unselectedIconColor = navigationContent, unselectedTextColor = navigationContent) ) } } }, floatingActionButton = { if (tab == Tab.SERVICE || tab == Tab.HISTORY) FloatingActionButton({ editing = null; serviceEditor = true }, shape = RoundedCornerShape(18.dp), containerColor = Color(0xFF006B9A), contentColor = Color.White) { Icon(Icons.Outlined.Add, "إضافة", Modifier.size(26.dp)) } else if (tab == Tab.FUEL) FloatingActionButton({ editingFuel = null; fuelEditor = true }, shape = RoundedCornerShape(18.dp), containerColor = Color(0xFF006B9A), contentColor = Color.White) { Icon(Icons.Outlined.Add, "إضافة تعبئة", Modifier.size(26.dp)) } }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (tab) {
                        Tab.HOME -> Home(backgroundUri, services, profile)
                        Tab.SERVICE -> Services(backgroundUri, services, { editing = it; serviceEditor = true }, { record -> services = services.filterNot { it.id == record.id }.toMutableList(); store.saveServices(services) })
                        Tab.HISTORY -> History(backgroundUri, services)
                        Tab.FUEL -> Fuel(backgroundUri, fuels, { editingFuel = it; fuelEditor = true }, { record -> fuels = fuels.filterNot { it.id == record.id }.toMutableList(); store.saveFuels(fuels) })
                        Tab.SETTINGS -> Settings(backgroundUri, chooseBackground, resetBackground, profile, { profile = it; store.saveProfile(it) }, { profilePicker.launch(arrayOf("image/*")) })
                    }
                }
            }
            }
            if (serviceEditor) ServiceEditor(editing, { serviceEditor = false }, { record -> val next = services.toMutableList(); val index = next.indexOfFirst { it.id == record.id }; if (index >= 0) next[index] = record else next.add(0, record); services = next; store.saveServices(next); serviceEditor = false })
            if (fuelEditor) FuelEditor(editingFuel, { fuelEditor = false }, { record -> val next = fuels.toMutableList(); val index = next.indexOfFirst { it.id == record.id }; if (index >= 0) next[index] = record else next.add(0, record); fuels = next; store.saveFuels(next); fuelEditor = false })
        }
    }
}

@Composable
fun SectionFrame(backgroundUri: String?, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        val textColor = if (backgroundUri == null) MaterialTheme.colorScheme.onBackground else Color.White
        val textShadow = if (backgroundUri == null) null else Shadow(Color.Black.copy(alpha = .7f), Offset(0f, 1.5f), 2f)
        CompositionLocalProvider(LocalContentColor provides textColor) {
            ProvideTextStyle(LocalTextStyle.current.merge(TextStyle(shadow = textShadow))) {
                content()
            }
        }
    }
}

@Composable
fun adaptiveCardColors(backgroundUri: String?): CardColors = CardDefaults.cardColors(
    containerColor = if (backgroundUri == null) Color.White else Color(0xE61B2930),
    contentColor = if (backgroundUri == null) MaterialTheme.colorScheme.onSurface else Color.White
)

@Composable
fun adaptiveListColors(backgroundUri: String?): ListItemColors = ListItemDefaults.colors(
    containerColor = if (backgroundUri == null) Color.White else Color(0xE61B2930),
    headlineColor = if (backgroundUri == null) MaterialTheme.colorScheme.onSurface else Color.White,
    supportingColor = if (backgroundUri == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
    leadingIconColor = if (backgroundUri == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
)
@Composable
fun BackgroundImage(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap = remember(uri) {
        runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull()
    }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier) }
}

@Composable
fun BackgroundControls(backgroundUri: String?, onChoose: () -> Unit, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onChoose, Modifier.weight(1f), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) { Icon(Icons.Outlined.AddPhotoAlternate, "اختيار خلفية", Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (backgroundUri == null) "اختيار خلفية" else "تغيير الخلفية", fontWeight = FontWeight.SemiBold) }
        if (backgroundUri != null) OutlinedButton(onReset, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) { Icon(Icons.Outlined.RestartAlt, "إزالة الخلفية", Modifier.size(20.dp)); Spacer(Modifier.width(4.dp)); Text("إزالة الخلفية") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(value: String, label: String, onValueChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { },
        readOnly = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        label = { Text(label) },
        trailingIcon = { IconButton(onClick = { showPicker = true }) { Icon(Icons.Outlined.CalendarMonth, "اختيار التاريخ") } }
    )
    if (showPicker) {
        val initialMillis = runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onValueChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; showPicker = false }) { Text("تأكيد") } },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("إلغاء") } }
        ) { DatePicker(state = state, showModeToggle = false) }
    }
}

@Composable
fun Home(backgroundUri: String?, services: List<ServiceRecord>, profile: Profile) {
    val upcoming = services.filter { it.dueDate != null }.sortedBy { it.dueDate }
    SectionFrame(backgroundUri) {
        LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text(if (profile.name.isBlank()) "أهلاً بك في RayZR Care" else "مرحباً ${profile.name}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(if (profile.motorcycle.isBlank()) "أنشئ ملف دراجتك وابدأ تسجيل صيانتها" else profile.motorcycle, color = if (backgroundUri == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White.copy(alpha = .92f)) }
            item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = adaptiveCardColors(backgroundUri)) { Column(Modifier.padding(20.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.TwoWheeler, null, tint = if (backgroundUri == null) MaterialTheme.colorScheme.primary else Color.White); Spacer(Modifier.width(10.dp)); Text(if (services.isEmpty()) "جاهز لتسجيل أول صيانة" else "متابعة صيانة دراجتك", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }; Spacer(Modifier.height(6.dp)); Text(if (services.isEmpty()) "ستظهر مواعيدك وتنبيهاتك هنا بعد إضافة سجلاتك." else "يتم حساب المواعيد من تاريخ الهاتف تلقائياً.") } } }
            if (upcoming.isEmpty()) item { Text("لا توجد مواعيد قادمة", color = if (backgroundUri == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White.copy(alpha = .92f)) }
            items(upcoming, key = { it.id }) { DueCard(it, backgroundUri) }
        }
    }
}

@Composable fun DueCard(record: ServiceRecord, backgroundUri: String?) { val days = try { record.dueDate?.let { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(it)) } } catch (_: Exception) { null }; Card(Modifier.fillMaxWidth(), colors = adaptiveCardColors(backgroundUri)) { Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(if (record.type.contains("زيت")) Icons.Outlined.Opacity else Icons.Outlined.Build, null); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(record.type, fontWeight = FontWeight.Bold); Text("آخر صيانة: ${record.date}"); Text("الاستحقاق: ${record.dueDate}") }; Text(when { days == null -> ""; days < 0 -> "متأخر ${-days} يوم"; days == 0L -> "اليوم"; else -> "باقي $days يوم" }, color = when { days != null && days > 0 -> Color(0xFF218739); days != null && days < 0 -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.primary }, fontWeight = FontWeight.Bold) } } }

@Composable fun Services(backgroundUri: String?, list: List<ServiceRecord>, onEdit: (ServiceRecord) -> Unit, onDelete: (ServiceRecord) -> Unit) { SectionFrame(backgroundUri) { LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Text("الصيانة", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }; if (list.isEmpty()) item { EmptyState("لا توجد سجلات صيانة", "أضف أول سجل من زر +", backgroundUri) }; items(list, key = { it.id }) { record -> RecordCard(record.type, record.date, record.notes, record.imageUri, backgroundUri, { onEdit(record) }, { onDelete(record) }) } } } }
@Composable fun History(backgroundUri: String?, list: List<ServiceRecord>) { SectionFrame(backgroundUri) { LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Text("سجل الصيانة", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }; if (list.isEmpty()) item { EmptyState("السجل فارغ", "ستظهر سجلاتك هنا بعد إضافتها", backgroundUri) }; items(list.sortedByDescending { it.date }, key = { it.id }) { record -> Card(Modifier.fillMaxWidth(), colors = adaptiveCardColors(backgroundUri)) { ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = if (backgroundUri == null) MaterialTheme.colorScheme.onSurface else Color.White, supportingColor = if (backgroundUri == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White, leadingIconColor = if (backgroundUri == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White), headlineContent = { Text(record.type) }, supportingContent = { Text(listOf(record.date, record.notes).filter { it.isNotBlank() }.joinToString(" • ")) }, leadingContent = { Icon(Icons.Outlined.History, null) }) } } } } }
@Composable fun Fuel(backgroundUri: String?, list: List<FuelRecord>, onEdit: (FuelRecord) -> Unit, onDelete: (FuelRecord) -> Unit) { SectionFrame(backgroundUri) { LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Text("سجل البنزين", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }; if (list.isEmpty()) item { EmptyState("لا توجد تعبئات", "أضف أول تعبئة من زر +", backgroundUri) }; items(list, key = { it.id }) { record -> RecordCard("${record.amount} - ${record.date}", record.date, record.notes, record.imageUri, backgroundUri, { onEdit(record) }, { onDelete(record) }) } } } }

@Composable fun UriImage(uri: Uri, modifier: Modifier, alpha: Float = 1f) { androidx.compose.ui.viewinterop.AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }, update = { it.setImageURI(uri); it.alpha = alpha }, modifier = modifier) }
@Composable fun RecordCard(title: String, date: String, notes: String, image: String?, backgroundUri: String?, onEdit: () -> Unit, onDelete: () -> Unit) { Card(Modifier.fillMaxWidth(), colors = adaptiveCardColors(backgroundUri)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { if (image != null) UriImage(Uri.parse(image), Modifier.size(58.dp).clip(RoundedCornerShape(10.dp))) else Icon(Icons.Outlined.Build, null, Modifier.padding(8.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(date); if (notes.isNotBlank()) Text(notes, color = if (backgroundUri == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White) }; IconButton(onEdit) { Icon(Icons.Outlined.Edit, "تعديل") }; IconButton(onDelete) { Icon(Icons.Outlined.Delete, "حذف") } } } }
@Composable fun EmptyState(title: String, message: String, backgroundUri: String?) { Card(Modifier.fillMaxWidth(), colors = adaptiveCardColors(backgroundUri)) { Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.Inbox, null, Modifier.size(40.dp)); Spacer(Modifier.height(8.dp)); Text(title, fontWeight = FontWeight.Bold); Text(message, color = if (backgroundUri == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White) } } }

@Composable
fun Settings(backgroundUri: String?, onChooseBackground: () -> Unit, onResetBackground: () -> Unit, profile: Profile, onProfile: (Profile) -> Unit, onChooseProfileImage: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    SectionFrame(backgroundUri) { LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackgroundControls(backgroundUri, onChooseBackground, onResetBackground) }
        item { Text("الإعدادات", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Card(Modifier.fillMaxWidth(), colors = adaptiveCardColors(backgroundUri)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { if (profile.imageUri != null) UriImage(Uri.parse(profile.imageUri), Modifier.size(56.dp).clip(RoundedCornerShape(28.dp))) else Icon(Icons.Outlined.TwoWheeler, null, Modifier.size(42.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("ملف الدراجة", fontWeight = FontWeight.Bold); Text(if (profile.motorcycle.isBlank()) "لم تتم إضافة بيانات بعد" else profile.motorcycle, color = if (backgroundUri == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White) }; IconButton({ editing = true }) { Icon(Icons.Outlined.Edit, "تعديل الملف") }; IconButton(onChooseProfileImage) { Icon(Icons.Outlined.AddPhotoAlternate, "اختيار صورة الملف") } } } }
        item { ListItem(colors = adaptiveListColors(backgroundUri), headlineContent = { Text("الصور والسجلات") }, supportingContent = { Text("يمكنك إضافة صورة من داخل كل نموذج صيانة أو تعبئة") }, leadingContent = { Icon(Icons.Outlined.PhotoLibrary, null) }) }
        item { ListItem(colors = adaptiveListColors(backgroundUri), headlineContent = { Text("البيانات محلية") }, supportingContent = { Text("تبقى سجلاتك وصورك وخلفياتك محفوظة على هذا الجهاز") }, leadingContent = { Icon(Icons.Outlined.CloudOff, null) }) }
        item { Text("RayZR Care 1.0", color = if (backgroundUri == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White.copy(alpha = .92f), modifier = Modifier.padding(top = 12.dp)) }
    } }
    if (editing) ProfileEditor(profile, { editing = false }, { onProfile(it); editing = false })
}

@Composable fun ProfileEditor(profile: Profile, onDismiss: () -> Unit, onSave: (Profile) -> Unit) { var name by remember { mutableStateOf(profile.name) }; var motorcycle by remember { mutableStateOf(profile.motorcycle) }; AlertDialog(shape = RoundedCornerShape(24.dp), onDismissRequest = onDismiss, title = { Text("ملف الدراجة", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), label = { Text("اسم المالك (اختياري)") }); OutlinedTextField(motorcycle, { motorcycle = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), label = { Text("اسم الدراجة (اختياري)") }) } }, confirmButton = { Button({ onSave(Profile(name.trim(), motorcycle.trim(), profile.imageUri)) }, shape = RoundedCornerShape(12.dp)) { Text("حفظ") } }, dismissButton = { TextButton(onDismiss, shape = RoundedCornerShape(12.dp)) { Text("إلغاء") } }) }

@Composable fun ServiceEditor(existing: ServiceRecord?, onDismiss: () -> Unit, onSave: (ServiceRecord) -> Unit) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(existing?.type ?: "") }; var date by remember { mutableStateOf(existing?.date ?: LocalDate.now().toString()) }; var due by remember { mutableStateOf(existing?.dueDate ?: "") }; var notes by remember { mutableStateOf(existing?.notes ?: "") }; var image by remember { mutableStateOf(existing?.imageUri) }; var error by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}; image = it.toString() } }
    AlertDialog(shape = RoundedCornerShape(24.dp), onDismissRequest = onDismiss, title = { Text(if (existing == null) "إضافة صيانة" else "تعديل الصيانة", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(type, { type = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), label = { Text("نوع الصيانة") }); DateField(date, "تاريخ الصيانة", { date = it }); DateField(due, "الاستحقاق القادم (اختياري)", { due = it }); OutlinedTextField(notes, { notes = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(14.dp), label = { Text("ملاحظات") }); OutlinedButton({ picker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(vertical = 12.dp)) { Icon(Icons.Outlined.AddPhotoAlternate, "صورة السجل", Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (image == null) "إضافة صورة للسجل" else "تغيير صورة السجل") }; if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }, confirmButton = { Button({ try { LocalDate.parse(date); if (due.isNotBlank()) LocalDate.parse(due); if (type.isBlank()) { error = "اكتب نوع الصيانة"; return@Button }; onSave(ServiceRecord(existing?.id ?: System.currentTimeMillis(), type.trim(), date, due.ifBlank { null }, notes.trim(), image)) } catch (_: Exception) { error = "صيغة التاريخ لازم تكون YYYY-MM-DD" } }, shape = RoundedCornerShape(12.dp)) { Text("حفظ") } }, dismissButton = { TextButton(onDismiss, shape = RoundedCornerShape(12.dp)) { Text("إلغاء") } })
}

@Composable fun FuelEditor(existing: FuelRecord?, onDismiss: () -> Unit, onSave: (FuelRecord) -> Unit) {
    val context = LocalContext.current
    var date by remember { mutableStateOf(existing?.date ?: LocalDate.now().toString()) }; var amount by remember { mutableStateOf(existing?.amount ?: "") }; var notes by remember { mutableStateOf(existing?.notes ?: "") }; var image by remember { mutableStateOf(existing?.imageUri) }; var error by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}; image = it.toString() } }
    AlertDialog(shape = RoundedCornerShape(24.dp), onDismissRequest = onDismiss, title = { Text(if (existing == null) "إضافة تعبئة بنزين" else "تعديل التعبئة", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { DateField(date, "تاريخ التعبئة", { date = it }); OutlinedTextField(amount, { amount = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), label = { Text("المبلغ أو الكمية") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)); OutlinedTextField(notes, { notes = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(14.dp), label = { Text("ملاحظات") }); OutlinedButton({ picker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(vertical = 12.dp)) { Icon(Icons.Outlined.AddPhotoAlternate, "صورة التعبئة", Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (image == null) "إضافة صورة للتعبئة" else "تغيير صورة التعبئة") }; if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }, confirmButton = { Button({ try { LocalDate.parse(date); if (amount.isBlank()) { error = "أدخل المبلغ أو الكمية"; return@Button }; onSave(FuelRecord(existing?.id ?: System.currentTimeMillis(), date, amount.trim(), notes.trim(), image)) } catch (_: Exception) { error = "صيغة التاريخ لازم تكون YYYY-MM-DD" } }, shape = RoundedCornerShape(12.dp)) { Text("حفظ") } }, dismissButton = { TextButton(onDismiss, shape = RoundedCornerShape(12.dp)) { Text("إلغاء") } })
}