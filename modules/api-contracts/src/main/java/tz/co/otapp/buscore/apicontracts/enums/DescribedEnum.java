package tz.co.otapp.buscore.apicontracts.enums;

/**
 * The shape every enum in this system exposes: a stable code, a human label, and an explanation.
 *
 * <p>Declared once here rather than re-invented per module, so a client that renders a dropdown, a tooltip
 * or a filter list works the same way whichever enum it is given — and so a single generic endpoint can
 * one day serve all of them without knowing any of their names.
 *
 * <h2>The three, and why each exists separately</h2>
 *
 * <table border="1">
 *   <caption>Which is the contract and which is copy</caption>
 *   <tr><th></th><th>Example</th><th>May it change?</th></tr>
 *   <tr><td>{@link #getValue()}</td><td>{@code OPERATOR}</td>
 *       <td><b>Never.</b> Persisted and sent on the wire; clients branch on it.</td></tr>
 *   <tr><td>{@link #getName()}</td><td>{@code Operator User}</td>
 *       <td>Freely. Copy, shown to humans.</td></tr>
 *   <tr><td>{@link #getDescription()}</td><td>{@code A person at a bus company…}</td>
 *       <td>Freely. Copy, shown to humans.</td></tr>
 * </table>
 *
 * <p>Keeping the label out of the code is what lets the wording be corrected without a migration. If the
 * two were one field, every rewording would rewrite stored rows and break every client branching on it.
 *
 * <h2>⚠ getName() is not name()</h2>
 *
 * <p>{@code Enum.name()} is final and returns the <em>constant name</em>; {@link #getName()} returns the
 * <em>display label</em>. They deliberately differ — {@code ROOT.name()} is {@code "ROOT"} while
 * {@code ROOT.getName()} is {@code "Root"} — and that is a genuine trap when reading code quickly.
 *
 * <p><b>Never switch on, persist, or compare {@code getName()}.</b> It is copy. Use {@link #getValue()},
 * which {@link #assertValueMatchesConstant()} guarantees is identical to {@code name()}.
 *
 * <h2>The value must equal the constant name</h2>
 *
 * <p>Enums are persisted with {@code EnumType.STRING}, which writes {@code name()} — not
 * {@link #getValue()}. If the two ever diverged there would be two codes for one concept: one in the
 * database and a different one on the wire. {@link #assertValueMatchesConstant()} exists so a test can
 * make that impossible rather than merely unlikely.
 */
public interface DescribedEnum {

    /** Enum-provided. Declared here so the default method below can compare against it. */
    String name();

    /**
     * The stable code, persisted and sent on the wire. Identical to {@link #name()}.
     *
     * <p>Spelled out as a field rather than left implicit so it reads as a deliberate part of the
     * contract, and so a serialiser or a mapper can reach it through this interface without special-casing
     * enums.
     */
    String getValue();

    /** Short human label for a dropdown or a table cell. Copy — reword freely, never branch on it. */
    String getName();

    /** A sentence explaining what this value means, for a tooltip or generated documentation. */
    String getDescription();

    /**
     * Fails when a constant's value has drifted from its name.
     *
     * <p>Call this from a test over every constant of every implementing enum. It is one line per enum and
     * it removes a whole failure mode: a value that disagrees with the constant name means the database and
     * the API describe the same concept with two different codes, and nothing else in the build would
     * notice.
     */
    default void assertValueMatchesConstant() {
        if (!name().equals(getValue())) {
            throw new IllegalStateException(
                    "Enum value must equal the constant name, because persistence writes name(): "
                            + name() + " declares value '" + getValue() + "'");
        }
    }
}
