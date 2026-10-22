package io.hyperfoil.tools.jjq.xml;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance for the xq-compatible mapping (issue #169): parse shapes,
 * security rejections, number coercion, emission round-trips, and
 * {@link JqProgram} interop.
 */
class JqXmlTest {

    private static String jsonOf(String xml) {
        return JqXml.parse(xml).toJsonString();
    }

    // ---- Mapping ----

    @Test
    void textOnlyElementBecomesBareString() {
        assertEquals("{\"a\":\"hello\"}", jsonOf("<a>hello</a>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<a/>", "<a></a>", "<a>   </a>"})
    void emptyElementsBecomeNull(String xml) {
        assertEquals("{\"a\":null}", jsonOf(xml));
    }

    @Test
    void attributesAndText() {
        assertEquals("{\"a\":{\"@x\":\"1\",\"@y\":\"two\",\"#text\":\"t\"}}",
                jsonOf("<a x=\"1\" y=\"two\">t</a>"));
    }

    @Test
    void repeatedSiblingsCollapseToArray() {
        assertEquals("{\"a\":{\"b\":[\"1\",\"2\",\"3\"]}}",
                jsonOf("<a><b>1</b><b>2</b><b>3</b></a>"));
    }

    @Test
    void whitespaceBetweenElementsIsDropped() {
        assertEquals("{\"root\":{\"a\":\"1\",\"b\":\"2\"}}",
                jsonOf("<root>\n  <a>1</a>\n  <b>2</b>\n</root>"));
    }

    @Test
    void mixedContentConcatenatesInOrder() {
        // Documented wart: order across text and children is not preserved
        // (children first, then #text).
        assertEquals("{\"p\":{\"b\":\"x\",\"#text\":\"one  two\"}}",
                jsonOf("<p>one <b>x</b> two</p>"));
    }

    @Test
    void namespacesKeepPrefixesAndDeclarations() {
        assertEquals("{\"p:root\":{\"@xmlns:p\":\"urn:test\",\"p:item\":\"v\"}}",
                jsonOf("<p:root xmlns:p=\"urn:test\"><p:item>v</p:item></p:root>"));
    }

    @Test
    void cdataAndReferencesAreText() {
        assertEquals("{\"a\":\"one & twoAthree\"}",
                jsonOf("<a>one &amp; two&#65;<![CDATA[three]]></a>"));
    }

    @Test
    void commentsAndProcessingInstructionsAreDropped() {
        assertEquals("{\"a\":{\"b\":\"1\"}}",
                jsonOf("<?pi data?><a><!--note--><b>1</b></a>"));
    }

    @Test
    void jenkinsStyleDocument() {
        String xml = """
                <flow-build>
                  <actions>
                    <hudson.model.CauseAction>
                      <causeBag>
                        <entry>
                          <hudson.model.Cause_-UserIdCause>
                            <userId>found</userId>
                          </hudson.model.Cause_-UserIdCause>
                        </entry>
                        <entry>
                          <hudson.model.Cause_-UserIdCause>
                            <userId>wrong</userId>
                          </hudson.model.Cause_-UserIdCause>
                        </entry>
                      </causeBag>
                    </hudson.model.CauseAction>
                  </actions>
                </flow-build>
                """;
        JqValue doc = JqXml.parse(xml);
        var found = JqProgram.compile(
                ".[\"flow-build\"].actions[\"hudson.model.CauseAction\"]"
                        + ".causeBag.entry[0][\"hudson.model.Cause_-UserIdCause\"].userId")
                .apply(doc);
        assertEquals("found", found.stringValue());
    }

    // ---- Security (XXE hardening is non-negotiable) ----

    @Test
    void doctypeRejected() {
        assertThrows(JqXmlException.class, () -> JqXml.parse("<!DOCTYPE r><r/>"));
    }

    @Test
    void doctypeWithEntityRejected() {
        String xml = "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///nonexistent-jjq-test\">]>"
                + "<r>&x;</r>";
        assertThrows(JqXmlException.class, () -> JqXml.parse(xml));
    }

    @Test
    void malformedRejected() {
        assertThrows(JqXmlException.class, () -> JqXml.parse("<a>"));
        assertThrows(JqXmlException.class, () -> JqXml.parse("<a></b>"));
    }

    @Test
    void multipleRootsRejected() {
        assertThrows(JqXmlException.class, () -> JqXml.parse("<a/><b/>"));
    }

    @Test
    void emptyInputIsNull() {
        assertTrue(JqXml.parse("").isNull());
        assertTrue(JqXml.parse("   ").isNull());
    }

    @Test
    void depthCapRejected() {
        String deep = "<a>".repeat(10_001) + "x" + "</a>".repeat(10_001);
        JqXmlException e = assertThrows(JqXmlException.class, () -> JqXml.parse(deep));
        assertTrue(e.getMessage().contains("10000"), e.getMessage());
    }

    // ---- Number coercion (opt-in, JSON shapes only) ----

    @Test
    void stringsByDefault() {
        assertEquals("{\"a\":{\"@n\":\"30\",\"#text\":\"3.5\"}}",
                jsonOf("<a n=\"30\">3.5</a>"));
    }

    @Test
    void coerceNumbersLiftsJsonShapes() {
        String json = JqXml.parse("<a n=\"30\" f=\"3.5\" e=\"1e3\" z=\"001\" v=\"1.2.3\">42</a>",
                XmlOptions.COERCE_NUMBERS).toJsonString();
        assertEquals("{\"a\":{\"@n\":30,\"@f\":3.5,\"@e\":1000,\"@z\":\"001\","
                + "\"@v\":\"1.2.3\",\"#text\":42}}", json);
    }

    @Test
    void coerceKeepsLongOverflowExact() {
        String json = JqXml.parse("<a>9999999999999999999</a>", XmlOptions.COERCE_NUMBERS)
                .toJsonString();
        assertEquals("{\"a\":9999999999999999999}", json);
    }

    // ---- Emission ----

    @Test
    void roundTripCommonSubset() {
        String[] docs = {
            "<a>hello</a>",
            "<a x=\"1\">t</a>",
            "<a><b>1</b><b>2</b></a>",
            "<root>\n  <a>1</a>\n  <b>2</b>\n</root>",
            "<p:root xmlns:p=\"urn:test\"><p:item v=\"1\">x</p:item></p:root>",
        };
        for (String doc : docs) {
            JqValue tree = JqXml.parse(doc);
            JqValue back = JqXml.parse(JqXml.toXml(tree));
            assertEquals(tree.toJsonString(), back.toJsonString(), "round trip: " + doc);
        }
    }

    @Test
    void emitEscapesTextAndAttributes() {
        JqValue tree = JqValues.parseStrict("{\"a\":{\"@x\":\"a\\\"b&c\",\"#text\":\"1<2>3&4\"}}");
        assertEquals("<a x=\"a&quot;b&amp;c\">1&lt;2&gt;3&amp;4</a>", JqXml.toXml(tree));
    }

    @Test
    void emitRejectsNonRootedValues() {
        assertThrows(JqXmlException.class, () -> JqXml.toXml(JqValues.parseStrict("[1]")));
        assertThrows(JqXmlException.class,
                () -> JqXml.toXml(JqValues.parseStrict("{\"a\":1,\"b\":2}")));
    }

    @Test
    void queryInteropFiltersAndUpdates() {
        JqValue doc = JqXml.parse(
                "<items><item><name>a</name><price>10</price></item>"
                        + "<item><name>b</name><price>20</price></item></items>");
        var names = JqProgram.compile("[.items.item[].name]").apply(doc);
        assertEquals("[\"a\",\"b\"]", names.toJsonString());
        var updated = JqProgram.compile(".items.item[0].price = \"15\"").apply(doc);
        assertEquals("{\"items\":{\"item\":[{\"name\":\"a\",\"price\":\"15\"},"
                + "{\"name\":\"b\",\"price\":\"20\"}]}}", updated.toJsonString());
        assertEquals(updated.toJsonString(),
                JqXml.parse(JqXml.toXml(updated)).toJsonString());
    }

    // ---- Input variants ----

    @Test
    void streamRespectsEncodingDeclaration() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><r>caf\u00e9</r>";
        var in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_16));
        assertEquals("{\"r\":\"caf\u00e9\"}", JqXml.parse(in).toJsonString());
    }

    @Test
    void stringIgnoresEncodingDeclaration() {
        // Decoded content is authoritative; the declaration must not
        // reinterpret it.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><r>caf\u00e9</r>";
        assertEquals("{\"r\":\"caf\u00e9\"}", JqXml.parse(xml).toJsonString());
    }
}
