package io.github.bropines.lens.app.net

import io.github.bropines.lens.app.data.ProxyMode
import io.github.bropines.lens.app.data.ProxyPrefs

/** Why a proxy cannot be used as it is set. */
enum class ProxyProblem { NoHost, BadHost, BadPort }

/**
 * Where a request goes out: what [ProxyPrefs] and the stored password come to
 * once checked, in the terms the HTTP engine is configured with.
 */
sealed interface ProxyRoute {
    /** No proxy, whatever the system has. */
    data object Direct : ProxyRoute

    /** Whatever the system says for the address being asked. */
    data object System : ProxyRoute

    data class Server(
        val type: Type,
        val host: String,
        val port: Int,
        /** Empty = no sign-in. */
        val username: String = "",
        val password: String = "",
    ) : ProxyRoute {
        enum class Type { Http, Socks5 }

        val hasCredentials: Boolean get() = username.isNotEmpty()

        // Routes end up in logs and error messages; the password must not.
        override fun toString(): String = "$type $host:$port" + if (hasCredentials) " as $username" else ""
    }

    /**
     * A proxy was asked for and cannot be used. Requests fail rather than go
     * out directly: someone who set a proxy does not want to be seen without it.
     */
    data class Unusable(val problem: ProxyProblem) : ProxyRoute
}

/** The address as it is used: no spaces around it, no brackets around an IPv6 literal. */
val ProxyPrefs.cleanHost: String get() = host.trim().removeSurrounding("[", "]")

/** What is wrong with these settings, or null when they can be used. Only a server needs checking. */
fun ProxyPrefs.problem(): ProxyProblem? {
    if (mode != ProxyMode.Http && mode != ProxyMode.Socks5) return null
    val address = cleanHost
    return when {
        address.isEmpty() -> ProxyProblem.NoHost
        // A pasted URL ("socks5://host:1080", "user@host") is not a host name; the port and user have their own fields.
        address.any { it.isWhitespace() || it in "/\\@?#[]" } -> ProxyProblem.BadHost
        port !in 1..65535 -> ProxyProblem.BadPort
        else -> null
    }
}

/** The route these settings ask for. [password] is used only with a user name. */
fun ProxyPrefs.toRoute(password: String = ""): ProxyRoute {
    problem()?.let { return ProxyRoute.Unusable(it) }
    return when (mode) {
        ProxyMode.None -> ProxyRoute.Direct
        ProxyMode.System -> ProxyRoute.System
        ProxyMode.Http, ProxyMode.Socks5 -> {
            val user = username.trim()
            ProxyRoute.Server(
                type = if (mode == ProxyMode.Http) ProxyRoute.Server.Type.Http else ProxyRoute.Server.Type.Socks5,
                host = cleanHost,
                port = port,
                username = user,
                password = if (user.isEmpty()) "" else password,
            )
        }
    }
}

/** A port as typed: 1..65535, or 0 for anything else, which [problem] then reports. */
fun parseProxyPort(text: String): Int = text.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 0
