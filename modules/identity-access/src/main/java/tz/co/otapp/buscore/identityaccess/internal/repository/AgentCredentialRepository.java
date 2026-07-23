package tz.co.otapp.buscore.identityaccess.internal.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import tz.co.otapp.buscore.identityaccess.internal.domain.entity.AgentCredential;
import tz.co.otapp.buscore.identityaccess.internal.domain.entity.AgentIdentity;

/**
 * PIN material for agent identities.
 *
 * <p>{@link Optional} rather than a list is honest only because the schema makes the foreign key unique —
 * one credential per identity. Without that constraint this signature would be a lie that happens to hold,
 * and the day it stopped holding, only one of two PINs would carry the failure counter.
 */
public interface AgentCredentialRepository extends JpaRepository<AgentCredential, Long> {

    Optional<AgentCredential> findByAgentIdentity(AgentIdentity agentIdentity);
}
