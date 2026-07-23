package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import java.time.Instant;

/**
 * What a successful sign-in returns.
 *
 * <p>Carries the token and the minimum a client needs to render a session — no more. In particular it does
 * <b>not</b> carry the account's status, its tenancy, or anything else the token already encodes: a client
 * that reads authority from a response body is a client whose authority can be edited in a proxy.
 *
 * <p>There is no refresh token in this slice. Adding one is not a field, it is a lifecycle — rotation,
 * reuse detection, and revocation — and issuing something called a refresh token without them would be
 * worse than not having one.
 *
 * @param accessToken  the bearer token, presented as {@code Authorization: Bearer …}
 * @param expiresAt    when it stops being accepted. Absolute rather than a duration, so a client with a
 *                     skewed clock refreshes at the wrong time rather than believing a stale token is fine
 * @param displayName  for greeting the person, so the client need not immediately call another endpoint
 */
public record LoginResponse(String accessToken, Instant expiresAt, String displayName) {
}
