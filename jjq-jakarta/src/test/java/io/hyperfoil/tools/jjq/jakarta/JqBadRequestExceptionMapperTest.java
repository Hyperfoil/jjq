package io.hyperfoil.tools.jjq.jakarta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;

import org.junit.jupiter.api.Test;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

/**
 * Bind failures negotiate their error shape (issue #153): an explicit
 * {@code application/problem+json} accept (or an always-problem policy)
 * renders an RFC 9457 problem detail; everything else keeps the historical
 * {@code text/plain} body byte-identical. The thin {@code toResponse}
 * wrapper itself is covered by container tests (it needs a JAX-RS
 * {@code RuntimeDelegate}).
 */
class JqBadRequestExceptionMapperTest {

    private static final MediaType PROBLEM = new MediaType("application", "problem+json");

    private static JqBadRequestExceptionMapper mapper() {
        return new JqBadRequestExceptionMapper();
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static HttpHeaders accept(MediaType... types) {
        return new HttpHeaders() {
            @Override
            public List<MediaType> getAcceptableMediaTypes() {
                return List.of(types);
            }

            @Override
            public MultivaluedMap<String, String> getRequestHeaders() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Locale> getAcceptableLanguages() {
                throw new UnsupportedOperationException();
            }

            @Override
            public MediaType getMediaType() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Locale getLanguage() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Map<String, Cookie> getCookies() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Date getDate() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int getLength() {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getHeaderString(String name) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<String> getRequestHeader(String name) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean containsHeaderString(String name, String value,
                    java.util.function.Predicate<String> valuePredicate) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static Instance<JqProblemPolicy> policy(boolean always) {
        JqProblemPolicy bound = new JqProblemPolicy(always);
        return new Instance<>() {
            @Override
            public boolean isUnsatisfied() {
                return false;
            }

            @Override
            public boolean isResolvable() {
                return true;
            }

            @Override
            public boolean isAmbiguous() {
                return false;
            }

            @Override
            public JqProblemPolicy get() {
                return bound;
            }

            @Override
            public java.util.Iterator<JqProblemPolicy> iterator() {
                return List.of(bound).iterator();
            }

            @Override
            public void destroy(JqProblemPolicy instance) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Instance<JqProblemPolicy> select(Annotation... qualifiers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <U extends JqProblemPolicy> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <U extends JqProblemPolicy> Instance<U> select(
                    jakarta.enterprise.util.TypeLiteral<U> subtype, Annotation... qualifiers) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Instance.Handle<JqProblemPolicy> getHandle() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Iterable<? extends Instance.Handle<JqProblemPolicy>> handles() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void defaultsToTextWithoutAccept() {
        assertFalse(mapper().rendersProblem());
    }

    @Test
    void explicitProblemAcceptRendersProblem() throws Exception {
        var mapper = mapper();
        inject(mapper, "headers", accept(PROBLEM));
        assertTrue(mapper.rendersProblem());
    }

    @Test
    void jsonAcceptOnlyStaysText() throws Exception {
        var mapper = mapper();
        inject(mapper, "headers", accept(MediaType.APPLICATION_JSON_TYPE));
        assertFalse(mapper.rendersProblem());
    }

    @Test
    void wildcardAcceptStaysText() throws Exception {
        var mapper = mapper();
        inject(mapper, "headers", accept(MediaType.WILDCARD_TYPE));
        assertFalse(mapper.rendersProblem());
    }

    @Test
    void zeroQualityProblemDeclines() throws Exception {
        var mapper = mapper();
        inject(mapper, "headers",
                accept(new MediaType("application", "problem+json", Map.of("q", "0"))));
        assertFalse(mapper.rendersProblem());
    }

    @Test
    void explicitMediaTypeQualitiesChooseTheErrorShape() throws Exception {
        var mapper = mapper();
        inject(mapper, "headers", accept(
                new MediaType("application", "problem+json", Map.of("q", "0.2")),
                new MediaType("text", "plain", Map.of("q", "0.9"))));
        assertFalse(mapper.rendersProblem());
        inject(mapper, "headers", accept(
                PROBLEM, new MediaType("text", "plain", Map.of("q", "0.9"))));
        assertTrue(mapper.rendersProblem());
    }

    @Test
    void invalidProblemQualityDoesNotOptIn() throws Exception {
        var mapper = mapper();
        for (String quality : List.of("invalid", "NaN", "-1", "1.1")) {
            inject(mapper, "headers", accept(
                    new MediaType("application", "problem+json", Map.of("q", quality))));
            assertFalse(mapper.rendersProblem(), quality);
        }
    }

    @Test
    void policyAlwaysRendersProblemWithoutAccept() throws Exception {
        var mapper = mapper();
        inject(mapper, "policies", policy(true));
        assertTrue(mapper.rendersProblem());
    }

    @Test
    void policyNeverFallsBackToNegotiation() throws Exception {
        var mapper = mapper();
        inject(mapper, "policies", policy(false));
        assertFalse(mapper.rendersProblem());
        inject(mapper, "headers", accept(PROBLEM));
        assertTrue(mapper.rendersProblem());
    }

    @Test
    void problemEntityCarriesRfcShape() {
        JqValue body = JqValues.parse(JqBadRequestExceptionMapper.problemEntity(400, "Cannot bind it", null));
        assertEquals("about:blank", body.getField("type").stringValue());
        assertEquals("Bad Request", body.getField("title").stringValue());
        assertEquals(400L, body.getField("status").longValue());
        assertEquals("Cannot bind it", body.getField("detail").stringValue());
        assertFalse(body.has("instance"));
    }

    @Test
    void problemEntityIncludesInstanceWhenKnown() {
        JqValue body = JqValues.parse(
                JqBadRequestExceptionMapper.problemEntity(400, "Cannot bind it", "/jjq/record"));
        assertEquals("/jjq/record", body.getField("instance").stringValue());
    }

    @Test
    void problemEntityEscapesDetail() {
        String nasty = "bad \"key\"\nnewline tab\there ünïcodé \\";
        JqValue body = JqValues.parse(JqBadRequestExceptionMapper.problemEntity(400, nasty, null));
        assertEquals(nasty, body.getField("detail").stringValue());
    }

    @Test
    void requestPathNullOutsideContainer() {
        assertNull(mapper().requestPath());
    }
}
