package com.silentpulse.messenger

import com.silentpulse.messenger.common.util.CrashlyticsTree
import com.silentpulse.messenger.common.util.BillingManagerImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class TelemetryConfigurationTest {
    private val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
        .first { File(it, "settings.gradle").isFile }

    @Test
    fun `no build variant can apply telemetry dependencies or plugins`() {
        for (path in listOf("build.gradle", "data/build.gradle", "presentation/build.gradle")) {
            val source = File(root, path).readText()
            for (forbidden in listOf("com.google.firebase", "com.google.gms", "com.amplitude",
                "com.android.installreferrer", "com.android.billingclient", "AMPLITUDE_API_KEY")) {
                assertFalse("$path retains $forbidden", source.contains(forbidden))
            }
        }
    }

    @Test
    fun `flavors share inert telemetry implementations instead of overriding them`() {
        val implementations = listOf(
            "data" to "com/silentpulse/messenger/manager/AnalyticsManagerImpl.kt",
            "data" to "com/silentpulse/messenger/manager/ReferralManagerImpl.kt",
            "presentation" to "com/silentpulse/messenger/common/util/CrashlyticsTree.kt",
            "presentation" to "com/silentpulse/messenger/common/util/BillingManagerImpl.kt"
        )
        for ((module, path) in implementations) {
            assertTrue(File(root, "$module/src/main/java/$path").isFile)
            for (flavor in listOf("noAnalytics", "withAnalytics")) {
                assertFalse(File(root, "$module/src/$flavor/java/$path").exists())
            }
        }
    }

    @Test
    fun `legacy crash tree is inert and Firebase SDK is absent`() {
        CrashlyticsTree().log(6, "synthetic-private-tag", "synthetic-private-body",
            IllegalStateException("synthetic-private-token"))
        for (name in listOf("com.google.firebase.crashlytics.FirebaseCrashlytics",
            "com.google.firebase.provider.FirebaseInitProvider", "com.amplitude.api.Amplitude",
            "com.android.installreferrer.api.InstallReferrerClient",
            "com.android.billingclient.api.BillingClient",
            "com.google.android.datatransport.runtime.TransportRuntime",
            "com.google.android.gms.common.GoogleApiAvailability",
            "com.google.firebase.encoders.DataEncoder")) {
            assertThrows(ClassNotFoundException::class.java) { Class.forName(name) }
        }
    }

    @Test
    fun `billing keeps production feature access without a purchase connection`() = runBlocking {
        val billing = BillingManagerImpl()
        val products = billing.products.test()
        val upgraded = billing.upgradeStatus.test()
        billing.checkForPurchases()
        billing.queryProducts()
        products.assertValue(emptyList())
        upgraded.assertValue(true)
        products.dispose()
        upgraded.dispose()
    }

    @Test
    fun `both merged flavor manifests retain visibility and startup without telemetry providers`() {
        for (flavor in listOf("NoAnalytics", "WithAnalytics")) {
            val variant = "${flavor}Debug"
            val lowerVariant = variant.replaceFirstChar { it.lowercase() }
            val file = File(root,
                "presentation/build/intermediates/merged_manifests/$lowerVariant/process${variant}Manifest/AndroidManifest.xml")
            assertTrue("Run :presentation:process${variant}Manifest first", file.isFile)
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val content = file.readText()
            for (forbidden in listOf("com.google.firebase", "com.amplitude", "com.android.installreferrer",
                "com.google.android.gms", "com.google.android.datatransport", "com.android.vending.BILLING",
                "androidx.emoji2.text.EmojiCompatInitializer")) {
                assertFalse(content.contains(forbidden))
            }
            assertTrue(content.contains("androidx.work.WorkManagerInitializer"))
            val queries = document.getElementsByTagName("queries").item(0) as Element
            val packages = queries.getElementsByTagName("package")
            val names = (0 until packages.length).map { (packages.item(it) as Element).getAttribute("android:name") }
            assertTrue(names.containsAll(listOf("com.grafium.app", "com.microcore.microcore")))
            val actions = queries.getElementsByTagName("action")
            val actionNames = (0 until actions.length).map { (actions.item(it) as Element).getAttribute("android:name") }
            assertTrue(actionNames.containsAll(listOf("android.speech.RecognitionService", "android.intent.action.TTS_SERVICE")))
            val app = document.getElementsByTagName("application").item(0) as Element
            assertEquals("false", app.getAttribute("android:allowBackup"))
        }
    }
}
