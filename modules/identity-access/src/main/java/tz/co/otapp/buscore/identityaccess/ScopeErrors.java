package tz.co.otapp.buscore.identityaccess;

import tz.co.otapp.buscore.apicontracts.error.ErrorCode;

/**
 * Tenancy refusals — the third gate, and the quiet one.
 *
 * <p>A domain of its own rather than more {@code AUTH.*} codes, because the three refusals a caller can
 * meet have three different remedies:
 *
 * <table border="1">
 *   <caption>Three gates, three answers</caption>
 *   <tr><th>Code</th><th>Means</th><th>What the caller does</th></tr>
 *   <tr><td>{@code AUTH.AUDIENCE_MISMATCH}</td><td>wrong kind of caller</td>
 *       <td>nothing — no grant fixes it</td></tr>
 *   <tr><td>{@code COMMON.FORBIDDEN}</td><td>lacks the permission</td>
 *       <td>ask for the role that carries it</td></tr>
 *   <tr><td>{@code SCOPE.*}</td><td>another operator's rows</td>
 *       <td>nothing — the row is not theirs</td></tr>
 * </table>
 *
 * <p><b>Published, unlike this module's other error enum</b>, because {@link OperatorScope} throws these
 * and is itself published — a published type may not reference an internal one.
 *
 * <h2>Tenancy usually refuses nobody</h2>
 *
 * <p>These codes are raised on a by-uid fetch or a create. On a <em>list</em> endpoint tenancy does not
 * refuse at all: it simply returns fewer rows. That silence is what makes a missing scope filter so hard
 * to notice — nothing errors, the response is well-formed, and the only symptom is that a caller can see
 * rows that are not theirs.
 */
public enum ScopeErrors implements ErrorCode {

    /**
     * The row, or the named operator, is outside the caller's scope.
     *
     * <p>Also what an operator staff member with <b>no</b> memberships receives. That is deliberate: an
     * empty membership set means "serves nobody", and the safe reading of it is nothing rather than
     * everything. Returning an empty scope instead would be one {@code isEmpty()} away from unrestricted
     * access.
     */
    NOT_AUTHORISED(403, "That is outside your scope."),

    /**
     * A new row needs an owning operator and the caller's scope does not name exactly one.
     *
     * <p>400 rather than 403, because nothing is being refused — the request is simply ambiguous, and
     * naming the operator resolves it. Platform staff always get this on a create, which reads backwards
     * and is not: they are precisely the caller for whom the owner is genuinely undetermined.
     */
    OPERATOR_REQUIRED(400, "Name the owning operator.");

    private final int status;
    private final String defaultMessage;

    ScopeErrors(int status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String domain() {
        return "SCOPE";
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
