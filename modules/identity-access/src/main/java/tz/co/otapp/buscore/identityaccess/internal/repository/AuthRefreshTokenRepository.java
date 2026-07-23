package tz.co.otapp.buscore.identityaccess.internal.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tz.co.otapp.buscore.identityaccess.internal.domain.entity.AuthRefreshToken;

/**
 * Refresh tokens.
 *
 * <p><b>The only lookup is by fingerprint</b>, and that absence of any other is deliberate — the same shape
 * as {@link PasswordResetRepository}. A refresh arrives with a token and nothing else; the caller has not
 * proved which session it belongs to, so the token must be the whole of the query. Resolving one by session
 * instead would be a second path for the presented form and the stored form to disagree, and its symptom
 * would be a holder logged out mid-session for no reason they could see.
 */
public interface AuthRefreshTokenRepository extends JpaRepository<AuthRefreshToken, Long> {

    /**
     * Resolve a presented refresh token by the fingerprint of its value.
     *
     * <p>Returns the row whether or not it is still live: a spent one must come back too, because a spent
     * token presented again is the reuse the caller has to detect, not an absence to report as unknown.
     */
    Optional<AuthRefreshToken> findByTokenHash(String tokenHash);
}
