package tz.co.otapp.buscore.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import tz.co.otapp.buscore.apicontracts.error.ApiException;
import tz.co.otapp.buscore.apicontracts.error.CommonErrors;
import tz.co.otapp.buscore.apicontracts.error.ErrorCode;
import tz.co.otapp.buscore.apicontracts.response.ApiResponse;
import tz.co.otapp.buscore.apicontracts.response.ErrorDetail;
import tz.co.otapp.buscore.shared.abstraction.Uuids;
import tz.co.otapp.buscore.shared.logging.LogSanitizer;

/**
 * Makes every response a well-formed envelope, whether it left a controller or an exception.
 *
 * <p>Two jobs that are one responsibility: <b>nothing leaves this application except a complete
 * {@link ApiResponse} carrying a trace identifier.</b> Splitting them would mean two classes that must
 * agree about the envelope, and the day they disagree is the day a client meets a shape nobody declared.
 *
 * <h2>Why it lives here and not in api-contracts</h2>
 *
 * <p>{@code api-contracts} is the wire contract and holds no implementation and no framework dependency —
 * that is what lets its types back a generated client SDK. This class is behaviour, and behaviour belongs
 * in the deployable that assembles the application.
 *
 * <h2>Why traceId is stamped here</h2>
 *
 * <p>Because an invariant that depends on a hundred call sites remembering is not an invariant. Handlers
 * build their response and never think about correlation; it is applied on the way out, to every response,
 * including the ones produced by failures.
 *
 * <h2>What is deliberately NOT handled</h2>
 *
 * <p>There is no handler for {@link IllegalArgumentException} or its relatives. Mapping them to 400 looks
 * convenient and is a trap: the JDK and every library throw them for ordinary programming mistakes, so the
 * mapping quietly converts genuine bugs into 400s shown to callers instead of 500s in the log, and the
 * defect is never investigated because nothing ever looked broken. A refusal is expressed by throwing
 * {@link ApiException}; anything else is a bug and is treated as one.
 */
@RestControllerAdvice
@Slf4j
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    /** Field-error code used when Bean Validation supplies no better one. */
    private static final String INVALID = "INVALID";

    // ─────────────────────────── trace stamping, for every response ───────────────────────────

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // Only our envelope. Actuator health, the OpenAPI document and anything else that is not an
        // ApiResponse passes through untouched — this advice must not reshape endpoints it does not own.
        return ApiResponse.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType,
            ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof ApiResponse<?> envelope)) {
            return body;
        }

        // THE ENVELOPE'S STATUS IS THE RESPONSE'S STATUS. A handler returning ApiResponse.created(...) says
        // 201 in the body; without this line the transport still says 200, and the two disagree on the one
        // field the whole envelope derives `success` from. Making the body authoritative means a handler
        // never has to remember to wrap itself in a ResponseEntity just to get the status right.
        //
        // Set unconditionally rather than only when it differs — ServerHttpResponse has no getter, and
        // writing the value that is already there costs nothing. On the refusal paths below it is exactly a
        // no-op: they build a ResponseEntity whose status came from the same ErrorCode the envelope did.
        response.setStatusCode(HttpStatusCode.valueOf(envelope.statusCode()));

        return envelope.traceId() == null ? envelope.withTraceId(newTraceId()) : envelope;
    }

    // ─────────────────────────────────── refusals ───────────────────────────────────

    /**
     * A module refusing the request. The status comes from the code, never from the throw site — see
     * {@link ApiException}.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> onApiException(ApiException exception) {
        ErrorCode code = exception.errorCode();
        // Logged at INFO, not WARN or ERROR: a refusal is an expected outcome of a working system. Logging
        // every 404 as an error trains everyone to ignore errors.
        log.info("refused: code={} message={}", code.code(), LogSanitizer.clean(exception.getMessage(), 500));
        return render(code, exception.getMessage(), exception.details());
    }

    /**
     * A permission check refused the caller.
     *
     * <p><b>This handler is not optional.</b> Method security throws from <em>inside</em> the controller
     * invocation, so the security chain's own access-denied handler never sees it — that one only catches
     * denials made at the filter level. Without this, every {@code @PreAuthorize} refusal in the entire
     * application falls through to the fallback below and becomes a <b>500 with a stack trace logged at
     * ERROR</b>: ordinary "you may not do that" answers indistinguishable from outages, and alerting full
     * of them.
     *
     * <p>Handling the supertype covers both the modern denial and anything else that raises the classic
     * {@code AccessDeniedException}.
     *
     * <p>The message is the generic one. Naming the missing permission would tell a caller exactly which
     * grant to go looking for, and the set of permissions they lack maps the administrative surface.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> onAccessDenied(AccessDeniedException exception) {
        // INFO, not ERROR: the authorisation layer working is not a failure of anything.
        log.info("access denied: {}", exception.getClass().getSimpleName());
        return render(CommonErrors.FORBIDDEN, CommonErrors.FORBIDDEN.defaultMessage(), List.of());
    }

    // ────────────────────────────── validation, both paths ──────────────────────────────

    /**
     * Body validation, raised by {@code @Valid} on a request object.
     *
     * <p>This and {@link #onConstraintViolation} must produce the <b>same shape</b> as a domain rule
     * throwing {@link ApiException} with details. Where they differ, a client's error handling silently
     * depends on how the check happened to be implemented on the server, and misses every failure raised
     * the other way.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> onBodyValidation(MethodArgumentNotValidException exception) {
        List<ErrorDetail> details = new ArrayList<>();
        for (ObjectError error : exception.getBindingResult().getAllErrors()) {
            // A global error has no field. Reporting it with a null field is what lets cross-field rules
            // ("these dates are the wrong way round") be expressed at all.
            String field = error instanceof FieldError fieldError ? fieldError.getField() : null;
            details.add(new ErrorDetail(field, codeOf(error), error.getDefaultMessage()));
        }
        return render(CommonErrors.VALIDATION_FAILED, CommonErrors.VALIDATION_FAILED.defaultMessage(), details);
    }

    /** Parameter validation, raised by constraints on method arguments rather than on a body object. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> onConstraintViolation(ConstraintViolationException exception) {
        List<ErrorDetail> details = new ArrayList<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            details.add(new ErrorDetail(
                    violation.getPropertyPath().toString(),
                    violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName().toUpperCase(),
                    violation.getMessage()));
        }
        return render(CommonErrors.VALIDATION_FAILED, CommonErrors.VALIDATION_FAILED.defaultMessage(), details);
    }

    /**
     * The body could not be parsed at all — malformed JSON, a number where an object was expected.
     *
     * <p>The exception's own message is <b>not</b> passed through: it can quote the offending input and name
     * internal types, which is both a disclosure and useless to the caller.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> onUnreadableBody(HttpMessageNotReadableException exception) {
        log.info("unreadable request body: {}", LogSanitizer.clean(exception.getMostSpecificCause().getMessage(), 300));
        return render(CommonErrors.MALFORMED_REQUEST, CommonErrors.MALFORMED_REQUEST.defaultMessage(), List.of());
    }

    // ─────────────────────────────────── the fallback ───────────────────────────────────

    /**
     * Everything not handled above.
     *
     * <p>It splits in two, and the split is the important part.
     *
     * <h3>Framework refusals are not bugs</h3>
     *
     * <p>Spring's own MVC exceptions — no route matched, wrong method, unreadable media type, missing
     * parameter — implement {@link ErrorResponse} and already carry the correct status. Letting them fall
     * through to the 500 branch was a real defect during development: an unknown path answered
     * {@code 500 INTERNAL_ERROR} and logged a stack trace at ERROR, so every crawler and every typo'd URL
     * would have looked like an outage and buried the failures that actually were one.
     *
     * <p>Handling the interface rather than enumerating a dozen exception classes means a Spring upgrade
     * that adds another one is covered on the day it appears, instead of the day someone notices a new
     * kind of 500.
     *
     * <h3>Anything else is a bug</h3>
     *
     * <p>The caller gets a generic message and the trace identifier, and <b>nothing else</b>. An exception
     * message can carry a SQL fragment, a file path, or a value from another user's row; whatever it holds,
     * the caller can do nothing with it. The detail goes to the log under the same identifier the caller
     * was given, so a support conversation opening with that string reaches the exact line.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> onUnexpected(Exception exception) {
        if (exception instanceof ErrorResponse framework) {
            int status = framework.getStatusCode().value();
            ErrorCode code = forStatus(status);
            // INFO, not ERROR: the caller got it wrong, we did not.
            log.info("refused by framework: status={} type={}", status, exception.getClass().getSimpleName());
            return render(code, code.defaultMessage(), List.of());
        }

        String traceId = newTraceId();
        // ERROR with the stack trace: unlike a refusal, this is something somebody has to look at.
        log.error("unhandled failure traceId={}", traceId, exception);
        ApiResponse<Void> body = ApiResponse.<Void>failure(
                CommonErrors.INTERNAL_ERROR, CommonErrors.INTERNAL_ERROR.defaultMessage())
                .withTraceId(traceId);
        return ResponseEntity.status(CommonErrors.INTERNAL_ERROR.status()).body(body);
    }

    /**
     * The catalog entry for a status the framework chose.
     *
     * <p>Anything unrecognised in the 4xx range becomes a generic client error rather than a 500 — the
     * framework already decided the caller was at fault, and overriding that to "our bug" would be both
     * wrong and noisy. A 5xx we did not raise ourselves stays a 5xx.
     */
    private static ErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> CommonErrors.VALIDATION_FAILED;
            case 401 -> CommonErrors.UNAUTHENTICATED;
            case 403 -> CommonErrors.FORBIDDEN;
            case 404 -> CommonErrors.NOT_FOUND;
            case 405 -> CommonErrors.METHOD_NOT_ALLOWED;
            case 406 -> CommonErrors.NOT_ACCEPTABLE;
            case 409 -> CommonErrors.CONFLICT;
            case 415 -> CommonErrors.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> CommonErrors.TOO_MANY_REQUESTS;
            case 503 -> CommonErrors.SERVICE_UNAVAILABLE;
            default -> status < 500 ? CommonErrors.MALFORMED_REQUEST : CommonErrors.INTERNAL_ERROR;
        };
    }

    // ───────────────────────────────────── helpers ─────────────────────────────────────

    private ResponseEntity<ApiResponse<Void>> render(ErrorCode code, String message, List<ErrorDetail> details) {
        ApiResponse<Void> body = ApiResponse.<Void>failure(code, message, details).withTraceId(newTraceId());
        return ResponseEntity.status(HttpStatus.valueOf(code.status())).body(body);
    }

    /**
     * A short correlation identifier.
     *
     * <p>Derived from a time-ordered uuid so identifiers issued near each other sort together in a log
     * search. Twelve hex characters is enough to be unique in any window a human will search and short
     * enough to be read aloud during a support call.
     *
     * <p>Generated here because nothing upstream provides one yet. When distributed tracing is introduced,
     * this should prefer the incoming trace identifier so a request can be followed across services.
     */
    private String newTraceId() {
        return Uuids.next().toString().replace("-", "").substring(0, 12);
    }

    /** Bean Validation names the annotation that failed; that name is a serviceable machine-readable code. */
    private static String codeOf(ObjectError error) {
        String code = error.getCode();
        return code == null ? INVALID : code.toUpperCase();
    }
}
