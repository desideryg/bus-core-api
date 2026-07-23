package tz.co.otapp.buscore.identityaccess.internal.domain.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import tz.co.otapp.buscore.identityaccess.StaffTenancy;
import tz.co.otapp.buscore.identityaccess.internal.domain.enums.AccountStatus;
import tz.co.otapp.buscore.shared.abstraction.BaseEntity;

/**
 * A staff member's <b>login identity</b> — not the person.
 *
 * <p>The employee is owned by the {@code staff} module: their department, contract, leave and payroll.
 * This row answers exactly one question, <em>may this login work</em>, and the two have independent
 * lifecycles. Someone can be employed with no login, or keep a login through a change of role.
 *
 * <p>That split is why the type is called {@code StaffIdentity} rather than {@code Staff}: the latter is
 * the other module's aggregate, and one name for two different things is how a codebase starts lying.
 *
 * <p>Password material lives on {@link StaffCredential}, a separate table, so a query that reads identity
 * never drags a password hash into memory — and so the credential can be replaced without touching the
 * identity a hundred other rows refer to by uid.
 */
@Entity
@Table(name = "staff_identities")
public class StaffIdentity extends BaseEntity {

    /**
     * The login name. Unique case-insensitively — see the migration's functional index.
     *
     * <p>Stored as typed, so it displays the way the person wrote it; matched ignoring case, so
     * {@code Alice} and {@code alice} cannot both exist.
     */
    @Column(name = "username", nullable = false, length = 64)
    private String username;

    /**
     * Required, and unique case-insensitively.
     *
     * <p>Not null because a staff member with no email address is one forgotten password away from a dead
     * account that only a database write can recover.
     */
    @Column(name = "email", nullable = false, length = 128)
    private String email;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    /** Fixed at provisioning and never changed afterwards. See {@link StaffTenancy}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tenancy", nullable = false, length = 32)
    private StaffTenancy tenancy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AccountStatus status;

    /**
     * The company this person belongs to, for operator staff.
     *
     * <p><b>Null for everyone else, and the database enforces the equivalence:</b> a staff member has a
     * company if and only if their tenancy is {@code OPERATOR}. Platform staff belong to no tenancy, which
     * is exactly why they may be scoped to all of it.
     *
     * <p>It is also the anchor of the cross-company guard. Every operator membership carries the same
     * value and a composite foreign key holds the two together, so one credential can never reach two
     * companies' data — not through this application, not through a data fix, and not through a future
     * admin surface that forgot to check.
     *
     * <p>A bare handle into the tenancy module: no foreign key, never joined.
     */
    @Column(name = "company_uid")
    private UUID companyUid;

    /** JPA requires it. Not for application use — see {@link #of}. */
    protected StaffIdentity() {
    }

    private StaffIdentity(String username, String email, String displayName, StaffTenancy tenancy,
            AccountStatus status, UUID companyUid) {
        this.username = username;
        this.email = email;
        this.displayName = displayName;
        this.tenancy = tenancy;
        this.status = status;
        this.companyUid = companyUid;
    }

    /**
     * A new identity.
     *
     * <p>A factory rather than a public constructor so there is one place the required fields are named,
     * and no way to construct a half-populated row that only fails at flush time.
     */
    public static StaffIdentity of(String username, String email, String displayName, StaffTenancy tenancy,
            AccountStatus status, UUID companyUid) {
        return new StaffIdentity(username, email, displayName, tenancy, status, companyUid);
    }

    /**
     * A staff member who belongs to no company — platform or partner.
     *
     * <p>A named factory rather than passing null, so the two cases read differently at the call site and
     * nobody has to remember which argument the null was for.
     */
    public static StaffIdentity ofPlatform(String username, String email, String displayName,
            StaffTenancy tenancy, AccountStatus status) {
        return new StaffIdentity(username, email, displayName, tenancy, status, null);
    }

    /**
     * Whether this account may log in.
     *
     * <p>The question is asked here rather than by comparing statuses at each call site, so adding a
     * fifth status cannot silently make it loggable somewhere that forgot to check.
     */
    public boolean canAuthenticate() {
        return status == AccountStatus.ACTIVE;
    }

    /**
     * Whether this is the break-glass identity.
     *
     * <p>Asked here rather than compared at each call site, so every surface that must refuse to touch ROOT
     * refuses it the same way, and the set of them is findable by searching for this method.
     */
    public boolean isRoot() {
        return tenancy == StaffTenancy.ROOT;
    }

    /**
     * Withdraw access, for now or for good.
     *
     * <p>Takes the resulting status rather than exposing a general setter: the only statuses reachable from
     * outside are the two withdrawals and {@link #restore()}. {@code PENDING} is a state an account is born
     * in, and no sequence of administrative actions should be able to return it there — an account that has
     * had a password does not become one that never had one.
     */
    public void withdraw(AccountStatus withdrawnStatus) {
        this.status = withdrawnStatus;
    }

    /** Return a withdrawn account to use. */
    public void restore() {
        this.status = AccountStatus.ACTIVE;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public StaffTenancy getTenancy() {
        return tenancy;
    }

    public AccountStatus getStatus() {
        return status;
    }

    /** Null for anyone who is not operator staff. */
    public UUID getCompanyUid() {
        return companyUid;
    }
}
