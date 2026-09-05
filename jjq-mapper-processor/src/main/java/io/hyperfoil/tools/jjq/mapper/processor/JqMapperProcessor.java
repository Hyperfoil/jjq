package io.hyperfoil.tools.jjq.mapper.processor;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.mapper.JqConverter;
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

        // Collect record component metadata
        List<ComponentInfo> components = new ArrayList<>();
        for (Element enclosed : recordType.getEnclosedElements()) {
            if (enclosed instanceof RecordComponentElement rc) {
                String name = rc.getSimpleName().toString();
                String typeName = rc.asType().toString();
                boolean ignored = rc.getAnnotation(JqIgnore.class) != null;

                // Apply naming strategy for default expression
                String jsonName = namingStrategy.transform(name);
                String jqExpr = "." + jsonName;
                JqField jqField = rc.getAnnotation(JqField.class);
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
                }

                // Resolve field-level @JqInclude (overrides class-level)
                JqInclude fieldInclude = rc.getAnnotation(JqInclude.class);
                String inclusion = fieldInclude != null ? fieldInclude.value().name() : classInclusion;

                String serName = (jqField != null) ? name : jsonName; // @JqField overrides naming
                JqConverter jqConverter = rc.getAnnotation(JqConverter.class);
                String converterClass = jqConverter != null ? jqConverter.value().getCanonicalName() : null;

                components.add(new ComponentInfo(name, serName, typeName, jqExpr, ignored, jqField != null, inclusion, converterClass));
            }
        }

        // Generate the mapping class
        String packageName = processingEnv.getElementUtils().getPackageOf(recordType).getQualifiedName().toString();
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
                packageName, recordSourceName, recordQualifiedName, mappingClassName, components);

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

        // Collect field metadata (declared fields only, skip static/synthetic)
        List<PropertyInfo> properties = new ArrayList<>();
        // Collect method names for getter/setter resolution
        var methods = new java.util.HashSet<String>();
        for (Element enclosed : classType.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD) {
                methods.add(enclosed.getSimpleName().toString());
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
            String jqExpr = "." + jsonName;
            JqField jqField = field.getAnnotation(JqField.class);
            if (jqField != null) {
                jqExpr = jqField.value();
                try {
                    JqProgram.compile(jqExpr);
                } catch (Exception e) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Invalid jq expression in @JqField(\"" + jqExpr + "\"): " + e.getMessage(), field);
                    return null;
                }
            }

            // Determine access strategy
            boolean isPublic = field.getModifiers().contains(Modifier.PUBLIC);
            boolean isFinal = field.getModifiers().contains(Modifier.FINAL);
            String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);

            // Getter: public field, getX(), isX() for boolean
            String getterName;
            if (isPublic) {
                getterName = null; // direct field access
            } else if (methods.contains("get" + capitalized)) {
                getterName = "get" + capitalized;
            } else if ((typeName.equals("boolean") || typeName.equals("java.lang.Boolean"))
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

            // Resolve field-level @JqInclude (overrides class-level)
            JqInclude fieldInclude = field.getAnnotation(JqInclude.class);
            String inclusion = fieldInclude != null ? fieldInclude.value().name() : classInclusion;

            String serName = (jqField != null) ? name : jsonName;
            JqConverter jqConverter = field.getAnnotation(JqConverter.class);
            String converterClass = jqConverter != null ? jqConverter.value().getCanonicalName() : null;

            properties.add(new PropertyInfo(name, serName, typeName, jqExpr, ignored, jqField != null,
                    getterName, setterName, isPublic, inclusion, converterClass));
        }

        // Generate the mapping class
        String packageName = processingEnv.getElementUtils().getPackageOf(classType).getQualifiedName().toString();
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
                packageName, classSourceName, classQualifiedName, mappingClassName, properties);

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
        return classInclude != null ? classInclude.value().name() : "ALWAYS";
    }

    /** Resolve class-level @JqNaming, defaulting to IDENTITY. */
    private JqNaming.Strategy resolveNamingStrategy(TypeElement type) {
        JqNaming naming = type.getAnnotation(JqNaming.class);
        return naming != null ? naming.value() : JqNaming.Strategy.IDENTITY;
    }

    /** Metadata for a single record component. */
    record ComponentInfo(String name, String jsonName, String typeName, String jqExpr, boolean ignored, boolean hasJqField,
                         String inclusion, String converterClass) {}

    /** Metadata for a single POJO field. */
    record PropertyInfo(String name, String jsonName, String typeName, String jqExpr, boolean ignored, boolean hasJqField,
                        String getterName, String setterName, boolean isPublicField, String inclusion,
                        String converterClass) {}
}
