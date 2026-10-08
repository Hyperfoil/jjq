package io.hyperfoil.tools.jjq.mapper.processor;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.mapper.JqAnyGetter;
import io.hyperfoil.tools.jjq.mapper.JqAnySetter;
import io.hyperfoil.tools.jjq.mapper.JqField;
import io.hyperfoil.tools.jjq.mapper.JqIgnore;
import io.hyperfoil.tools.jjq.mapper.JqInclude;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqNaming;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Annotation processor that generates optimized mapping classes for records
 * annotated with {@link JqMapped}.
 *
 * <p>For each annotated record, generates a {@code ClassName_JqMapping} class
 * that extends {@link io.hyperfoil.tools.jjq.mapper.GeneratedMapping} with
 * direct constructor calls, direct accessor calls, and inlined type conversions.</p>
 */
@SupportedAnnotationTypes("io.hyperfoil.tools.jjq.mapper.JqMapped")
public class JqMapperProcessor extends AbstractProcessor {

    /** Creates a new JqMapperProcessor. */
    public JqMapperProcessor() {}

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Track generated mapping class names per package for registry generation
        var mappingsByPackage = new java.util.LinkedHashMap<String, List<String>>();

        for (Element element : roundEnv.getElementsAnnotatedWith(JqMapped.class)) {
            if (element.getKind() != ElementKind.RECORD && element.getKind() != ElementKind.CLASS) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@JqMapped can only be applied to record or class types", element);
                continue;
            }
            TypeElement typeElement = (TypeElement) element;
            String mappingClassName;
            if (element.getKind() == ElementKind.RECORD) {
                mappingClassName = processRecord(typeElement);
            } else {
                mappingClassName = processClass(typeElement);
            }
            if (mappingClassName != null) {
                String pkg = processingEnv.getElementUtils().getPackageOf(typeElement).getQualifiedName().toString();
                mappingsByPackage.computeIfAbsent(pkg, k -> new ArrayList<>()).add(mappingClassName);
            }
        }

        // Generate one JqMappingRegistry per package
        for (var entry : mappingsByPackage.entrySet()) {
            generateRegistry(entry.getKey(), entry.getValue());
        }

        return true;
    }

    /**
     * Process a single {@code @JqMapped} record and generate its mapping class.
     * @return the simple mapping class name (e.g., "User_JqMapping"), or null on error
     */
    private String processRecord(TypeElement recordType) {
        // Resolve class-level @JqInclude and @JqNaming
        String classInclusion = resolveClassInclusion(recordType);
        JqNaming.Strategy namingStrategy = resolveNamingStrategy(recordType);
        String mappingPackage = processingEnv.getElementUtils().getPackageOf(recordType)
                .getQualifiedName().toString();

        // Any-setter/getter first: any-getter-backed components are suppressed (issue #88.1)
        AnyInfo recordAnyInfo = resolveAnyInfo(recordType);

        // Collect record component metadata
        List<ComponentInfo> components = new ArrayList<>();
        for (Element enclosed : recordType.getEnclosedElements()) {
            if (enclosed instanceof RecordComponentElement rc) {
                String name = rc.getSimpleName().toString();
                String typeName = rc.asType().toString();
                boolean ignored = rc.getAnnotation(JqIgnore.class) != null;
                if (!ignored && recordAnyInfo.hasGetter()
                        && (name.equals(recordAnyInfo.getterName())
                            || name.equals(anyGetterPropertyName(recordAnyInfo.getterName())))) {
                    ignored = true;
                }

                // Apply naming strategy for default expression
                String jsonName = namingStrategy.transform(name);
                JqField jqField = rc.getAnnotation(JqField.class);
                String jqExpr;
                if (jqField != null) {
                    jqExpr = jqField.value();
                    // Validate the jq expression at compile time
                    try {
                        JqProgram.compile(jqExpr);
                    } catch (Exception e) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                "Invalid jq expression in @JqField(\"" + jqExpr + "\"): " + e.getMessage(), rc);
                        return null;
                    }
                } else {
                    // Bridge renames (e.g. @JsonProperty("host-paths")) override
                    // the naming strategy, mirroring the reflection path (issue #103)
                    String renamed = bridgeRenameForRecord(rc, recordType);
                    if (renamed != null) jsonName = renamed;
                    jqExpr = fieldProgram(jsonName);
                }

                // Resolve field-level @JqInclude (overrides class-level), else the
                // bridged @JsonInclude default (mirrors the reflection path)
                JqInclude fieldInclude = rc.getAnnotation(JqInclude.class);
                String inclusion;
                if (fieldInclude != null) {
                    inclusion = fieldInclude.value().name();
                } else {
                    String bridged = bridgeInclusionForRecord(rc, recordType);
                    inclusion = bridged != null ? bridged : classInclusion;
                }

                String serName = (jqField != null) ? name : jsonName; // @JqField overrides naming
                String converterClass = resolveConverterClass(rc);
                if (converterClass != null && !validateConverter(rc, mappingPackage)) return null;

                String rcAccess = resolveJacksonAccess(rc, recordType);
                components.add(new ComponentInfo(name, serName, typeName, jqExpr, ignored, jqField != null, inclusion, converterClass,
                        isRecordType(rc.asType()),
                        "WRITE_ONLY".equals(rcAccess), "READ_ONLY".equals(rcAccess)));
            }
        }

        // Generate the mapping class
        String packageName = mappingPackage;
        String recordQualifiedName = recordType.getQualifiedName().toString();

        // For nested records (e.g., Outer.Inner), compute the source-level name
        // that works in generated code: "Outer.Inner" (using dots, not $)
        String recordSourceName;
        if (!packageName.isEmpty() && recordQualifiedName.startsWith(packageName + ".")) {
            recordSourceName = recordQualifiedName.substring(packageName.length() + 1);
        } else {
            recordSourceName = recordQualifiedName;
        }
        // Mapping class name uses underscore-separated nesting: Outer_Inner_JqMapping
        String mappingClassName = recordSourceName.replace('.', '_') + "_JqMapping";
        String qualifiedMappingName = packageName.isEmpty() ? mappingClassName : packageName + "." + mappingClassName;

        String source = MappingCodeGenerator.generate(
                packageName, recordSourceName, recordQualifiedName, mappingClassName, components,
                recordAnyInfo);

        // Write the generated source file
        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedMappingName, recordType);
            try (PrintWriter writer = new PrintWriter(file.openWriter())) {
                writer.print(source);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write generated mapping: " + e.getMessage(), recordType);
            return null;
        }
        return mappingClassName;
    }

    /**
     * Process a single {@code @JqMapped} POJO class and generate its mapping class.
     * @return the simple mapping class name (e.g., "User_JqMapping"), or null on error
     */
    private String processClass(TypeElement classType) {
        // Resolve class-level @JqInclude and @JqNaming
        String classInclusion = resolveClassInclusion(classType);
        JqNaming.Strategy namingStrategy = resolveNamingStrategy(classType);
        String mappingPackage = processingEnv.getElementUtils().getPackageOf(classType)
                .getQualifiedName().toString();

        // Any-setter/getter first: any-getter-backed properties are suppressed (issue #88.1)
        AnyInfo pojoAnyInfo = resolveAnyInfo(classType);

        // Getter visibility per class (issue #89): AutoDetect NONE suppresses getX/isX
        boolean[] noGetters = jacksonGetterSuppression(classType);

        // Collect field metadata (declared fields only, skip static/synthetic)
        List<PropertyInfo> properties = new ArrayList<>();
        // Collect method names for getter/setter resolution (+ Elements for annotation reads)
        var methods = new java.util.HashSet<String>();
        var methodElements = new java.util.HashMap<String, Element>();
        for (Element enclosed : classType.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD) {
                methods.add(enclosed.getSimpleName().toString());
                methodElements.putIfAbsent(enclosed.getSimpleName().toString(), enclosed);
            }
        }

        for (Element enclosed : classType.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            VariableElement field = (VariableElement) enclosed;
            if (field.getModifiers().contains(Modifier.STATIC)) continue;

            String name = field.getSimpleName().toString();
            String typeName = field.asType().toString();
            boolean ignored = field.getAnnotation(JqIgnore.class) != null;

            // Apply naming strategy
            String jsonName = namingStrategy.transform(name);
            JqField jqField = field.getAnnotation(JqField.class);
            String jqExpr;
            if (jqField != null) {
                jqExpr = jqField.value();
                try {
                    JqProgram.compile(jqExpr);
                } catch (Exception e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Invalid jq expression in @JqField(\"" + jqExpr + "\"): " + e.getMessage(), field);
                    return null;
                }
            } else {
                // Bridge renames override the naming strategy, mirroring the
                // reflection path (issue #103)
                String renamed = bridgeRename(field);
                if (renamed != null) jsonName = renamed;
                jqExpr = fieldProgram(jsonName);
            }

            // Determine access strategy
            boolean isPublic = field.getModifiers().contains(Modifier.PUBLIC);
            boolean isFinal = field.getModifiers().contains(Modifier.FINAL);
            boolean isPrivate = field.getModifiers().contains(Modifier.PRIVATE);
            String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);

            // Getter: public field, getX(), isX() for boolean.
            // AutoDetect NONE suppresses getX/isX binding (issue #89) -> setAccessible fallback
            String getterName;
            if (isPublic) {
                getterName = null; // direct field access
            } else if (!noGetters[0] && methods.contains("get" + capitalized)) {
                getterName = "get" + capitalized;
            } else if (!noGetters[1]
                       && (typeName.equals("boolean") || typeName.equals("java.lang.Boolean"))
                       && methods.contains("is" + capitalized)) {
                getterName = "is" + capitalized;
            } else {
                getterName = null; // will use setAccessible at runtime
            }

            // Setter: public field, setX()
            String setterName;
            if (isPublic && !isFinal) {
                setterName = null; // direct field access
            } else if (methods.contains("set" + capitalized) && !isFinal) {
                setterName = "set" + capitalized;
            } else {
                setterName = null; // will use setAccessible at runtime, or read-only
            }

            // Resolve field-level @JqInclude (overrides class-level), else the
            // bridged @JsonInclude default (mirrors the reflection path)
            JqInclude fieldInclude = field.getAnnotation(JqInclude.class);
            String inclusion;
            if (fieldInclude != null) {
                inclusion = fieldInclude.value().name();
            } else {
                String bridged = bridgeInclusion(field);
                inclusion = bridged != null ? bridged : classInclusion;
            }

            String serName = (jqField != null) ? name : jsonName;
            String converterClass = resolveConverterClass(field);
            if (converterClass != null && !validateConverter(field, mappingPackage)) return null;

            if (!ignored && pojoAnyInfo.hasGetter()
                    && ((getterName != null && getterName.equals(pojoAnyInfo.getterName()))
                        || name.equals(anyGetterPropertyName(pojoAnyInfo.getterName())))) {
                ignored = true;
            }
            String fieldAccess = resolveJacksonAccess(field, classType);
            boolean skipSer = "WRITE_ONLY".equals(fieldAccess)
                    || hasJacksonJsonIgnore(methodElements.get(getterName));
            boolean skipDeser = "READ_ONLY".equals(fieldAccess)
                    || hasJacksonJsonIgnore(methodElements.get(setterName));
            properties.add(new PropertyInfo(name, serName, typeName, jqExpr, ignored, jqField != null,
                    getterName, setterName, isPublic, inclusion, converterClass,
                    isRecordType(field.asType()),
                    skipSer, skipDeser, isPrivate, isFinal));
        }

        // Generate the mapping class
        String packageName = mappingPackage;
        String classQualifiedName = classType.getQualifiedName().toString();

        String classSourceName;
        if (!packageName.isEmpty() && classQualifiedName.startsWith(packageName + ".")) {
            classSourceName = classQualifiedName.substring(packageName.length() + 1);
        } else {
            classSourceName = classQualifiedName;
        }
        String mappingClassName = classSourceName.replace('.', '_') + "_JqMapping";
        String qualifiedMappingName = packageName.isEmpty() ? mappingClassName : packageName + "." + mappingClassName;

        String source = MappingCodeGenerator.generateForClass(
                packageName, classSourceName, classQualifiedName, mappingClassName, properties,
                pojoAnyInfo);

        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedMappingName, classType);
            try (PrintWriter writer = new PrintWriter(file.openWriter())) {
                writer.print(source);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write generated mapping: " + e.getMessage(), classType);
            return null;
        }
        return mappingClassName;
    }

    /**
     * Generate a {@code JqMappingRegistry} class for a package that registers
     * all generated mappings in a single method call.
     */
    private void generateRegistry(String packageName, List<String> mappingClassNames) {
        String registryClassName = "JqMappingRegistry";
        String qualifiedName = packageName.isEmpty() ? registryClassName : packageName + "." + registryClassName;

        String source = MappingCodeGenerator.generateRegistry(packageName, mappingClassNames);

        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName);
            try (PrintWriter writer = new PrintWriter(file.openWriter())) {
                writer.print(source);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write generated registry: " + e.getMessage());
        }
    }

    /** Resolve class-level @JqInclude, defaulting to "ALWAYS". */
    private String resolveClassInclusion(TypeElement type) {
        JqInclude classInclude = type.getAnnotation(JqInclude.class);
        if (classInclude != null) return classInclude.value().name();
        // Bridged @JsonInclude default (mirrors the reflection path, issue #107.2)
        String bridged = bridgeInclusion(type);
        return bridged != null ? bridged : "ALWAYS";
    }

    /**
     * Bridged inclusion for a class or field: Jackson {@code @JsonInclude(value)}
     * maps 1:1 onto {@code JqInclude.Include} names; unmapped constants
     * ({@code NON_ABSENT}, {@code CUSTOM}, ...) mean "no opinion" (null),
     * mirroring the bridge. An absent {@code value} member (annotation default)
     * also yields null.
     */
    private static String bridgeInclusion(Element element) {
        for (var mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString()
                    .equals("com.fasterxml.jackson.annotation.JsonInclude")) {
                for (var entry : mirror.getElementValues().entrySet()) {
                    if (entry.getKey().getSimpleName().contentEquals("value")) {
                        return switch (entry.getValue().getValue().toString()) {
                            case "ALWAYS", "NON_NULL", "NON_EMPTY", "NON_DEFAULT" ->
                                entry.getValue().getValue().toString();
                            default -> null;
                        };
                    }
                }
            }
        }
        return null;
    }

    /**
     * Bridged inclusion for a record component. {@code @JsonInclude} targets
     * fields (not components), so fall back to the same-named field's mirrors
     * (same fallback as the other Jackson mirrors).
     */
    private static String bridgeInclusionForRecord(RecordComponentElement rc, TypeElement enclosing) {
        for (Element e : enclosing.getEnclosedElements()) {
            if (e.getKind() == ElementKind.FIELD && e.getSimpleName().contentEquals(rc.getSimpleName())) {
                String inclusion = bridgeInclusion(e);
                if (inclusion != null) return inclusion;
            }
        }
        return null;
    }

    /**
     * Read Jackson's {@code @JsonProperty(access)} without a Jackson dependency
     * (stringly-typed annotation mirrors). For record components, Jackson annotations
     * land on the field — fall back to the same-named field's mirrors.
     *
     * @return the access enum constant name ({@code WRITE_ONLY}/{@code READ_ONLY}/...), or null
     */
    private String resolveJacksonAccess(Element element, TypeElement enclosing) {
        String access = jacksonAccessFromMirrors(element.getAnnotationMirrors());
        if (access != null || !(element instanceof RecordComponentElement)) return access;
        for (Element e : enclosing.getEnclosedElements()) {
            if (e.getKind() == ElementKind.FIELD && e.getSimpleName().contentEquals(element.getSimpleName())) {
                access = jacksonAccessFromMirrors(e.getAnnotationMirrors());
                if (access != null) return access;
            }
        }
        return null;
    }

    /** Extract the {@code access} value from a {@code @JsonProperty} mirror, if present. */
    private static String jacksonAccessFromMirrors(
            java.util.List<? extends javax.lang.model.element.AnnotationMirror> mirrors) {
        for (javax.lang.model.element.AnnotationMirror m : mirrors) {
            if (m.getAnnotationType().toString().equals("com.fasterxml.jackson.annotation.JsonProperty")) {
                for (var entry : m.getElementValues().entrySet()) {
                    if (entry.getKey().getSimpleName().contentEquals("access")) {
                        return entry.getValue().getValue().toString();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Stringly-typed Jackson {@code @JsonAutoDetect} getter suppression (no Jackson dep).
     * Returns {@code {suppressGet, suppressIs}} for getter/isGetter visibility NONE.
     */
    private static boolean[] jacksonGetterSuppression(TypeElement type) {
        boolean[] result = {false, false};
        for (var mirror : type.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString()
                    .equals("com.fasterxml.jackson.annotation.JsonAutoDetect")) {
                for (var entry : mirror.getElementValues().entrySet()) {
                    String member = entry.getKey().getSimpleName().toString();
                    String value = entry.getValue().getValue().toString();
                    if (member.equals("getterVisibility") && value.equals("NONE")) result[0] = true;
                    if (member.equals("isGetterVisibility") && value.equals("NONE")) result[1] = true;
                }
            }
        }
        return result;
    }

    /**
     * Stringly-typed Jackson {@code @JsonIgnore} presence (no Jackson dep).
     * Respects explicit {@code value = false} (means NOT ignored).
     */
    private static boolean hasJacksonJsonIgnore(javax.lang.model.element.Element e) {
        if (e == null) return false;
        for (var mirror : e.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString()
                    .equals("com.fasterxml.jackson.annotation.JsonIgnore")) {
                for (var entry : mirror.getElementValues().entrySet()) {
                    if (entry.getKey().getSimpleName().contentEquals("value")) {
                        return Boolean.parseBoolean(entry.getValue().getValue().toString());
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve {@code @JqConverter(value)} via annotation mirrors (issue #100).
     * Reading {@code annotation.value()} directly throws
     * {@code MirroredTypeException} when the converter class is compiled in the
     * same round (it has no {@code Class} object yet) — the normal case for
     * custom union converters. The mirror's {@code TypeMirror} works for both
     * same-round and already-compiled converters; {@code toString()} yields the
     * qualified name used in generated {@code new <name>()} expressions.
     *
     * @return the converter qualified name, or null when absent
     */
     static String resolveConverterClass(Element element) {
        javax.lang.model.type.TypeMirror tm = converterTypeMirror(element);
        return tm != null ? tm.toString() : null;
    }

    /**
     * The {@code @JqConverter} value as a {@code TypeMirror} (null when
     * absent). Works for same-round classes that have no {@code Class} object
     * yet, where reading the annotation value directly would throw.
     */
    private static javax.lang.model.type.TypeMirror converterTypeMirror(Element element) {
        for (var mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString()
                    .equals("io.hyperfoil.tools.jjq.mapper.JqConverter")) {
                for (var entry : mirror.getElementValues().entrySet()) {
                    if (entry.getKey().getSimpleName().contentEquals("value")) {
                        Object value = entry.getValue().getValue();
                        if (value instanceof javax.lang.model.type.TypeMirror tm) {
                            return tm;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Fail the build with a named error when a {@code @JqConverter} class
     * cannot be instantiated in generated code (issue #110): not a concrete
     * class, a non-static nested class (no outer instance to construct with),
     * or no accessible no-arg constructor. Generated code shares the mapping's
     * package, so same-package non-private suffices while cross-package needs
     * public. A converter that cannot be constructed is a programming error;
     * failing at compile time beats the native runtime warning.
     *
     * @param annotated      the annotated field/component (error location)
     * @param mappingPackage the package of the generated mapping class
     * @return true when valid (an error is already reported otherwise)
     */
    private boolean validateConverter(Element annotated, String mappingPackage) {
        javax.lang.model.type.TypeMirror tm = converterTypeMirror(annotated);
        if (tm == null) return true;
        String name = tm.toString();
        Element element = processingEnv.getTypeUtils().asElement(tm);
        if (!(element instanceof TypeElement converter)
                || converter.getKind() != ElementKind.CLASS
                || converter.getModifiers().contains(Modifier.ABSTRACT)) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Converter " + name + " must be a concrete class", annotated);
            return false;
        }
        Element enclosing = converter.getEnclosingElement();
        if ((enclosing.getKind() == ElementKind.CLASS || enclosing.getKind() == ElementKind.RECORD)
                && !converter.getModifiers().contains(Modifier.STATIC)) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Converter " + name + " must be a top-level or static nested class", annotated);
            return false;
        }
        String converterPackage = processingEnv.getElementUtils().getPackageOf(converter)
                .getQualifiedName().toString();
        boolean samePackage = converterPackage.equals(mappingPackage);
        boolean hasAccessibleDefault = false;
        boolean hasDeclaredCtor = false;
        for (Element enclosed : converter.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.CONSTRUCTOR) continue;
            hasDeclaredCtor = true;
            var ctor = (javax.lang.model.element.ExecutableElement) enclosed;
            if (ctor.getParameters().isEmpty()
                    && isAccessibleFrom(ctor.getModifiers(), samePackage)) {
                hasAccessibleDefault = true;
                break;
            }
        }
        if (!hasDeclaredCtor) {
            // Implicit default constructor: same access as the class itself
            hasAccessibleDefault = isAccessibleFrom(converter.getModifiers(), samePackage);
        }
        if (!hasAccessibleDefault) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Converter " + name + " must have an accessible no-arg constructor", annotated);
            return false;
        }
        return true;
    }

    /** Public, or non-private within the same package. */
    private static boolean isAccessibleFrom(java.util.Set<Modifier> modifiers, boolean samePackage) {
        if (modifiers.contains(Modifier.PUBLIC)) return true;
        if (modifiers.contains(Modifier.PRIVATE)) return false;
        return samePackage;
    }

    /**
     * Bridge rename for a field: {@code @JsonProperty} first, then
     * {@code @JsonbProperty} (issue #103). Mirrors the bridge precedence of
     * the reflection path for the default bridge set.
     *
     * @return the wire name, or null when no bridge renames the field
     */
    private static String bridgeRename(Element element) {
        String name = renameFromMirrors(element.getAnnotationMirrors(),
                "com.fasterxml.jackson.annotation.JsonProperty");
        if (name != null) return name;
        return renameFromMirrors(element.getAnnotationMirrors(),
                "jakarta.json.bind.annotation.JsonbProperty");
    }

    /**
     * Bridge rename for a record component. Jackson/JSON-B annotations land on
     * the field rather than the component, so fall back to the same-named
     * field's mirrors (same fallback as {@link #resolveJacksonAccess}).
     */
    private static String bridgeRenameForRecord(RecordComponentElement rc, TypeElement enclosing) {
        String name = bridgeRename(rc);
        if (name != null) return name;
        for (Element e : enclosing.getEnclosedElements()) {
            if (e.getKind() == ElementKind.FIELD && e.getSimpleName().contentEquals(rc.getSimpleName())) {
                name = bridgeRename(e);
                if (name != null) return name;
            }
        }
        return null;
    }

    /**
     * Read a string-valued {@code value} member from one annotation type's
     * mirrors (stringly-typed: no framework dependency). Empty values mean
     * "no rename" (Jackson's {@code USE_DEFAULT_NAME} is {@code ""}).
     */
    private static String renameFromMirrors(
            java.util.List<? extends javax.lang.model.element.AnnotationMirror> mirrors,
            String annotationName) {
        for (javax.lang.model.element.AnnotationMirror m : mirrors) {
            if (m.getAnnotationType().toString().equals(annotationName)) {
                for (var entry : m.getElementValues().entrySet()) {
                    if (entry.getKey().getSimpleName().contentEquals("value")) {
                        Object value = entry.getValue().getValue();
                        if (value instanceof String s && !s.isEmpty()) return s;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Extraction program for a JSON field name: {@code .name} for plain ASCII
     * identifiers, {@code ."..."} (escaped) otherwise — {@code .host-paths}
     * would parse as a subtraction (issue #103). Both forms compile to
     * {@code DOT_FIELD}, so the {@code FIELD_ACCESS} fast path is preserved.
     */
    static String fieldProgram(String jsonName) {
        if (isPlainIdentifier(jsonName)) return "." + jsonName;
        return ".\"" + jsonName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** ASCII identifier check; anything else (dashes, spaces, non-ASCII) needs quoting. */
    private static boolean isPlainIdentifier(String s) {
        if (s.isEmpty()) return false;
        char c0 = s.charAt(0);
        if (c0 != '_' && (c0 < 'a' || c0 > 'z') && (c0 < 'A' || c0 > 'Z')) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '_' && (c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }

    /** Resolve class-level @JqNaming, defaulting to IDENTITY. */
    private JqNaming.Strategy resolveNamingStrategy(TypeElement type) {
        JqNaming naming = type.getAnnotation(JqNaming.class);
        return naming != null ? naming.value() : JqNaming.Strategy.IDENTITY;
    }

    /** Metadata for a single record component. */
    record ComponentInfo(String name, String jsonName, String typeName, String jqExpr, boolean ignored, boolean hasJqField,
                         String inclusion, String converterClass, boolean nestedRecord,
                         boolean skipSerialize, boolean skipDeserialize) {}

    /** Metadata for a single POJO field. */
    record PropertyInfo(String name, String jsonName, String typeName, String jqExpr, boolean ignored, boolean hasJqField,
                        String getterName, String setterName, boolean isPublicField, String inclusion,
                        String converterClass, boolean nestedRecord,
                        boolean skipSerialize, boolean skipDeserialize,
                        boolean privateField, boolean finalField) {}

    /**
     * Any-setter/any-getter methods discovered on a mapped type (issue #87).
     * Null names mean absent. {@code setterTakesJqValue} selects the value
     * conversion (raw {@code JqValue} vs {@code toJavaObject()}).
     */
    record AnyInfo(String setterName, boolean setterTakesJqValue, String getterName) {
        static final AnyInfo NONE = new AnyInfo(null, false, null);

        boolean hasSetter() { return setterName != null; }
        boolean hasGetter() { return getterName != null; }
    }

    /**
     * Implied property name of an any-getter method: strip get/is prefix, decapitalize.
     * Null when the name carries no prefix (caller falls back to exact-name match).
     */
    static String anyGetterPropertyName(String methodName) {
        String stripped = null;
        if (methodName.startsWith("get") && methodName.length() > 3) {
            stripped = methodName.substring(3);
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            stripped = methodName.substring(2);
        }
        if (stripped == null || stripped.isEmpty()) return null;
        return Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
    }

    /**
     * Discover {@code @JqAnySetter}/{@code @JqAnyGetter} methods on a mapped type.
     * Bridge method-form marks (Jackson {@code @JsonAnySetter}/{@code @JsonAnyGetter})
     * count the same as native ones, mirroring the runtime bridges (issue #105):
     * without this the any-getter is hijacked as a bean getter (double emission)
     * while the write fallback references an undeclared field constant (fatal).
     * Reports compile errors for malformed signatures (mirrors the runtime validation).
     *
     * @return the discovered methods, or {@link AnyInfo#NONE} when neither is present
     */
    private AnyInfo resolveAnyInfo(TypeElement type) {
        String setterName = null;
        boolean setterTakesJqValue = false;
        String getterName = null;
        // Walk the superclass chain, declared methods first: the runtime
        // discovers any-methods via getMethods(), which includes inherited
        // ones, so a base-declared any-setter must resolve too (issue #107.1).
        // First mark per role wins.
        for (TypeElement t = type; t != null; t = superclassOf(t)) {
            for (Element enclosed : t.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD) continue;
                var method = (javax.lang.model.element.ExecutableElement) enclosed;
                if (setterName == null
                        && (enclosed.getAnnotation(JqAnySetter.class) != null
                            || hasMirrorAnnotation(enclosed,
                                "com.fasterxml.jackson.annotation.JsonAnySetter"))) {
                var params = method.getParameters();
                if (!method.getModifiers().contains(Modifier.STATIC)
                        && params.size() == 2
                        && params.get(0).asType().toString().equals("java.lang.String")
                        && (params.get(1).asType().toString().equals("io.hyperfoil.tools.jjq.value.JqValue")
                            || params.get(1).asType().toString().equals("java.lang.Object"))
                        && method.getReturnType().toString().equals("void")) {
                    setterName = method.getSimpleName().toString();
                    setterTakesJqValue = params.get(1).asType().toString()
                            .equals("io.hyperfoil.tools.jjq.value.JqValue");
                } else {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Any-setter must be a non-static void (String, JqValue|Object) method",
                            enclosed);
                    return AnyInfo.NONE;
                }
            }
            if (getterName == null
                    && (enclosed.getAnnotation(JqAnyGetter.class) != null
                        || hasMirrorAnnotation(enclosed,
                            "com.fasterxml.jackson.annotation.JsonAnyGetter"))) {
                String rt = method.getReturnType().toString();
                if (!method.getModifiers().contains(Modifier.STATIC)
                        && method.getParameters().isEmpty()
                        && (rt.startsWith("java.util.Map")
                            || rt.equals("io.hyperfoil.tools.jjq.value.JqObject"))) {
                    getterName = method.getSimpleName().toString();
                } else {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Any-getter must be a non-static no-arg method returning Map<String, ?> or JqObject",
                            enclosed);
                    return AnyInfo.NONE;
                }
            }
        }
        }
        if (setterName == null && getterName == null) return AnyInfo.NONE;
        return new AnyInfo(setterName, setterTakesJqValue, getterName);
    }

    /**
     * The superclass as a {@code TypeElement}, or null at the top of the
     * chain ({@code java.lang.Object} has no methods of interest).
     * Interfaces declare no superclass for this walk (boundary: an any-method
     * default on an interface stays invisible, unlike the runtime's
     * {@code getMethods()} view).
     */
    private static TypeElement superclassOf(TypeElement type) {
        javax.lang.model.type.TypeMirror sup = type.getSuperclass();
        if (sup.getKind() != javax.lang.model.type.TypeKind.DECLARED) return null;
        Element element = ((javax.lang.model.type.DeclaredType) sup).asElement();
        if (!(element instanceof TypeElement superType)
                || superType.getQualifiedName().contentEquals("java.lang.Object")) {
            return null;
        }
        return superType;
    }

    /** Presence of a framework annotation by mirror (no framework dependency). */
    private static boolean hasMirrorAnnotation(Element element, String annotationName) {
        for (var mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals(annotationName)) return true;
        }
        return false;
    }

    /**
     * True if the type is a record. Only records recurse into
     * {@code mapper.appendJson} in generated serializers: the canonical
     * constructor is guaranteed by the language and records are final,
     * so the runtime value is always mappable. Every other shape
     * (POJO, enum, container, scalar) keeps the TypeConverter fallback,
     * which alone handles unmappable classes via fromJavaObject.
     */
    private boolean isRecordType(javax.lang.model.type.TypeMirror type) {
        javax.lang.model.element.Element element =
                processingEnv.getTypeUtils().asElement(type);
        return element != null
                && element.getKind() == javax.lang.model.element.ElementKind.RECORD;
    }
}
