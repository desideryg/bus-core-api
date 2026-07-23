package tz.co.otapp.buscore.apicontracts.error;

import java.io.Serial;
import java.util.List;

import tz.co.otapp.buscore.apicontracts.response.ErrorDetail;

/**
 * The exception every module throws to refuse a request.
 *
 * <p>It carries an {@link ErrorCode}, and the HTTP status comes from that code rather than from the throw
 * site. That is deliberate: if the status were a constructor argument, the same condition would eventually
 * be reported as a 404 in one place and a 409 in another, and a caller could not write retry logic against
 * a status that varies by which method happened to raise it.
 *
 * <pre>
 * throw new ApiException(BookingErrors.SEAT_TAKEN);
 * throw new ApiException(BookingErrors.SEAT_TAKEN, "Seat 12B was taken while you were paying.");
 * </pre>
 *
 * <p>It is <b>unchecked</b>. A refusal can arise many layers below the handler that renders it, and forcing
 * every intermediate signature to declare it would say nothing useful while making the intermediate code
 * harder to read.
 *
 * <h2>No stack trace</h2>
 *
 * <p>This exception is <em>control flow</em>, not a bug report. "That seat is taken" is an expected outcome
 * of a busy system, and capturing a stack trace for it costs real time on a hot path — walking the stack is
 * the expensive part of constructing an exception, not the object itself. So the suppression-and-writable-
 * stack-trace constructor is used to switch it off.
 *
 * <p>The consequence to be aware of: <b>a log line for an ApiException will have no stack trace</b>. That is
 * correct for a refusal. If you find yourself wanting one, the failure was probably not an ApiException —
 * an unexpected error should propagate as itself and be caught by the fallback handler, which logs it in
 * full.
 */
public class ApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;
    private final transient List<ErrorDetail> details;

    /** Refuse with the code's own default message. */
    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of());
    }

    /**
     * Refuse with a specific message.
     *
     * <p>The message reaches the caller, so it must not disclose anything the caller has not already
     * proved they may know — the existence of a row they cannot see, or which of a username and a password
     * was wrong.
     */
    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    /** Refuse with field-level detail, for validation failures raised by domain rules rather than by
     *  annotations. Both paths must produce the same response shape; see {@link ErrorDetail}. */
    public ApiException(ErrorCode errorCode, String message, List<ErrorDetail> details) {
        // enableSuppression=false, writableStackTrace=false — see "No stack trace" above.
        super(message, null, false, false);
        this.errorCode = errorCode;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Never null; empty when the refusal is not about specific fields. */
    public List<ErrorDetail> details() {
        return details;
    }
}
