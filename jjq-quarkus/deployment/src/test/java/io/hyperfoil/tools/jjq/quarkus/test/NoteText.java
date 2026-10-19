package io.hyperfoil.tools.jjq.quarkus.test;

import org.hibernate.type.SqlTypes;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * Hibernate fixture: a {@link String} field mapped as a JSON column.
 * The column carries raw JSON text; the extension-provided
 * {@code FormatMapper} yields it unchanged on read and writes it back
 * verbatim (native queries select jsonb aggregates as String).
 */
@Entity
public class NoteText {

    @Id
    @GeneratedValue
    public Long id;

    @JdbcTypeCode(SqlTypes.JSON)
    public String doc;
}
