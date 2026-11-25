package io.github.bropines.lens.app.net

import androidx.room.RoomDatabase
import io.github.bropines.lens.app.data.ProxyMode
import io.github.bropines.lens.app.data.ProxyPrefs
import io.github.bropines.lens.app.data.SecretStore
import io.github.bropines.lens.app.data.SettingsRepository
import io.github.bropines.lens.app.data.history.LensDatabase
import io.github.bropines.lens.app.platform.PlatformServices
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

class AppNetworkTest {
    /** Settings in a scratch directory; a secret store in memory, so the test sees what is kept. */
    private class Services : PlatformServices {
        override val dataDir: String = Files.createTempDirectory("seenly-network").toString()
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

    private val proxy = ProxyPrefs(ProxyMode.Socks5, "proxy.example.com", 1080, "ann")

    @Test
    fun thePasswordIsASecretAndNotASetting(): Unit = runBlocking {
        val services = Services()
        val settings = SettingsRepository(services)
        val network = AppNetwork(services, settings)

        network.save(proxy, "hunter2")

        settings.settings.first().proxy shouldBe proxy
        network.passwordFor(proxy) shouldBe "hunter2"
        services.kept.size shouldBe 1
        // Neither the settings file nor a plain export has it.
        File(services.dataDir, "settings.preferences_pb").readText(Charsets.ISO_8859_1) shouldNotContain "hunter2"
        settings.exportJson() shouldNotContain "hunter2"

        // It is still there for the next start.
        AppNetwork(services, settings).passwordFor(proxy) shouldBe "hunter2"
    }

    @Test
    fun thePasswordIsOfferedOnlyToTheServerItWasTypedFor(): Unit = runBlocking {
        val services = Services()
        val network = AppNetwork(services, SettingsRepository(services))
        network.save(proxy, "hunter2")

        network.passwordFor(proxy.copy(host = "PROXY.example.com")) shouldBe "hunter2"
        // Another address, port or user (an imported backup, say) does not inherit it.
        network.passwordFor(proxy.copy(host = "other.example.com")) shouldBe ""
        network.passwordFor(proxy.copy(port = 1081)) shouldBe ""
        network.passwordFor(proxy.copy(username = "bob")) shouldBe ""
    }

    @Test
    fun anEmptyPasswordOrNoUserNameForgetsIt(): Unit = runBlocking {
        val services = Services()
        val network = AppNetwork(services, SettingsRepository(services))
        network.save(proxy, "hunter2")

        network.save(proxy, "")
        services.kept.isEmpty() shouldBe true

        network.save(proxy, "hunter2")
        network.save(proxy.copy(username = ""), "hunter2")
        services.kept.isEmpty() shouldBe true
    }

    @Test
    fun theTestRefusesSettingsThatCannotBeTried(): Unit = runBlocking {
        val services = Services()
        val network = AppNetwork(services, SettingsRepository(services))
        network.test(ProxyPrefs(ProxyMode.Http, "", 8080), "") shouldBe ProxyTestResult.Unusable(ProxyProblem.NoHost)
        network.test(ProxyPrefs(ProxyMode.Socks5, "proxy.example.com", 0), "") shouldBe ProxyTestResult.Unusable(ProxyProblem.BadPort)
    }
}
