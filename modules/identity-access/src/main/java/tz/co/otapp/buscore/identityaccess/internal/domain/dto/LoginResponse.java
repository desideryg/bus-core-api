package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import java.time.Instant;

/**
 * What a successful sign-in returns.
 *
 * <p>Carries the tokens and the minimum a client needs to render a session — no more. In particular it does
 * <b>not</b> carry the account's status, its tenancy, or anything else a token already encodes: a client
 * that reads authority from a response body is a client whose authority can be edited in a proxy.
 *
 * <h2>Two tokens, two lifetimes, two jobs</h2>
 *
 * <p>The <b>access token</b> is short-lived and presented as a bearer on every call; the <b>refresh
 * token</b> is long-lived, presented only to {@code /refresh}, and single-use. That split is the whole
 * design: the credential that is exposed on every request expires in minutes, and the one that lasts is kept
 * off the request path and can be revoked. The same response is returned by {@code /refresh}, because a
 * renewal issues a fresh pair — the refresh token rotates, so the one just presented is already spent.
 *
 * @param accessToken      the bearer token, presented as {@code Authorization: Bearer …}
 * @param expiresAt        when the access token stops being accepted. Absolute rather than a duration, so a
 *                         client with a skewed clock refreshes at the wrong time rather than trusting a stale
 *                         token
 * @param displayName      for greeting the person, so the client need not immediately call another endpoint
 * @param refreshToken     the token to present to {@code /refresh} for the next access token. A bearer
 *                         credential in its own right — stored where the access token is not logged
 * @param refreshExpiresAt when the session behind the refresh token lapses. Absolute, and unmoved by
 *                         renewals — refreshing rotates the token but never pushes this out
 */
public record LoginResponse(String accessToken, Instant expiresAt, String displayName,
        String refreshToken, Instant refreshExpiresAt) {
}
