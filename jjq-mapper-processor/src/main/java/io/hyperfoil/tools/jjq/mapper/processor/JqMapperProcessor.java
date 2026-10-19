package io.hyperfoil.tools.jjq.mapper.processor;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.mapper.JqAccess;
import io.hyperfoil.tools.jjq.mapper.JqAdapter;
import io.hyperfoil.tools.jjq.mapper.JqAlias;
import io.hyperfoil.tools.jjq.mapper.JqAnyGetter;
import io.hyperfoil.tools.jjq.mapper.JqAnySetter;
import io.hyperfoil.tools.jjq.mapper.JqBuilder;
import io.hyperfoil.tools.jjq.mapper.JqCreator;
import io.hyperfoil.tools.jjq.mapper.JqField;
import io.hyperfoil.tools.jjq.mapper.JqFormat;
import io.hyperfoil.tools.jjq.mapper.JqIgnore;
import io.hyperfoil.tools.jjq.mapper.JqInclude;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqName;
import io.hyperfoil.tools.jjq.mapper.JqNaming;
import io.hyperfoil.tools.jjq.mapper.JqOrder;
import io.hyperfoil.tools.jjq.mapper.JqRequired;
import io.hyperfoil.tools.jjq.mapper.JqVisibility;

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
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.SimpleTypeVisitor9;
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

    /**
     * Renders a {@link TypeMirror} as a source-level type name without
     * annotations. {@code TypeMirror.toString()} includes type-use
     * annotations (e.g. bean-validation annotations on record components),
     * which is invalid Java in generated code.
     *
     * @param mirror the type to render
     * @return the annotation-free type name
     */
    static String unannotatedTypeName(TypeMirror mirror) {
        return mirror.accept(new SimpleTypeVisitor9<String, Void>() {
            @Override
            public String visitDeclared(DeclaredType t, Void unused) {
                StringBuilder sb = new StringBuilder(
                        ((TypeElement) t.asElement()).getQualifiedName().toString());
                if (!t.getTypeArguments().isEmpty()) {
                    sb.append('<');
                    boolean first = true;
                    for (TypeMirror arg : t.getTypeArguments()) {
                        if (!first) sb.append(", ");
                        first = false;
                        sb.append(arg.accept(this, null));
                    }
                    sb.append('>');
                }
                return sb.toString();
            }

            @Override
            public String visitArray(ArrayType t, Void unused) {
                return t.getComponentType().accept(this, null) + "[]";
            }

            @Override
            public String visitPrimitive(PrimitiveType t, Void unused) {
                // toString() would carry type-use annotations; map by kind instead.
                return switch (t.getKind()) {
                    case BOOLEAN -> "boolean";
                    case BYTE -> "byte";
                    case SHORT -> "short";
                    case INT -> "int";
                    case LONG -> "long";
                    case CHAR -> "char";
                    case FLOAT -> "float";
                    case DOUBLE -> "double";
                    default -> t.toString();
                };
            }

            @Override
            public String visitTypeVariable(TypeVariable t, Void unused) {
                return t.asElement().getSimpleName().toString();
            }

            @Override
            public String visitWildcard(WildcardType t, Void unused) {
                if (t.getExtendsBound() != null) {
                    return "? extends " + t.getExtendsBound().accept(this, null);
                }
                if (t.getSuperBound() != null) {
                    return "? super " + t.getSuperBound().accept(this, null);
                }
                return "?";
            }

            @Override
            public String visitError(ErrorType t, Void unused) {
                return t.toString();
            }

            @Override
            protected String defaultAction(TypeMirror t, Void unused) {
                return t.toString();
            }
        }, null);
    }

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
        // Required wire names in declaration order (absent keys fail binding).
        List<String> componentRequired = new ArrayList<>();
        // Shared CONV_ field names per converter class (issue #116)
        var convFields = new java.util.LinkedHashMap<String, String>();
        var usedConvNames = new java.util.HashSet<String>();
        for (Element enclosed : recordType.getEnclosedElements()) {
            if (enclosed instanceof RecordComponentElement rc) {
                String name = rc.getSimpleName().toString();
                String typeName = unannotatedTypeName(rc.asType());
                boolean ignored = rc.getAnnotation(JqIgnore.class) != null;
                if (!ignored && recordAnyInfo.hasGetter()
                        && (name.equals(recordAnyInfo.getterName())
                            || name.equals(anyGetterPropertyName(recordAnyInfo.getterName())))) {
                    ignored = true;
                }

                // Apply naming strategy for default expression
                String jsonName = namingStrategy.transform(name);
                JqField jqField = rc.getAnnotation(JqField.class);
                // Direct read is safe: JqName is compiled, and String values never mirror
                JqName jqNameAnn = rc.getAnnotation(JqName.class);
                String jqName = jqNameAnn != null && !jqNameAnn.value().isEmpty()
                        ? jqNameAnn.value() : null;
                if (jqField != null && jqName != null) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "@JqName and @JqField conflict on '" + name
                                    + "': rename the key or query it, not both",
                            rc);
                    return null;
                }
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
                    // Explicit @JqName wins over naming strategy and bridges;
                    // bridge renames apply only without one (issue #103)
                    if (jqName != null) {
                        jsonName = jqName;
                    } else {
                        String renamed = bridgeRenameForRecord(rc, recordType);
                        if (renamed != null) jsonName = renamed;
                    }
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
                String converterField = converterClass != null && !ignored
                        ? converterFieldFor(convFields, usedConvNames, converterClass)
                        : null;

                // Native @JqAccess wins over the bridged access (issue #113)
                String rcAccess = nativeAccess(rc);
                if (rcAccess == null) rcAccess = resolveJacksonAccess(rc, recordType);
                AdapterInfo adapter = resolveAdapterInfo(rc, rc.asType(), mappingPackage);
                if (adapter == null && rc.getAnnotation(JqAdapter.class) != null) return null;
                if (!ignored && (rc.getAnnotation(JqRequired.class) != null
                        || bridgeRequired(rc, recordType))) {
                    componentRequired.add(jsonName);
                }
                java.util.List<String> compAliases = resolveElementAliases(rc);
                if (!compAliases.isEmpty() && jqField != null) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "@JqAlias and @JqField conflict on '" + name
                                    + "': programs do not read keys",
                            rc);
                    return null;
                }
                JqFormat.Spec compFormat = resolveFormatSpec(rc, recordType, typeName,
                        "'" + name + "' of " + recordType.getQualifiedName());
                Boolean compStringShape = resolveShapeFlag(rc, recordType, typeName,
                        rc.asType(), "'" + name + "' of " + recordType.getQualifiedName());
                if (compStringShape == null) return null;
                if (compFormat == null && hasPatternMarker(rc)
                        && !isNumberTypeName(typeName) && !isBooleanTypeName(typeName)) return null;
                components.add(new ComponentInfo(name, serName, typeName, jqExpr, ignored, jqField != null, inclusion, converterClass,
                        isRecordType(rc.asType()),
                        "WRITE_ONLY".equals(rcAccess), "READ_ONLY".equals(rcAccess), adapter, converterField,
                        compAliases, compFormat, compStringShape));
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

        CreatorInfo recordCreator = resolveCreatorInfo(recordType, recordSourceName, packageName,
                convFields, usedConvNames);
        if (recordCreator == null && hasCreatorMarker(recordType)) return null;
        if (hasBuilderMarker(recordType)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Builder configuration on record " + recordType.getQualifiedName()
                            + " needs a POJO — records construct through their canonical"
                            + " constructor or a @JqCreator creator",
                    recordType);
            return null;
        }
        List<String> recordRequired = resolveRequiredWires(recordType, components, componentRequired,
                recordCreator);
        if (recordRequired == null) return null;
        JqOrder.Spec recordOrder = orderSpecFor(recordType);
        if (!validateOrderSpec(recordType, components, ComponentInfo::jsonName, recordOrder)) return null;
        String source = MappingCodeGenerator.generate(
                packageName, recordSourceName, recordQualifiedName, mappingClassName, components,
                recordAnyInfo, recordCreator, recordRequired, recordOrder);

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

        // Getter visibility per class (issue #89): AutoDetect NONE suppresses getX/isX.
        // Native @JqVisibility NONE ORs in on top; bridges still apply under ANY (issue #114).
        boolean[] noGetters = jacksonGetterSuppression(classType);
        JqVisibility visibility = classType.getAnnotation(JqVisibility.class);
        if (visibility != null) {
            if (visibility.getters() == JqVisibility.Visibility.NONE) noGetters[0] = true;
            if (visibility.isGetters() == JqVisibility.Visibility.NONE) noGetters[1] = true;
        }

        // Collect field metadata (declared fields only, skip static/synthetic)
        List<PropertyInfo> properties = new ArrayList<>();
        // Required wire names in declaration order (absent keys fail binding).
        List<String> propertyRequired = new ArrayList<>();
        // Shared CONV_ field names per converter class (issue #116)
        var convFields = new java.util.LinkedHashMap<String, String>();
        var usedConvNames = new java.util.HashSet<String>();
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
            String typeName = unannotatedTypeName(field.asType());
            boolean ignored = field.getAnnotation(JqIgnore.class) != null;

            // Apply naming strategy
            String jsonName = namingStrategy.transform(name);
            JqField jqField = field.getAnnotation(JqField.class);
            JqName jqNameAnn = field.getAnnotation(JqName.class);
            String jqName = jqNameAnn != null && !jqNameAnn.value().isEmpty()
                    ? jqNameAnn.value() : null;
            if (jqField != null && jqName != null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@JqName and @JqField conflict on '" + name
                                + "': rename the key or query it, not both",
                        field);
                return null;
            }
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
                // Explicit @JqName wins over naming strategy and bridges;
                // bridge renames apply only without one
                if (jqName != null) {
                    jsonName = jqName;
                } else {
                    String renamed = bridgeRename(field);
                    if (renamed != null) jsonName = renamed;
                }
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
            // Native @JqAccess wins over the bridged access (issue #113)
            String fieldAccess = nativeAccess(field);
            if (fieldAccess == null) fieldAccess = resolveJacksonAccess(field, classType);
            boolean skipSer = "WRITE_ONLY".equals(fieldAccess)
                    || hasJacksonJsonIgnore(methodElements.get(getterName));
            boolean skipDeser = "READ_ONLY".equals(fieldAccess)
                    || hasJacksonJsonIgnore(methodElements.get(setterName));
            AdapterInfo adapter = resolveAdapterInfo(field, field.asType(), mappingPackage);
            if (adapter == null && field.getAnnotation(JqAdapter.class) != null) return null;
            String converterField = converterClass != null && !ignored
                    ? converterFieldFor(convFields, usedConvNames, converterClass)
                    : null;
            if (!ignored && (field.getAnnotation(JqRequired.class) != null
                    || bridgeRequired(field, null))) {
                propertyRequired.add(jsonName);
            }
            java.util.List<String> propAliases = resolveElementAliases(field);
            if (!propAliases.isEmpty() && jqField != null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@JqAlias and @JqField conflict on '" + name
                                + "': programs do not read keys",
                        field);
                return null;
            }
            JqFormat.Spec propFormat = resolveFormatSpec(field, null, typeName,
                    "'" + name + "' of " + classType.getQualifiedName());
            Boolean propStringShape = resolveShapeFlag(field, null, typeName,
                    field.asType(), "'" + name + "' of " + classType.getQualifiedName());
            if (propStringShape == null) return null;
            if (propFormat == null && hasPatternMarker(field)
                    && !isNumberTypeName(typeName) && !isBooleanTypeName(typeName)) return null;
            properties.add(new PropertyInfo(name, serName, typeName, jqExpr, ignored, jqField != null,
                    getterName, setterName, isPublic, inclusion, converterClass,
                    isRecordType(field.asType()),
                    skipSer, skipDeser, isPrivate, isFinal, adapter, converterField, propAliases,
                    propFormat, propStringShape));
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

        CreatorInfo pojoCreator = resolveCreatorInfo(classType, classSourceName, packageName,
                convFields, usedConvNames);
        if (pojoCreator == null && hasCreatorMarker(classType)) return null;
        BuilderInfo pojoBuilder = resolveBuilderInfo(classType, classSourceName, properties);
        if (pojoBuilder == null && hasBuilderMarker(classType)) return null;
        if (pojoCreator != null && pojoBuilder != null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    classType.getQualifiedName()
                            + " declares both a creator and a builder: pick one construction strategy",
                    classType);
            return null;
        }
        List<String> pojoRequired = resolvePojoRequiredWires(classType, properties, propertyRequired,
                pojoCreator);
        if (pojoRequired == null) return null;
        JqOrder.Spec pojoOrder = orderSpecFor(classType);
        if (!validateOrderSpec(classType, properties, PropertyInfo::jsonName, pojoOrder)) return null;
        String source = MappingCodeGenerator.generateForClass(
                packageName, classSourceName, classQualifiedName, mappingClassName, properties,
                pojoAnyInfo, pojoCreator, pojoBuilder, pojoRequired, pojoOrder);

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
     * Native {@code @JqAccess} direction (null when absent). Enum names align
     * with Jackson's, so downstream comparisons work unchanged (issue #113).
     */
    private static String nativeAccess(Element element) {
        JqAccess access = element.getAnnotation(JqAccess.class);
        return access != null ? access.value().name() : null;
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
     * Resolve {@code @JqAdapter} policy methods on the field's declared type
     * for direct static emission (issue #120). Returns null when absent;
     * malformed adapters report a named error (the caller aborts when the
     * annotation is present but no descriptor comes back). Mirrors the runtime
     * lookup order: {@code (JqValue)} before {@code (String)} for
     * {@code from}, instance before static for {@code to}.
     *
     * @param annotated      the annotated field/component (error location)
     * @param fieldType      the declared field type (policy methods resolve against it)
     * @param mappingPackage the package of the generated mapping class (access rules)
     */
    private AdapterInfo resolveAdapterInfo(Element annotated,
            javax.lang.model.type.TypeMirror fieldType, String mappingPackage) {
        JqAdapter adapter = annotated.getAnnotation(JqAdapter.class);
        if (adapter == null) return null;
        String where = "'" + annotated.getSimpleName() + "'";
        if (hasMirrorAnnotation(annotated, "io.hyperfoil.tools.jjq.mapper.JqConverter")) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "@JqAdapter and @JqConverter conflict on " + where
                            + ": delegate statically or convert, not both",
                    annotated);
            return null;
        }
        if (adapter.from().isEmpty() || adapter.to().isEmpty()) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "@JqAdapter on " + where + " must name both from and to methods",
                    annotated);
            return null;
        }
        Element ownerElement = processingEnv.getTypeUtils().asElement(fieldType);
        if (!(ownerElement instanceof TypeElement owner)
                || (owner.getKind() != ElementKind.CLASS
                    && owner.getKind() != ElementKind.RECORD
                    && owner.getKind() != ElementKind.ENUM)) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "@JqAdapter on " + where + " needs a reference-typed field of a concrete"
                            + " class, record, or enum (adapter methods resolve against "
                            + fieldType + "); use @JqConverter for other shapes",
                    annotated);
            return null;
        }
        String ownerPackage = processingEnv.getElementUtils().getPackageOf(owner)
                .getQualifiedName().toString();
        boolean samePackage = ownerPackage.equals(mappingPackage);
        javax.lang.model.element.ExecutableElement fromJq = null;
        javax.lang.model.element.ExecutableElement fromString = null;
        javax.lang.model.element.ExecutableElement toInstance = null;
        javax.lang.model.element.ExecutableElement toStatic = null;
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            var method = (javax.lang.model.element.ExecutableElement) enclosed;
            if (method.getSimpleName().contentEquals(adapter.from())
                    && method.getModifiers().contains(Modifier.STATIC)
                    && method.getParameters().size() == 1
                    && method.getReturnType().getKind()
                        != javax.lang.model.type.TypeKind.VOID) {
                String param = unannotatedTypeName(method.getParameters().get(0).asType());
                if (param.equals("io.hyperfoil.tools.jjq.value.JqValue") && fromJq == null) {
                    fromJq = method;
                } else if (param.equals("java.lang.String") && fromString == null) {
                    fromString = method;
                }
            }
            if (method.getSimpleName().contentEquals(adapter.to())) {
                if (!method.getModifiers().contains(Modifier.STATIC)
                        && method.getParameters().isEmpty()
                        && method.getReturnType().getKind()
                            != javax.lang.model.type.TypeKind.VOID
                        && toInstance == null) {
                    toInstance = method;
                } else if (method.getModifiers().contains(Modifier.STATIC)
                        && method.getParameters().size() == 1 && toStatic == null) {
                    toStatic = method;
                }
            }
        }
        if (fromJq == null && fromString == null) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "@JqAdapter on " + where + ": no suitable static '" + adapter.from()
                            + "' on " + owner.getQualifiedName()
                            + " (static (JqValue)->T or (String)->T)",
                    annotated);
            return null;
        }
        javax.lang.model.element.ExecutableElement from = fromJq != null ? fromJq : fromString;
        if (!isAssignableBoxed(from.getReturnType(), fieldType)) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "@JqAdapter on " + where + ": '" + adapter.from() + "' returns "
                            + from.getReturnType() + ", not assignable to " + fieldType,
                    annotated);
            return null;
        }
        javax.lang.model.element.ExecutableElement to = toInstance != null ? toInstance : toStatic;
        if (to == null) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "@JqAdapter on " + where + ": no suitable '" + adapter.to()
                            + "' on " + owner.getQualifiedName()
                            + " (instance ()->JqValue/String or static (T)->JqValue/String)",
                    annotated);
            return null;
        }
        String toReturn = unannotatedTypeName(to.getReturnType());
        boolean toStringForm;
        if (toReturn.equals("java.lang.String")) {
            toStringForm = true;
        } else if (isJqValueType(to.getReturnType())) {
            toStringForm = false;
        } else {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "@JqAdapter on " + where + ": '" + adapter.to()
                            + "' must return JqValue or String, not " + toReturn,
                    annotated);
            return null;
        }
        if (to == toStatic && !isAssignableBoxed(fieldType,
                to.getParameters().get(0).asType())) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "@JqAdapter on " + where + ": '" + adapter.to() + "' takes "
                            + to.getParameters().get(0).asType()
                            + ", not assignable from " + fieldType,
                    annotated);
            return null;
        }
        for (javax.lang.model.element.ExecutableElement m : new javax.lang.model.element.ExecutableElement[]{from, to}) {
            if (!isAccessibleFrom(m.getModifiers(), samePackage)) {
                processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                        "@JqAdapter on " + where + ": '" + m.getSimpleName()
                                + "' must be public"
                                + (samePackage ? "" : " (or non-private in " + mappingPackage + ")"),
                        annotated);
                return null;
            }
        }
        return new AdapterInfo(from.getSimpleName().toString(), from == fromString,
                to.getSimpleName().toString(), to == toStatic, toStringForm);
    }

    /** Assignability with a boxed-name fallback (Types.isAssignable may not box). */
    private boolean isAssignableBoxed(javax.lang.model.type.TypeMirror from,
            javax.lang.model.type.TypeMirror to) {
        if (processingEnv.getTypeUtils().isAssignable(from, to)) return true;
        return boxedName(from.toString()).equals(boxedName(to.toString()));
    }

    /** Normalize a type name for boxing-tolerant comparison. */
    private static String boxedName(String name) {
        return switch (name) {
            case "boolean" -> "java.lang.Boolean";
            case "byte" -> "java.lang.Byte";
            case "short" -> "java.lang.Short";
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "float" -> "java.lang.Float";
            case "double" -> "java.lang.Double";
            case "char" -> "java.lang.Character";
            default -> name;
        };
    }

    /** True when the type is JqValue or a subtype of it. */
    private boolean isJqValueType(javax.lang.model.type.TypeMirror type) {
        TypeElement jqValue =
                processingEnv.getElementUtils().getTypeElement("io.hyperfoil.tools.jjq.value.JqValue");
        return jqValue != null && processingEnv.getTypeUtils().isAssignable(type, jqValue.asType());
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
     * Bridge-required check for an element: Jackson {@code @JsonProperty}
     * with {@code required = true}, read off mirrors (stringly-typed).
     * Record components fall back to the same-named field, mirroring
     * {@link #bridgeRenameForRecord} (a null owner skips the fallback).
     */
    private static boolean bridgeRequired(Element element, TypeElement enclosing) {
        for (var mirror : element.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().toString()
                    .equals("com.fasterxml.jackson.annotation.JsonProperty")) continue;
            for (var entry : mirror.getElementValues().entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals("required")
                        && Boolean.TRUE.equals(entry.getValue().getValue())) {
                    return true;
                }
            }
        }
        if (enclosing != null && element instanceof RecordComponentElement rc) {
            for (Element e : enclosing.getEnclosedElements()) {
                if (e.getKind() == ElementKind.FIELD && e.getSimpleName().contentEquals(rc.getSimpleName())) {
                    if (bridgeRequired(e, null)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Aliases for an element: native {@code @JqAlias} first, then the bridge
     * {@code @JsonAlias} mirror. Empty entries dropped. Never null.
     */
    private static java.util.List<String> resolveElementAliases(Element element) {
        var aliases = new ArrayList<String>();
        JqAlias nativeAnn = element.getAnnotation(JqAlias.class);
        if (nativeAnn != null) {
            for (String alias : nativeAnn.value()) {
                if (!alias.isEmpty() && !aliases.contains(alias)) aliases.add(alias);
            }
        }
        for (var mirror : element.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().toString()
                    .equals("com.fasterxml.jackson.annotation.JsonAlias")) continue;
            for (var entry : mirror.getElementValues().entrySet()) {
                if (!entry.getKey().getSimpleName().contentEquals("value")) continue;
                Object value = entry.getValue().getValue();
                if (value instanceof java.util.List<?> items) {
                    for (Object item : items) {
                        Object itemValue = item instanceof javax.lang.model.element.AnnotationValue av
                                ? av.getValue() : item;
                        if (itemValue instanceof String s && !s.isEmpty() && !aliases.contains(s)) {
                            aliases.add(s);
                        }
                    }
                }
            }
        }
        return aliases;
    }

    /**
     * Resolve a custom string format for a date/time element: native
     * {@code @JqFormat} first, then the bridge {@code @JsonFormat} mirror
     * (element plus same-named-field fallback for record components,
     * mirroring {@link #bridgeRenameForRecord}). Null when default ISO
     * handling applies. Gaps report compile errors (the caller bails when a
     * marker is present but no spec comes back).
     */
    private JqFormat.Spec resolveFormatSpec(Element element, TypeElement enclosing, String typeName,
                                            String fieldDesc) {
        String pattern = null;
        String timezone = "";
        String locale = "";
        boolean fromNative = false;
        JqFormat nativeAnn = element.getAnnotation(JqFormat.class);
        if (nativeAnn != null && !nativeAnn.pattern().isEmpty()) {
            pattern = nativeAnn.pattern();
            timezone = nativeAnn.timezone();
            locale = nativeAnn.locale();
            fromNative = true;
        } else {
            String bridgedPattern = null;
            String bridgedTimezone = "";
            String bridgedLocale = "";
            for (Element source : formatSources(element, enclosing)) {
                String[] bridged = bridgeFormat(source);
                if (bridged != null) {
                    bridgedPattern = bridged[0];
                    bridgedTimezone = bridged[1];
                    bridgedLocale = bridged[2];
                    break;
                }
            }
            if (bridgedPattern != null) {
                pattern = bridgedPattern;
                timezone = bridgedTimezone;
                locale = bridgedLocale;
            }
        }
        if (pattern == null || pattern.isEmpty()) return null;
        if (!isTemporalDateName(typeName)) {
            if (!fromNative && (isNumberTypeName(typeName) || isBooleanTypeName(typeName))) {
                // Moot: Jackson ignores DecimalFormat patterns on numbers
                // and booleans — drop the pattern, keep the shape (if any).
                return null;
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@JqFormat on '" + fieldDesc
                            + "': patterns need a date/time type (Instant, LocalDate/Time/DateTime,"
                            + " OffsetDateTime, ZonedDateTime)",
                    element);
            return null;
        }
        try {
            io.hyperfoil.tools.jjq.mapper.TypeConverter.formatterFor(pattern, timezone, locale);
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@JqFormat on '" + fieldDesc + "': invalid pattern or zone (" + e.getMessage() + ")",
                    element);
            return null;
        }
        return new JqFormat.Spec(pattern, timezone, locale);
    }

    /** True for the six date/time types supporting custom patterns. */
    private static boolean isTemporalDateName(String typeName) {
        return typeName.equals("java.time.Instant")
                || typeName.equals("java.time.LocalDate")
                || typeName.equals("java.time.LocalTime")
                || typeName.equals("java.time.LocalDateTime")
                || typeName.equals("java.time.OffsetDateTime")
                || typeName.equals("java.time.ZonedDateTime");
    }

    /** True for the numeric type names supporting string wire shapes. */
    private static boolean isNumberTypeName(String typeName) {
        return typeName.equals("byte") || typeName.equals("java.lang.Byte")
                || typeName.equals("short") || typeName.equals("java.lang.Short")
                || typeName.equals("int") || typeName.equals("java.lang.Integer")
                || typeName.equals("long") || typeName.equals("java.lang.Long")
                || typeName.equals("float") || typeName.equals("java.lang.Float")
                || typeName.equals("double") || typeName.equals("java.lang.Double")
                || typeName.equals("java.math.BigInteger")
                || typeName.equals("java.math.BigDecimal");
    }

    /** True for boolean type names (string wire shapes quote them). */
    private static boolean isBooleanTypeName(String typeName) {
        return typeName.equals("boolean") || typeName.equals("java.lang.Boolean");
    }

    /**
     * Bridge annotation sources for an element: the element itself plus the
     * same-named field for record components (Jackson/JSON-B annotations
     * target fields, not components).
     */
    private static Element[] formatSources(Element element, TypeElement enclosing) {
        Element fallback = element;
        if (enclosing != null && element instanceof RecordComponentElement rc) {
            fallback = null;
            for (Element e : enclosing.getEnclosedElements()) {
                if (e.getKind() == ElementKind.FIELD
                        && e.getSimpleName().contentEquals(rc.getSimpleName())) {
                    fallback = e;
                    break;
                }
            }
        }
        return fallback != null && fallback != element
                ? new Element[]{element, fallback} : new Element[]{element};
    }

    /**
     * String-shape flag for an element: native {@code @JqFormat(shape)}
     * first, then the bridged {@code @JsonFormat} shape. True only for
     * numbers and booleans with {@code STRING} (mirrors the runtime
     * {@code resolveShape} — codegen would otherwise misrender). Null when
     * an invalid combination reports a compile error (the caller bails).
     */
    private Boolean resolveShapeFlag(Element element, TypeElement enclosing, String typeName,
                                     javax.lang.model.type.TypeMirror type, String fieldDesc) {
        String shape = "ANY";
        JqFormat nativeAnn = element.getAnnotation(JqFormat.class);
        if (nativeAnn != null && !nativeAnn.shape().name().equals("ANY")) {
            shape = nativeAnn.shape().name();
        } else {
            for (Element source : formatSources(element, enclosing)) {
                String[] bridged = bridgeFormat(source);
                if (bridged != null) {
                    shape = bridged[3];
                    break;
                }
            }
        }
        if (shape.equals("ANY")) return Boolean.FALSE;
        if (typeName.startsWith("java.util.Optional")) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@JqFormat on '" + fieldDesc + "': shape " + shape
                            + " is not supported on Optional (bind the inner type directly)",
                    element);
            return null;
        }
        if (shape.equals("STRING")) {
            return (isNumberTypeName(typeName) || isBooleanTypeName(typeName))
                    ? Boolean.TRUE : Boolean.FALSE;
        }
        // NUMBER is the default form: no-op on numbers, strings, and
        // characters; explicit failures mirror the runtime rule.
        if (isNumberTypeName(typeName) || typeName.equals("java.lang.String")
                || typeName.equals("char") || typeName.equals("java.lang.Character")) {
            return Boolean.FALSE;
        }
        if (isBooleanTypeName(typeName)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@JqFormat on '" + fieldDesc + "': shape NUMBER would render"
                            + " booleans as 0/1 — booleans stay booleans (jq wins on conflict)",
                    element);
            return null;
        }
        if (isTemporalDateName(typeName)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@JqFormat on '" + fieldDesc + "': shape NUMBER would render"
                            + " timestamps — string forms only (epoch output is out of scope)",
                    element);
            return null;
        }
        javax.lang.model.element.Element typeElement =
                processingEnv.getTypeUtils().asElement(type);
        if (typeElement != null && typeElement.getKind() == ElementKind.ENUM) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@JqFormat on '" + fieldDesc + "': shape NUMBER would render"
                            + " enum ordinals — enums serialize by name (ordinals are out of scope)",
                    element);
            return null;
        }
        return Boolean.FALSE;
    }

    /**
     * Bridge format reading: Jackson {@code @JsonFormat} mirror as
     * {@code [pattern, timezone, locale, shape]}, or null when absent.
     * Shapes never gate the spec (non-string shapes are no-ops on numbers
     * — verified on databind 2.18); shape handling lives in
     * {@link #resolveShapeFlag}.
     */
    private String[] bridgeFormat(Element element) {
        for (var mirror : element.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().toString()
                    .equals("com.fasterxml.jackson.annotation.JsonFormat")) continue;
            String shape = "ANY";
            String pattern = null;
            String timezone = "";
            String locale = "";
            for (var entry : mirror.getElementValues().entrySet()) {
                String member = entry.getKey().getSimpleName().toString();
                Object value = entry.getValue().getValue();
                if (member.equals("shape") && value instanceof javax.lang.model.element.VariableElement ve) {
                    shape = ve.getSimpleName().toString();
                } else if (member.equals("pattern") && value instanceof String s) {
                    pattern = s;
                } else if (member.equals("timezone") && value instanceof String s) {
                    timezone = s.equals("##default") ? "" : s;
                } else if (member.equals("locale") && value instanceof String s) {
                    locale = s.equals("##default") ? "" : s;
                }
            }
            if (pattern == null || pattern.isEmpty()) return new String[]{null, "", "", shape};
            return new String[]{pattern, timezone, locale, shape};
        }
        return null;
    }

    /**
     * True when the element carries a pattern-bearing format marker (bail
     * check): a native pattern, or a bridged pattern member. Shape-only and
     * bare markers never bail — they generate normally (with a string-shape
     * flag when applicable).
     */
    private static boolean hasPatternMarker(Element element) {
        JqFormat nativeAnn = element.getAnnotation(JqFormat.class);
        if (nativeAnn != null && !nativeAnn.pattern().isEmpty()) return true;
        for (var mirror : element.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().toString()
                    .equals("com.fasterxml.jackson.annotation.JsonFormat")) continue;
            for (var entry : mirror.getElementValues().entrySet()) {
                if (entry.getKey().getSimpleName().toString().equals("pattern")
                        && entry.getValue().getValue() instanceof String s
                        && !s.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
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

    /** Metadata for one creator parameter. */
    record CreatorParamInfo(String name, String jsonName, String typeName,
                            String converterClass, String converterField, boolean required) {}

    /**
     * Metadata for a {@code @JqCreator}/{@code @JsonCreator} deserialization
     * creator. Delegating creators (single unnamed parameter) bind scalar
     * documents; property creators bind object documents by parameter name.
     */
    record CreatorInfo(boolean delegating, String label, String constructPrefix,
                       List<CreatorParamInfo> params) {}

    /**
     * Resolve a {@code @JqCreator}/{@code @JsonCreator} deserialization
     * creator on the mapped type (null when absent). Native wins; an invalid
     * native creator reports a compile error (the caller bails when a marker
     * is present but no info comes back). Mirrors the runtime
     * {@code CreatorSpec} rules: single unnamed parameter delegates scalars,
     * anything else binds properties by name.
     */
    private CreatorInfo resolveCreatorInfo(TypeElement type, String sourceSimpleName,
            String mappingPackage,
            java.util.Map<String, String> convFields, java.util.Set<String> usedConvNames) {
        var nativeCandidates = new ArrayList<javax.lang.model.element.ExecutableElement>();
        var bridgeCandidates = new ArrayList<javax.lang.model.element.ExecutableElement>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (!(enclosed instanceof javax.lang.model.element.ExecutableElement executable)) continue;
            var kind = executable.getKind();
            if (kind != ElementKind.METHOD && kind != ElementKind.CONSTRUCTOR) continue;
            if (executable.getAnnotation(JqCreator.class) != null) {
                nativeCandidates.add(executable);
            } else {
                String mode = jsonCreatorMode(executable);
                if (mode == null) continue;
                int params = executable.getParameters().size();
                if (mode.equals("PROPERTIES") && params >= 1) bridgeCandidates.add(executable);
                else if (mode.equals("DELEGATING") && params == 1) bridgeCandidates.add(executable);
                else if (mode.equals("DEFAULT") && params >= 1) bridgeCandidates.add(executable);
            }
        }
        javax.lang.model.element.ExecutableElement executable;
        boolean nativeAnn;
        if (!nativeCandidates.isEmpty()) {
            if (nativeCandidates.size() > 1) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@JqCreator on " + type.getQualifiedName()
                                + ": at most one creator per type",
                        type);
                return null;
            }
            executable = nativeCandidates.get(0);
            nativeAnn = true;
        } else if (!bridgeCandidates.isEmpty()) {
            if (bridgeCandidates.size() > 1) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@JsonCreator on " + type.getQualifiedName()
                                + ": at most one creator per type",
                        type);
                return null;
            }
            executable = bridgeCandidates.get(0);
            nativeAnn = false;
        } else {
            return null;
        }
        String label = nativeAnn ? "@JqCreator" : "@JsonCreator";
        boolean isMethod = executable.getKind() == ElementKind.METHOD;
        if (isMethod && !executable.getModifiers().contains(Modifier.PUBLIC)
                && executable.getModifiers().contains(Modifier.PRIVATE)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    label + " on " + type.getQualifiedName()
                            + ": the creator must be public (or non-private in this package)"
                            + " — generated code shares the package, not the class",
                    executable);
            return null;
        }
        if (executable.getParameters().isEmpty()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    label + " on " + type.getQualifiedName()
                            + ": creators need at least one parameter",
                    executable);
            return null;
        }
        if (isMethod && !processingEnv.getTypeUtils()
                .isAssignable(executable.getReturnType(), type.asType())) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    label + " on " + type.getQualifiedName()
                            + ": the factory must return " + type.getSimpleName(),
                    executable);
            return null;
        }
        var params = new ArrayList<CreatorParamInfo>();
        boolean anyExplicitName = false;
        for (var param : executable.getParameters()) {
            if (param.getAnnotation(JqField.class) != null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        label + " on " + type.getQualifiedName()
                                + ": @JqField programs are not supported on creator parameters"
                                + " (rename with @JqName instead)",
                        param);
                return null;
            }
            if (param.getAnnotation(JqAdapter.class) != null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        label + " on " + type.getQualifiedName()
                                + ": @JqAdapter is not supported on creator parameters"
                                + " (use @JqConverter instead)",
                        param);
                return null;
            }
            JqName jqNameAnn = param.getAnnotation(JqName.class);
            String jqName = jqNameAnn != null && !jqNameAnn.value().isEmpty()
                    ? jqNameAnn.value() : null;
            String wire = jqName != null ? jqName : bridgeRename(param);
            String paramName = param.getSimpleName().toString();
            String jsonName = wire != null ? wire : paramName;
            if (wire != null) anyExplicitName = true;
            String paramType = unannotatedTypeName(param.asType());
            String converterClass = resolveConverterClass(param);
            if (converterClass != null && !validateConverter(param, mappingPackage)) return null;
            String converterField = converterClass != null
                    ? converterFieldFor(convFields, usedConvNames, converterClass) : null;
            boolean paramRequired = param.getAnnotation(JqRequired.class) != null
                    || bridgeRequired(param, null);
            params.add(new CreatorParamInfo(paramName, jsonName, paramType, converterClass, converterField,
                    paramRequired));
        }
        boolean delegating = params.size() == 1 && !anyExplicitName;
        String methodName = executable.getSimpleName().toString();
        String constructPrefix = isMethod
                ? sourceSimpleName + "." + methodName + "("
                : "new " + sourceSimpleName + "(";
        return new CreatorInfo(delegating, label, constructPrefix, List.copyOf(params));
    }

    /** True when the type carries any creator marker (bail check for failed resolution). */
    private static boolean hasCreatorMarker(TypeElement type) {
        for (Element enclosed : type.getEnclosedElements()) {
            if (!(enclosed instanceof javax.lang.model.element.ExecutableElement executable)) continue;
            var kind = executable.getKind();
            if (kind != ElementKind.METHOD && kind != ElementKind.CONSTRUCTOR) continue;
            if (executable.getAnnotation(JqCreator.class) != null) return true;
            if (jsonCreatorMode(executable) != null) return true;
        }
        return false;
    }

    /** True when the type carries any builder marker (native or bridge). */
    private static boolean hasBuilderMarker(TypeElement type) {
        if (type.getAnnotation(JqBuilder.class) != null) return true;
        for (var mirror : type.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString()
                    .endsWith(".JsonDeserialize")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve builder binding for a POJO (null when unconfigured). Native
     * {@code @JqBuilder} wins over the Jackson pair; gaps report compile
     * errors (the caller bails when a marker is present but no info comes
     * back). Mirrors the runtime {@code BuilderSpec} rules: one binder per
     * deserializable field, terminal build method, factory or no-arg
     * construction.
     */
    private BuilderInfo resolveBuilderInfo(TypeElement type, String classSourceName,
            List<PropertyInfo> properties) {
        JqBuilder nativeAnn = type.getAnnotation(JqBuilder.class);
        String builderName = null;
        String factory = "";
        String prefix = "with";
        String build = "build";
        boolean nativeDecl = nativeAnn != null;
        if (nativeDecl) {
            // Class-valued members read via mirrors (direct access throws
            // MirroredTypeException for same-round classes).
            for (var mirror : type.getAnnotationMirrors()) {
                if (!mirror.getAnnotationType().toString()
                        .equals("io.hyperfoil.tools.jjq.mapper.JqBuilder")) continue;
                for (var entry : mirror.getElementValues().entrySet()) {
                    String member = entry.getKey().getSimpleName().toString();
                    Object value = entry.getValue().getValue();
                    if (member.equals("value") && value instanceof javax.lang.model.type.TypeMirror tm) {
                        builderName = tm.toString();
                    } else if (value instanceof String s) {
                        if (member.equals("factory")) factory = s;
                        else if (member.equals("prefix")) prefix = s;
                        else if (member.equals("build")) build = s;
                    }
                }
            }
            if (builderName == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@JqBuilder on " + type.getQualifiedName() + " needs a builder class",
                        type);
                return null;
            }
        } else {
            for (var mirror : type.getAnnotationMirrors()) {
                if (!mirror.getAnnotationType().toString().endsWith(".JsonDeserialize")) continue;
                for (var entry : mirror.getElementValues().entrySet()) {
                    if (entry.getKey().getSimpleName().contentEquals("builder")) {
                        Object value = entry.getValue().getValue();
                        if (value instanceof javax.lang.model.type.DeclaredType dt
                                && dt.asElement() instanceof TypeElement builderType) {
                            builderName = builderType.getQualifiedName().toString();
                        }
                    }
                }
            }
            if (builderName == null) return null;
            TypeElement builderType =
                    processingEnv.getElementUtils().getTypeElement(builderName);
            if (builderType != null) {
                for (var mirror : builderType.getAnnotationMirrors()) {
                    if (!mirror.getAnnotationType().toString().endsWith(".JsonPOJOBuilder")) continue;
                    for (var entry : mirror.getElementValues().entrySet()) {
                        String member = entry.getKey().getSimpleName().toString();
                        Object value = entry.getValue().getValue();
                        if (!(value instanceof String s) || s.isEmpty() || s.equals("<none>")) continue;
                        if (member.equals("buildMethodName")) build = s;
                        else if (member.equals("withPrefix")) prefix = s;
                    }
                }
            }
        }
        TypeElement builderType = processingEnv.getElementUtils().getTypeElement(builderName);
        if (builderType == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Cannot resolve builder class " + builderName + " for " + type.getQualifiedName(),
                    type);
            return null;
        }
        String builderSourceName = sourceNameOf(builderType, type);
        // Factory on the value type, else the builder no-arg constructor.
        boolean hasFactory = false;
        if (!factory.isEmpty()) {
            boolean found = false;
            for (Element enclosed : type.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.METHOD
                        && enclosed.getSimpleName().contentEquals(factory)
                        && enclosed instanceof javax.lang.model.element.ExecutableElement method
                        && method.getParameters().isEmpty()
                        && method.getModifiers().contains(Modifier.STATIC)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Builder factory '" + factory + "' must be a static no-arg method on "
                                + type.getQualifiedName(),
                        type);
                return null;
            }
            hasFactory = true;
        } else {
            boolean hasCtor = false;
            for (Element enclosed : builderType.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.CONSTRUCTOR
                        && enclosed instanceof javax.lang.model.element.ExecutableElement ctor
                        && ctor.getParameters().isEmpty()) {
                    hasCtor = true;
                    break;
                }
            }
            if (!hasCtor) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Builder " + builderName + " needs a no-arg constructor (or name a factory)",
                        type);
                return null;
            }
        }
        // Terminal build method returning the value type.
        boolean hasBuild = false;
        for (Element enclosed : builderType.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD
                    && enclosed.getSimpleName().contentEquals(build)
                    && enclosed instanceof javax.lang.model.element.ExecutableElement method
                    && method.getParameters().isEmpty()
                    && !method.getModifiers().contains(Modifier.STATIC)
                    && processingEnv.getTypeUtils().isAssignable(method.getReturnType(), type.asType())) {
                hasBuild = true;
                break;
            }
        }
        if (!hasBuild) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Builder " + builderName + " has no terminal method '" + build + "()' returning "
                            + type.getSimpleName(),
                    type);
            return null;
        }
        // One binder per deserializable field.
        var bound = new ArrayList<BuilderFieldInfo>();
        for (PropertyInfo prop : properties) {
            if (prop.ignored() || prop.skipDeserialize()) continue;
            String candidate = prefix.isEmpty() ? prop.name()
                    : prefix + Character.toUpperCase(prop.name().charAt(0)) + prop.name().substring(1);
            boolean found = false;
            boolean isVoid = false;
            for (Element enclosed : builderType.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD
                        || !enclosed.getSimpleName().contentEquals(candidate)) continue;
                if (!(enclosed instanceof javax.lang.model.element.ExecutableElement method)
                        || method.getParameters().size() != 1
                        || method.getModifiers().contains(Modifier.STATIC)) continue;
                String paramType = unannotatedTypeName(method.getParameters().get(0).asType());
                if (!paramType.equals(prop.typeName()) && !boxedEquals(paramType, prop.typeName())) continue;
                found = true;
                isVoid = method.getReturnType().getKind() == javax.lang.model.type.TypeKind.VOID;
                break;
            }
            if (!found) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "No builder method '" + candidate + "(" + prop.typeName() + ")' on "
                                + builderName + " for field '" + prop.name() + "'",
                        type);
                return null;
            }
            bound.add(new BuilderFieldInfo(prop.jsonName(), candidate, isVoid));
        }
        String factoryPrefix = hasFactory
                ? classSourceName + "." + factory + "()"
                : "new " + builderSourceName + "()";
        String infoLabel = nativeDecl ? "@JqBuilder" : "@JsonDeserialize";
        return new BuilderInfo(builderSourceName, factoryPrefix, build, infoLabel, List.copyOf(bound));
    }

    /** Source-level name of a (possibly nested) type, for generated code. */
    private static String sourceNameOf(TypeElement builderType, TypeElement context) {
        String qualified = builderType.getQualifiedName().toString();
        String pkg = processingPackageOf(context);
        if (!pkg.isEmpty() && qualified.startsWith(pkg + ".")) {
            return qualified.substring(pkg.length() + 1);
        }
        return qualified;
    }

    /** Boxed equivalence for binder parameter matching (int ↔ Integer, ...). */
    private static boolean boxedEquals(String a, String b) {
        return boxedName(a).equals(boxedName(b));
    }

    /** Package of a type element (empty for the default package). */
    private static String processingPackageOf(TypeElement type) {
        String qualified = type.getQualifiedName().toString();
        int dot = qualified.lastIndexOf('.');
        String simple = type.getSimpleName().toString();
        if (dot < 0) return "";
        String pkg = qualified.substring(0, dot);
        // Strip nesting (Outer.Inner -> package of Outer).
        Element enclosing = type.getEnclosingElement();
        while (enclosing != null
                && (enclosing.getKind() == ElementKind.CLASS
                    || enclosing.getKind() == ElementKind.RECORD
                    || enclosing.getKind() == ElementKind.INTERFACE
                    || enclosing.getKind() == ElementKind.ENUM)) {
            int cut = pkg.lastIndexOf('.');
            pkg = cut < 0 ? "" : pkg.substring(0, cut);
            enclosing = enclosing.getEnclosingElement();
        }
        return pkg;
    }

    /** The {@code @JsonCreator} mode name (stringly-typed: no framework dependency). */
    private static String jsonCreatorMode(javax.lang.model.element.ExecutableElement executable) {
        for (var mirror : executable.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString()
                    .equals("com.fasterxml.jackson.annotation.JsonCreator")) {
                for (var entry : mirror.getElementValues().entrySet()) {
                    if (entry.getKey().getSimpleName().contentEquals("mode")) {
                        return entry.getValue().getValue().toString();
                    }
                }
                return "DEFAULT";
            }
        }
        return null;
    }

    /** Metadata for one builder-bound field. */
    record BuilderFieldInfo(String jsonName, String binderName, boolean isVoid) {}

    /**
     * Metadata for a {@code @JqBuilder}/{@code @JsonDeserialize} builder
     * binding on a POJO. Construction is a builder instance plus one call
     * per bound field plus the terminal build call.
     */
    record BuilderInfo(String builderSourceName, String factory, String build, String label,
                       List<BuilderFieldInfo> bound) {}

    /**
     * Select the enforced required wires, mirroring the runtime rules:
     * property creators bind by parameter name (component markers apply
     * only where the creator binds that wire, anything else fails fast);
     * delegating creators bind whole scalars (any component marker fails
     * fast); canonical binding enforces component markers, and programs
     * cannot prove presence (fail fast). Returns null on error (reported).
     */
    private List<String> resolveRequiredWires(TypeElement type, List<ComponentInfo> components,
            List<String> componentRequired, CreatorInfo creator) {
        if (creator != null && !creator.delegating()) {
            var paramWires = new java.util.HashSet<String>();
            for (var p : creator.params()) paramWires.add(p.jsonName());
            for (String wire : componentRequired) {
                if (!paramWires.contains(wire)) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "@JqRequired on '" + wire + "' of " + type.getQualifiedName()
                                    + " is never bound: the creator binds other wires"
                                    + " (mark the creator parameter instead)",
                            type);
                    return null;
                }
            }
            var required = new ArrayList<String>();
            for (var p : creator.params()) {
                if (p.required()) required.add(p.jsonName());
            }
            return required;
        }
        if (creator != null) {
            if (!componentRequired.isEmpty()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@JqRequired on '" + componentRequired.get(0) + "' of "
                                + type.getQualifiedName() + " is never bound: the creator"
                                + " binds whole scalars (mark nothing — scalars are always present)",
                        type);
                return null;
            }
            return List.of();
        }
        for (var comp : components) {
            if (!comp.ignored() && comp.hasJqField() && componentRequired.contains(comp.jsonName())) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@JqRequired on '" + comp.name() + "' of " + type.getQualifiedName()
                                + ": required needs a direct lookup (@JqField programs cannot prove presence)",
                        type);
                return null;
            }
        }
        return componentRequired;
    }

    /**
     * POJO twin of the record selection above (mirrors the runtime rules
     * for properties instead of components).
     */
    private List<String> resolvePojoRequiredWires(TypeElement type, List<PropertyInfo> properties,
            List<String> propertyRequired, CreatorInfo creator) {
        if (creator != null && !creator.delegating()) {
            var paramWires = new java.util.HashSet<String>();
            for (var p : creator.params()) paramWires.add(p.jsonName());
            for (String wire : propertyRequired) {
                if (!paramWires.contains(wire)) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "@JqRequired on '" + wire + "' of " + type.getQualifiedName()
                                    + " is never bound: the creator binds other wires"
                                    + " (mark the creator parameter instead)",
                            type);
                    return null;
                }
            }
            var required = new ArrayList<String>();
            for (var p : creator.params()) {
                if (p.required()) required.add(p.jsonName());
            }
            return required;
        }
        if (creator != null) {
            if (!propertyRequired.isEmpty()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@JqRequired on '" + propertyRequired.get(0) + "' of "
                                + type.getQualifiedName() + " is never bound: the creator"
                                + " binds whole scalars (mark nothing — scalars are always present)",
                        type);
                return null;
            }
            return List.of();
        }
        for (var prop : properties) {
            if (!prop.ignored() && prop.hasJqField() && propertyRequired.contains(prop.jsonName())) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@JqRequired on '" + prop.name() + "' of " + type.getQualifiedName()
                                + ": required needs a direct lookup (@JqField programs cannot prove presence)",
                        type);
                return null;
            }
        }
        return propertyRequired;
    }

    /**
     * Serialization order for a type: native {@code @JqOrder} first, then
     * the bridge {@code @JsonPropertyOrder} mirror. Null when unconfigured
     * (declaration order).
     */
    private JqOrder.Spec orderSpecFor(TypeElement type) {
        JqOrder nativeAnn = type.getAnnotation(JqOrder.class);
        if (nativeAnn != null) {
            return new JqOrder.Spec(java.util.List.of(nativeAnn.value()), nativeAnn.alphabetic());
        }
        for (var mirror : type.getAnnotationMirrors()) {
            if (!mirror.getAnnotationType().toString().endsWith(".JsonPropertyOrder")) continue;
            var explicit = new ArrayList<String>();
            boolean alphabetic = false;
            for (var entry : mirror.getElementValues().entrySet()) {
                String member = entry.getKey().getSimpleName().toString();
                Object value = entry.getValue().getValue();
                if (member.equals("value") && value instanceof java.util.List<?> items) {
                    for (Object item : items) {
                        Object itemValue =
                                item instanceof javax.lang.model.element.AnnotationValue av
                                        ? av.getValue() : item;
                        if (itemValue instanceof String s) explicit.add(s);
                    }
                } else if (member.equals("alphabetic") && Boolean.TRUE.equals(value)) {
                    alphabetic = true;
                }
            }
            return new JqOrder.Spec(explicit, alphabetic);
        }
        return null;
    }

    /**
     * Validate an order spec against bound wires (unknown names fail fast).
     * Sorting itself happens at emission from the validated spec; binding
     * lists stay declaration-ordered (constructor alignment depends on it).
     */
    private <T> boolean validateOrderSpec(TypeElement type, List<T> items,
                                          java.util.function.Function<T, String> jsonName,
                                          JqOrder.Spec spec) {
        if (spec == null) return true;
        var known = new java.util.HashSet<String>();
        for (T item : items) known.add(jsonName.apply(item));
        for (String name : spec.explicit()) {
            if (!known.contains(name)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@JqOrder on " + type.getQualifiedName()
                                + ": unknown wire name '" + name + "'",
                        type);
                return false;
            }
        }
        return true;
    }

    record ComponentInfo(String name, String jsonName, String typeName, String jqExpr, boolean ignored, boolean hasJqField,
                         String inclusion, String converterClass, boolean nestedRecord,
                         boolean skipSerialize, boolean skipDeserialize, AdapterInfo adapter,
                         String converterField, java.util.List<String> aliases,
                         io.hyperfoil.tools.jjq.mapper.JqFormat.Spec format, boolean stringShape) {}

    /** Metadata for a single POJO field. */
    record PropertyInfo(String name, String jsonName, String typeName, String jqExpr, boolean ignored, boolean hasJqField,
                        String getterName, String setterName, boolean isPublicField, String inclusion,
                        String converterClass, boolean nestedRecord,
                        boolean skipSerialize, boolean skipDeserialize,
                        boolean privateField, boolean finalField, AdapterInfo adapter,
                        String converterField, java.util.List<String> aliases,
                        io.hyperfoil.tools.jjq.mapper.JqFormat.Spec format, boolean stringShape) {}

    /**
     * Shared {@code CONV_} field name per converter class, first-seen order
     * (issue #116): fields sharing a class share one static instance.
     * Colliding simple names disambiguate with a counter suffix.
     *
     * @return the full field name (including the {@code CONV_} prefix)
     */
    private static String converterFieldFor(java.util.Map<String, String> assigned,
            java.util.Set<String> used, String converterClass) {
        return assigned.computeIfAbsent(converterClass, cls -> {
            String base = "CONV_" + cls.substring(cls.lastIndexOf('.') + 1)
                    .toUpperCase(java.util.Locale.ROOT);
            String name = base;
            for (int i = 2; used.contains(name); i++) name = base + "_" + i;
            used.add(name);
            return name;
        });
    }

    /**
     * Compile-time adapter descriptor: resolved method names plus call shapes
     * for direct static emission (issue #120). The owner is always the
     * field's declared type, so it is not stored.
     */
    record AdapterInfo(String fromName, boolean fromString, String toName, boolean toStatic,
                       boolean toStringForm) {}

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
        var acc = new AnyAcc();
        // Superclass chain first (most specific wins), then implemented
        // interfaces breadth-first: the runtime discovers any-methods via
        // getMethods(), which sees inherited and interface-default methods
        // alike (issues #107.1, 0.2 scope item 3). First mark per role wins.
        var seen = new java.util.HashSet<String>();
        var queue = new java.util.ArrayDeque<TypeElement>();
        for (TypeElement t = type; t != null; t = superclassOf(t)) {
            queue.addLast(t);
        }
        while (!queue.isEmpty()) {
            TypeElement t = queue.removeFirst();
            if (!seen.add(t.getQualifiedName().toString())) continue;
            if (!scanAnyMethods(t, acc)) return AnyInfo.NONE;
            if (acc.setterName != null && acc.getterName != null) break;
            for (javax.lang.model.type.TypeMirror iface : t.getInterfaces()) {
                if (iface.getKind() != javax.lang.model.type.TypeKind.DECLARED) continue;
                Element element = ((javax.lang.model.type.DeclaredType) iface).asElement();
                if (element instanceof TypeElement ite) queue.addLast(ite);
            }
        }
        if (acc.setterName == null && acc.getterName == null) return AnyInfo.NONE;
        return new AnyInfo(acc.setterName, acc.setterTakesJqValue, acc.getterName);
    }

    /** Mutable accumulator for any-method discovery across the hierarchy walk. */
    private static final class AnyAcc {
        String setterName;
        boolean setterTakesJqValue;
        String getterName;
    }

    /**
     * Scan one type's declared methods for any-setter/any-getter marks.
     *
     * @return false when a marked method is malformed (error already reported)
     */
    private boolean scanAnyMethods(TypeElement t, AnyAcc acc) {
        for (Element enclosed : t.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            var method = (javax.lang.model.element.ExecutableElement) enclosed;
            if (acc.setterName == null
                    && (enclosed.getAnnotation(JqAnySetter.class) != null
                        || hasMirrorAnnotation(enclosed,
                            "com.fasterxml.jackson.annotation.JsonAnySetter"))) {
            var params = method.getParameters();
            if (!method.getModifiers().contains(Modifier.STATIC)
                    && params.size() == 2
                    && unannotatedTypeName(params.get(0).asType()).equals("java.lang.String")
                    && (unannotatedTypeName(params.get(1).asType()).equals("io.hyperfoil.tools.jjq.value.JqValue")
                        || unannotatedTypeName(params.get(1).asType()).equals("java.lang.Object"))
                    && method.getReturnType().toString().equals("void")) {
                acc.setterName = method.getSimpleName().toString();
                acc.setterTakesJqValue = unannotatedTypeName(params.get(1).asType())
                        .equals("io.hyperfoil.tools.jjq.value.JqValue");
            } else {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Any-setter must be a non-static void (String, JqValue|Object) method",
                        enclosed);
                return false;
            }
        }
        if (acc.getterName == null
                && (enclosed.getAnnotation(JqAnyGetter.class) != null
                    || hasMirrorAnnotation(enclosed,
                        "com.fasterxml.jackson.annotation.JsonAnyGetter"))) {
            String rt = method.getReturnType().toString();
            if (!method.getModifiers().contains(Modifier.STATIC)
                    && method.getParameters().isEmpty()
                    && (rt.startsWith("java.util.Map")
                        || rt.equals("io.hyperfoil.tools.jjq.value.JqObject"))) {
                acc.getterName = method.getSimpleName().toString();
            } else {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Any-getter must be a non-static no-arg method returning Map<String, ?> or JqObject",
                        enclosed);
                return false;
            }
        }
        }
        return true;
    }

    /**
     * The superclass as a {@code TypeElement}, or null at the top of the
     * chain ({@code java.lang.Object} has no methods of interest).
     * Implemented interfaces are enqueued separately (breadth-first) by the
     * caller; diamond defaults resolve first-found-wins.
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
