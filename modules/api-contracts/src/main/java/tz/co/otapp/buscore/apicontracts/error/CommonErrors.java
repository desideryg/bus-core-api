package tz.co.otapp.buscore.apicontracts.error;

/**
 * The failure conditions that belong to no single module.
 *
 * <p>Deliberately short. Anything a module can name for itself belongs in <em>that module's</em>
 * {@link ErrorCode} enum, where the condition is described in the vocabulary of the domain that raised it.
 * A generic {@code NOT_FOUND} tells a caller far less than {@code BOOKING.SEAT_TAKEN}, so reach for these
 * only when the failure genuinely has no domain — a malformed request body, an unhandled exception, a
 * caller who is simply not authenticated.
 *
 * <p>The temptation this list exists to resist is using {@code VALIDATION_FAILED} or {@code CONFLICT} for
 * everything. That produces an API where every failure looks the same and clients end up parsing messages.
 */
public enum CommonErrors implements ErrorCode {

    /**
     * The request body or parameters failed validation. Accompanied by a populated errors list naming each
     * offending field — this code alone tells a caller only <em>that</em> something was wrong.
     */
    VALIDATION_FAILED(400, "The request contains invalid values."),

    /** The request could not be parsed at all: malformed JSON, a wrong content type, an unreadable body. */
    MALFORMED_REQUEST(400, "The request could not be read."),

    /** No credential was presented, or the one presented is not valid. */
    UNAUTHENTICATED(401, "Authentication is required."),

    /**
     * Authenticated, but not permitted. Prefer a domain-specific code where one exists: "you lack this
     * permission", "you are the wrong kind of caller", and "that row belongs to someone else" are three
     * different problems, and a caller that cannot tell them apart cannot act on any of them.
     */
    FORBIDDEN(403, "You are not allowed to do that."),

    /** No such resource, or none the caller is allowed to know about. */
    NOT_FOUND(404, "Not found."),

    /** The HTTP method is not supported on this path. */
    METHOD_NOT_ALLOWED(405, "That method is not supported here."),

    /** The caller asked for a representation this endpoint cannot produce. */
    NOT_ACCEPTABLE(406, "That response format is not available here."),

    /** The request body is in a format this endpoint cannot read — usually a missing or wrong content type. */
    UNSUPPORTED_MEDIA_TYPE(415, "That content type is not supported here."),

    /** The request conflicts with current state. Prefer a domain code that says *which* state. */
    CONFLICT(409, "That conflicts with the current state."),

    /** The caller has sent too many requests and is being rate limited. */
    TOO_MANY_REQUESTS(429, "Too many requests. Try again shortly."),

    /**
     * An unhandled failure. The message is deliberately vague: whatever went wrong, the caller can do
     * nothing about it, and the detail belongs in the log line the trace identifier points at — not in a
     * response that may reach an untrusted client.
     */
    INTERNAL_ERROR(500, "Something went wrong on our side."),

    /** A dependency the request needed is not available. Distinct from a bug: retrying may well work. */
    SERVICE_UNAVAILABLE(503, "That service is temporarily unavailable.");

    private final int status;
    private final String defaultMessage;

    CommonErrors(int status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String domain() {
        return "COMMON";
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
