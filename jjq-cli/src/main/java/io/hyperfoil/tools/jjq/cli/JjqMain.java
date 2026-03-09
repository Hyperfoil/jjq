package io.hyperfoil.tools.jjq.cli;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.evaluator.Environment;
import io.hyperfoil.tools.jjq.value.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JjqMain {

    private boolean compactOutput = false;
    private boolean rawOutput = false;
    private boolean rawInput = false;
    private boolean slurp = false;
    private boolean nullInput = false;
    private boolean exitStatus = false;
    private boolean sortKeys = false;
    private boolean tab = false;
    private boolean joinOutput = false;
    private boolean colorOutput = false;
    private boolean monochromeOutput = false;
    private int indent = 2;
    private String filter = null;
    private String fromFile = null;
    private final List<String> files = new ArrayList<>();
    private final Map<String, String> argVars = new LinkedHashMap<>();
    private final Map<String, String> argJsonVars = new LinkedHashMap<>();

    public static void main(String[] args) {
        var main = new JjqMain();
        main.parseArgs(args);
        int exitCode = main.run();
        System.exit(exitCode);
    }

    private void parseArgs(String[] args) {
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            switch (arg) {
                case "-c", "--compact-output" -> compactOutput = true;
                case "-r", "--raw-output" -> rawOutput = true;
                case "-R", "--raw-input" -> rawInput = true;
                case "-s", "--slurp" -> slurp = true;
                case "-n", "--null-input" -> nullInput = true;
                case "-e", "--exit-status" -> exitStatus = true;
                case "-S", "--sort-keys" -> sortKeys = true;
                case "--tab" -> { tab = true; indent = 1; }
                case "--indent" -> { i++; indent = Integer.parseInt(args[i]); }
                case "-j", "--join-output" -> joinOutput = true;
                case "-C", "--color-output" -> { colorOutput = true; monochromeOutput = false; }
                case "-M", "--monochrome-output" -> { monochromeOutput = true; colorOutput = false; }
                case "-f", "--from-file" -> { i++; fromFile = args[i]; }
                case "--arg" -> {
                    String name = args[++i];
                    String value = args[++i];
                    argVars.put(name, value);
                }
                case "--argjson" -> {
                    String name = args[++i];
                    String json = args[++i];
                    argJsonVars.put(name, json);
                }
                case "-h", "--help" -> { printHelp(); System.exit(0); }
                case "--version" -> { System.out.println("jjq 0.1.0"); System.exit(0); }
                default -> {
                    if (arg.startsWith("-") && !arg.equals("-")) {
                        System.err.println("Unknown option: " + arg);
                        System.exit(2);
                    }
                    if (filter == null) {
                        filter = arg;
                    } else {
                        files.add(arg);
                    }
                }
            }
            i++;
        }

        // If --from-file is specified, read filter from file
        if (fromFile != null) {
            try {
                filter = Files.readString(Path.of(fromFile), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                System.err.println("jjq: could not read filter file: " + fromFile + ": " + e.getMessage());
                System.exit(2);
            }
        }

        if (filter == null) {
            System.err.println("jjq - commandline JSON processor");
            System.err.println("Usage: jjq [OPTIONS] FILTER [FILE...]");
            System.exit(2);
        }
    }

    private boolean useColor() {
        if (colorOutput) return true;
        if (monochromeOutput) return false;
        // Default: color if stdout is a terminal (not redirected)
        return System.console() != null;
    }

    private int run() {
        try {
            JqProgram program = JqProgram.compile(filter);

            // Build environment with --arg and --argjson variables
            Environment env = new Environment();
            for (var entry : argVars.entrySet()) {
                env.setVariable(entry.getKey(), JqString.of(entry.getValue()));
            }
            for (var entry : argJsonVars.entrySet()) {
                env.setVariable(entry.getKey(), JqValues.parse(entry.getValue()));
            }

            List<JqValue> inputs = readInputs();
            boolean anyOutput = false;
            boolean firstOutput = true;

            for (JqValue input : inputs) {
                List<JqValue> results = program.applyAll(input, env);
                for (JqValue result : results) {
                    anyOutput = true;
                    if (joinOutput && !firstOutput) {
                        // no separator between outputs
                    }
                    printValue(result, firstOutput);
                    firstOutput = false;
                }
            }

            if (exitStatus && !anyOutput) return 4;
            return 0;
        } catch (Exception e) {
            System.err.println("jjq: error: " + e.getMessage());
            return 5;
        }
    }

    private List<JqValue> readInputs() throws IOException {
        if (nullInput) {
            return List.of(io.hyperfoil.tools.jjq.value.JqNull.NULL);
        }

        List<String> jsonInputs = new ArrayList<>();

        if (files.isEmpty()) {
            // Read from stdin
            jsonInputs.addAll(readJsonFromStream(System.in));
        } else {
            for (String file : files) {
                if (file.equals("-")) {
                    jsonInputs.addAll(readJsonFromStream(System.in));
                } else {
                    String content = Files.readString(Path.of(file), StandardCharsets.UTF_8);
                    if (rawInput) {
                        for (String line : content.split("\n", -1)) {
                            jsonInputs.add("\"" + escapeJson(line) + "\"");
                        }
                    } else {
                        jsonInputs.add(content.trim());
                    }
                }
            }
        }

        if (slurp) {
            var sb = new StringBuilder("[");
            for (int i = 0; i < jsonInputs.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(jsonInputs.get(i));
            }
            sb.append("]");
            return List.of(JqValues.parse(sb.toString()));
        }

        return jsonInputs.stream()
                .map(JqValues::parse)
                .toList();
    }

    private List<String> readJsonFromStream(InputStream in) throws IOException {
        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        if (rawInput) {
            var result = new ArrayList<String>();
            for (String line : content.split("\n", -1)) {
                result.add("\"" + escapeJson(line) + "\"");
            }
            return result;
        }
        // Handle JSON stream (multiple values)
        var results = new ArrayList<String>();
        if (content.isEmpty()) return results;
        results.add(content);
        return results;
    }

    private void printValue(JqValue value, boolean firstOutput) {
        String text;
        if (rawOutput && value instanceof JqString s) {
            text = s.stringValue();
        } else if (compactOutput) {
            text = useColor() ? colorize(value.toJsonString(), value) : value.toJsonString();
        } else {
            text = prettyPrint(value, 0, useColor());
        }

        if (joinOutput) {
            System.out.print(text);
        } else {
            System.out.println(text);
        }
    }

    // ANSI color codes for JSON syntax highlighting
    private static final String RESET   = "\033[0m";
    private static final String DIM     = "\033[2m";      // null → grey/dim
    private static final String YELLOW  = "\033[33m";     // booleans
    private static final String CYAN    = "\033[36m";     // numbers
    private static final String GREEN   = "\033[32m";     // strings
    private static final String BLUE_BOLD = "\033[1;34m"; // keys
    // brackets/braces use default (no color)

    private String colorizeScalar(JqValue value) {
        String json = value.toJsonString();
        return switch (value) {
            case JqNull ignored   -> DIM + json + RESET;
            case JqBoolean ignored -> YELLOW + json + RESET;
            case JqNumber ignored  -> CYAN + json + RESET;
            case JqString ignored  -> GREEN + json + RESET;
            default -> json;
        };
    }

    /**
     * Simple colorization for compact output of a single scalar value.
     */
    private String colorize(String json, JqValue value) {
        // For scalars, wrap the whole thing
        if (value instanceof JqNull || value instanceof JqBoolean
                || value instanceof JqNumber || value instanceof JqString) {
            return colorizeScalar(value);
        }
        // For arrays/objects in compact mode, fall back to uncolored
        return json;
    }

    private String prettyPrint(JqValue value, int depth, boolean color) {
        String indentStr = tab ? "\t".repeat(depth) : " ".repeat(depth * indent);
        String childIndent = tab ? "\t".repeat(depth + 1) : " ".repeat((depth + 1) * indent);

        return switch (value) {
            case JqArray arr -> {
                if (arr.arrayValue().isEmpty()) yield "[]";
                var sb = new StringBuilder("[\n");
                var items = arr.arrayValue();
                for (int i = 0; i < items.size(); i++) {
                    sb.append(childIndent).append(prettyPrint(items.get(i), depth + 1, color));
                    if (i < items.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append(indentStr).append("]");
                yield sb.toString();
            }
            case JqObject obj -> {
                if (obj.objectValue().isEmpty()) yield "{}";
                var sb = new StringBuilder("{\n");
                var entries = sortKeys
                        ? obj.objectValue().entrySet().stream()
                                .sorted(java.util.Map.Entry.comparingByKey())
                                .toList()
                        : new ArrayList<>(obj.objectValue().entrySet());
                for (int i = 0; i < entries.size(); i++) {
                    var entry = entries.get(i);
                    String keyStr = JqString.of(entry.getKey()).toJsonString();
                    if (color) keyStr = BLUE_BOLD + keyStr + RESET;
                    sb.append(childIndent)
                      .append(keyStr)
                      .append(": ")
                      .append(prettyPrint(entry.getValue(), depth + 1, color));
                    if (i < entries.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append(indentStr).append("}");
                yield sb.toString();
            }
            default -> color ? colorizeScalar(value) : value.toJsonString();
        };
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void printHelp() {
        System.out.println("Usage: jjq [OPTIONS] FILTER [FILE...]");
        System.out.println();
        System.out.println("jjq - commandline JSON processor (Java implementation)");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -c, --compact-output   Compact output");
        System.out.println("  -r, --raw-output       Raw string output (no quotes)");
        System.out.println("  -R, --raw-input         Read each line as a string");
        System.out.println("  -s, --slurp             Slurp all inputs into an array");
        System.out.println("  -n, --null-input        Use null as input");
        System.out.println("  -e, --exit-status       Set exit status based on output");
        System.out.println("  -S, --sort-keys         Sort object keys");
        System.out.println("  -j, --join-output       Don't print newlines between outputs");
        System.out.println("  -f, --from-file FILE    Read filter from file");
        System.out.println("  -C, --color-output      Force colored output");
        System.out.println("  -M, --monochrome-output Disable colored output");
        System.out.println("  --arg NAME VALUE        Set $NAME to VALUE (string)");
        System.out.println("  --argjson NAME JSON     Set $NAME to parsed JSON value");
        System.out.println("  --tab                   Use tab for indentation");
        System.out.println("  --indent N              Use N spaces for indentation");
        System.out.println("  -h, --help              Show this help");
        System.out.println("  --version               Show version");
    }
}
