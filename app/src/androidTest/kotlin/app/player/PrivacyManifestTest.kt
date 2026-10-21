package app.player

import android.Manifest
import android.app.Activity
import android.app.LocaleConfig
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.util.Locale
import java.util.regex.Pattern

/** What the installed app declares: permissions, screens, locales, back and edge-to-edge (P2.5b). */
@RunWith(AndroidJUnit4::class)
class PrivacyManifestTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val pm = context.packageManager
    private val pkg = context.packageName

    private fun requested(): List<String> =
        pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()

    @Ears("US-71.3")
    @Test fun us_71_3_no_location_camera_contacts_overlay_ad_id_billing_or_install_referrer_permission() {
        val declared = requested()
        val forbidden = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.GET_ACCOUNTS,
            Manifest.permission.SYSTEM_ALERT_WINDOW,
            "com.google.android.gms.permission.AD_ID", "android.permission.ACCESS_ADSERVICES_AD_ID",
            "android.permission.ACCESS_ADSERVICES_ATTRIBUTION", "android.permission.ACCESS_ADSERVICES_TOPICS",
            "com.android.vending.BILLING", "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
        )
        val words = Regex("LOCATION|CAMERA|CONTACTS|ALERT_WINDOW|AD_ID|ADSERVICES|BILLING|INSTALL_REFERRER")
        val found = declared.filter { it in forbidden || words.containsMatchIn(it) }
        android.util.Log.i("P2.5b", "declared permissions: $declared")
        assertEquals("forbidden permissions declared", emptyList<String>(), found)
    }

    @Ears("US-71.2")
    @Test fun us_71_2_no_broad_file_access_is_declared() {
        val broad = listOf(
            "android.permission.MANAGE_EXTERNAL_STORAGE", Manifest.permission.WRITE_EXTERNAL_STORAGE,
            "android.permission.MANAGE_MEDIA", Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        assertEquals(emptyList<String>(), requested().filter { it in broad })
    }

    @Ears("US-72.2")
    @Test fun us_72_2_no_account_login_or_sign_in_screen() {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES)
        val screens = info.activities.orEmpty().map { it.name }
        val login = Regex("(?i)login|log_in|signin|sign_in|signup|account|auth")
        assertEquals("login-like screens: $screens", emptyList<String>(), screens.filter { login.containsMatchIn(it.substringAfterLast('.')) })
        assertTrue("account authenticator declared",
            pm.queryIntentServices(Intent("android.accounts.AccountAuthenticator").setPackage(pkg), 0).isEmpty())
        val accountPerms = listOf(Manifest.permission.GET_ACCOUNTS, "android.permission.USE_CREDENTIALS",
            "android.permission.MANAGE_ACCOUNTS", "android.permission.AUTHENTICATE_ACCOUNTS")
        assertEquals(emptyList<String>(), requested().filter { it in accountPerms })

        // Starting the app goes straight to the library: nothing asks to sign in.
        val device = UiDevice.getInstance(instr)
        context.startActivity(pm.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        SystemClock.sleep(3_000)
        evidenceShot("first-screen")
        assertFalse(device.hasObject(By.text(Pattern.compile("(?i).*(sign in|log in|login|sign up|create account).*"))))
        instr.runOnMainSync { resumed()?.finish() }
    }

    @Ears("US-68.1")
    @Test fun us_68_1_supported_locales_are_declared_for_the_system_language_page() {
        val config = LocaleConfig(context)
        assertEquals(LocaleConfig.STATUS_SUCCESS, config.status)
        val locales = config.supportedLocales!!
        val tags = (0 until locales.size()).map { locales[it].toLanguageTag() }
        android.util.Log.i("P2.5b", "declared locales: $tags")
        assertTrue("en-US missing from $tags", Locale.forLanguageTag("en-US").toLanguageTag() in tags)
    }

    @Ears("US-67.2")
    @Test fun us_67_2_edge_to_edge_and_predictive_back() {
        // Predictive back: declared for the whole app, and no screen opts out.
        assertTrue("targets API ${context.applicationInfo.targetSdkVersion}", context.applicationInfo.targetSdkVersion >= 36)
        val parser = context.assets.openXmlResourceParser("AndroidManifest.xml")
        var appBack: String? = null
        val optedOut = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            val value = parser.getAttributeValue(ANDROID, "enableOnBackInvokedCallback")
            when (parser.name) {
                "application" -> appBack = value
                "activity", "activity-alias" -> if (value == "false") optedOut += parser.getAttributeValue(ANDROID, "name")
            }
        }
        assertEquals("predictive back not enabled for the app", "true", appBack)
        assertEquals("screens opting out of predictive back", emptyList<String>(), optedOut)

        // Edge to edge: the library's content starts under the status bar.
        context.startActivity(pm.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val end = SystemClock.uptimeMillis() + 10_000
        while (resumed() == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(100)
        val activity = resumed()
        assertNotNull("app did not start", activity)
        SystemClock.sleep(1_500)
        var top = -1
        var statusBar = 0
        instr.runOnMainSync {
            val content = activity!!.findViewById<View>(android.R.id.content)
            val at = IntArray(2).also { content.getLocationOnScreen(it) }
            top = at[1]
            statusBar = content.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
        }
        evidenceShot("edge-to-edge")
        assertTrue("no status bar inset to draw under", statusBar > 0)
        assertEquals("content does not start under the status bar", 0, top)
        instr.runOnMainSync { activity!!.finish() }
    }

    private fun resumed(): Activity? {
        var found: Activity? = null
        instr.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return found
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
