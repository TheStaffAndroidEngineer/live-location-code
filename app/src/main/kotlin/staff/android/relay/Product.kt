package staff.android.relay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.UUID

private fun integral(value:Any):Long {
    require(value is Int||value is Long)
    return (value as Number).toLong()
}
fun validateView(data:JSONObject){
    val epoch = integral(data.get("epoch"))
    require(epoch >= 0)
    require(data.getJSONArray("route").length() == 4)
    val trip = data.optJSONObject("trip")
    if (trip != null) {
        UUID.fromString(trip.getString("id"))
        require(trip.getString("phase") in setOf("ACTIVE", "ENDED"))
        require(integral(trip.get("startedAt")) >= 0)
    }
    if (!data.isNull("accepted")) {
        require(trip != null)
        val fix = data.getJSONObject("accepted")
        require(fix.getString("tripId") == trip.getString("id"))
        require(integral(fix.get("epoch")) == epoch)
        require(integral(fix.get("sequence")) in 1L..100L)
        require(fix.getString("name").isNotBlank())
        val lat = fix.get("lat")
        val lon = fix.get("lon")
        require(lat is Number && lon is Number)
        require(lat.toDouble().isFinite() && lat.toDouble() in -90.0..90.0)
        require(lon.toDouble().isFinite() && lon.toDouble() in -180.0..180.0)
        require(
            integral(fix.get("capturedAt")) >= 0 &&
                integral(fix.get("receivedAt")) >= integral(fix.get("capturedAt")))
        require(integral(data.get("fixAgeMs")) >= 0 && integral(data.get("receiptAgeMs")) >= 0)
    }
}

@Composable fun Product(state: CaseState, model: CaseModel, elapsed: Long) {
    val data = state.data
    val fix = data?.optJSONObject("accepted")
    val sequence = fix?.optInt("sequence") ?: 0
    Text("North depot → Collection door", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text("Demo route · no live GPS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 620.dp) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { RouteMap(sequence); RouteStatus(state, elapsed) }
            Column(Modifier.weight(1f)) { RouteItinerary(sequence) }
        } else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            RouteStatus(state, elapsed)
            RouteMap(sequence)
            RouteItinerary(sequence)
        }
    }
}

@Composable private fun RouteStatus(state: CaseState, elapsed: Long) {
    val data = state.data
    val trip = data?.optJSONObject("trip")
    val fix = data?.optJSONObject("accepted")
    val ended = trip?.getString("phase") == "ENDED"
    val age = if (fix == null) 0L else data!!.getLong("fixAgeMs") + elapsed
    val status = when { data == null -> if (state.busy) "Loading route" else "Route unavailable"; ended -> "Route ended"; trip == null -> "Ready for collection"; fix == null -> "Awaiting first fix"; age > 30000 -> "Position is stale"; else -> "Position updated" }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(status, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(when { data == null -> "Connect to the local service and refresh."; ended -> "Start another collection when you are ready."; trip == null -> "Four stops. Start the route when you are ready."; fix == null -> "Open Lab to send the first simulated position."; else -> "${fix.getString("name")} · measured ${age / 1000}s ago" }, style = MaterialTheme.typography.bodyMedium)
        if (fix != null) Text("Conservative freshness · not an arrival estimate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun RouteItinerary(sequence: Int) {
    val currentStop = if (sequence == 0) -1 else minOf(sequence - 1, 3)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Route stops", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        listOf("North depot", "Market crossing", "Library corner", "Collection door").forEachIndexed { index, name ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = if (index == currentStop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
                    Box(Modifier.size(32.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("${index + 1}", fontWeight = FontWeight.Bold, color = if (index == currentStop) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Bold)
                    if (index == currentStop) Text("Last reported position", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text("Schematic only. Stop numbers match the map; only the last accepted report is known.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun RelayActions(state: CaseState, model: CaseModel) {
    val data = state.data
    val trip = data?.optJSONObject("trip")
    val ended = trip?.getString("phase") == "ENDED"
    if (trip == null || ended) Button(onClick = { model.send("start") }, enabled = data != null && ready(state), modifier = Modifier.fillMaxWidth().testTag("start")) { Text(if (ended) "Start another route" else "Start route") }
    else OutlinedButton(onClick = { model.send("end", JSONObject().put("tripId", trip.getString("id")).put("epoch", data.getInt("epoch"))) }, enabled = ready(state), modifier = Modifier.fillMaxWidth().testTag("end")) { Text("End route") }
}

@Composable private fun RouteMap(sequence: Int) {
    val surface = MaterialTheme.colorScheme.surface
    val block = MaterialTheme.colorScheme.surfaceVariant
    val road = MaterialTheme.colorScheme.outlineVariant
    val route = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.onPrimary
    val labelColor = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(240.dp).testTag("route-map")) {
        drawRoundRect(surface, cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()))
        for (x in 0..5) for (y in 0..3) {
            drawRoundRect(block, topLeft = Offset(size.width * x / 6 + 10, size.height * y / 4 + 10), size = androidx.compose.ui.geometry.Size(size.width / 6 - 20, size.height / 4 - 20), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f))
        }
        val points = listOf(Offset(size.width * .12f, size.height * .78f), Offset(size.width * .36f, size.height * .49f), Offset(size.width * .64f, size.height * .54f), Offset(size.width * .88f, size.height * .20f))
        points.zipWithNext().forEach { (a, b) -> drawLine(road, a, b, 16.dp.toPx(), StrokeCap.Round); drawLine(route, a, b, 4.dp.toPx(), StrokeCap.Round) }
        points.forEachIndexed { i, point ->
            val current = sequence > 0 && i == minOf(sequence - 1, 3)
            if (current) drawCircle(route.copy(alpha = .22f), 28.dp.toPx(), point)
            drawCircle(if (current) route else surface, 17.dp.toPx(), point)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(255, ((if (current) marker else labelColor).red * 255).toInt(), ((if (current) marker else labelColor).green * 255).toInt(), ((if (current) marker else labelColor).blue * 255).toInt()); textSize = 15.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true }
            drawContext.canvas.nativeCanvas.drawText("${i + 1}", point.x, point.y - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }
}

@Composable fun RelayLab(state: CaseState, model: CaseModel, elapsed: Long) {
    SectionTitle("Route laboratory")
    Text("Chapter 34 · a local sensor simulator", color = MaterialTheme.colorScheme.onSurfaceVariant)
    InfoCard("Advance the courier", "This control sends the next seeded position. It does not read device GPS. Return to Route to see which stop the service accepted.")
    val data = state.data
    val trip = data?.optJSONObject("trip")
    val fix = data?.optJSONObject("accepted")
    if (trip != null) {
        Text("Route ${trip.getString("id")}\nGrant ${data.getInt("epoch")}", style = MaterialTheme.typography.bodySmall)
        if (trip.getString("phase") != "ENDED") Button(onClick = { model.send("next", JSONObject().put("tripId", trip.getString("id")).put("epoch", data.getInt("epoch"))) }, enabled = ready(state), modifier = Modifier.testTag("next")) { Text("Send next fix") }
        else InfoCard("Route ended", "Return to Route to start another collection.")
    } else Text("Start a collection on the Route tab first.")
    if (fix != null) InfoCard("Accepted sensor report", "Sequence ${fix.getInt("sequence")} · ${fix.getString("name")}\nMeasured ${(data!!.getLong("fixAgeMs") + elapsed) / 1000}s ago · received ${(data.getLong("receiptAgeMs") + elapsed) / 1000}s ago. Ages include the whole refresh round trip and continue ticking; stale can be reported early.")
    state.pending?.let { Text("Saved ${it.getString("action")} · ${it.getString("id")}", style = MaterialTheme.typography.bodySmall) }
    Text("Fault injection and clock controls are documented in the companion README. No live tracking or ETA is implemented.", style = MaterialTheme.typography.bodyMedium)
}
