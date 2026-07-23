package tz.co.otapp.buscore.identityaccess.internal.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.Getter;
import tz.co.otapp.buscore.shared.abstraction.BaseEntity;

/**
 * One thing that may be done, as a row.
 *
 * <p>The table exists so a role can point at permissions and so an administrative surface can list what
 * is grantable. The <b>authoritative list is the Java catalog</b>, not this table: a permission a route
 * names but the seed never inserted refuses everyone, and a row here that no route names grants nothing.
 * A test asserts the two agree in both directions, because neither half fails on its own.
 */
@Getter
@Entity
@Table(name = "permissions")
public class Permission extends BaseEntity {

    /** {@code DOMAIN.ACTION} — the string a route names and a caller is checked against. */
    @Column(name = "code", nullable = false, length = 128)
    private String code;

    /**
     * What holding it lets somebody do, in plain words.
     *
     * <p>Not optional. Whoever assembles a role is choosing from a list of codes, and
     * {@code USER.LINK_OPERATOR} does not explain itself.
     */
    @Column(name = "description", nullable = false, length = 512)
    private String description;

    protected Permission() {
    }
}
