package io.hyperfoil.tools.jjq.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.hyperfoil.tools.jjq.quarkus.test.Note;
import io.hyperfoil.tools.jjq.quarkus.test.NoteText;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * Extension integration test: Hibernate {@code FormatMapper} wiring.
 * A {@code JqValue} JSON column round-trips with no Jackson on the classpath
 * and no user-written mapper subclass.
 */
class JjqHibernateTest {

    @RegisterExtension
    static final QuarkusExtensionTest UNIT_TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Note.class, NoteText.class))
            .overrideConfigKey("quarkus.datasource.db-kind", "h2")
            .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:jjqtest")
            .overrideConfigKey("quarkus.hibernate-orm.database.generation", "drop-and-create");

    @Inject
    EntityManager em;

    @Inject
    UserTransaction tx;

    @Test
    void jsonColumnRoundTrip() throws Exception {
        tx.begin();
        Note note = new Note();
        note.payload = JqValues.parse("{\"a\":[1,2,{\"b\":true}]}");
        em.persist(note);
        em.flush();
        em.clear();
        tx.commit();

        tx.begin();
        Note found = em.find(Note.class, note.id);
        tx.commit();
        assertNotNull(found);
        assertEquals("{\"a\":[1,2,{\"b\":true}]}", found.payload.toJsonString());
    }

    @Test
    void stringColumnYieldsRawText() throws Exception {
        tx.begin();
        NoteText note = new NoteText();
        note.doc = "{\"a\":[1,2,{\"b\":true}]}";
        em.persist(note);
        em.flush();
        em.clear();
        tx.commit();

        tx.begin();
        NoteText found = em.find(NoteText.class, note.id);
        tx.commit();
        assertNotNull(found);
        assertEquals("{\"a\":[1,2,{\"b\":true}]}", found.doc);
    }
}
