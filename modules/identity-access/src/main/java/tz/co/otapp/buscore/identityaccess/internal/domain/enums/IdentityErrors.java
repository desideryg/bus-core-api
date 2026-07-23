package tz.co.otapp.buscore.identityaccess.internal.domain.enums;

import tz.co.otapp.buscore.apicontracts.error.ErrorCode;

/**
 * The failure conditions this module can report.
 *
 * <p>Module-private: the <em>enum</em> is an implementation detail, while the <em>string</em> it produces
 * is the contract a client branches on. Nothing outside this module needs the type.
 *
 * <h2>The tension these codes have to resolve</h2>
 *
 * <p>Two rules pull in opposite directions here, and getting the balance wrong is a real vulnerability
 * rather than an inconvenience.
 *
 * <p><b>Distinct causes get distinct codes</b>, because a caller who cannot tell why they were refused
 * cannot act on it and a support process cannot route the ticket.
 *
 * <p><b>But a login must not be an oracle.</b> If an unknown username and a wrong password answer
 * differently, the login form becomes a free tool for discovering which accounts exist — and account
 * existence is the first thing an attacker wants.
 *
 * <p>So the sign-in path resolves it deliberately: <b>one code, {@link #INVALID_CREDENTIALS}, covers
 * unknown username, wrong password, and every non-active account status.</b> Three quite different
 * internal situations, one indistinguishable answer.
 *
 * <p>{@link #ACCOUNT_LOCKED} is the one considered exception, and it is a trade rather than an oversight —
 * see its own note.
 */
public enum IdentityErrors implements ErrorCode {

    /**
     * The credentials were not accepted.
     *
     * <p><b>Deliberately covers several distinct causes:</b> no such username, wrong password, and an
     * account that is pending, suspended or blocked. They must remain indistinguishable — including in
     * timing where practical, which is why the password is verified even when the account is known to be
     * unusable rather than short-circuited.
     */
    INVALID_CREDENTIALS(401, "Those credentials are not valid."),

    /**
     * Too many consecutive failures; the account is locked for a period.
     *
     * <p><b>This one is distinguishable, and that is a considered trade.</b> It leaks existence: an
     * attacker who guesses a username and fails five times learns the account is real — and denies that
     * person access in the process.
     *
     * <p>It is accepted because these are internal staff usernames rather than public sign-ups, and
     * because the alternative has a concrete operational cost: an employee told only "credentials not
     * valid" retries, deepens the lock, and calls support. Telling them the truth is worth more here than
     * the enumeration it permits.
     *
     * <p>The mitigation is rate limiting, which arrives in a later slice. If this module is ever exposed to
     * public self-registration, revisit this decision first.
     */
    ACCOUNT_LOCKED(423, "This account is temporarily locked. Try again later."),

    /**
     * The password is valid but must be changed before the account can be used.
     *
     * <p>Distinguishable on purpose, and safe to be: reaching it requires already having presented the
     * correct password, so it discloses nothing the caller did not already prove they knew.
     *
     * <p><b>No token is issued with this refusal.</b> Returning one "just so the change endpoint can be
     * called" would defeat the point — the account would be fully usable while nominally requiring a
     * rotation.
     */
    PASSWORD_CHANGE_REQUIRED(409, "Your password must be changed before you can continue."),

    /**
     * The request carries no valid authentication.
     *
     * <p>Distinct from {@link #INVALID_CREDENTIALS}: that one answers "your username and password were
     * rejected", this one answers "you presented no usable token". Different remedies — sign in, versus
     * refresh or re-attach the header.
     */
    NOT_AUTHENTICATED(401, "Authentication is required."),

    /**
     * The caller is authenticated, but is the wrong <em>kind</em> of caller for this surface.
     *
     * <p>Distinct from a missing permission, and the distinction matters to whoever receives it: a missing
     * permission is fixed by a grant, while this one cannot be fixed at all — an agent will never be
     * allowed onto the staff surface, whatever it holds. One code for both would leave a caller and a
     * support desk unable to tell a one-line grant from an unfixable situation.
     *
     * <p>Safe to be distinguishable: reaching it requires having already authenticated, so it discloses
     * only which surface the caller's own credential belongs to.
     */
    AUDIENCE_MISMATCH(403, "This surface is not for your kind of account."),

    // ───────────────────────── administration ─────────────────────────
    //
    // These are freely distinguishable, unlike the sign-in refusals above. The caller has already proved
    // who they are and has been granted the permission to administer accounts, so telling them precisely
    // what was wrong discloses nothing they could not discover through the surface they already hold.

    /** No staff account with that handle. */
    STAFF_NOT_FOUND(404, "No such staff account."),

    /** No role with that code. The message names the code, because a typo is the usual cause. */
    ROLE_NOT_FOUND(404, "No such role."),

    /**
     * The role exists but cannot be given to this account.
     *
     * <p>Covers two causes deliberately — the role is archived, or it is declared for a different class of
     * staff. They share a code because the remedy is the same (choose a different role) and the message
     * says which applied.
     */
    ROLE_NOT_GRANTABLE(409, "That role cannot be granted to this account."),

    /**
     * The username or email address is already taken.
     *
     * <p>409 and explicit, which reads like the account-enumeration hazard the sign-in refusals exist to
     * avoid and is not: this caller already holds {@code STAFF.CREATE} and can list accounts outright. The
     * alternative — a generic failure — would leave an administrator retrying a name that can never work.
     */
    STAFF_ALREADY_EXISTS(409, "That username or email address is already in use."),

    /**
     * The account exists but this operation may not be performed on it.
     *
     * <p>Covers ROOT, which no surface may suspend or restore, and the caller's own account, which they may
     * not withdraw from under themselves. Both are refusals about the <em>target</em> rather than the
     * caller's grants, which is why holding the permission does not help.
     */
    STAFF_NOT_MUTABLE(409, "That account cannot be changed here."),

    /**
     * The caller may not create or administer an account of that tenancy.
     *
     * <p>A privilege-escalation guard, not a missing grant: operator staff creating a platform account
     * would mint an account more powerful than their own, and no permission should imply that.
     */
    TENANCY_NOT_PERMITTED(403, "You cannot administer an account of that kind."),

    /**
     * An operator account was requested without naming the company it belongs to.
     *
     * <p>400 rather than 403 — nothing is refused, the request is incomplete. Only platform staff can meet
     * this, since operator staff take the company from their own account and are never asked.
     */
    COMPANY_REQUIRED(400, "Name the company this account belongs to."),

    // ─────────────────────────── the credential lifecycle ───────────────────────────

    /**
     * The new password is the one already in use.
     *
     * <p>Its own code rather than a field-validation message, because it is the only password rule that
     * cannot be checked without the stored hash. Refusing it matters most on a forced change, where
     * "changing" the password to itself would satisfy the requirement while defeating the reason for it.
     */
    PASSWORD_UNCHANGED(400, "The new password must be different from the current one."),

    /**
     * The reset token is unknown, expired, or already spent.
     *
     * <p><b>One code for three causes, deliberately</b>, and the opposite of the rule that administrative
     * refusals are freely distinguishable — a redemption is an unauthenticated request from somebody who
     * has proved nothing. "Expired" tells a guesser they found a real token and should look for a fresher
     * one; "already used" tells them the account was recently reset and is worth attention. The trail keeps
     * the distinction; the caller does not get it.
     */
    RESET_TOKEN_INVALID(400, "That reset link is not usable. Ask for a new one."),

    /**
     * The PIN was correct but must be replaced before the account can be used.
     *
     * <p>A separate code from {@link #PASSWORD_CHANGE_REQUIRED} only because the caller-facing wording
     * differs — an agent has a PIN, not a password, and telling them to change something they do not have
     * is the kind of small wrongness that generates support calls. The trail records both under one event
     * type, because internally it is the same question.
     */
    PIN_CHANGE_REQUIRED(409, "Your PIN must be changed before you can continue."),

    // ─────────────────────────────── the session lifecycle ───────────────────────────────

    /**
     * The refresh token is unknown, expired, revoked, or already rotated away.
     *
     * <p><b>One code for every cause, and it is the {@link #RESET_TOKEN_INVALID} decision again</b>: a
     * refresh is an unauthenticated request whose whole proof is the token, so the refuser must not become an
     * oracle. "Expired" would tell a holder of a stolen token it was once real; "revoked" would confirm the
     * account it belonged to exists. The trail keeps the distinction — including the reuse that revoked the
     * session — and the caller gets one answer: the session is over, sign in again.
     *
     * <p>401 rather than 400: the remedy is to re-authenticate, which is what a 401 means. It is deliberately
     * <em>not</em> {@link #NOT_AUTHENTICATED}, whose remedy might be "re-attach the header" — here the header
     * was attached and the credential behind it is spent.
     */
    REFRESH_TOKEN_INVALID(401, "Your session has ended. Sign in again.");

    private final int status;
    private final String defaultMessage;

    IdentityErrors(int status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String domain() {
        return "AUTH";
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
