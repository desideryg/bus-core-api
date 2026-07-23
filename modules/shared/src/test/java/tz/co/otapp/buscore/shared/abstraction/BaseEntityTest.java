package tz.co.otapp.buscore.shared.abstraction;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tz.co.otapp.buscore.shared.time.Times;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Equality and lifecycle for the class every entity extends.
 *
 * <p>These are the invariants that make JPA equality safe. The classic bug — an entity whose hash changes
 * when it is persisted, stranding it in the wrong bucket of a set that already holds it — is prevented here
 * by assigning the uid at construction rather than at insert. That is worth a test, because the fix is
 * invisible: nothing in the code says "this is why hashCode works", and a well-meaning change to generate
 * uid in the database would reintroduce the bug silently.
 */
class BaseEntityTest {

    /** A minimal concrete entity. BaseEntity is abstract, and its behaviour is what is under test. */
    private static final class Vehicle extends BaseEntity {
    }

    private static final class Driver extends BaseEntity {
    }

    @AfterEach
    void restoreClock() {
        Times.reset();
    }

    @Test
    @DisplayName("uid exists before the entity is ever persisted")
    void uid_is_assigned_at_construction() {
        assertThat(new Vehicle().getUid())
                .as("code that returns or references a uid must not have to flush first")
                .isNotNull();
    }

    @Test
    @DisplayName("two instances are never equal by accident")
    void distinct_instances_are_not_equal() {
        assertThat(new Vehicle()).isNotEqualTo(new Vehicle());
    }

    @Test
    @DisplayName("an entity equals itself, and its hash does not move when it is persisted")
    void hash_is_stable_across_persist() {
        Vehicle vehicle = new Vehicle();
        Set<Vehicle> holder = new HashSet<>();
        holder.add(vehicle);

        // Simulate the insert. This is precisely the moment a database-generated uid would appear and a
        // uid-based hash would change underneath the set.
        vehicle.onInsert();

        assertThat(holder)
                .as("an entity must still be findable in a collection it was added to before being saved")
                .contains(vehicle);
        assertThat(vehicle).isEqualTo(vehicle);
    }

    @Test
    @DisplayName("different types with the same uid are not equal")
    void type_is_part_of_identity() throws Exception {
        Vehicle vehicle = new Vehicle();
        Driver driver = new Driver();
        // Force the same uid onto both — impossible in practice, but it isolates the class check.
        var field = BaseEntity.class.getDeclaredField("uid");
        field.setAccessible(true);
        field.set(driver, vehicle.getUid());

        assertThat(vehicle).isNotEqualTo(driver);
    }

    @Test
    @DisplayName("insert stamps both timestamps to the same instant")
    void insert_sets_both_timestamps() {
        Instant when = Instant.parse("2026-07-23T10:15:30Z");
        Times.fixedAt(when);

        Vehicle vehicle = new Vehicle();
        vehicle.onInsert();

        assertThat(vehicle.getCreatedAt()).isEqualTo(when);
        assertThat(vehicle.getUpdatedAt())
                .as("equal timestamps is how 'never modified' is expressed; a null would be unqueryable")
                .isEqualTo(when);
    }

    @Test
    @DisplayName("update moves updatedAt and leaves createdAt alone")
    void update_moves_only_updated_at() {
        Instant created = Instant.parse("2026-07-23T10:15:30Z");
        Instant modified = Instant.parse("2026-07-24T08:00:00Z");

        Vehicle vehicle = new Vehicle();
        Times.fixedAt(created);
        vehicle.onInsert();
        Times.fixedAt(modified);
        vehicle.onUpdate();

        assertThat(vehicle.getCreatedAt()).isEqualTo(created);
        assertThat(vehicle.getUpdatedAt()).isEqualTo(modified);
    }

    @Test
    @DisplayName("toString exposes the uid and nothing else")
    void to_string_is_safe() {
        Vehicle vehicle = new Vehicle();

        // A subclass adding fields to toString is one refactor away from logging a password hash. The base
        // implementation is final in effect: this test is the reminder.
        assertThat(vehicle.toString()).isEqualTo("Vehicle(uid=" + vehicle.getUid() + ")");
    }
}
