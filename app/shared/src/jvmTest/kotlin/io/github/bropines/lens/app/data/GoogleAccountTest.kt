package io.github.bropines.lens.app.data

import androidx.room.RoomDatabase
import io.github.bropines.lens.app.data.history.LensDatabase
import io.github.bropines.lens.app.platform.PlatformServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleAccountTest {
    /** Settings and secrets in a scratch directory; a secret store in memory, so the test sees what is kept. */
    private class Services : PlatformServices {
        override val dataDir: String = Files.createTempDirectory("seenly-account").toString()
        val kept = mutableMapOf<String, String>()
        override val secrets = object : SecretStore {
            override val encrypted = true
            override fun read(name: String): String? = kept[name]
            override fun write(name: String, value: String?) {
                if (value == null) kept.remove(name) else kept[name] = value
            }
        }

        override fun copyText(text: String) {}
        override fun shareText(text: String) {}
        override fun shareImage(bytes: ByteArray, fileName: String) {}
        override fun openUrl(url: String) {}
        override fun databaseBuilder(): RoomDatabase.Builder<LensDatabase> = error("no database in this test")
    }

    private val cookies = "SID=sid; HSID=hsid; SAPISID=sapisid"

    @Test
    fun connectsOnlyASignedInSessionAndKeepsItAcrossRestarts() {
        val services = Services()
        val settings = SettingsRepository(services)
        val account = GoogleAccount(services, settings, io.github.bropines.lens.app.net.AppNetwork(services, settings))
        assertFalse(account.state.value.connected)
        assertNull(account.current())

        // Cookies of a signed-out browser are not an account.
        assertFalse(account.connect("NID=n; SOCS=s"))
        assertFalse(account.connect("not cookies at all"))
        assertTrue(services.kept.isEmpty())

        assertTrue(account.connect("Cookie: $cookies"))
        assertTrue(account.state.value.connected)
        assertEquals(cookies, account.current()?.cookieHeader)
        assertNotNull(account.state.value.lastUsed)
        assertEquals(cookies, account.exportCookies())

        // A new process reads the same session back.
        val again = GoogleAccount(services, settings, io.github.bropines.lens.app.net.AppNetwork(services, settings))
        assertTrue(again.state.value.connected)
        assertEquals(cookies, again.current()?.cookieHeader)

        again.disconnect()
        assertTrue(services.kept.isEmpty())
        assertNull(again.current())
        assertNull(again.exportCookies())
    }

    @Test
    fun aRefusedSessionIsSetAsideAndSaidOnce() {
        val services = Services()
        val account = SettingsRepository(services).let { s -> GoogleAccount(services, s, io.github.bropines.lens.app.net.AppNetwork(services, s)) }
        account.connect(cookies)

        account.rejected(401)
        assertNull(account.current())
        assertTrue(account.state.value.rejected)
        assertTrue(account.refused.value)
        account.refusalShown()
        // A second refusal of the same session is not news.
        account.rejected(403)
        assertFalse(account.refused.value)
        // Set aside, not deleted: the user may try it again, and a restart remembers the refusal.
        assertTrue(SettingsRepository(Services()).let { s -> GoogleAccount(services, s, io.github.bropines.lens.app.net.AppNetwork(services, s)) }.state.value.rejected)

        account.retry()
        assertFalse(account.state.value.rejected)
        assertNotNull(account.current())
    }

    @Test
    fun theSettingTurnsTheAccountOffWithoutForgettingIt() = runBlocking {
        val services = Services()
        val settings = SettingsRepository(services)
        val account = GoogleAccount(services, settings, io.github.bropines.lens.app.net.AppNetwork(services, settings))
        account.connect(cookies)
        settings.update { it.copy(account = AccountPrefs(useForLens = false)) }
        settings.current.first { !it.account.useForLens }
        assertNull(account.current())
        assertTrue(account.state.value.connected)
    }

    @Test
    fun cookiesGoIntoABackupOnlyWhenAsked() = runBlocking {
        val services = Services()
        val settings = SettingsRepository(services)
        settings.update { it.copy(updates = UpdatePrefs(autoCheck = false, beta = true), onDevice = OnDevicePrefs(always = true)) }

        val plain = settings.exportJson()
        assertFalse("sapisid" in plain)
        val withAccount = settings.exportJson(googleCookies = cookies)
        assertTrue(cookies in withAccount)

        val other = SettingsRepository(Services())
        assertNull(other.importJson(plain).googleCookies)
        // The new preferences travel with the backup; the cookies come back for the caller to connect.
        assertEquals(UpdatePrefs(autoCheck = false, beta = true), other.settings.first().updates)
        assertTrue(other.settings.first().onDevice.always)
        assertEquals(cookies, other.importJson(withAccount).googleCookies)
    }

    @Test
    fun theProxyTravelsWithABackupAndItsPasswordOnlyWhenAsked() = runBlocking {
        val settings = SettingsRepository(Services())
        val proxy = ProxyPrefs(ProxyMode.Socks5, "proxy.example.com", 1080, "ann")
        settings.update { it.copy(proxy = proxy) }

        val plain = settings.exportJson()
        assertFalse("hunter2" in plain)
        val withPassword = settings.exportJson(proxyPassword = "hunter2")

        val other = SettingsRepository(Services())
        assertNull(other.importJson(plain).proxyPassword)
        assertEquals(proxy, other.settings.first().proxy)
        assertEquals("hunter2", other.importJson(withPassword).proxyPassword)
    }
}
