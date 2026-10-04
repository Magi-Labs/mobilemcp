package labs.magi.mobilemcp

/** Error messages start with an UPPER_CASE code the server surfaces as a structured error code. */
class ActionError(message: String) : Exception(message)

fun fail(code: String, message: String): Nothing = throw ActionError("$code: $message")
