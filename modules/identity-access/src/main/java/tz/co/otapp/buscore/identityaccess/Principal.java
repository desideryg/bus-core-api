package tz.co.otapp.buscore.identityaccess;

import java.util.UUID;

/**
 * The authenticated actor, as every other module sees it.
 *
 * <p><b>Authority is read from here and never from a request body.</b> Whose rows a caller may touch, what
 * they may do, and which agent or operator they act for are all derived from the authenticated token — a
 * field on a request body that names who the caller is, is a field a caller can forge.
 *
 * <p><b>It carries no {@code Long} id.</b> The module-private primary key does not cross this boundary;
 * {@link #uid} is the handle, and it is the only one.
 *
 * <p>Deliberately small for now. Roles, permissions and the operator scope will be added as the slices
 * that give them meaning land — this record is a compile-time contract within the reactor, so growing it
 * is an ordinary refactor rather than a wire-breaking change, and adding fields that nothing populates
 * would only invite code to branch on values that are always empty.
 *
 * @param uid  the actor's public handle
 * @param type which kind of actor the uid refers to
 */
public record Principal(UUID uid, PrincipalType type) {
}
