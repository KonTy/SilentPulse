package com.silentpulse.messenger.feature.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentCaptor
import org.mockito.MockedConstruction
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class CommandRouterPrivacyTest {
    private val context = mock(Context::class.java)
    private val pm = mock(PackageManager::class.java)
    private val ownPackage = "com.silentpulse.messenger"
    private val grafium = "com.grafium.app"
    private val microcore = "com.microcore.microcore"
    private val router = CommandRouter(context) { 1_000L }
    private lateinit var intents: MockedConstruction<Intent>

    @Before
    fun setUp() {
        intents = mockConstruction(Intent::class.java)
        `when`(context.packageName).thenReturn(ownPackage)
        `when`(context.packageManager).thenReturn(pm)
        `when`(pm.checkSignatures(anyString(), anyString())).thenReturn(PackageManager.SIGNATURE_NO_MATCH)
        approve(grafium, 101)
        approve(microcore, 102)
        val receivers = listOf(
            receiver("untrusted.spoof", "what"),
            receiver("com.grafium.companion", "journal"),
            receiver(grafium, "journal,task,todo"),
            receiver(microcore, "")
        )
        `when`(pm.queryBroadcastReceivers(any(Intent::class.java), eq(PackageManager.GET_META_DATA)))
            .thenReturn(receivers)
    }

    @After
    fun tearDown() {
        intents.close()
    }

    private fun approve(packageName: String, uid: Int) {
        `when`(pm.checkSignatures(ownPackage, packageName)).thenReturn(PackageManager.SIGNATURE_MATCH)
        val app = mock(ApplicationInfo::class.java).apply {
            this.uid = uid
            enabled = true
        }
        @Suppress("DEPRECATION")
        `when`(pm.getApplicationInfo(packageName, 0)).thenReturn(app)
    }

    private fun receiver(packageName: String, prefixes: String, version: Int = 2): ResolveInfo {
        val metadata = mock(Bundle::class.java)
        `when`(metadata.getString("com.silentpulse.command_prefixes")).thenReturn(prefixes)
        `when`(metadata.getInt("com.silentpulse.voice_protocol_version", 0)).thenReturn(version)
        val activity = mock(ActivityInfo::class.java).apply {
            this.packageName = packageName
            enabled = true
            exported = true
            metaData = metadata
        }
        return mock(ResolveInfo::class.java).apply { activityInfo = activity }
    }

    private fun sentIntent(): Intent {
        val capture = ArgumentCaptor.forClass(Intent::class.java)
        verify(context, atLeastOnce()).sendBroadcast(capture.capture())
        return capture.allValues.last()
    }

    private fun extra(intent: Intent, name: String): String {
        val capture = ArgumentCaptor.forClass(String::class.java)
        verify(intent).putExtra(eq(name), capture.capture())
        return capture.value
    }

    @Test
    fun `only the two approved signed companions can claim voice prefixes`() {
        router.refreshApps()
        assertEquals(listOf("Grafium", "Microcore"), router.getAppNames())
        assertNull(router.route("what is the time"))
        assertEquals(grafium, router.route("journal a synthetic entry")?.targetPackage)
        assertEquals(microcore, router.route("tell microcore to log weight 100")?.targetPackage)
        assertEquals("log weight 100", router.route("tell micro core to log weight 100")?.rawCommand)
    }

    @Test
    fun `same package name without matching signature is not approved`() {
        `when`(pm.checkSignatures(ownPackage, grafium)).thenReturn(PackageManager.SIGNATURE_NO_MATCH)
        router.refreshApps()
        assertEquals(listOf("Microcore"), router.getAppNames())
        assertNull(router.route("journal a synthetic entry"))
    }

    @Test
    fun `forged routes and schema requests cannot bypass discovery restrictions`() {
        val route = CommandRouter.RouteResult("untrusted.spoof", "Grafium", "synthetic command")
        assertFalse(router.dispatch(route, CommandRouter.newSessionId()))
        router.requestSchema("untrusted.spoof")
        verify(context, never()).sendBroadcast(any(Intent::class.java))
    }

    @Test
    fun `approved commands remain explicit and replies require their live capability`() {
        val token = CommandRouter.newSessionId()
        assertTrue(router.dispatch(CommandRouter.RouteResult(grafium, "Grafium", "journal test"), token))
        val command = sentIntent()
        val nonce = extra(command, CommandRouter.EXTRA_REPLY_NONCE)
        verify(command).setPackage(grafium)
        assertNotEquals(token, nonce)
        verify(command).putExtra(CommandRouter.EXTRA_SESSION_ID, nonce)
        verify(context).sendBroadcast(command)
        assertNull(router.acceptReply(CommandRouter.newSessionId()))
        assertEquals(CommandRouter.AuthorizedReply(token, grafium), router.acceptReply(nonce))
        assertNull(router.acceptReply(nonce))
    }

    @Test
    fun `available sender identity must match the recorded companion UID`() {
        val token = CommandRouter.newSessionId()
        router.dispatch(CommandRouter.RouteResult(grafium, "Grafium", "journal test"), token)
        val nonce = extra(sentIntent(), CommandRouter.EXTRA_REPLY_NONCE)
        assertNull(router.acceptReply(nonce, senderUid = 999))
        assertNull(router.acceptReply(nonce, senderUid = 101))
    }

    @Test
    fun `removed replaced or unsigned companions cannot complete old requests`() {
        val token = CommandRouter.newSessionId()
        router.dispatch(CommandRouter.RouteResult(grafium, "Grafium", "journal test"), token)
        val nonce = extra(sentIntent(), CommandRouter.EXTRA_REPLY_NONCE)
        approve(grafium, 103)
        assertNull(router.acceptReply(nonce))
    }

    @Test
    fun `delivery failure revokes the reply capability`() {
        val token = CommandRouter.newSessionId()
        doThrow(SecurityException()).`when`(context).sendBroadcast(any(Intent::class.java))
        assertFalse(router.dispatch(CommandRouter.RouteResult(grafium, "Grafium", "journal test"), token))
        assertNull(router.acceptReply(extra(sentIntent(), CommandRouter.EXTRA_REPLY_NONCE)))
    }

    @Test
    fun `legacy global-reply Microcore bridge is rejected before any private command is sent`() {
        val legacyReceiver = receiver(microcore, "", version = 1)
        `when`(pm.queryBroadcastReceivers(any(Intent::class.java), eq(PackageManager.GET_META_DATA)))
            .thenReturn(listOf(legacyReceiver))
        assertFalse(router.dispatch(
            CommandRouter.RouteResult(microcore, "Microcore", "synthetic command"), CommandRouter.newSessionId()
        ))
        verify(context, never()).sendBroadcast(any(Intent::class.java))
        assertTrue(router.isCompanionCommand("ask microcore what is my weight"))
        assertTrue(router.isCompanionCommand("ask micro core what is my weight"))
    }

    @Test
    fun `Microcore protocol correlates both IDs and requires a fresh one-use nonce`() {
        val conversation = CommandRouter.newSessionId()
        val route = CommandRouter.RouteResult(microcore, "Microcore", "synthetic command")
        assertTrue(router.dispatch(route, conversation))
        val first = sentIntent()
        val firstNonce = extra(first, CommandRouter.EXTRA_REPLY_NONCE)
        assertEquals(conversation, extra(first, CommandRouter.EXTRA_SESSION_ID))
        assertNull(router.acceptReply(conversation))
        assertEquals(CommandRouter.AuthorizedReply(conversation, microcore),
            router.acceptReply(conversation, firstNonce))

        assertTrue(router.dispatch(route, conversation))
        val nextNonce = extra(sentIntent(), CommandRouter.EXTRA_REPLY_NONCE)
        assertNotEquals(firstNonce, nextNonce)
        assertNull(router.acceptReply(conversation, firstNonce))
        assertEquals(CommandRouter.AuthorizedReply(conversation, microcore),
            router.acceptReply(conversation, nextNonce))
    }

    @Test
    fun `schema capabilities cannot be substituted for speech replies`() {
        assertTrue(router.requestSchema(microcore))
        val first = sentIntent()
        val session = extra(first, CommandRouter.EXTRA_SESSION_ID)
        val nonce = extra(first, CommandRouter.EXTRA_REPLY_NONCE)
        assertNull(router.acceptReply(session, nonce))
        assertNull(router.acceptSchemaReply(session, nonce, null))

        assertTrue(router.requestSchema(microcore))
        val second = sentIntent()
        val reply = router.acceptSchemaReply(extra(second, CommandRouter.EXTRA_SESSION_ID),
            extra(second, CommandRouter.EXTRA_REPLY_NONCE), null)
        assertEquals(microcore, reply?.packageName)
    }

    @Test
    fun `legacy Grafium help uses signed manifest prefixes instead of unauthenticated schema broadcasts`() {
        assertFalse(router.requestSchema(grafium))
        verify(context, never()).sendBroadcast(any(Intent::class.java))
        assertEquals(listOf("journal", "task", "todo"), router.declaredCommandPrefixes(grafium))
    }

    @Test
    fun `unavailable app-directed private commands remain local rather than becoming web questions`() {
        `when`(pm.checkSignatures(anyString(), anyString())).thenReturn(PackageManager.SIGNATURE_NO_MATCH)
        assertNull(router.route("journal why was today difficult"))
        assertTrue(router.isCompanionCommand("journal why was today difficult"))
        assertTrue(router.isCompanionCommand("ask microcore what is my weight"))
        assertFalse(router.isCompanionCommand("what is the weather in Tokyo"))
    }

    @Test
    fun `bare health questions stay private with missing incompatible or prefixless companions`() {
        val commands = listOf(
            "how long did I fast",
            "how long have I been fasting?",
            "how many calories have I used today",
            "how many calories do I have remaining?",
            "what is my weight?",
            "what was my last blood pressure reading?",
            "log blood pressure 120 over 80",
            "can you log blood pressure 120 over 80",
            "can you record weight 220 pounds",
            "could you please save blood pressure 120 over 80?",
            "is blood pressure 120 over 80 normal?",
            "is blood pressure one hundred twenty over eighty normal?",
            "is weight 100kg healthy?",
            "what is her latest weight?"
        )
        for (receivers in listOf(
            emptyList(),
            listOf(receiver(microcore, "", version = 1)),
            listOf(receiver(microcore, "", version = 2))
        )) {
            `when`(pm.queryBroadcastReceivers(any(Intent::class.java), eq(PackageManager.GET_META_DATA)))
                .thenReturn(receivers)
            for (command in commands) {
                assertNull(router.route(command))
                assertTrue(command, router.isCompanionCommand(command))
                assertTrue(command, router.isPrivateHealthCommand(command))
            }
        }
        `when`(pm.checkSignatures(ownPackage, microcore)).thenReturn(PackageManager.SIGNATURE_NO_MATCH)
        for (command in commands) {
            assertNull(router.route(command))
            assertTrue(command, router.isCompanionCommand(command))
        }
        verify(context, never()).sendBroadcast(any(Intent::class.java))
    }

    @Test
    fun `health topics default local even without personal pronouns or numeric readings`() {
        for (command in listOf(
            "how many calories are in an apple",
            "what is fasting",
            "what was the fasting duration",
            "what is a healthy blood pressure"
        )) {
            assertTrue(command, router.isPrivateHealthCommand(command))
            assertTrue(command, router.isCompanionCommand(command))
        }
    }

    @Test
    fun `nonhealth knowledge and ordinary app commands remain available`() {
        for (command in listOf(
            "how fast is my car",
            "what is the capital of France",
            "open Microcore",
            "what is the weather today"
        )) {
            assertFalse(command, router.isPrivateHealthCommand(command))
        }
    }

    @Test
    fun `new dispatch supersedes an older outstanding request`() {
        val firstConversation = CommandRouter.newSessionId()
        val secondConversation = CommandRouter.newSessionId()
        val route = CommandRouter.RouteResult(grafium, "Grafium", "journal synthetic")
        router.dispatch(route, firstConversation)
        val oldNonce = extra(sentIntent(), CommandRouter.EXTRA_REPLY_NONCE)
        router.dispatch(route, secondConversation)
        val nextNonce = extra(sentIntent(), CommandRouter.EXTRA_REPLY_NONCE)
        assertNull(router.acceptReply(oldNonce))
        assertEquals(CommandRouter.AuthorizedReply(secondConversation, grafium), router.acceptReply(nextNonce))
    }
}
