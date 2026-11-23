package io.github.bropines.lens.translate

import kotlin.time.Duration

/** Every way a translation fails. [id] is the wire name, shared with Glossa's module protocol. */
public enum class ErrorCode(public val id: String) {
    /** The service refuses for now; see [TranslationException.retryAfter]. Also an exhausted quota. */
    RateLimited("rate_limited"),
    Http("http"),

    /** The service changed its response, or returned nothing usable. */
    BadResponse("bad_response"),
    SourceLanguage("source_language"),
    TargetLanguage("target_language"),
    BadRequest("bad_request"),
    NotFound("not_found"),

    /** A required setting is blank or the service rejected the key. */
    MissingCredential("missing_credential"),
    Network("network"),
    Timeout("timeout"),
    Cancelled("cancelled"),
    Unsupported("unsupported"),
    EmptyInput("empty_input"),
    SameLanguage("same_language"),
    ;

    /** Errors another engine would not fix: [TranslationPipeline] stops instead of failing over. */
    public val stopsFailOver: Boolean
        get() = this == MissingCredential || this == Cancelled || this == EmptyInput || this == SameLanguage

    public companion object {
        public fun fromId(id: String): ErrorCode? = entries.firstOrNull { it.id == id }
    }
}

/** The one error type of this module. */
public class TranslationException(
    public val code: ErrorCode,
    message: String,
    /** With [ErrorCode.RateLimited]: how long the service asked to wait. */
    public val retryAfter: Duration? = null,
    public val httpStatus: Int? = null,
    /** The engine that failed, when thrown by [TranslationPipeline]. */
    public val engineId: String? = null,
    /** When thrown by [TranslationPipeline]: every engine that was tried or skipped. */
    public val failures: List<EngineFailure> = emptyList(),
    cause: Throwable? = null,
) : Exception(message, cause)

/** Why one engine did not produce the translation. */
public data class EngineFailure(
    val engineId: String,
    val code: ErrorCode,
    val message: String,
    /** True when the engine was passed over without being sent the text. */
    val skipped: Boolean = false,
)
