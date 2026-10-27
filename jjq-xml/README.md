# jjq-xml

XML parsing, querying, and emission via jjq's [JqValue](../jjq-core) tree: jq programs over XML through an xq-compatible data projection.

```xml
<dependency>
    <groupId>io.hyperfoil.tools</groupId>
    <artifactId>jjq-xml</artifactId>
    <version>0.2.4</version>
</dependency>
```

```java
import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.xml.JqXml;

JqValue doc = JqXml.parse("<items><item><name>a</name></item><item><name>b</name></item></items>");
JqValue names = JqProgram.compile("[.items.item[].name]").apply(doc);
// ["a","b"]

String xml = JqXml.toXml(doc); // the parsed tree renders back to equivalent XML
```

## Mapping

| XML | JqValue |
|-----|---------|
| Element with children | `JqObject` keyed by child name |
| Attribute | `@`-prefixed key (`<a x="1"/>` to `{"a":{"@x":"1"}}`) |
| Character content | `#text` key |
| Repeated siblings | `JqArray` |
| Empty element (`<a/>`, `<a></a>`, whitespace-only) | `JqNull` |
| Namespace declaration | Retained `@xmlns` / `@xmlns:prefix` keys |

Element and attribute names keep their prefixes (`<p:item>` to `"p:item"`).
Comments and processing instructions are dropped. Mixed content concatenates
text in document order after the children.

A single child stays a singleton; only repeated siblings become arrays, so
queries must handle both shapes (`.items.item` when one, `.items.item[]`
when many). This matches xq and is inherent to the mapping, not a bug.

## Security

DOCTYPE declarations and entity references are rejected (XXE hardening);
predefined references (`&amp;`, `&#65;`) and CDATA keep working. Nesting
past 10,000 is rejected, matching the JSON parser cap.

## Text and numbers

Text and attributes are strings by default. `XmlOptions.COERCE_NUMBERS`
lifts JSON-number-shaped strings to numbers (`30` to `30`, `3.5` to `3.5`);
anything else (`001`, `1.2.3`, version strings) stays a string.

## Emitting XML

```java
String xml = JqXml.toXml(doc);
JqXml.toXml(doc, outputStream); // UTF-8
```

The value needs a single root-element object. `@` keys become attributes,
`#text` becomes character content, other keys become child elements, arrays
become repeated elements. No XML declaration is written. The tree holds no
comments, styles, or namespace URIs beyond the retained declarations, so
those do not survive emission.
