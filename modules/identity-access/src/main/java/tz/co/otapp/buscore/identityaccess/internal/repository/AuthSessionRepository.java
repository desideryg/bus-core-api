package tz.co.otapp.buscore.identityaccess.internal.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import tz.co.otapp.buscore.identityaccess.PrincipalType;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.AuthSession;

/**
 * Sessions.
 *
 * <p>Looked up by principal, never by access token: an access token is stateless and names no row here. The
 * only handle a request carries into this table is a refresh token, and that is resolved through {@link
 * AuthRefreshTokenRepository} to its session — never the other way round.
 */
public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    /**
     * Every live session a principal holds.
     *
     * <p>The set revoked in one act when the account is suspended, its password changed, or it is recovered.
     * Filtered to the unrevoked in the query rather than afterwards, so ending "all sessions" does not load
     * and re-revoke a history of already-dead ones.
     */
    List<AuthSession> findAllByPrincipalUidAndPrincipalTypeAndRevokedAtIsNull(UUID principalUid,
            PrincipalType principalType);
}
