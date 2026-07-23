package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Which role to grant.
 *
 * <p>The role is named by <b>code</b> rather than uid: a grant is written by a person or a script, and
 * {@code PLATFORM_ADMIN} is reviewable in a pull request in a way a uuid is not.
 *
 * <p>The recipient is <b>not</b> in the body — it is the path. A body that names both parties makes it
 * possible for the two to disagree, and leaves the route's meaning depending on which one wins.
 *
 * @param roleCode the role's stable code
 */
public record GrantRoleRequest(@NotBlank(message = "Name the role to grant.") String roleCode) {
}
