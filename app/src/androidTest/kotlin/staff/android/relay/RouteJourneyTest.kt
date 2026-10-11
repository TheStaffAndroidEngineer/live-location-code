package staff.android.relay

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class RouteJourneyTest {
    @get:Rule(order=0)val permission=GrantPermissionRule.grant(Manifest.permission.ACCESS_LOCAL_NETWORK)
    @get:Rule(order=1)val compose=createAndroidComposeRule<MainActivity>()
    @Test fun lostFixReplyReplaysWithoutCreatingAnotherMeasurement(){
        try {
            waitTag("start");tap("start");waitReady();tap("lab-tab");waitTag("next")
            control("{\"drop_reply\":true}");tap("next");waitTag("retry")
            assertEquals(1,control().getJSONObject("state").getJSONObject("accepted").getInt("sequence"))
            control("{}");tap("retry");waitReady()
            assertEquals(1,control().getJSONObject("state").getJSONObject("accepted").getInt("sequence"))
            tap("next");waitReady()
            assertEquals(2,control().getJSONObject("state").getJSONObject("accepted").getInt("sequence"))
            control("{\"advance_ms\":31000}");tap("refresh");waitReady();tap("route-tab");waitText("Position is stale")
            tap("end");waitText("Route ended")
            assertEquals("ENDED",control().getJSONObject("state").getJSONObject("trip").getString("phase"))
        }finally{control("{}")}
    }
    private fun tap(tag:String){
        val node = compose.onNodeWithTag(tag)
        if (tag in setOf("route-tab", "lab-tab", "refresh", "start", "end")) node.performClick()
        else node.performScrollTo().performClick()
    }
    private fun waitTag(tag:String){compose.waitUntil(30000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()};waitReady()}
    private fun waitText(text:String){compose.waitUntil(30000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()};waitReady()}
    private fun waitReady(){compose.waitUntil(30000){compose.onAllNodesWithTag("busy").fetchSemanticsNodes().isEmpty()}}
    private fun control(body:String?=null):JSONObject{
        val connection=URL("http://10.0.2.2:8914/__control").openConnection() as HttpURLConnection
        connection.connectTimeout=5000;connection.readTimeout=5000
        if(body!=null){connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{it.write(body.toByteArray())}}
        return try{JSONObject(connection.inputStream.bufferedReader().use{it.readText()})}finally{connection.disconnect()}
    }
}
