package tz.co.otapp.buscore.identityaccess.internal.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import tz.co.otapp.buscore.identityaccess.internal.domain.entity.AuthAuditEvent;

/**
 * The authentication trail.
 *
 * <p>Append-only in practice: nothing in this module updates or deletes a row. Retention, when it is
 * needed, will be a scheduled deletion by age — not something a request path is ever allowed to do.
 */
public interface AuthAuditEventRepository extends JpaRepository<AuthAuditEvent, Long> {

    /** One account's history, newest first — the query the composite index exists for. */
    Page<AuthAuditEvent> findByPrincipalUidOrderByCreatedAtDesc(UUID principalUid, Pageable pageable);

    /**
     * Everything from one address, newest first.
     *
     * <p>The query that finds a spray across many accounts, which is invisible from any single account's
     * history — and therefore the reason source_ip is indexed at all.
     */
    Page<AuthAuditEvent> findBySourceIpOrderByCreatedAtDesc(String sourceIp, Pageable pageable);

    Page<AuthAuditEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
