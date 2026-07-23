package tz.co.otapp.buscore.identityaccess.internal.domain.dto;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import tz.co.otapp.buscore.identityaccess.StaffTenancy;

/**
 * A new staff login identity.
 *
 * <p><b>No password field, and no status field.</b> The account is created {@code PENDING} and cannot sign
 * in until a password is set through the credential surface. A create that accepted a password would put a
 * plaintext secret in a request body that is logged, retried and traced — and would let whoever provisions
 * accounts know the credential of every account they provision.
 *
 * <p><b>No role field either.</b> Creating an account and granting it authority are separate acts with
 * separate permissions and separate audit entries; folding them together means the record of "who gave this
 * person their access" is the same row as "who created them", and one of the two questions loses its answer.
 *
 * @param username    the sign-in name. Matched case-insensitively, stored as typed
 * @param email       required — an account with no address is one forgotten password from unrecoverable
 * @param displayName how the person is shown to others
 * @param tenancy     which class of account this is. <b>Constrained by the caller's own</b>: see
 *                    {@code StaffAdministrationService}, since nobody may create an account more powerful
 *                    than their own
 * @param companyUid  the company an {@code OPERATOR} account belongs to. Required for platform staff, who
 *                    belong to no company and so must name one; <b>ignored for operator staff</b>, whose
 *                    own company is used instead. It is deliberately not authoritative when the caller has
 *                    a company of their own — a body may not choose whose data an account will reach
 */
public record CreateStaffRequest(

        @NotBlank(message = "Give the account a username.")
        @Size(max = 64, message = "A username may be at most 64 characters.")
        String username,

        @NotBlank(message = "Give the account an email address.")
        @Email(message = "That is not a valid email address.")
        @Size(max = 128, message = "An email address may be at most 128 characters.")
        String email,

        @NotBlank(message = "Give the account a display name.")
        @Size(max = 128, message = "A display name may be at most 128 characters.")
        String displayName,

        @NotNull(message = "Say which kind of account this is.")
        StaffTenancy tenancy,

        UUID companyUid) {
}
