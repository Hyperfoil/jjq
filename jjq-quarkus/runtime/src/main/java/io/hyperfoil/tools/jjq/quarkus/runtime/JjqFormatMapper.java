package io.hyperfoil.tools.jjq.quarkus.runtime;

import io.hyperfoil.tools.jjq.jakarta.JqValueFormatMapper;
import io.quarkus.hibernate.orm.JsonFormat;
import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Hibernate {@link org.hibernate.type.format.FormatMapper} for jjq, wired as the
 * default JSON format mapper when the {@code jjq-quarkus} extension is used
 * without Jackson on the classpath.
 *
 * <p>This is the annotated subclass the {@link JqValueFormatMapper} javadoc
 * describes — provided here so users no longer need to write it themselves.
 * The deployment module registers it as a CDI bean only when Hibernate ORM is
 * present and Jackson is absent (Jackson's own mapper keeps precedence when
 * both are on the classpath).</p>
 */
@ApplicationScoped
@PersistenceUnitExtension
@JsonFormat
public class JjqFormatMapper extends JqValueFormatMapper {

    /** Creates a new JjqFormatMapper. */
    public JjqFormatMapper() {}
}
