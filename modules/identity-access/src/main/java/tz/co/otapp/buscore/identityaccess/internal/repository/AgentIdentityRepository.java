package tz.co.otapp.buscore.identityaccess.internal.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tz.co.otapp.buscore.identityaccess.internal.domain.entity.AgentIdentity;

/**
 * Agent login identities.
 *
 * <p><b>The msisdn passed here must already be canonical.</b> There is no ignore-case or normalising
 * variant of this lookup, and that absence is deliberate: a second way to look one up is a second chance
 * for the stored form and the queried form to disagree, and the symptom of that is an agent being told
 * their PIN is wrong forever. Canonicalisation happens once, at the boundary — see {@code Msisdn}.
 */
public interface AgentIdentityRepository extends JpaRepository<AgentIdentity, Long> {

    Optional<AgentIdentity> findByMsisdn(String canonicalMsisdn);
}
