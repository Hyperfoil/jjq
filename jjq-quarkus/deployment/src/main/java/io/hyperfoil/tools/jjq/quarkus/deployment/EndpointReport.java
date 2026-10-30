package io.hyperfoil.tools.jjq.quarkus.deployment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.MethodParameterInfo;
import org.jboss.jandex.Type;

/**
 * Build-time report of JSON endpoints jjq serves vs declines (issue #198).
 *
 * <p>When a REST endpoint's type is served by no JSON provider, RESTEasy
 * Reactive falls back to {@code toString()} with HTTP 200 — silent
 * garbage, not an error. This report turns that fallback into build log
 * lines: every JSON-producing/consuming resource method is classified
 * with the same rules the runtime providers use
 * ({@code CollectionSupport} in {@code jjq-jakarta}: directly mappable
 * types plus homogeneous collections/maps/arrays through the mapper),
 * mirrored here over Jandex types so no application classloading is
 * needed. Any drift between the mirror and the runtime rules breaks the
 * fixture tests on both sides, not production.</p>
 *
 * <p>Verdicts: {@code SERVED} (jjq binds it), {@code DECLINED} (jjq
 * declines; the container default serves it), {@code UNKNOWN} (cannot be
 * decided statically — unverifiable, never a warning), {@code SKIPPED}
 * (not a JSON endpoint at all). The report is warn-only and never fails
 * the build.</p>
 */
final class EndpointReport {

    /** How a single endpoint direction is served. */
    enum Verdict {
        SERVED,
        DECLINED,
        UNKNOWN,
        SKIPPED
    }

    /** Request body (IN) or response (OUT). */
    enum Direction {
        IN,
        OUT
    }

    /** One classified endpoint direction. */
    record Entry(String httpMethod, String path, Direction direction, String javaType,
            Verdict verdict, String reason) {
    }

    private static final DotName JAX_PATH = DotName.createSimple("jakarta.ws.rs.Path");
    private static final DotName JAX_CONSUMES = DotName.createSimple("jakarta.ws.rs.Consumes");
    private static final DotName JAX_PRODUCES = DotName.createSimple("jakarta.ws.rs.Produces");
    private static final List<DotName> HTTP_METHODS = List.of(
            DotName.createSimple("jakarta.ws.rs.GET"),
            DotName.createSimple("jakarta.ws.rs.POST"),
            DotName.createSimple("jakarta.ws.rs.PUT"),
            DotName.createSimple("jakarta.ws.rs.DELETE"),
            DotName.createSimple("jakarta.ws.rs.PATCH"),
            DotName.createSimple("jakarta.ws.rs.HEAD"),
            DotName.createSimple("jakarta.ws.rs.OPTIONS"));

    private static final List<DotName> BINDING_ANNOTATIONS = List.of(
            DotName.createSimple("jakarta.ws.rs.QueryParam"),
            DotName.createSimple("jakarta.ws.rs.PathParam"),
            DotName.createSimple("jakarta.ws.rs.HeaderParam"),
            DotName.createSimple("jakarta.ws.rs.CookieParam"),
            DotName.createSimple("jakarta.ws.rs.FormParam"),
            DotName.createSimple("jakarta.ws.rs.MatrixParam"),
            DotName.createSimple("jakarta.ws.rs.BeanParam"),
            DotName.createSimple("jakarta.ws.rs.core.Context"),
            DotName.createSimple("jakarta.ws.rs.container.Suspended"));

    private static final DotName JQ_MAPPED = DotName.createSimple("io.hyperfoil.tools.jjq.mapper.JqMapped");
    private static final DotName JQ_TYPE = DotName.createSimple("io.hyperfoil.tools.jjq.mapper.JqType");
    private static final DotName JQ_KEYS = DotName.createSimple("io.hyperfoil.tools.jjq.mapper.JqKeys");
    private static final DotName JQ_VALUE = DotName.createSimple("io.hyperfoil.tools.jjq.value.JqValue");
    private static final List<DotName> JQ_VALUE_TYPES = List.of(
            JQ_VALUE,
            DotName.createSimple("io.hyperfoil.tools.jjq.value.JqObject"),
            DotName.createSimple("io.hyperfoil.tools.jjq.value.JqArray"),
            DotName.createSimple("io.hyperfoil.tools.jjq.value.JqString"),
            DotName.createSimple("io.hyperfoil.tools.jjq.value.JqNumber"),
            DotName.createSimple("io.hyperfoil.tools.jjq.value.JqBoolean"),
            DotName.createSimple("io.hyperfoil.tools.jjq.value.JqNull"));

    private static final DotName UNI = DotName.createSimple("io.smallrye.mutiny.Uni");
    private static final String COMPLETION_STAGE = "java.util.concurrent.CompletionStage";
    private static final String COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture";
    private static final DotName JAX_RESPONSE = DotName.createSimple("jakarta.ws.rs.core.Response");
    private static final DotName REST_RESPONSE = DotName.createSimple("org.jboss.resteasy.reactive.RestResponse");

    private static final List<String> NON_JSON_TYPES = List.of(
            "java.lang.String",
            "java.nio.ByteBuffer",
            "java.io.InputStream",
            "java.io.File",
            "jakarta.ws.rs.core.StreamingOutput");

    private EndpointReport() {
    }

    /**
     * Classify every JSON-producing/consuming resource method in the index.
     * Pure function of the index for unit testing; the build step logs it.
     *
     * @param index the application index (non-null)
     * @return entries sorted by path, method, and direction (never null)
     */
    static List<Entry> endpointReportEntries(IndexView index) {
        var entries = new ArrayList<Entry>();
        for (DotName httpMethod : HTTP_METHODS) {
            for (AnnotationInstance ann : index.getAnnotations(httpMethod)) {
                if (ann.target().kind() != AnnotationTarget.Kind.METHOD) continue;
                MethodInfo method = ann.target().asMethod();
                ClassInfo resource = method.declaringClass();
                String path = joinPath(pathOf(resource), pathOf(method));
                String verb = httpMethod.withoutPackagePrefix();
                classifyRequest(index, entries, verb, path, method);
                classifyResponse(index, entries, verb, path, method);
            }
        }
        entries.sort(Comparator.comparing(Entry::path)
                .thenComparing(Entry::httpMethod)
                .thenComparing(e -> e.direction().name()));
        return entries;
    }

    private static String pathOf(ClassInfo resource) {
        AnnotationInstance path = resource.declaredAnnotation(JAX_PATH);
        return path != null && path.value() != null ? path.value().asString() : "";
    }

    private static String pathOf(MethodInfo method) {
        AnnotationInstance path = method.annotation(JAX_PATH);
        return path != null && path.value() != null ? path.value().asString() : "";
    }

    private static String joinPath(String base, String sub) {
        String joined = (base + "/" + sub).replace("//", "/");
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private static void classifyRequest(IndexView index, List<Entry> entries,
            String verb, String path, MethodInfo method) {
        List<Type> entity = new ArrayList<>();
        for (MethodParameterInfo param : method.parameters()) {
            if (!isBoundParameter(param)) entity.add(param.type());
        }
        if (entity.isEmpty()) return;
        if (entity.size() > 1) {
            entries.add(new Entry(verb, path, Direction.IN, entity.get(0).toString(),
                    Verdict.UNKNOWN, "ambiguous-entity"));
            return;
        }
        if (!isJsonMedia(method, method.declaringClass(), JAX_CONSUMES)) {
            entries.add(new Entry(verb, path, Direction.IN, entity.get(0).toString(),
                    Verdict.SKIPPED, "non-json-media"));
            return;
        }
        entries.add(classify(index, verb, path, Direction.IN, entity.get(0), false));
    }

    private static void classifyResponse(IndexView index, List<Entry> entries,
            String verb, String path, MethodInfo method) {
        Type returns = method.returnType();
        if (returns.kind() == Type.Kind.VOID || returns.kind() == Type.Kind.PRIMITIVE) {
            return;
        }
        if (!isJsonMedia(method, method.declaringClass(), JAX_PRODUCES)) {
            entries.add(new Entry(verb, path, Direction.OUT, returns.toString(),
                    Verdict.SKIPPED, "non-json-media"));
            return;
        }
        entries.add(classify(index, verb, path, Direction.OUT, returns, true));
    }

    private static boolean isBoundParameter(MethodParameterInfo param) {
        for (DotName binding : BINDING_ANNOTATIONS) {
            if (param.annotation(binding) != null) return true;
        }
        return false;
    }

    private static boolean isJsonMedia(MethodInfo method, ClassInfo resource, DotName consumesOrProduces) {
        AnnotationInstance ann = method.annotation(consumesOrProduces);
        if (ann == null) ann = resource.declaredAnnotation(consumesOrProduces);
        if (ann == null || ann.value() == null) return true;
        for (String media : ann.value().asStringArray()) {
            if (media.toLowerCase(java.util.Locale.ROOT).contains("json")) return true;
        }
        return false;
    }

    private static Entry classify(IndexView index, String verb, String path,
            Direction direction, Type type, boolean unwrapAsync) {
        String display = type.toString();
        if (isNonJsonType(type)) {
            return new Entry(verb, path, direction, display, Verdict.SKIPPED, "non-json-type");
        }
        if (isJqValue(type)) {
            return new Entry(verb, path, direction, display, Verdict.SERVED, "jqvalue-passthrough");
        }
        if (unwrapAsync && isAsyncWrapper(type)) {
            List<Type> args = type.asParameterizedType().arguments();
            if (args.size() != 1) {
                return new Entry(verb, path, direction, display, Verdict.DECLINED, "nested-generics");
            }
            return classify(index, verb, path, direction, args.get(0), true);
        }
        if (isResponseWrapper(type)) {
            return new Entry(verb, path, direction, display, Verdict.UNKNOWN, "response-wrapped");
        }
        if (isScalar(type)) {
            return new Entry(verb, path, direction, display, Verdict.DECLINED, "scalar-type");
        }
        Boolean direct = isDirectlyMappable(index, type);
        if (direct == null) {
            return new Entry(verb, path, direction, display, Verdict.UNKNOWN, "unresolvable-type");
        }
        if (direct) {
            return new Entry(verb, path, direction, display, Verdict.SERVED, "directly-mappable");
        }
        String collectionReason = collectionReason(index, type);
        if (collectionReason == null) {
            return new Entry(verb, path, direction, display, Verdict.SERVED, "collection-through-mapper");
        }
        if (collectionReason.isEmpty()) {
            return new Entry(verb, path, direction, display, Verdict.DECLINED, "unmapped-pojo");
        }
        return new Entry(verb, path, direction, display, Verdict.DECLINED, collectionReason);
    }

    private static boolean isNonJsonType(Type type) {
        if (type.kind() == Type.Kind.CLASS) {
            return NON_JSON_TYPES.contains(type.asClassType().name().toString());
        }
        if (type.kind() == Type.Kind.ARRAY) {
            Type component = type.asArrayType().component();
            return component.kind() == Type.Kind.PRIMITIVE
                    && component.asPrimitiveType().primitive()
                            == org.jboss.jandex.PrimitiveType.Primitive.BYTE;
        }
        return false;
    }

    private static boolean isJqValue(Type type) {
        return type.kind() == Type.Kind.CLASS && JQ_VALUE_TYPES.contains(type.asClassType().name());
    }

    private static boolean isAsyncWrapper(Type type) {
        if (type.kind() != Type.Kind.PARAMETERIZED_TYPE) return false;
        DotName raw = type.name();
        if (raw.equals(UNI)) return true;
        if (raw.toString().equals(COMPLETION_STAGE) || raw.toString().equals(COMPLETABLE_FUTURE)) return true;
        try {
            Class<?> rawClass = Class.forName(raw.toString(), false,
                    Thread.currentThread().getContextClassLoader());
            return java.util.concurrent.CompletionStage.class.isAssignableFrom(rawClass);
        } catch (LinkageError | ClassNotFoundException | SecurityException e) {
            return false;
        }
    }

    private static boolean isResponseWrapper(Type type) {
        if (type.kind() != Type.Kind.CLASS) return false;
        DotName name = type.asClassType().name();
        return name.equals(JAX_RESPONSE) || name.equals(REST_RESPONSE);
    }

    private static final List<String> SCALAR_TYPES = List.of(
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Short",
            "java.lang.Byte",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Boolean",
            "java.lang.Character",
            "java.lang.Number",
            "java.math.BigDecimal",
            "java.math.BigInteger");

    private static boolean isScalar(Type type) {
        if (type.kind() == Type.Kind.PRIMITIVE) return true;
        return type.kind() == Type.Kind.CLASS && SCALAR_TYPES.contains(type.asClassType().name().toString());
    }

    /**
     * Mirror of {@code CollectionSupport.isDirectlyMappable} over Jandex:
     * the class carries a mapping annotation. Unresolvable classes report
     * null (unknown) instead of guessing — except JDK platform classes,
     * whose annotations the runtime can always see (and which carry none
     * of ours), so those decline structurally like the runtime does.
     */
    private static Boolean isDirectlyMappable(IndexView index, Type type) {
        if (type.kind() != Type.Kind.CLASS) return Boolean.FALSE;
        DotName name = type.asClassType().name();
        ClassInfo info = index.getClassByName(name);
        if (info == null) {
            String dotted = name.toString();
            if (dotted.startsWith("java.") || dotted.startsWith("jdk.")) return Boolean.FALSE;
            return null;
        }
        return info.declaredAnnotation(JQ_MAPPED) != null
                || info.declaredAnnotation(JQ_TYPE) != null
                || info.declaredAnnotation(JQ_KEYS) != null;
    }

    /**
     * Mirror of {@code CollectionSupport.bindsThroughMapper} over Jandex.
     *
     * @return null when the shape binds (served), a reason code when it
     *         declines for a structural cause, or "" when it is simply not
     *         a mappable shape (plain POJO or scalar)
     */
    private static String collectionReason(IndexView index, Type type) {
        if (type.kind() == Type.Kind.ARRAY) {
            String element = elementReason(index, type.asArrayType().component());
            return element == null ? null : (element.isEmpty() ? "raw-collection-element" : element);
        }
        if (type.kind() != Type.Kind.PARAMETERIZED_TYPE) return "";
        DotName raw = type.name();
        List<Type> args = type.asParameterizedType().arguments();
        if (isCollectionRaw(index, raw)) {
            if (args.size() != 1) return "nested-generics";
            String element = elementReason(index, args.get(0));
            if (element == null) return null;
            return element.isEmpty() ? "raw-collection-element" : element;
        }
        if (isMapRaw(index, raw)) {
            if (args.size() != 2) return "nested-generics";
            if (!isStringKey(args.get(0))) return "non-string-map-keys";
            String element = elementReason(index, args.get(1));
            if (element == null) return null;
            return element.isEmpty() ? "raw-collection-element" : element;
        }
        return "";
    }

    private static String elementReason(IndexView index, Type element) {
        if (element.kind() == Type.Kind.WILDCARD_TYPE) {
            element = element.asWildcardType().extendsBound();
            if (element == null || element.kind() != Type.Kind.CLASS) return "nested-generics";
        }
        if (isJqValue(element)) return null;
        if (element.kind() != Type.Kind.CLASS) return "nested-generics";
        Boolean direct = isDirectlyMappable(index, element);
        if (direct == null) return "unresolvable-type";
        return direct ? null : "";
    }

    private static boolean isStringKey(Type key) {
        return key.kind() == Type.Kind.CLASS
                && key.asClassType().name().toString().equals("java.lang.String");
    }

    private static final List<String> COLLECTION_TYPES = List.of(
            "java.util.Collection",
            "java.util.List",
            "java.util.Set",
            "java.util.SortedSet",
            "java.util.NavigableSet",
            "java.util.Queue",
            "java.util.Deque",
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.ArrayDeque");

    private static final List<String> MAP_TYPES = List.of(
            "java.util.Map",
            "java.util.SortedMap",
            "java.util.NavigableMap",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.concurrent.ConcurrentHashMap");

    /**
     * Whether a raw type is a collection: well-known JDK names plus a walk
     * up the indexed superclass/interface chain, so application-defined
     * implementations resolve without classloading.
     */
    private static boolean isAssignableThrough(IndexView index, DotName raw, List<String> targets) {
        var seen = new java.util.HashSet<DotName>();
        var work = new ArrayDeque<DotName>();
        work.add(raw);
        while (!work.isEmpty()) {
            DotName name = work.removeFirst();
            if (targets.contains(name.toString())) return true;
            if (!seen.add(name)) continue;
            ClassInfo info = index.getClassByName(name);
            if (info == null) continue;
            DotName superName = info.superName();
            if (superName != null) work.add(superName);
            for (DotName face : info.interfaces()) work.add(face);
        }
        return false;
    }

    private static boolean isCollectionRaw(IndexView index, DotName raw) {
        return isAssignableThrough(index, raw, COLLECTION_TYPES);
    }

    private static boolean isMapRaw(IndexView index, DotName raw) {
        return isAssignableThrough(index, raw, MAP_TYPES);
    }
}
