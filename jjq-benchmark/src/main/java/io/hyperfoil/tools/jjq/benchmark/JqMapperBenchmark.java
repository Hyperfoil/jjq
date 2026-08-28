package io.hyperfoil.tools.jjq.benchmark;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Compares jjq-mapper vs Jackson ObjectMapper for record data binding.
 *
 * <p>Tests three scenarios:</p>
 * <ul>
 *   <li><b>Simple record</b> — 5 scalar fields</li>
 *   <li><b>Nested record</b> — record with a sub-record</li>
 *   <li><b>Record with list</b> — record containing a List of records</li>
 * </ul>
 *
 * <p>Each scenario measures deserialization (JSON string → record) and
 * serialization (record → JSON string). For jjq-mapper, also measures
 * the pre-parsed path (JqValue → record) which is the h5m use case.</p>
 *
 * <h3>Running</h3>
 * <pre>
 * mvn package -pl jjq-core,jjq-jackson,jjq-fastjson2,jjq-mapper,jjq-benchmark -DskipTests -Pbenchmark
 * java -jar jjq-benchmark/target/jjq-benchmark-*.jar JqMapperBenchmark
 *
 * # With allocation profiling
 * java -jar jjq-benchmark/target/jjq-benchmark-*.jar JqMapperBenchmark -prof gc
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgs = {"-Xmx2g", "-Xms2g"})
@State(Scope.Benchmark)
public class JqMapperBenchmark {

    // ========================================================================
    //  Record types used in benchmarks
    // ========================================================================

    public record SimpleRecord(String name, int age, double score, boolean active, String email) {}

    public record Address(String city, String zip, String country) {}
    public record PersonRecord(String name, int age, Address address) {}

    public record Item(String name, double price, int quantity) {}
    public record OrderRecord(String id, String customer, List<Item> items) {}

    // ========================================================================
    //  Benchmark state
    // ========================================================================

    // JSON strings
    private String simpleJson;
    private String nestedJson;
    private String listJson;

    // JSON byte arrays (for jjq byte parser)
    private byte[] simpleJsonBytes;
    private byte[] nestedJsonBytes;
    private byte[] listJsonBytes;

    // Pre-parsed JqValue (for jjq pre-parsed path)
    private JqValue simpleJqValue;
    private JqValue nestedJqValue;
    private JqValue listJqValue;

    // Pre-parsed Jackson JsonNode (for Jackson pre-parsed path)
    private JsonNode simpleJsonNode;
    private JsonNode nestedJsonNode;
    private JsonNode listJsonNode;

    // Record instances (for serialization benchmarks)
    private SimpleRecord simpleRecord;
    private PersonRecord personRecord;
    private OrderRecord orderRecord;

    // Mappers (created once, reused)
    private JqMapper jqMapper;
    private ObjectMapper jacksonMapper;

    @Setup
    public void setup() throws IOException {
        jqMapper = JqMapper.create();
        jacksonMapper = new ObjectMapper();

        // Simple record JSON
        simpleJson = "{\"name\":\"Alice\",\"age\":30,\"score\":98.5,\"active\":true,\"email\":\"alice@example.com\"}";
        simpleJsonBytes = simpleJson.getBytes();

        // Nested record JSON
        nestedJson = "{\"name\":\"Alice\",\"age\":30,\"address\":{\"city\":\"New York\",\"zip\":\"10001\",\"country\":\"US\"}}";
        nestedJsonBytes = nestedJson.getBytes();

        // Record with list JSON (3 items)
        listJson = """
                {"id":"order-42","customer":"Alice","items":[
                    {"name":"Widget","price":9.99,"quantity":3},
                    {"name":"Gadget","price":24.99,"quantity":1},
                    {"name":"Doohickey","price":4.50,"quantity":10}
                ]}""";
        listJsonBytes = listJson.getBytes();

        // Pre-parse for the pre-parsed benchmarks
        simpleJqValue = JqValues.parse(simpleJsonBytes);
        nestedJqValue = JqValues.parse(nestedJsonBytes);
        listJqValue = JqValues.parse(listJsonBytes);

        simpleJsonNode = jacksonMapper.readTree(simpleJson);
        nestedJsonNode = jacksonMapper.readTree(nestedJson);
        listJsonNode = jacksonMapper.readTree(listJson);

        // Record instances for serialization
        simpleRecord = new SimpleRecord("Alice", 30, 98.5, true, "alice@example.com");
        personRecord = new PersonRecord("Alice", 30, new Address("New York", "10001", "US"));
        orderRecord = new OrderRecord("order-42", "Alice", List.of(
                new Item("Widget", 9.99, 3),
                new Item("Gadget", 24.99, 1),
                new Item("Doohickey", 4.50, 10)
        ));

        // Warmup the mapper caches (class introspection happens on first call)
        jqMapper.fromJqValue(simpleJqValue, SimpleRecord.class);
        jqMapper.fromJqValue(nestedJqValue, PersonRecord.class);
        jqMapper.fromJqValue(listJqValue, OrderRecord.class);
    }

    // ========================================================================
    //  Deserialization: JSON String → Record
    // ========================================================================

    @Benchmark
    public SimpleRecord deser_simple_jjq_fromString() {
        return jqMapper.fromJson(simpleJson, SimpleRecord.class);
    }

    @Benchmark
    public SimpleRecord deser_simple_jackson_fromString() throws JsonProcessingException {
        return jacksonMapper.readValue(simpleJson, SimpleRecord.class);
    }

    @Benchmark
    public PersonRecord deser_nested_jjq_fromString() {
        return jqMapper.fromJson(nestedJson, PersonRecord.class);
    }

    @Benchmark
    public PersonRecord deser_nested_jackson_fromString() throws JsonProcessingException {
        return jacksonMapper.readValue(nestedJson, PersonRecord.class);
    }

    @Benchmark
    public OrderRecord deser_list_jjq_fromString() {
        return jqMapper.fromJson(listJson, OrderRecord.class);
    }

    @Benchmark
    public OrderRecord deser_list_jackson_fromString() throws JsonProcessingException {
        return jacksonMapper.readValue(listJson, OrderRecord.class);
    }

    // ========================================================================
    //  Deserialization: JSON byte[] → Record
    // ========================================================================

    @Benchmark
    public SimpleRecord deser_simple_jjq_fromBytes() {
        return jqMapper.fromJson(simpleJsonBytes, SimpleRecord.class);
    }

    @Benchmark
    public SimpleRecord deser_simple_jackson_fromBytes() throws IOException {
        return jacksonMapper.readValue(simpleJsonBytes, SimpleRecord.class);
    }

    @Benchmark
    public PersonRecord deser_nested_jjq_fromBytes() {
        return jqMapper.fromJson(nestedJsonBytes, PersonRecord.class);
    }

    @Benchmark
    public PersonRecord deser_nested_jackson_fromBytes() throws IOException {
        return jacksonMapper.readValue(nestedJsonBytes, PersonRecord.class);
    }

    @Benchmark
    public OrderRecord deser_list_jjq_fromBytes() {
        return jqMapper.fromJson(listJsonBytes, OrderRecord.class);
    }

    @Benchmark
    public OrderRecord deser_list_jackson_fromBytes() throws IOException {
        return jacksonMapper.readValue(listJsonBytes, OrderRecord.class);
    }

    // ========================================================================
    //  Deserialization: Pre-parsed → Record (jjq's sweet spot)
    // ========================================================================

    @Benchmark
    public SimpleRecord deser_simple_jjq_preParsed() {
        return jqMapper.fromJqValue(simpleJqValue, SimpleRecord.class);
    }

    @Benchmark
    public SimpleRecord deser_simple_jackson_preParsed() throws JsonProcessingException {
        return jacksonMapper.treeToValue(simpleJsonNode, SimpleRecord.class);
    }

    @Benchmark
    public PersonRecord deser_nested_jjq_preParsed() {
        return jqMapper.fromJqValue(nestedJqValue, PersonRecord.class);
    }

    @Benchmark
    public PersonRecord deser_nested_jackson_preParsed() throws JsonProcessingException {
        return jacksonMapper.treeToValue(nestedJsonNode, PersonRecord.class);
    }

    @Benchmark
    public OrderRecord deser_list_jjq_preParsed() {
        return jqMapper.fromJqValue(listJqValue, OrderRecord.class);
    }

    @Benchmark
    public OrderRecord deser_list_jackson_preParsed() throws JsonProcessingException {
        return jacksonMapper.treeToValue(listJsonNode, OrderRecord.class);
    }

    // ========================================================================
    //  Serialization: Record → JSON String
    // ========================================================================

    @Benchmark
    public String ser_simple_jjq() {
        return jqMapper.toJson(simpleRecord);
    }

    @Benchmark
    public String ser_simple_jackson() throws JsonProcessingException {
        return jacksonMapper.writeValueAsString(simpleRecord);
    }

    @Benchmark
    public String ser_nested_jjq() {
        return jqMapper.toJson(personRecord);
    }

    @Benchmark
    public String ser_nested_jackson() throws JsonProcessingException {
        return jacksonMapper.writeValueAsString(personRecord);
    }

    @Benchmark
    public String ser_list_jjq() {
        return jqMapper.toJson(orderRecord);
    }

    @Benchmark
    public String ser_list_jackson() throws JsonProcessingException {
        return jacksonMapper.writeValueAsString(orderRecord);
    }

    // ========================================================================
    //  Serialization: Record → byte[]
    // ========================================================================

    @Benchmark
    public byte[] ser_simple_jjq_bytes() {
        return jqMapper.toJsonBytes(simpleRecord);
    }

    @Benchmark
    public byte[] ser_simple_jackson_bytes() throws JsonProcessingException {
        return jacksonMapper.writeValueAsBytes(simpleRecord);
    }

    @Benchmark
    public byte[] ser_nested_jjq_bytes() {
        return jqMapper.toJsonBytes(personRecord);
    }

    @Benchmark
    public byte[] ser_nested_jackson_bytes() throws JsonProcessingException {
        return jacksonMapper.writeValueAsBytes(personRecord);
    }
}
