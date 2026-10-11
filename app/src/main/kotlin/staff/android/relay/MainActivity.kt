package staff.android.relay

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity:ComponentActivity(){
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted ->
        if(granted)ViewModelProvider(this)[CaseModel::class.java].refresh()
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        if(Build.VERSION.SDK_INT>=37&&checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)!=PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val model=ViewModelProvider(this)[CaseModel::class.java]
        setContent{ProductTheme{val state by model.state.collectAsStateWithLifecycle();CaseScreen(state,model)}}
    }
}
val BodyFont=FontFamily(Font(R.font.google_sans_regular),Font(R.font.google_sans_bold,FontWeight.Bold))
val HeadingFont=FontFamily(Font(R.font.sora_bold,FontWeight.Bold))
@Composable fun ProductTheme(content:@Composable ()->Unit){
    val scheme=if(isSystemInDarkTheme())darkColorScheme(
        primary=Color(0xFFA7C8FF),onPrimary=Color(0xFF103461),background=Color(0xFF101C2B),onBackground=Color(0xFFE8EFF9),
        surface=Color(0xFF182A40),onSurface=Color(0xFFE8EFF9),surfaceVariant=Color(0xFF273C54),onSurfaceVariant=Color(0xFFC2D0E2),
        secondaryContainer=Color(0xFF264E7C),onSecondaryContainer=Color(0xFFD6E6FF),outline=Color(0xFF9BAFC7),outlineVariant=Color(0xFF405670))
    else lightColorScheme(
        primary=Color(0xFF245EBD),onPrimary=Color(0xFFFFFFFF),background=Color(0xFFF1F5F9),onBackground=Color(0xFF142C43),
        surface=Color(0xFFFFFFFF),onSurface=Color(0xFF142C43),surfaceVariant=Color(0xFFE0E9F4),onSurfaceVariant=Color(0xFF455C74),
        secondaryContainer=Color(0xFFDDEAFF),onSecondaryContainer=Color(0xFF163E71),outline=Color(0xFF637C97),outlineVariant=Color(0xFFC3D2E3))
    val defaults=Typography()
    MaterialTheme(scheme,typography=defaults.copy(bodyLarge=defaults.bodyLarge.copy(fontFamily=BodyFont),
        bodyMedium=defaults.bodyMedium.copy(fontFamily=BodyFont),titleMedium=defaults.titleMedium.copy(fontFamily=BodyFont),
        labelLarge=defaults.labelLarge.copy(fontFamily=BodyFont)),content=content)
}
@Composable fun CaseScreen(state: CaseState, model: CaseModel) {
    var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); clock = SystemClock.elapsedRealtime() } }
    var lab by rememberSaveable { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    BackHandler(enabled = lab) { lab = false }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.statusBarsPadding().fillMaxWidth()) {
                AppChrome("Relay", statusInsets = false) {
                    TextButton(onClick = model::refresh, enabled = !state.busy && !state.storageBlocked, modifier = Modifier.testTag("refresh")) { Text("Refresh") }
                    TextButton(onClick = { about = true }) { Text("About") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    TextButton(onClick = { lab = false }, modifier = Modifier.weight(1f).testTag("route-tab").semantics { selected = !lab; role = Role.Tab }) { Text(if (lab) "Route" else "● Route") }
                    TextButton(onClick = { lab = true }, modifier = Modifier.weight(1f).testTag("lab-tab").semantics { selected = lab; role = Role.Tab }) { Text(if (lab) "● Lab" else "Lab") }
                }
            }
        }
    }, bottomBar = {
        if (!lab) Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Box(Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = 860.dp).fillMaxWidth()) { RelayActions(state, model) }
            }
        }
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 900.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("busy"))
                if (state.problem != null) InfoCard(if (state.storageBlocked) "Saved route needs attention" else "Connection interrupted", state.problem)
                state.pending?.let {
                    InfoCard("Waiting for confirmation", "Your route update is saved. Retry to check the same update before making another change.")
                    Button(onClick = model::retry, enabled = !state.busy && !state.storageBlocked, modifier = Modifier.testTag("retry")) { Text("Retry saved request") }
                }
                state.outcome?.takeIf { it.has("reason") }?.let { InfoCard("Update not accepted", it.getString("reason")) }
                val elapsed = if (state.receivedElapsed == 0L) 0L else (clock - state.receivedElapsed).coerceAtLeast(0)
                if (lab) RelayLab(state, model, elapsed) else Product(state, model, elapsed)
            }
        }
    }
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("About Relay") }, text = { Text("A local dispatch simulation for Chapter 34. Positions come from four seeded stops, not GPS. Relay does not track a real courier, run background location or estimate arrival time. The map is a route schematic; use Lab to advance the simulated sensor.") }, confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } })
}
@Composable fun InfoCard(title:String,body:String){
    Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(title,fontWeight=FontWeight.Bold);Text(body,style=MaterialTheme.typography.bodyLarge)
        }
    }
}
@Composable fun SectionTitle(title:String){Text(title,fontFamily=HeadingFont,fontSize=27.sp,lineHeight=34.sp)}
fun ready(state:CaseState)=!state.busy&&!state.storageBlocked&&state.pending==null

@Composable
private fun AppChrome(title: String, statusInsets: Boolean = true, actions: @Composable () -> Unit) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    BoxWithConstraints((if (statusInsets) Modifier.statusBarsPadding() else Modifier).fillMaxWidth().padding(horizontal = 16.dp)) {
        val needed = (title.length * 13 + if (title == "Fieldnotes") 290 else 190).dp * fontScale
        if (maxWidth < needed) {
            Column(Modifier.fillMaxWidth()) {
                Text(title, fontFamily = HeadingFont, fontSize = 22.sp, lineHeight = 28.sp, modifier = Modifier.padding(top = 10.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontFamily = HeadingFont, fontSize = 22.sp, lineHeight = 28.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                actions()
            }
        }
    }
}
