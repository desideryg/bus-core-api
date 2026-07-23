package tz.co.otapp.buscore.shared.abstraction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import tz.co.otapp.buscore.shared.time.Times;

/**
 * The base every entity in every module extends: two identifiers and two timestamps.
 *
 * <h2>Two identifiers, and the rule that makes them worth having</h2>
 *
 * <table border="1">
 *   <caption>The id/uid split</caption>
 *   <tr><th></th><th>{@code id}</th><th>{@code uid}</th></tr>
 *   <tr><td>Type</td><td>{@code Long}</td><td>{@code UUID} (version 7)</td></tr>
 *   <tr><td>Assigned by</td><td>the database, on insert</td><td>this class, at construction</td></tr>
 *   <tr><td>Visible to</td><td><b>this module only</b></td><td>anyone</td></tr>
 * </table>
 *
 * <p><b>{@code id} never leaves its module.</b> Not in a published method signature, not in a URL, not in an
 * event payload, not in a DTO. It is a storage detail: sequential, guessable, and meaningless outside the
 * table it indexes. Exposing it hands callers a key that lets them enumerate rows and that cannot be
 * changed later without breaking them.
 *
 * <p>{@code uid} is the handle that crosses boundaries. When one module refers to another module's row, it
 * stores that row's uid as a plain column — no foreign key, no association. See the inter-module rules.
 *
 * <h2>uid is assigned here, not by the database</h2>
 *
 * <p>Deliberately, and it buys two things. The uid exists the moment the entity does, so code can return it
 * or reference it without first flushing to the database — the omission that otherwise produces a null in a
 * place nothing checks. And because it is never null, {@code equals} and {@code hashCode} can use it.
 *
 * <h2>Why equals and hashCode are on uid alone</h2>
 *
 * <p>Not on {@code id}: it is null until insert, so two unsaved entities would compare equal to each other
 * and an entity's hash would change the moment it was persisted — stranding it in the wrong bucket of any
 * set or map already holding it. That is the classic JPA equality bug, and it is why some codebases resort
 * to a constant {@code hashCode}. Assigning uid up front removes the problem instead of working around it.
 *
 * <p>Not on business fields either: a username can be corrected, and an entity is the same row before and
 * after.
 *
 * <p>{@code getClass()} rather than {@code instanceof}: Hibernate proxies would otherwise make a proxy and
 * its target unequal in one direction. Comparing uid first means the common case short-circuits before the
 * class check ever runs.
 *
 * <h2>Timestamps</h2>
 *
 * <p>Set by JPA lifecycle callbacks reading {@link Times}, so a test can move the clock. Spring Data
 * auditing would be the idiomatic alternative, but it requires the JPA starter, which would put an
 * entity-manager autoconfiguration on the classpath of everything depending on this module — including a
 * deployable that has no database yet.
 *
 * <p>Both columns are {@code timestamp} holding UTC, never {@code timestamptz}. Each module's migration
 * declares them; this class does not create any table of its own.
 */
@MappedSuperclass
public abstract class BaseEntity {

    /** Module-private surrogate key. See the class javadoc: this must never cross a module boundary. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The public handle. Assigned at construction, so it is never null and is safe to use in equality.
     *
     * <p>{@code updatable = false}: a row's identity does not change. Letting it be updated would silently
     * break every other module holding the old value as a uid handle.
     */
    @Column(name = "uid", nullable = false, updatable = false, unique = true)
    private UUID uid = Uuids.next();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BaseEntity() {
    }

    @PrePersist
    void onInsert() {
        Instant now = Times.now();
        this.createdAt = now;
        // Set on insert as well as on update, so "never touched since creation" reads as
        // createdAt == updatedAt rather than as a null nothing can be compared against.
        this.updatedAt = now;
        if (this.uid == null) {
            // Belt and braces: field initialisers do not run when a persistence provider or a
            // deserialiser constructs an instance reflectively without calling a constructor.
            this.uid = Uuids.next();
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Times.now();
    }

    /** Module-private. Do not widen this: see the class javadoc. */
    protected Long getId() {
        return id;
    }

    public UUID getUid() {
        return uid;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return uid.equals(((BaseEntity) other).uid);
    }

    @Override
    public final int hashCode() {
        return Objects.hashCode(uid);
    }

    @Override
    public String toString() {
        // uid only. A subclass that adds fields here is one refactor away from putting a password hash or
        // a token in a log line, which is the single most common way a secret escapes.
        return getClass().getSimpleName() + "(uid=" + uid + ")";
    }
}
