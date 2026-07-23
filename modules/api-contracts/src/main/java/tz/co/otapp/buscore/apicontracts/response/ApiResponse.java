package tz.co.otapp.buscore.apicontracts.response;

import java.util.List;

import tz.co.otapp.buscore.apicontracts.error.ErrorCode;
import tz.co.otapp.buscore.apicontracts.paging.PageMeta;

/**
 * The response envelope. Every endpoint returns this, on success and on failure alike.
 *
 * <pre>
 * {
 *   "success":    false,
 *   "statusCode": 404,
 *   "code":       "BOOKING.NOT_FOUND",
 *   "message":    "No such booking.",
 *   "data":       null,
 *   "errors":     [],
 *   "meta":       null,
 *   "traceId":    "0193f2a1c4d7"
 * }
 * </pre>
 *
 * <h2>One shape, and every key always present</h2>
 *
 * <p>One envelope means a client writes one deserialiser. The moment a second wrapper type exists, the
 * thing telling a client which to expect is no longer in the payload — it is <em>which endpoint they
 * called</em>, and that is not a contract at all.
 *
 * <p>Every key is present even when null, which is why there is no {@code @JsonInclude(NON_NULL)} here and
 * must never be one. A key that is sometimes absent forces every client to test for it, and the first
 * endpoint that omits a <em>different</em> key has invented a second envelope nobody declared. A few null
 * fields on the wire is a small price for removing that whole class of problem.
 *
 * <h2>success is derived, never supplied</h2>
 *
 * <p>The compact constructor computes {@code success} from {@code statusCode} and ignores whatever was
 * passed. Two fields encoding one fact will disagree the day somebody sets one and forgets the other —
 * making it impossible to set them inconsistently is cheaper than remembering not to.
 *
 * @param success    {@code statusCode < 400}. Derived; see above.
 * @param statusCode mirrors the HTTP status. Present in the body because responses get logged, proxied and
 *                   batched, where the transport status is long gone.
 * @param code       {@code OK}, or a failure's {@link ErrorCode#code()}. <b>This is the contract</b> —
 *                   clients branch on it, support routes on it, dashboards count it.
 * @param message    human-readable. Reword and localise freely; never branch on it.
 * @param data       the payload, or null. A list for collections.
 * @param errors     never null, empty when there is nothing to report.
 * @param meta       pagination, or null. Nothing else goes here.
 * @param traceId    ties this response to a log line. Stamped centrally on the way out, so a handler never
 *                   has to remember it; null until then.
 */
public record ApiResponse<T>(
        boolean success,
        int statusCode,
        String code,
        String message,
        T data,
        List<ErrorDetail> errors,
        PageMeta meta,
        String traceId) {

    /** The code reported on every successful response, so clients can branch on {@code code} uniformly. */
    public static final String OK = "OK";

    private static final String DEFAULT_SUCCESS_MESSAGE = "Success";

    public ApiResponse {
        // Derived, not trusted: see "success is derived, never supplied".
        success = statusCode < 400;
        // Never null, so a client can iterate unconditionally and never needs a null check.
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** 200 with a payload. */
    public static <T> ApiResponse<T> ok(T data) {
        return ok(data, DEFAULT_SUCCESS_MESSAGE);
    }

    /** 200 with a payload and a message worth showing the caller. */
    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, 200, OK, message, data, List.of(), null, null);
    }

    /**
     * 200 with a page of results.
     *
     * <p>The payload stays a plain list and the paging numbers go in {@code meta}, so paging can later gain
     * a cursor or an approximate-total flag without adding top-level keys that mean nothing on the other
     * 90% of responses.
     */
    public static <T> ApiResponse<List<T>> page(List<T> data, PageMeta meta) {
        return new ApiResponse<>(true, 200, OK, DEFAULT_SUCCESS_MESSAGE, data, List.of(), meta, null);
    }

    /**
     * 200 for an operation that returns nothing.
     *
     * <p><b>Not 204.</b> A no-content response would be the one case where the envelope is absent, which
     * means every client needs a second code path for "no body" — the second shape this type exists to
     * prevent, arrived at by accident.
     */
    public static ApiResponse<Void> done(String message) {
        return new ApiResponse<>(true, 200, OK, message, null, List.of(), null, null);
    }

    /** A refusal, with no field-level detail. */
    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message) {
        return failure(errorCode, message, List.of());
    }

    /** A refusal, with the specific things that were wrong. */
    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message, List<ErrorDetail> errors) {
        return new ApiResponse<>(false, errorCode.status(), errorCode.code(), message, null, errors, null, null);
    }

    /**
     * The same response, carrying a trace identifier.
     *
     * <p>Applied centrally as the response leaves, rather than at every construction site — an invariant
     * that depends on 200 call sites remembering is not an invariant.
     */
    public ApiResponse<T> withTraceId(String newTraceId) {
        return new ApiResponse<>(success, statusCode, code, message, data, errors, meta, newTraceId);
    }
}
