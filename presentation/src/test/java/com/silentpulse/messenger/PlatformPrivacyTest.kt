package com.silentpulse.messenger

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class PlatformPrivacyTest {
    private val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
        .first { File(it, "settings.gradle").isFile }
    private val presentation = File(root, "presentation")
    private val res = File(presentation, "src/main/res")
    private val java = File(presentation, "src/main/java/com/silentpulse/messenger")
    private val credentialDomains = setOf("root", "file", "database", "sharedpref", "external")
    private val allDomains = credentialDomains +
        setOf("device_root", "device_file", "device_database", "device_sharedpref")

    private fun xml(file: File): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    private fun Element.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun assertExcluded(element: Element, domains: Set<String>) {
        assertTrue("An include could re-enable extraction", element.elements("include").isEmpty())
        val exclusions = element.elements("exclude")
        assertEquals(domains.size, exclusions.size)
        assertEquals(domains, exclusions.map { it.getAttribute("domain") }.toSet())
        assertTrue(exclusions.all { it.getAttribute("path") == "." })
    }

    @Test
    fun `legacy backup excludes every credential protected domain`() {
        val rules = xml(File(res, "xml/backup_rules.xml"))
        assertEquals("full-backup-content", rules.tagName)
        assertExcluded(rules, credentialDomains)
    }

    @Test
    fun `API 24 backup also excludes every device protected domain`() {
        val rules = xml(File(res, "xml-v24/backup_rules.xml"))
        assertEquals("full-backup-content", rules.tagName)
        assertExcluded(rules, allDomains)
    }

    @Test
    fun `API 31 rules exclude all cloud and device transfer domains`() {
        val rules = xml(File(res, "xml/data_extraction_rules.xml"))
        assertEquals("data-extraction-rules", rules.tagName)
        for (transport in listOf("cloud-backup", "device-transfer")) {
            assertExcluded(rules.elements(transport).single(), allDomains)
        }
    }

    @Test
    fun `merged noAnalytics manifest disables backup and preserves WorkManager startup`() {
        val variant = if (BuildConfig.DEBUG) "NoAnalyticsDebug" else "NoAnalyticsRelease"
        val lowerVariant = variant.replaceFirstChar { it.lowercase() }
        val file = File(presentation,
            "build/intermediates/merged_manifests/$lowerVariant/process${variant}Manifest/AndroidManifest.xml")
        assertTrue("Run :presentation:process${variant}Manifest before this test", file.isFile)
        val manifest = xml(file)
        val app = manifest.elements("application").single()
        assertEquals("false", app.getAttribute("android:allowBackup"))
        assertEquals("@xml/backup_rules", app.getAttribute("android:fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttribute("android:dataExtractionRules"))
        val startup = app.elements("provider").single {
            it.getAttribute("android:name") == "androidx.startup.InitializationProvider"
        }
        assertEquals("false", startup.getAttribute("android:exported"))
        val initializers = startup.elements("meta-data").map { it.getAttribute("android:name") }
        assertTrue(initializers.contains("androidx.work.WorkManagerInitializer"))
        assertFalse(initializers.contains("androidx.emoji2.text.EmojiCompatInitializer"))
        assertFalse(app.elements("meta-data").any { it.getAttribute("android:name") == "preloaded_fonts" })
    }

    @Test
    fun `only provider backed emoji initializer is removed from startup`() {
        val manifest = xml(File(presentation, "src/main/AndroidManifest.xml"))
        val startup = manifest.elements("provider").single {
            it.getAttribute("android:name") == "androidx.startup.InitializationProvider"
        }
        assertEquals("merge", startup.getAttribute("tools:node"))
        val metadata = startup.elements("meta-data").single()
        assertEquals("androidx.emoji2.text.EmojiCompatInitializer", metadata.getAttribute("android:name"))
        assertEquals("remove", metadata.getAttribute("tools:node"))
    }

    @Test
    fun `fonts use OS typeface without an asynchronous download path`() {
        val provider = File(java, "common/util/FontProvider.kt").readText()
        assertTrue(provider.contains("fun getLato(callback: (Typeface) -> Unit)"))
        assertTrue(provider.contains("callback(Typeface.DEFAULT)"))
        assertFalse(provider.contains("ResourcesCompat"))
        assertFalse(provider.contains("pendingCallbacks"))
        val sources = listOf(File(java, "common/QKApplication.kt"), File(java, "common/util/FontProvider.kt")) +
            res.walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()
        for (source in sources) {
            val content = source.readText()
            for (forbidden in listOf("FontRequest", "EmojiCompat.init", "fontProviderAuthority",
                "com_google_android_gms_fonts_certs", "@font/lato", "com.google.android.gms.fonts")) {
                assertFalse("${source.name} still contains $forbidden", content.contains(forbidden))
            }
        }
        val settings = xml(File(res, "layout/settings_controller.xml"))
        val fontOption = settings.elements("com.silentpulse.messenger.common.widget.PreferenceView").single {
            it.getAttribute("android:id") == "@+id/systemFont"
        }
        assertEquals("gone", fontOption.getAttribute("android:visibility"))
    }

    @Test
    fun `logging removal rules apply only to release builds`() {
        val build = File(presentation, "build.gradle").readText()
        val debugBlock = build.substringAfter("        debug {").substringBefore("        release {")
        val releaseBlock = build.substringAfter("        release {").substringBefore("    buildFeatures")
        assertFalse(debugBlock.contains("proguard-release-logging.pro"))
        assertTrue(releaseBlock.contains("'proguard-release-logging.pro'"))
        assertTrue(releaseBlock.contains("minifyEnabled true"))
        val rules = File(presentation, "proguard-release-logging.pro").readText()
        for (type in listOf("android.util.Log", "timber.log.Timber", "timber.log.Timber\$Forest",
            "timber.log.Timber\$Tree")) {
            assertTrue(rules.contains("-assumenosideeffects class $type {"))
        }
        for (method in listOf("v", "d", "i", "w", "e", "wtf", "println")) {
            assertTrue(rules.contains("public static int $method(...);"))
        }
        assertFalse("Do not remove arbitrary methods/callbacks", rules.contains("*;"))
        assertTrue(rules.contains("public void printStackTrace();"))
        assertFalse(rules.contains("printStackTrace(...);"))
        assertFalse(File(presentation, "proguard-rules.pro").readText().contains("-assumenosideeffects"))
    }

    @Test
    fun `application installs diagnostics only in debug and never persists crash details`() {
        val app = File(java, "common/QKApplication.kt").readText()
        assertTrue(app.contains(
            "if (BuildConfig.DEBUG) {\n            Timber.plant(MetadataDebugTree(), fileLoggingTree)\n" +
                "            setupUncaughtExceptionHandler()"))
        assertFalse(app.contains("CrashlyticsTree"))
        assertFalse(app.contains("Timber.e(throwable"))
        assertFalse(app.contains("Thread.sleep"))
        assertTrue(app.contains("if (!BuildConfig.DEBUG) RealmLog.setLevel(LogLevel.OFF)"))
        val handler = app.substringAfter("private fun setupUncaughtExceptionHandler()")
        assertTrue(handler.contains("if (!BuildConfig.DEBUG) return"))
        assertTrue(handler.contains("defaultHandler?.uncaughtException(thread, throwable)"))
    }

    @Test
    fun `file logger is defensively gated and accepts only diagnostic metadata`() {
        val tree = File(java, "common/util/FileLoggingTree.kt").readText()
        assertTrue(tree.contains("if (!BuildConfig.DEBUG || !prefs.logging.get()) return"))
        assertTrue(tree.contains("if (BuildConfig.DEBUG) cleanupOldLogs()"))
        assertTrue(tree.contains("if (!BuildConfig.DEBUG) return@scheduleDirect"))
        assertTrue(tree.contains("DiagnosticMetadata.describe(t)"))
        assertFalse(tree.contains("\$message"))
        assertFalse(tree.contains("\$tag"))
        assertFalse(tree.contains("getStackTraceString"))
        assertFalse(tree.contains("getExternalFilesDir"))
        assertTrue(tree.contains("context.filesDir"))
    }

    @Test
    fun `AI diagnostics neither write content nor mutate conversations`() {
        val scraper = File(java, "feature/assistant/WebAiSearchScraper.kt").readText()
        assertFalse(scraper.contains("writeText("))
        assertFalse(scraper.contains("java.io.File"))
        assertFalse(scraper.contains("console.log("))
        assertEquals(2, Regex("webChromeClient = DiagnosticFreeWebChromeClient\\(\\)").findAll(scraper).count())
        val diagnostic = scraper.substringAfter("fun debugDump(").substringBefore("// ── HTTP helper")
        assertTrue(diagnostic.contains("BuildConfig.DEBUG"))
        for (forbidden in listOf("loadUrl(", "evaluateJavascript(", "httpGet(", "leoInitialized =",
            "removeAllCookies", "clearHistory", "clearCache")) {
            assertFalse("Diagnostics must not change chat state", diagnostic.contains(forbidden))
        }

    }

    @Test
    fun `web console diagnostics are consumed without inspecting payloads`() {
        val client = File(java, "feature/assistant/DiagnosticFreeWebChromeClient.kt").readText()
        assertTrue(client.contains("onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true"))
        assertFalse(client.contains("consoleMessage."))
        assertFalse(client.contains("Log."))
        for (name in listOf("BingChatVerificationActivity", "OutlookVerificationActivity", "OutlookWebScraper")) {
            assertTrue(File(java, "feature/assistant/$name.kt").readText().contains("DiagnosticFreeWebChromeClient()"))
        }
    }
}
