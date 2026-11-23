package io.github.bropines.lens.protocol

/** Everything this library throws on purpose. */
public sealed class LensException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Lens answered with a non-2xx status. [body] is the start of what it sent. */
    public class Http(public val status: Int, public val body: String) :
        LensException("Lens returned HTTP $status: ${body.take(200)}")

    /** Lens answered with an error_type other than UNKNOWN_TYPE. */
    public class ServerError(public val errorType: String) : LensException("Lens reported $errorType")

    public class MalformedResponse(message: String, cause: Throwable? = null) :
        LensException("unreadable Lens response: $message", cause)

    public class UnreadableImage(message: String, cause: Throwable? = null) :
        LensException("cannot read the image: $message", cause)
}
