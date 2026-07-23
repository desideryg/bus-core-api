package tz.co.otapp.buscore.apicontracts.response;

/**
 * One thing that was wrong with a request.
 *
 * <p>Responses carry a <b>list</b> of these, never a map of field to message. A map cannot express two
 * problems on the same field — {@code amount} being both negative and over-precise is two facts, and a map
 * silently keeps one — and it has nowhere at all to put a failure that is not about a single field, such as
 * "these two dates are the wrong way round".
 *
 * @param field   the offending field, in dotted path form for nested bodies ({@code passengers[0].phone}).
 *                <b>Null when the problem is not about one field</b> — a cross-field rule, or a conflict
 *                between the body and the URL.
 * @param code    a stable, machine-readable reason: {@code REQUIRED}, {@code MIN}, {@code PATTERN},
 *                {@code CURRENCY_MISMATCH}. A client uses this to decide which control to highlight and
 *                which of its own translated strings to show. Like every code in this contract, it may be
 *                added to but never repurposed.
 * @param message human-readable text for this one problem. Safe to display, never to branch on.
 */
public record ErrorDetail(String field, String code, String message) {

    /** A problem with one field. */
    public static ErrorDetail of(String field, String code, String message) {
        return new ErrorDetail(field, code, message);
    }

    /** A problem that belongs to the request as a whole rather than to any single field. */
    public static ErrorDetail of(String code, String message) {
        return new ErrorDetail(null, code, message);
    }
}
