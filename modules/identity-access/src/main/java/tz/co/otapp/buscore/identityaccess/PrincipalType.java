package tz.co.otapp.buscore.identityaccess;

/**
 * The kind of actor a {@link Principal} is.
 *
 * <p>Only staff exist today. Agents and machine clients arrive in later slices, and they are genuinely
 * different kinds rather than variations on one: a staff member authenticates with a password and is
 * authorised by roles; an agent will authenticate with a short PIN and be authorised by selling grants;
 * a machine client will re-prove a key on every request and hold no session at all. One table with a
 * discriminator would force one credential shape onto all three.
 *
 * <p>The type is here from the first slice rather than added later because {@link Principal} is a
 * compile-time contract for every module that consumes it. Appending an enum constant is safe; adding a
 * component to the record later is a change every consumer has to absorb.
 */
public enum PrincipalType {

    /** A staff member: password today, second factor later, authorised by roles. */
    STAFF
}
