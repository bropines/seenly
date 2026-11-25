package io.github.bropines.lens.app.net

import io.github.bropines.lens.app.data.ProxyMode
import io.github.bropines.lens.app.data.ProxyPrefs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

class ProxyConfigTest {
    @Test
    fun noProxyAndSystemNeedNoAddress() {
        // The default follows the system, as the app did before it had a setting.
        ProxyPrefs().toRoute() shouldBe ProxyRoute.System
        ProxyPrefs(mode = ProxyMode.System).toRoute() shouldBe ProxyRoute.System
        // A server typed earlier and then switched off stays in the settings and is ignored.
        ProxyPrefs(mode = ProxyMode.None, host = "not a host", port = 0).toRoute() shouldBe ProxyRoute.Direct
    }

    @Test
    fun aServerBecomesARouteOfItsKind() {
        ProxyPrefs(ProxyMode.Http, "proxy.example.com", 8080).toRoute() shouldBe
            ProxyRoute.Server(ProxyRoute.Server.Type.Http, "proxy.example.com", 8080)
        ProxyPrefs(ProxyMode.Socks5, "10.0.0.2", 1080, "ann").toRoute("secret") shouldBe
            ProxyRoute.Server(ProxyRoute.Server.Type.Socks5, "10.0.0.2", 1080, "ann", "secret")
    }

    @Test
    fun theAddressIsCleanedUp() {
        val route = ProxyPrefs(ProxyMode.Socks5, "  [2001:db8::1] ", 1080, " ann ").toRoute("pw") as ProxyRoute.Server
        route.host shouldBe "2001:db8::1"
        route.username shouldBe "ann"
    }

    @Test
    fun aPasswordWithoutAUserNameIsNotUsed() {
        val route = ProxyPrefs(ProxyMode.Http, "proxy.example.com", 3128, username = " ").toRoute("left over") as ProxyRoute.Server
        route.hasCredentials shouldBe false
        route.password shouldBe ""
    }

    @Test
    fun anEmptyHostIsRefused() {
        ProxyPrefs(ProxyMode.Http, "", 8080).problem() shouldBe ProxyProblem.NoHost
        ProxyPrefs(ProxyMode.Socks5, "   ", 1080).toRoute() shouldBe ProxyRoute.Unusable(ProxyProblem.NoHost)
    }

    @Test
    fun aUrlOrAnAddressWithAPortIsNotAHost() {
        for (host in listOf("http://proxy.example.com", "user@proxy.example.com", "proxy example", "proxy.example.com/path", "[::1]:1080")) {
            ProxyPrefs(ProxyMode.Http, host, 8080).problem() shouldBe ProxyProblem.BadHost
        }
    }

    @Test
    fun aBadPortIsRefused() {
        for (port in listOf(0, -1, 65536, 100_000)) {
            ProxyPrefs(ProxyMode.Socks5, "proxy.example.com", port).problem() shouldBe ProxyProblem.BadPort
        }
        ProxyPrefs(ProxyMode.Socks5, "proxy.example.com", 1).problem() shouldBe null
        ProxyPrefs(ProxyMode.Socks5, "proxy.example.com", 65535).problem() shouldBe null
    }

    @Test
    fun aTypedPortIsANumberInRangeOrNothing() {
        parseProxyPort(" 8080 ") shouldBe 8080
        parseProxyPort("") shouldBe 0
        parseProxyPort("80a") shouldBe 0
        parseProxyPort("70000") shouldBe 0
        parseProxyPort("99999999999") shouldBe 0
    }

    @Test
    fun aRouteDoesNotPrintItsPassword() {
        val route = ProxyPrefs(ProxyMode.Http, "proxy.example.com", 8080, "ann").toRoute("hunter2")
        route.toString() shouldNotContain "hunter2"
    }
}
