package tz.co.otapp.buscore.identityaccess.internal.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import lombok.Getter;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AccountStatus;
import tz.co.otapp.buscore.shared.abstraction.BaseEntity;

/**
 * A selling agent's <b>login</b> — not the agent.
 *
 * <p>Who the agent is, what they may sell, for whom and under what limits belongs to the {@code agent}
 * module. That module is wave 6 and this one is wave 2, so the dependency could not exist even if it were
 * wanted.
 *
 * <p><b>Note which way the handle points.</b> There is no {@code agentUid} here. This login stands alone
 * and the {@code agent} module holds a reference to <em>its</em> uid — the only direction that works, since
 * a column here would have to be populated with the uid of a record four waves away that does not yet
 * exist. {@link #getUid()} is therefore what a {@code Principal} carries and what selling grants will be
 * keyed on.
 *
 * <p>The consequence is the defining fact about this type: <b>an agent principal holds no permissions and
 * never will.</b> Nothing here can resolve selling authority, so nothing here pretends to. An agent is kept
 * out of the staff surface by the audience gate, not by an empty permission set that happens to fail every
 * check — the difference matters, because an empty collection is a coincidence and a gate is a decision.
 *
 * @see AgentCredential for the PIN and the lockout that has to be harsher than a password's
 */
@Getter
@Entity
@Table(name = "agent_identities")
public class AgentIdentity extends BaseEntity {

    /**
     * The phone number they sign in with, canonical E.164.
     *
     * <p>Only ever the canonical form — see {@code Msisdn}. Storing as typed would let one handset hold
     * three identities while every lookup found none of them.
     */
    @Column(name = "msisdn", nullable = false, length = 20)
    private String msisdn;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AccountStatus status;

    protected AgentIdentity() {
    }

    private AgentIdentity(String msisdn, String displayName, AccountStatus status) {
        this.msisdn = msisdn;
        this.displayName = displayName;
        this.status = status;
    }

    /**
     * A new agent login.
     *
     * <p>Takes the msisdn already canonicalised. The factory does not normalise it itself so that there is
     * exactly one place a raw number becomes a canonical one, and it is on the boundary where the raw value
     * arrives — not buried where a second caller could skip it.
     */
    public static AgentIdentity of(String canonicalMsisdn, String displayName, AccountStatus status) {
        return new AgentIdentity(canonicalMsisdn, displayName, status);
    }

    /**
     * Whether this agent may sign in.
     *
     * <p>Asked here rather than by comparing statuses at each call site, so adding a fifth status cannot
     * silently make it loggable somewhere that forgot to check.
     */
    public boolean canAuthenticate() {
        return status == AccountStatus.ACTIVE;
    }
}
