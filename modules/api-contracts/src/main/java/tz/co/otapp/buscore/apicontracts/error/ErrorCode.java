package tz.co.otapp.buscore.apicontracts.error;

/**
 * One machine-readable failure condition: a stable code, and the HTTP status it is reported with.
 *
 * <p>This is an <b>interface, not an enum</b>, and that is the whole design. Error codes are inherently
 * cross-module — a client branches on them across the entire API — so they need a common type. But a single
 * central enum would have to name every condition in every module, which means it grows without bound, sits
 * in the artifact all 25 modules depend on, and turns "add an error case" into a change to shared code.
 *
 * <p>So each module declares its own enum implementing this interface:
 *
 * <pre>
 * public enum BookingErrors implements ErrorCode {
 *     SEAT_TAKEN(409, "That seat is no longer available.");
 *     // code() returns "BOOKING.SEAT_TAKEN"
 * }
 * </pre>
 *
 * <p>Open for extension, closed for modification: a new failure condition is a new constant in the module
 * that owns it, and nothing central changes.
 *
 * <h2>The obligations of a code</h2>
 *
 * <p><b>A released code may be added to, never repurposed, and never renamed.</b> Clients branch on it,
 * support routes on it, dashboards count it. Changing what one means is a silent breaking change to every
 * caller, and nothing in the build will catch it.
 *
 * <p><b>Distinct causes get distinct codes.</b> When a caller is refused because they lack a permission,
 * because they are the wrong kind of caller, and because the row belongs to someone else, those are three
 * different problems with three different fixes. A client that cannot tell them apart cannot act on any of
 * them, and a support process cannot route the ticket.
 *
 * <p><b>The status is part of the code, not chosen at the throw site.</b> Deriving it here is what stops the
 * same condition being reported as a 404 in one place and a 409 in another — a caller cannot write retry
 * logic against a status that varies by call site.
 */
public interface ErrorCode {

    /**
     * The stable identifier, in {@code DOMAIN.CONDITION} form — {@code BOOKING.SEAT_TAKEN}.
     *
     * <p>Uppercase, dot-separated, naming the <em>condition</em> rather than the remedy. The default
     * implementation composes it from {@link #domain()} and the enum constant name, so an implementing enum
     * gets the right shape for free and cannot drift from its own constant names.
     */
    default String code() {
        return domain() + "." + name();
    }

    /** The domain prefix — {@code BOOKING}, {@code AUTH}, {@code SCOPE}. Uppercase, no dots. */
    String domain();

    /**
     * The enum constant name. Implemented for free by every enum; declared here so {@link #code()} can be a
     * default method rather than something each implementation has to remember to write correctly.
     */
    String name();

    /**
     * The HTTP status this condition is always reported with, as a plain {@code int}.
     *
     * <p>An {@code int} rather than a framework status type on purpose: this module is the wire contract and
     * carries no framework dependency, so the same types can back a generated client SDK that has no Spring
     * on its classpath.
     */
    int status();

    /**
     * Human-readable default text, safe to show a caller.
     *
     * <p>Not the contract — reword or localise it freely. A client that has to read this to work out what
     * happened is a client whose code is missing or too coarse.
     */
    String defaultMessage();
}
