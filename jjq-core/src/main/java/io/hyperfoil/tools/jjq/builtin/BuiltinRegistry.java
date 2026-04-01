package io.hyperfoil.tools.jjq.builtin;

import io.hyperfoil.tools.jjq.ast.JqExpr;
import io.hyperfoil.tools.jjq.evaluator.EmptyException;
import io.hyperfoil.tools.jjq.evaluator.Environment;
import io.hyperfoil.tools.jjq.evaluator.Evaluator;
import io.hyperfoil.tools.jjq.evaluator.HaltException;
import io.hyperfoil.tools.jjq.evaluator.JqException;
import io.hyperfoil.tools.jjq.value.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class BuiltinRegistry {
    private static volatile BuiltinRegistry defaultInstance;

    private final Map<String, BuiltinFunction> builtins = new HashMap<>();

    /** Require the input to be a JqArray, throwing a descriptive error if not. */
    private static JqArray requireArray(JqValue input, String operation) {
        if (input instanceof JqArray arr) return arr;
        throw new JqException(operation + " requires array input");
    }

    public BuiltinRegistry() {
        registerDefaults();
    }

    /**
     * Returns a shared default registry with all standard builtins.
     * The returned instance should not be mutated.
     */
    public static BuiltinRegistry getDefault() {
        BuiltinRegistry inst = defaultInstance;
        if (inst == null) {
            synchronized (BuiltinRegistry.class) {
                inst = defaultInstance;
                if (inst == null) {
                    inst = new BuiltinRegistry();
                    defaultInstance = inst;
                }
            }
        }
        return inst;
    }

    public void register(String name, int arity, BuiltinFunction function) {
        builtins.put(name + "/" + arity, function);
    }

    public BuiltinFunction get(String name, int arity) {
        return builtins.get(name + "/" + arity);
    }

    private void registerDefaults() {
        // Core
        register("length", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqNumber n) {
                out.accept((n.isNaN() || n.isInfinite()) ? JqNumber.of(Math.abs(n.doubleValue())) : JqNumber.of(n.decimalValue().abs()));
            } else {
                out.accept(JqNumber.of(input.length()));
            }
        });

        register("utf8bytelength", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqString s) {
                out.accept(JqNumber.of(s.stringValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
            } else {
                throw new JqException(input.type().jqName() + " (" + input.toJsonString() + ") only strings have UTF-8 byte length");
            }
        });

        register("keys", 0, (input, args, env, eval, out) -> {
            switch (input) {
                case JqObject obj -> {
                    var keys = obj.objectValue().keySet().stream()
                            .sorted()
                            .map(k -> (JqValue) JqString.of(k))
                            .toList();
                    out.accept(JqArray.of(keys));
                }
                case JqArray arr -> {
                    var indices = new ArrayList<JqValue>();
                    for (int i = 0; i < arr.arrayValue().size(); i++) {
                        indices.add(JqNumber.of(i));
                    }
                    out.accept(JqArray.of(indices));
                }
                default -> throw new JqException("keys requires object or array input");
            }
        });

        register("keys_unsorted", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqObject obj) {
                var keys = obj.objectValue().keySet().stream()
                        .map(k -> (JqValue) JqString.of(k))
                        .toList();
                out.accept(JqArray.of(keys));
            } else {
                throw new JqException("keys_unsorted requires object input");
            }
        });

        register("values", 0, (input, args, env, eval, out) -> {
            // values is a type-selector filter: passes through non-null values
            if (!(input instanceof JqNull)) {
                out.accept(input);
            }
        });

        register("type", 0, (input, args, env, eval, out) ->
                out.accept(JqString.of(input.type().jqName())));

        register("empty", 0, (input, args, env, eval, out) -> {
            throw EmptyException.INSTANCE;
        });

        register("error", 0, (input, args, env, eval, out) -> {
            throw new JqException(input);
        });

        register("error", 1, (input, args, env, eval, out) -> {
            List<JqValue> msgVals = eval.eval(args.getFirst(), input, env);
            throw new JqException(msgVals.getFirst());
        });

        register("has", 1, (input, args, env, eval, out) -> {
            for (JqValue key : eval.eval(args.getFirst(), input, env)) {
                boolean result = switch (input) {
                    case JqObject obj -> obj.has(key instanceof JqString s ? s.stringValue() : key.toJsonString());
                    case JqArray arr -> {
                        if (key instanceof JqNumber n && n.isNaN()) yield false;
                        int idx = (int) key.longValue();
                        yield idx >= 0 && idx < arr.arrayValue().size();
                    }
                    default -> throw new JqException("has() requires object or array");
                };
                out.accept(JqBoolean.of(result));
            }
        });

        register("in", 1, (input, args, env, eval, out) -> {
            for (JqValue container : eval.eval(args.getFirst(), input, env)) {
                boolean result = switch (container) {
                    case JqObject obj -> obj.has(input instanceof JqString s ? s.stringValue() : input.toJsonString());
                    case JqArray arr -> {
                        int idx = (int) input.longValue();
                        yield idx >= 0 && idx < arr.arrayValue().size();
                    }
                    default -> throw new JqException("in() requires object or array argument");
                };
                out.accept(JqBoolean.of(result));
            }
        });

        register("contains", 1, (input, args, env, eval, out) -> {
            for (JqValue other : eval.eval(args.getFirst(), input, env)) {
                out.accept(JqBoolean.of(containsValue(input, other)));
            }
        });

        register("inside", 1, (input, args, env, eval, out) -> {
            for (JqValue other : eval.eval(args.getFirst(), input, env)) {
                out.accept(JqBoolean.of(containsValue(other, input)));
            }
        });

        register("not", 0, (input, args, env, eval, out) ->
                out.accept(JqBoolean.of(!input.isTruthy())));

        register("null", 0, (input, args, env, eval, out) ->
                out.accept(JqNull.NULL));

        register("true", 0, (input, args, env, eval, out) ->
                out.accept(JqBoolean.TRUE));

        register("false", 0, (input, args, env, eval, out) ->
                out.accept(JqBoolean.FALSE));

        // Collection builtins
        register("map", 1, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "map");
            var results = new ArrayList<JqValue>();
            for (JqValue elem : arr.arrayValue()) {
                eval.eval(args.getFirst(), elem, env, results::add);
            }
            out.accept(JqArray.of(results));
        });

        register("map_values", 1, (input, args, env, eval, out) -> {
            switch (input) {
                case JqArray arr -> {
                    var results = new ArrayList<JqValue>();
                    for (JqValue elem : arr.arrayValue()) {
                        results.addAll(eval.eval(args.getFirst(), elem, env));
                    }
                    out.accept(JqArray.of(results));
                }
                case JqObject obj -> {
                    var map = new LinkedHashMap<String, JqValue>();
                    for (var entry : obj.objectValue().entrySet()) {
                        var vals = eval.eval(args.getFirst(), entry.getValue(), env);
                        map.put(entry.getKey(), vals.getFirst());
                    }
                    out.accept(JqObject.of(map));
                }
                default -> throw new JqException("map_values requires array or object input");
            }
        });

        register("select", 1, (input, args, env, eval, out) -> {
            for (JqValue cond : eval.eval(args.getFirst(), input, env)) {
                if (cond.isTruthy()) {
                    out.accept(input);
                }
            }
        });

        register("add", 0, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "add");
            if (arr.arrayValue().isEmpty()) {
                out.accept(JqNull.NULL);
                return;
            }
            JqValue result = arr.arrayValue().getFirst();
            for (int i = 1; i < arr.arrayValue().size(); i++) {
                result = result.add(arr.arrayValue().get(i));
            }
            out.accept(result);
        });

        // add(f) — collect outputs of f, then add them
        register("add", 1, (input, args, env, eval, out) -> {
            var collected = new ArrayList<JqValue>();
            try {
                eval.eval(args.getFirst(), input, env, collected::add);
            } catch (EmptyException _) {
                // empty produces no outputs
            }
            if (collected.isEmpty()) {
                out.accept(JqNull.NULL);
                return;
            }
            JqValue result = collected.getFirst();
            for (int i = 1; i < collected.size(); i++) {
                result = result.add(collected.get(i));
            }
            out.accept(result);
        });

        register("any", 0, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "any");
            out.accept(JqBoolean.of(arr.arrayValue().stream().anyMatch(JqValue::isTruthy)));
        });

        register("any", 1, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "any");
            for (JqValue elem : arr.arrayValue()) {
                var results = eval.eval(args.getFirst(), elem, env);
                if (results.stream().anyMatch(JqValue::isTruthy)) {
                    out.accept(JqBoolean.TRUE);
                    return;
                }
            }
            out.accept(JqBoolean.FALSE);
        });

        register("all", 0, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "all");
            out.accept(JqBoolean.of(arr.arrayValue().stream().allMatch(JqValue::isTruthy)));
        });

        register("all", 1, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "all");
            for (JqValue elem : arr.arrayValue()) {
                var results = eval.eval(args.getFirst(), elem, env);
                if (results.stream().noneMatch(JqValue::isTruthy)) {
                    out.accept(JqBoolean.FALSE);
                    return;
                }
            }
            out.accept(JqBoolean.TRUE);
        });

        register("flatten", 0, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "flatten");
            var result = new ArrayList<JqValue>();
            flattenArray(arr, result, Integer.MAX_VALUE);
            out.accept(JqArray.of(result));
        });

        register("flatten", 1, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "flatten");
            eval.eval(args.getFirst(), input, env, depthVal -> {
                int depth = (int) depthVal.longValue();
                if (depth < 0) throw new JqException("flatten depth must not be negative");
                var result = new ArrayList<JqValue>();
                flattenArray(arr, result, depth);
                out.accept(JqArray.of(result));
            });
        });

        register("sort", 0, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "sort");
            var list = new ArrayList<>(arr.arrayValue());
            list.sort(JqValue::compareTo);
            out.accept(JqArray.of(list));
        });

        register("sort_by", 1, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "sort_by");
            var list = new ArrayList<>(arr.arrayValue());
            list.sort((a, b) -> {
                var keysA = eval.eval(args.getFirst(), a, env);
                var keysB = eval.eval(args.getFirst(), b, env);
                for (int i = 0; i < Math.min(keysA.size(), keysB.size()); i++) {
                    int cmp = keysA.get(i).compareTo(keysB.get(i));
                    if (cmp != 0) return cmp;
                }
                return Integer.compare(keysA.size(), keysB.size());
            });
            out.accept(JqArray.of(list));
        });

        register("group_by", 1, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "group_by");
            var groups = new LinkedHashMap<String, List<JqValue>>();
            var sortedList = new ArrayList<>(arr.arrayValue());
            sortedList.sort((a, b) -> {
                JqValue ka = eval.eval(args.getFirst(), a, env).getFirst();
                JqValue kb = eval.eval(args.getFirst(), b, env).getFirst();
                return ka.compareTo(kb);
            });
            for (JqValue elem : sortedList) {
                JqValue key = eval.eval(args.getFirst(), elem, env).getFirst();
                groups.computeIfAbsent(key.toJsonString(), k -> new ArrayList<>()).add(elem);
            }
            var result = groups.values().stream()
                    .map(g -> (JqValue) JqArray.of(g))
                    .toList();
            out.accept(JqArray.of(result));
        });

        register("unique", 0, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "unique");
            var seen = new LinkedHashSet<String>();
            var result = new ArrayList<JqValue>();
            var sorted = new ArrayList<>(arr.arrayValue());
            sorted.sort(JqValue::compareTo);
            for (JqValue v : sorted) {
                if (seen.add(v.toJsonString())) {
                    result.add(v);
                }
            }
            out.accept(JqArray.of(result));
        });

        register("unique_by", 1, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "unique_by");
            var seen = new LinkedHashSet<String>();
            var result = new ArrayList<JqValue>();
            for (JqValue elem : arr.arrayValue()) {
                JqValue key = eval.eval(args.getFirst(), elem, env).getFirst();
                if (seen.add(key.toJsonString())) {
                    result.add(elem);
                }
            }
            out.accept(JqArray.of(result));
        });

        register("reverse", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqArray arr) {
                var list = new ArrayList<>(arr.arrayValue());
                Collections.reverse(list);
                out.accept(JqArray.of(list));
            } else if (input instanceof JqString s) {
                out.accept(JqString.of(new StringBuilder(s.stringValue()).reverse().toString()));
            } else {
                throw new JqException("reverse requires array or string input");
            }
        });

        register("min", 0, (input, args, env, eval, out) -> {
            if (!(input instanceof JqArray arr) || arr.arrayValue().isEmpty()) {
                out.accept(JqNull.NULL);
                return;
            }
            out.accept(arr.arrayValue().stream().min(JqValue::compareTo).orElse(JqNull.NULL));
        });

        register("max", 0, (input, args, env, eval, out) -> {
            if (!(input instanceof JqArray arr) || arr.arrayValue().isEmpty()) {
                out.accept(JqNull.NULL);
                return;
            }
            out.accept(arr.arrayValue().stream().max(JqValue::compareTo).orElse(JqNull.NULL));
        });

        register("min_by", 1, (input, args, env, eval, out) -> {
            if (!(input instanceof JqArray arr) || arr.arrayValue().isEmpty()) {
                out.accept(JqNull.NULL);
                return;
            }
            out.accept(arr.arrayValue().stream()
                    .min((a, b) -> eval.eval(args.getFirst(), a, env).getFirst()
                            .compareTo(eval.eval(args.getFirst(), b, env).getFirst()))
                    .orElse(JqNull.NULL));
        });

        register("max_by", 1, (input, args, env, eval, out) -> {
            if (!(input instanceof JqArray arr) || arr.arrayValue().isEmpty()) {
                out.accept(JqNull.NULL);
                return;
            }
            // jq's max_by returns the LAST element with the max key
            JqValue maxElem = arr.arrayValue().getFirst();
            JqValue maxKey = eval.eval(args.getFirst(), maxElem, env).getFirst();
            for (int i = 1; i < arr.arrayValue().size(); i++) {
                JqValue elem = arr.arrayValue().get(i);
                JqValue key = eval.eval(args.getFirst(), elem, env).getFirst();
                if (key.compareTo(maxKey) >= 0) {
                    maxElem = elem;
                    maxKey = key;
                }
            }
            out.accept(maxElem);
        });

        register("transpose", 0, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "transpose");
            int maxLen = 0;
            for (JqValue v : arr.arrayValue()) {
                if (v instanceof JqArray a) maxLen = Math.max(maxLen, a.arrayValue().size());
            }
            var result = new ArrayList<JqValue>();
            for (int i = 0; i < maxLen; i++) {
                var row = new ArrayList<JqValue>();
                for (JqValue v : arr.arrayValue()) {
                    if (v instanceof JqArray a && i < a.arrayValue().size()) {
                        row.add(a.arrayValue().get(i));
                    } else {
                        row.add(JqNull.NULL);
                    }
                }
                result.add(JqArray.of(row));
            }
            out.accept(JqArray.of(result));
        });

        // Iteration
        register("range", 1, (input, args, env, eval, out) -> {
            eval.eval(args.getFirst(), input, env, endVal -> {
                long end = endVal.longValue();
                for (long i = 0; i < end; i++) {
                    out.accept(JqNumber.of(i));
                }
            });
        });

        register("range", 2, (input, args, env, eval, out) -> {
            eval.eval(args.get(0), input, env, startVal -> {
                eval.eval(args.get(1), input, env, endVal -> {
                    long start = startVal.longValue();
                    long end = endVal.longValue();
                    for (long i = start; i < end; i++) {
                        out.accept(JqNumber.of(i));
                    }
                });
            });
        });

        register("range", 3, (input, args, env, eval, out) -> {
            eval.eval(args.get(0), input, env, startVal -> {
                eval.eval(args.get(1), input, env, endVal -> {
                    eval.eval(args.get(2), input, env, stepVal -> {
                        boolean useDouble = !(startVal instanceof JqNumber sn && sn.isIntegral())
                                || !(endVal instanceof JqNumber en && en.isIntegral())
                                || !(stepVal instanceof JqNumber tn && tn.isIntegral());
                        if (useDouble) {
                            double start = startVal.doubleValue(), end = endVal.doubleValue(), step = stepVal.doubleValue();
                            if (step > 0) {
                                for (double i = start; i < end; i += step) out.accept(JqNumber.of(i));
                            } else if (step < 0) {
                                for (double i = start; i > end; i += step) out.accept(JqNumber.of(i));
                            }
                        } else {
                            long start = startVal.longValue(), end = endVal.longValue(), step = stepVal.longValue();
                            if (step > 0) {
                                for (long i = start; i < end; i += step) out.accept(JqNumber.of(i));
                            } else if (step < 0) {
                                for (long i = start; i > end; i += step) out.accept(JqNumber.of(i));
                            }
                        }
                    });
                });
            });
        });

        register("limit", 2, (input, args, env, eval, out) -> {
            eval.eval(args.get(0), input, env, nVal -> {
                long n = nVal.longValue();
                if (n < 0) throw new JqException("limit doesn't support negative count");
                if (n == 0) return;
                long[] count = {0};
                try {
                    eval.eval(args.get(1), input, env, val -> {
                        out.accept(val);
                        count[0]++;
                        if (count[0] >= n) throw EmptyException.INSTANCE;
                    });
                } catch (EmptyException ignored) {
                } catch (JqException | JqTypeError e) {
                    if (count[0] < n) throw e;
                }
            });
        });

        register("first", 1, (input, args, env, eval, out) -> {
            boolean[] found = {false};
            try {
                eval.eval(args.getFirst(), input, env, val -> {
                    if (!found[0]) {
                        found[0] = true;
                        out.accept(val);
                    }
                });
            } catch (EmptyException ignored) {
            } catch (JqException | JqTypeError e) {
                if (!found[0]) throw e; // propagate error if no value found yet
            }
        });

        register("last", 1, (input, args, env, eval, out) -> {
            JqValue[] last = {null};
            try {
                eval.eval(args.getFirst(), input, env, val -> last[0] = val);
            } catch (EmptyException ignored) {}
            if (last[0] != null) out.accept(last[0]);
        });

        // first/0 = .[0], last/0 = .[-1]
        register("first", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqArray arr && !arr.arrayValue().isEmpty()) {
                out.accept(arr.arrayValue().getFirst());
            }
        });

        register("last", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqArray arr && !arr.arrayValue().isEmpty()) {
                out.accept(arr.arrayValue().getLast());
            }
        });

        register("nth", 1, (input, args, env, eval, out) -> {
            // nth(n) = .[] | ... select nth
            int n = (int) eval.eval(args.getFirst(), input, env).getFirst().longValue();
            if (input instanceof JqArray arr && n >= 0 && n < arr.arrayValue().size()) {
                out.accept(arr.arrayValue().get(n));
            }
        });

        register("recurse", 0, (input, args, env, eval, out) -> {
            recurseValue(input, out);
        });

        register("recurse", 1, (input, args, env, eval, out) -> {
            recurseWithFilter(input, args.getFirst(), env, eval, out, new HashSet<>());
        });

        // recurse(f; cond) - recurse applying f while cond is truthy
        register("recurse", 2, (input, args, env, eval, out) -> {
            recurseWithCondition(input, args.get(0), args.get(1), env, eval, out);
        });

        register("repeat", 1, (input, args, env, eval, out) -> {
            JqValue current = input;
            for (int i = 0; i < 10000; i++) { // safety limit
                out.accept(current);
                var results = eval.eval(args.getFirst(), current, env);
                if (results.isEmpty()) break;
                current = results.getFirst();
            }
        });

        register("while", 2, (input, args, env, eval, out) -> {
            JqValue current = input;
            for (int i = 0; i < 10000; i++) {
                var cond = eval.eval(args.get(0), current, env).getFirst();
                if (!cond.isTruthy()) break;
                out.accept(current);
                current = eval.eval(args.get(1), current, env).getFirst();
            }
        });

        register("until", 2, (input, args, env, eval, out) -> {
            JqValue current = input;
            for (int i = 0; i < 10000; i++) {
                var cond = eval.eval(args.get(0), current, env).getFirst();
                if (cond.isTruthy()) break;
                current = eval.eval(args.get(1), current, env).getFirst();
            }
            out.accept(current);
        });

        // String builtins
        register("tostring", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqString) {
                out.accept(input);
            } else {
                out.accept(JqString.of(input.toJsonString()));
            }
        });

        register("tonumber", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqNumber) {
                out.accept(input);
            } else if (input instanceof JqString s) {
                try {
                    out.accept(JqNumber.of(new BigDecimal(s.stringValue())));
                } catch (NumberFormatException e) {
                    throw new JqException(input.type().jqName() + " (" + input.toJsonString() + ") cannot be parsed as a number");
                }
            } else {
                throw new JqException("Cannot convert " + input.type().jqName() + " to number");
            }
        });

        register("ascii_downcase", 0, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("ascii_downcase requires string");
            out.accept(JqString.of(asciiLowerCase(s.stringValue())));
        });

        register("ascii_upcase", 0, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("ascii_upcase requires string");
            out.accept(JqString.of(asciiUpperCase(s.stringValue())));
        });

        register("split", 1, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("split requires string input");
            JqValue sep = eval.eval(args.getFirst(), input, env).getFirst();
            if (!(sep instanceof JqString sepStr)) throw new JqException("split separator must be string");
            String str = s.stringValue();
            String sepVal = sepStr.stringValue();
            List<JqValue> result;
            if (sepVal.isEmpty()) {
                // split("") splits into individual characters (codepoints)
                result = str.codePoints()
                        .mapToObj(cp -> (JqValue) JqString.of(new String(Character.toChars(cp))))
                        .toList();
            } else {
                String[] parts = str.split(Pattern.quote(sepVal), -1);
                result = Arrays.stream(parts).map(p -> (JqValue) JqString.of(p)).toList();
            }
            out.accept(JqArray.of(result));
        });

        register("join", 1, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "join");
            eval.eval(args.getFirst(), input, env, sep -> {
                String sepStr = sep instanceof JqString s ? s.stringValue() : sep.toJsonString();
                var sb = new StringBuilder();
                for (int i = 0; i < arr.arrayValue().size(); i++) {
                    if (i > 0) sb.append(sepStr);
                    JqValue elem = arr.arrayValue().get(i);
                    if (elem instanceof JqNull) continue; // null treated as empty in join
                    if (elem instanceof JqString s) {
                        sb.append(s.stringValue());
                    } else if (elem instanceof JqNumber || elem instanceof JqBoolean) {
                        sb.append(elem.toJsonString());
                    } else {
                        // jq errors when adding string to array/object
                        throw new JqException("string (\"" + truncateValue(sb.toString()) + "\") and "
                                + elem.type().jqName() + " (" + truncateValue(elem.toJsonString()) + ") cannot be added");
                    }
                }
                out.accept(JqString.of(sb.toString()));
            });
        });

        register("test", 1, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("test requires string input");
            JqValue pattern = eval.eval(args.getFirst(), input, env).getFirst();
            String regex = pattern instanceof JqString ps ? ps.stringValue() : pattern.toJsonString();
            out.accept(JqBoolean.of(Pattern.compile(regex).matcher(s.stringValue()).find()));
        });

        register("test", 2, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("test requires string input");
            String regex = eval.eval(args.get(0), input, env).getFirst().stringValue();
            String flags = eval.eval(args.get(1), input, env).getFirst().stringValue();
            int pFlags = parseRegexFlags(flags);
            out.accept(JqBoolean.of(Pattern.compile(regex, pFlags).matcher(s.stringValue()).find()));
        });

        register("match", 1, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("match requires string input");
            String regex = eval.eval(args.getFirst(), input, env).getFirst().stringValue();
            var matcher = Pattern.compile(regex).matcher(s.stringValue());
            if (matcher.find()) {
                out.accept(buildMatchResult(matcher));
            } else {
                out.accept(JqNull.NULL);
            }
        });

        register("capture", 1, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("capture requires string input");
            String regex = eval.eval(args.getFirst(), input, env).getFirst().stringValue();
            var pattern = Pattern.compile(regex);
            var matcher = pattern.matcher(s.stringValue());
            if (matcher.find()) {
                var map = new LinkedHashMap<String, JqValue>();
                // Extract named groups using pattern
                for (int i = 1; i <= matcher.groupCount(); i++) {
                    String val = matcher.group(i);
                    map.put(String.valueOf(i), val != null ? JqString.of(val) : JqNull.NULL);
                }
                out.accept(JqObject.of(map));
            } else {
                out.accept(JqNull.NULL);
            }
        });

        register("scan", 1, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("scan requires string input");
            String regex = eval.eval(args.getFirst(), input, env).getFirst().stringValue();
            var matcher = Pattern.compile(regex).matcher(s.stringValue());
            while (matcher.find()) {
                if (matcher.groupCount() > 0) {
                    var groups = new ArrayList<JqValue>();
                    for (int i = 1; i <= matcher.groupCount(); i++) {
                        String g = matcher.group(i);
                        groups.add(g != null ? JqString.of(g) : JqNull.NULL);
                    }
                    out.accept(JqArray.of(groups));
                } else {
                    out.accept(JqString.of(matcher.group()));
                }
            }
        });

        register("sub", 2, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("sub requires string input");
            String regex = eval.eval(args.get(0), input, env).getFirst().stringValue();
            // The second arg is a jq expression to apply to the match
            var matcher = Pattern.compile(regex).matcher(s.stringValue());
            if (matcher.find()) {
                String replacement = eval.eval(args.get(1), JqString.of(matcher.group()), env).getFirst().stringValue();
                out.accept(JqString.of(matcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(replacement))));
            } else {
                out.accept(input);
            }
        });

        register("gsub", 2, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("gsub requires string input");
            String regex = eval.eval(args.get(0), input, env).getFirst().stringValue();
            var matcher = Pattern.compile(regex).matcher(s.stringValue());
            var sb = new StringBuilder();
            while (matcher.find()) {
                String replacement = eval.eval(args.get(1), JqString.of(matcher.group()), env).getFirst().stringValue();
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            out.accept(JqString.of(sb.toString()));
        });

        register("startswith", 1, (input, args, env, eval, out) -> {
            JqValue arg = eval.eval(args.getFirst(), input, env).getFirst();
            if (!(input instanceof JqString s) || !(arg instanceof JqString prefix)) {
                throw new JqException("startswith() requires string inputs");
            }
            out.accept(JqBoolean.of(s.stringValue().startsWith(prefix.stringValue())));
        });

        register("endswith", 1, (input, args, env, eval, out) -> {
            JqValue arg = eval.eval(args.getFirst(), input, env).getFirst();
            if (!(input instanceof JqString s) || !(arg instanceof JqString suffix)) {
                throw new JqException("endswith() requires string inputs");
            }
            out.accept(JqBoolean.of(s.stringValue().endsWith(suffix.stringValue())));
        });

        register("ltrimstr", 1, (input, args, env, eval, out) -> {
            JqValue arg = eval.eval(args.getFirst(), input, env).getFirst();
            if (!(input instanceof JqString s) || !(arg instanceof JqString prefix)) {
                throw new JqException("startswith() requires string inputs");
            }
            String str = s.stringValue();
            String p = prefix.stringValue();
            out.accept(JqString.of(str.startsWith(p) ? str.substring(p.length()) : str));
        });

        register("rtrimstr", 1, (input, args, env, eval, out) -> {
            JqValue arg = eval.eval(args.getFirst(), input, env).getFirst();
            if (!(input instanceof JqString s) || !(arg instanceof JqString suffix)) {
                throw new JqException("endswith() requires string inputs");
            }
            String str = s.stringValue();
            String sfx = suffix.stringValue();
            out.accept(JqString.of(str.endsWith(sfx) ? str.substring(0, str.length() - sfx.length()) : str));
        });

        register("explode", 0, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("explode requires string");
            var codepoints = s.stringValue().codePoints()
                    .mapToObj(cp -> (JqValue) JqNumber.of(cp))
                    .toList();
            out.accept(JqArray.of(codepoints));
        });

        register("implode", 0, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "implode");
            var sb = new StringBuilder();
            for (JqValue v : arr.arrayValue()) {
                if (!(v instanceof JqNumber n)) {
                    throw new JqException(v.type().jqName() + " (" + v.toJsonString() + ") can't be imploded, unicode codepoint needs to be numeric");
                }
                if (n.isNaN()) {
                    throw new JqException("number (null) can't be imploded, unicode codepoint needs to be numeric");
                }
                int cp = (int) n.longValue();
                // jq replaces invalid codepoints (negative, > 0x10FFFF, surrogates 0xD800-0xDFFF) with U+FFFD
                if (cp < 0 || !Character.isValidCodePoint(cp)
                        || (cp >= 0xD800 && cp <= 0xDFFF)) {
                    cp = 0xFFFD; // replacement character
                }
                sb.appendCodePoint(cp);
            }
            out.accept(JqString.of(sb.toString()));
        });

        register("indices", 1, (input, args, env, eval, out) -> {
            eval.eval(args.getFirst(), input, env, target -> {
                if (input instanceof JqString s && target instanceof JqString t) {
                    var indices = new ArrayList<JqValue>();
                    String str = s.stringValue();
                    String sub = t.stringValue();
                    int idx = 0;
                    while ((idx = str.indexOf(sub, idx)) >= 0) {
                        // Convert UTF-16 char offset to codepoint offset
                        int cpIdx = str.codePointCount(0, idx);
                        indices.add(JqNumber.of(cpIdx));
                        idx++;
                    }
                    out.accept(JqArray.of(indices));
                } else if (input instanceof JqArray arr) {
                    var indices = new ArrayList<JqValue>();
                    if (target instanceof JqArray sub) {
                        // Subsequence matching
                        List<JqValue> subList = sub.arrayValue();
                        List<JqValue> arrList = arr.arrayValue();
                        for (int i = 0; i <= arrList.size() - subList.size(); i++) {
                            boolean match = true;
                            for (int j = 0; j < subList.size(); j++) {
                                if (!arrList.get(i + j).equals(subList.get(j))) {
                                    match = false;
                                    break;
                                }
                            }
                            if (match) indices.add(JqNumber.of(i));
                        }
                    } else {
                        for (int i = 0; i < arr.arrayValue().size(); i++) {
                            if (arr.arrayValue().get(i).equals(target)) {
                                indices.add(JqNumber.of(i));
                            }
                        }
                    }
                    out.accept(JqArray.of(indices));
                } else {
                    throw new JqException("indices requires string or array");
                }
            });
        });

        register("index", 1, (input, args, env, eval, out) -> {
            eval.eval(args.getFirst(), input, env, target -> {
                if (input instanceof JqString s && target instanceof JqString t) {
                    if (t.stringValue().isEmpty()) {
                        out.accept(JqNull.NULL);
                    } else {
                        String str = s.stringValue();
                        int idx = str.indexOf(t.stringValue());
                        if (idx >= 0) {
                            out.accept(JqNumber.of(str.codePointCount(0, idx)));
                        } else {
                            out.accept(JqNull.NULL);
                        }
                    }
                } else if (input instanceof JqArray arr) {
                    if (target instanceof JqArray sub) {
                        List<JqValue> subList = sub.arrayValue();
                        List<JqValue> arrList = arr.arrayValue();
                        for (int i = 0; i <= arrList.size() - subList.size(); i++) {
                            boolean match = true;
                            for (int j = 0; j < subList.size(); j++) {
                                if (!arrList.get(i + j).equals(subList.get(j))) {
                                    match = false;
                                    break;
                                }
                            }
                            if (match) { out.accept(JqNumber.of(i)); return; }
                        }
                        out.accept(JqNull.NULL);
                    } else {
                        for (int i = 0; i < arr.arrayValue().size(); i++) {
                            if (arr.arrayValue().get(i).equals(target)) {
                                out.accept(JqNumber.of(i));
                                return;
                            }
                        }
                        out.accept(JqNull.NULL);
                    }
                } else {
                    throw new JqException("index requires string or array");
                }
            });
        });

        register("rindex", 1, (input, args, env, eval, out) -> {
            eval.eval(args.getFirst(), input, env, target -> {
                if (input instanceof JqString s && target instanceof JqString t) {
                    String str = s.stringValue();
                    int idx = str.lastIndexOf(t.stringValue());
                    out.accept(idx >= 0 ? JqNumber.of(str.codePointCount(0, idx)) : JqNull.NULL);
                } else if (input instanceof JqArray arr) {
                    if (target instanceof JqArray sub) {
                        List<JqValue> subList = sub.arrayValue();
                        List<JqValue> arrList = arr.arrayValue();
                        for (int i = arrList.size() - subList.size(); i >= 0; i--) {
                            boolean match = true;
                            for (int j = 0; j < subList.size(); j++) {
                                if (!arrList.get(i + j).equals(subList.get(j))) {
                                    match = false;
                                    break;
                                }
                            }
                            if (match) { out.accept(JqNumber.of(i)); return; }
                        }
                        out.accept(JqNull.NULL);
                    } else {
                        for (int i = arr.arrayValue().size() - 1; i >= 0; i--) {
                            if (arr.arrayValue().get(i).equals(target)) {
                                out.accept(JqNumber.of(i));
                                return;
                            }
                        }
                        out.accept(JqNull.NULL);
                    }
                } else {
                    throw new JqException("rindex requires string or array");
                }
            });
        });

        // Math builtins
        register("floor", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of((long) Math.floor(input.doubleValue())));
        });

        register("ceil", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of((long) Math.ceil(input.doubleValue())));
        });

        register("round", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Math.round(input.doubleValue())));
        });

        register("sqrt", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Math.sqrt(input.doubleValue())));
        });

        register("pow", 2, (input, args, env, eval, out) -> {
            double base = eval.eval(args.get(0), input, env).getFirst().doubleValue();
            double exp = eval.eval(args.get(1), input, env).getFirst().doubleValue();
            out.accept(JqNumber.of(Math.pow(base, exp)));
        });

        register("log", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Math.log(input.doubleValue())));
        });

        register("log2", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Math.log(input.doubleValue()) / Math.log(2)));
        });

        register("log10", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Math.log10(input.doubleValue())));
        });

        register("exp", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Math.exp(input.doubleValue())));
        });

        register("exp2", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Math.pow(2, input.doubleValue())));
        });

        register("exp10", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Math.pow(10, input.doubleValue())));
        });

        register("fabs", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Math.abs(input.doubleValue())));
        });

        register("nan", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Double.NaN));
        });

        register("infinite", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Double.POSITIVE_INFINITY));
        });

        register("isinfinite", 0, (input, args, env, eval, out) -> {
            out.accept(JqBoolean.of(Double.isInfinite(input.doubleValue())));
        });

        register("isnan", 0, (input, args, env, eval, out) -> {
            out.accept(JqBoolean.of(Double.isNaN(input.doubleValue())));
        });

        register("isnormal", 0, (input, args, env, eval, out) -> {
            if (!(input instanceof JqNumber)) {
                out.accept(JqBoolean.FALSE);
                return;
            }
            double d = input.doubleValue();
            out.accept(JqBoolean.of(!Double.isNaN(d) && !Double.isInfinite(d) && d != 0));
        });

        register("isfinite", 0, (input, args, env, eval, out) -> {
            out.accept(JqBoolean.of(Double.isFinite(input.doubleValue())));
        });

        // Path builtins
        register("path", 1, (input, args, env, eval, out) -> {
            eval.eval(new JqExpr.PathExpr(args.getFirst()), input, env, out);
        });

        register("getpath", 1, (input, args, env, eval, out) -> {
            JqValue pathArr = eval.eval(args.getFirst(), input, env).getFirst();
            if (!(pathArr instanceof JqArray arr)) throw new JqException("getpath requires array argument");
            JqValue current = input;
            for (JqValue p : arr.arrayValue()) {
                current = switch (current) {
                    case JqObject obj when p instanceof JqString s -> obj.get(s.stringValue());
                    case JqArray a when p instanceof JqNumber n -> a.get((int) n.longValue());
                    case JqNull ignored -> JqNull.NULL;
                    default -> JqNull.NULL;
                };
            }
            out.accept(current);
        });

        register("setpath", 2, (input, args, env, eval, out) -> {
            JqValue pathArr = eval.eval(args.get(0), input, env).getFirst();
            JqValue value = eval.eval(args.get(1), input, env).getFirst();
            if (!(pathArr instanceof JqArray arr)) throw new JqException("setpath requires array path");
            out.accept(setPath(input, arr.arrayValue(), 0, value));
        });

        register("delpaths", 1, (input, args, env, eval, out) -> {
            JqValue pathsArr = eval.eval(args.getFirst(), input, env).getFirst();
            if (!(pathsArr instanceof JqArray paths)) throw new JqException("Paths must be specified as an array");
            JqValue result = input;
            // Delete paths in reverse order to maintain indices
            var pathList = new ArrayList<>(paths.arrayValue());
            pathList.sort((a, b) -> {
                // Sort by length descending, then by index descending
                var aArr = ((JqArray) a).arrayValue();
                var bArr = ((JqArray) b).arrayValue();
                return -Integer.compare(aArr.size(), bArr.size());
            });
            for (JqValue p : pathList) {
                if (p instanceof JqArray pathArr) {
                    result = deletePath(result, pathArr.arrayValue(), 0);
                }
            }
            out.accept(result);
        });

        register("leaf_paths", 0, (input, args, env, eval, out) -> {
            leafPaths(input, new ArrayList<>(), out);
        });

        // Format builtins (as zero-arg functions)
        register("tojson", 0, (input, args, env, eval, out) -> {
            out.accept(JqString.of(input.toJsonString()));
        });

        register("fromjson", 0, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("fromjson requires string");
            try {
                out.accept(JqValues.parseStrict(s.stringValue()));
            } catch (IllegalArgumentException e) {
                throw new JqException(e.getMessage());
            }
        });

        register("ascii", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqNumber n) {
                out.accept(JqString.of(String.valueOf((char) n.longValue())));
            } else {
                throw new JqException("ascii requires number input");
            }
        });

        // Object builtins
        register("to_entries", 0, (input, args, env, eval, out) -> {
            if (!(input instanceof JqObject obj)) throw new JqException("to_entries requires object");
            var entries = new ArrayList<JqValue>();
            for (var entry : obj.objectValue().entrySet()) {
                var map = new LinkedHashMap<String, JqValue>();
                map.put("key", JqString.of(entry.getKey()));
                map.put("value", entry.getValue());
                entries.add(JqObject.of(map));
            }
            out.accept(JqArray.of(entries));
        });

        register("from_entries", 0, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "from_entries");
            var map = new LinkedHashMap<String, JqValue>();
            for (JqValue v : arr.arrayValue()) {
                if (v instanceof JqObject obj) {
                    // jq supports "key", "Key", "name", "Name" for the key field
                    JqValue key = obj.get("key");
                    if (key instanceof JqNull) key = obj.get("Key");
                    if (key instanceof JqNull) key = obj.get("name");
                    if (key instanceof JqNull) key = obj.get("Name");
                    // jq supports "value" and "Value" for the value field
                    JqValue value = obj.get("value");
                    if (value instanceof JqNull) {
                        JqValue altValue = obj.get("Value");
                        if (!(altValue instanceof JqNull)) value = altValue;
                    }
                    String keyStr = key instanceof JqString s ? s.stringValue() : key.toJsonString();
                    map.put(keyStr, value);
                }
            }
            out.accept(JqObject.of(map));
        });

        register("with_entries", 1, (input, args, env, eval, out) -> {
            if (!(input instanceof JqObject obj)) throw new JqException("with_entries requires object");
            var entries = new ArrayList<JqValue>();
            for (var entry : obj.objectValue().entrySet()) {
                var entryObj = new LinkedHashMap<String, JqValue>();
                entryObj.put("key", JqString.of(entry.getKey()));
                entryObj.put("value", entry.getValue());
                eval.eval(args.getFirst(), JqObject.of(entryObj), env, entries::add);
            }
            var map = new LinkedHashMap<String, JqValue>();
            for (JqValue v : entries) {
                if (v instanceof JqObject o) {
                    JqValue key = o.get("key");
                    if (key instanceof JqNull) key = o.get("name");
                    JqValue value = o.get("value");
                    String keyStr = key instanceof JqString s ? s.stringValue() : key.toJsonString();
                    map.put(keyStr, value);
                }
            }
            out.accept(JqObject.of(map));
        });

        // Misc
        register("debug", 0, (input, args, env, eval, out) -> {
            System.err.println("[\"DEBUG:\","+input.toJsonString()+"]");
            out.accept(input);
        });

        register("debug", 1, (input, args, env, eval, out) -> {
            JqValue msg = eval.eval(args.getFirst(), input, env).getFirst();
            System.err.println("[\"DEBUG:\","+msg.toJsonString()+"]");
            out.accept(input);
        });

        register("stderr", 0, (input, args, env, eval, out) -> {
            System.err.println(input.toJsonString());
            out.accept(input);
        });

        register("builtins", 0, (input, args, env, eval, out) -> {
            var names = builtins.keySet().stream()
                    .sorted()
                    .map(k -> (JqValue) JqString.of(k))
                    .toList();
            out.accept(JqArray.of(names));
        });

        // have_decnum — jjq uses BigDecimal internally, so decimal number support is available
        register("have_decnum", 0, (input, args, env, eval, out) -> {
            out.accept(JqBoolean.TRUE);
        });

        register("env", 0, (input, args, env, eval, out) -> {
            var map = new LinkedHashMap<String, JqValue>();
            System.getenv().forEach((k, v) -> map.put(k, JqString.of(v)));
            out.accept(JqObject.of(map));
        });

        register("input", 0, (input, args, env, eval, out) -> {
            if (env.hasInputs()) {
                JqValue next = env.nextInput();
                if (next != null) {
                    out.accept(next);
                    return;
                }
            }
            throw new JqException("break");
        });

        register("inputs", 0, (input, args, env, eval, out) -> {
            if (env.hasInputs()) {
                for (JqValue v : env.remainingInputs()) {
                    out.accept(v);
                }
                return;
            }
            throw new JqException("inputs not available in this context");
        });

        // --- Phase 2 builtins ---

        // del(path_expr) - delete elements at the given paths
        register("del", 1, (input, args, env, eval, out) -> {
            JqExpr pathExpr = args.getFirst();
            // Flatten comma expressions and collect all deletion targets
            var subExprs = new ArrayList<JqExpr>();
            flattenComma(pathExpr, subExprs);

            // For flat array deletions (mix of indices and slices), collect all indices to remove
            boolean allFlat = input instanceof JqArray && subExprs.stream().allMatch(
                    e -> e instanceof JqExpr.IndexExpr || e instanceof JqExpr.SliceExpr
                            || e instanceof JqExpr.IterateExpr);
            if (allFlat) {
                JqArray arr = (JqArray) input;
                int len = arr.arrayValue().size();
                var toRemove = new java.util.TreeSet<Integer>();
                for (JqExpr sub : subExprs) {
                    if (sub instanceof JqExpr.IndexExpr idx) {
                        for (JqValue index : eval.eval(idx.index(), input, env)) {
                            if (index instanceof JqNumber n && n.isNaN()) continue;
                            int i = (int) ((JqNumber) index).longValue();
                            if (i < 0) i += len;
                            if (i >= 0 && i < len) toRemove.add(i);
                        }
                    } else if (sub instanceof JqExpr.SliceExpr sl) {
                        Integer fromIdx = sl.from() != null ? (int) ((JqNumber) eval.eval(sl.from(), input, env).getFirst()).longValue() : null;
                        Integer toIdx = sl.to() != null ? (int) ((JqNumber) eval.eval(sl.to(), input, env).getFirst()).longValue() : null;
                        int start = fromIdx == null ? 0 : (fromIdx < 0 ? Math.max(0, len + fromIdx) : Math.min(fromIdx, len));
                        int end = toIdx == null ? len : (toIdx < 0 ? Math.max(0, len + toIdx) : Math.min(toIdx, len));
                        for (int i = start; i < end; i++) toRemove.add(i);
                    } else if (sub instanceof JqExpr.IterateExpr) {
                        for (int i = 0; i < len; i++) toRemove.add(i);
                    }
                }
                var result = new ArrayList<JqValue>();
                for (int i = 0; i < len; i++) {
                    if (!toRemove.contains(i)) result.add(arr.arrayValue().get(i));
                }
                out.accept(JqArray.of(result));
                return;
            }

            // General case: collect paths, sort, and delete
            var paths = new ArrayList<List<JqValue>>();
            for (JqExpr sub : subExprs) {
                if (containsSlice(sub)) {
                    // For slice in non-flat context, handle via updatePath
                    input = eval.updatePath(sub, input, env, old -> List.of());
                } else {
                    collectPaths(sub, input, env, eval, paths);
                }
            }
            JqValue result = input;
            // Sort paths: deeper first, higher array indices first
            paths.sort((a, b) -> {
                int lenCmp = Integer.compare(b.size(), a.size());
                if (lenCmp != 0) return lenCmp;
                for (int i = 0; i < a.size(); i++) {
                    if (a.get(i) instanceof JqNumber na && b.get(i) instanceof JqNumber nb) {
                        int cmp = Long.compare(nb.longValue(), na.longValue());
                        if (cmp != 0) return cmp;
                    }
                }
                return 0;
            });
            for (List<JqValue> path : paths) {
                result = deletePath(result, path, 0);
            }
            out.accept(result);
        });

        // paths - output all paths as arrays
        register("paths", 0, (input, args, env, eval, out) -> {
            allPaths(input, new ArrayList<>(), out);
        });

        // paths(filter) - output paths where filter is truthy
        register("paths", 1, (input, args, env, eval, out) -> {
            allPathsFiltered(input, new ArrayList<>(), args.getFirst(), env, eval, out);
        });

        // scalars - select scalar values
        register("scalars", 0, (input, args, env, eval, out) -> {
            if (!(input instanceof JqArray) && !(input instanceof JqObject)) {
                out.accept(input);
            }
        });

        // nulls, booleans, numbers, strings, arrays, objects, iterables, values - type selectors
        register("nulls", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqNull) out.accept(input);
        });
        register("booleans", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqBoolean) out.accept(input);
        });
        register("numbers", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqNumber) out.accept(input);
        });
        register("strings", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqString) out.accept(input);
        });
        register("arrays", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqArray) out.accept(input);
        });
        register("objects", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqObject) out.accept(input);
        });
        register("iterables", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqArray || input instanceof JqObject) out.accept(input);
        });
        // values/0 already registered above as a type-selector filter

        // isempty(expr) - true if expr produces no output
        register("isempty", 1, (input, args, env, eval, out) -> {
            boolean[] found = {false};
            try {
                eval.eval(args.getFirst(), input, env, val -> found[0] = true);
            } catch (EmptyException ignored) {
            } catch (JqException | JqTypeError e) {
                if (!found[0]) throw e;
            }
            out.accept(JqBoolean.of(!found[0]));
        });

        // any(generator; condition) - two-arg any
        register("any", 2, (input, args, env, eval, out) -> {
            boolean[] found = {false};
            try {
                eval.eval(args.get(0), input, env, val -> {
                    var conds = eval.eval(args.get(1), val, env);
                    if (conds.stream().anyMatch(JqValue::isTruthy)) {
                        found[0] = true;
                    }
                });
            } catch (EmptyException ignored) {
            } catch (JqException | JqTypeError e) {
                if (!found[0]) throw e;
            }
            out.accept(JqBoolean.of(found[0]));
        });

        // all(generator; condition) - two-arg all
        register("all", 2, (input, args, env, eval, out) -> {
            boolean[] allMatch = {true};
            boolean[] foundFalse = {false};
            try {
                eval.eval(args.get(0), input, env, val -> {
                    var conds = eval.eval(args.get(1), val, env);
                    if (conds.stream().noneMatch(JqValue::isTruthy)) {
                        allMatch[0] = false;
                        foundFalse[0] = true;
                    }
                });
            } catch (EmptyException ignored) {
            } catch (JqException | JqTypeError e) {
                if (!foundFalse[0]) throw e;
            }
            out.accept(JqBoolean.of(allMatch[0]));
        });

        // halt - exit with code 0
        register("halt", 0, (input, args, env, eval, out) -> {
            throw new HaltException(0, null);
        });

        // halt_error - exit with error
        register("halt_error", 0, (input, args, env, eval, out) -> {
            throw new HaltException(5, input instanceof JqString s ? s.stringValue() : input.toJsonString());
        });

        // halt_error(code)
        register("halt_error", 1, (input, args, env, eval, out) -> {
            int code = (int) eval.eval(args.getFirst(), input, env).getFirst().longValue();
            throw new HaltException(code, input instanceof JqString s ? s.stringValue() : input.toJsonString());
        });

        // Date builtins
        register("now", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(System.currentTimeMillis() / 1000.0));
        });

        register("todate", 0, (input, args, env, eval, out) -> {
            long epoch = input.longValue();
            var instant = java.time.Instant.ofEpochSecond(epoch);
            out.accept(JqString.of(java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant)));
        });

        register("fromdate", 0, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("fromdate requires string input");
            var instant = java.time.Instant.parse(s.stringValue());
            out.accept(JqNumber.of(instant.getEpochSecond()));
        });

        register("strftime", 1, (input, args, env, eval, out) -> {
            JqValue fmtVal = eval.eval(args.getFirst(), input, env).getFirst();
            if (!(fmtVal instanceof JqString)) throw new JqException("strftime/1 requires a string format");
            String format = fmtVal.stringValue();
            java.time.ZonedDateTime zdt;
            if (input instanceof JqNumber n) {
                zdt = java.time.ZonedDateTime.ofInstant(
                        java.time.Instant.ofEpochSecond(n.longValue()), java.time.ZoneOffset.UTC);
            } else if (input instanceof JqArray arr && !arr.arrayValue().isEmpty()) {
                for (int i = 0; i < arr.arrayValue().size(); i++) {
                    if (!(arr.arrayValue().get(i) instanceof JqNumber)) {
                        throw new JqException("strftime/1 requires parsed datetime inputs");
                    }
                }
                zdt = brokenDownTimeToZdt(arr);
            } else {
                throw new JqException("strftime/1 requires parsed datetime inputs");
            }
            var formatter = java.time.format.DateTimeFormatter.ofPattern(
                    jqFormatToJava(format));
            out.accept(JqString.of(formatter.format(zdt)));
        });

        register("gmtime", 0, (input, args, env, eval, out) -> {
            double epochDouble = input.doubleValue();
            long epochSec = (long) Math.floor(epochDouble);
            double frac = epochDouble - epochSec;
            var instant = java.time.Instant.ofEpochSecond(epochSec);
            var zdt = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
            JqValue seconds = frac != 0.0
                    ? JqNumber.of(zdt.getSecond() + frac)
                    : JqNumber.of(zdt.getSecond());
            out.accept(JqArray.of(List.of(
                    JqNumber.of(zdt.getYear()),
                    JqNumber.of(zdt.getMonthValue() - 1),
                    JqNumber.of(zdt.getDayOfMonth()),
                    JqNumber.of(zdt.getHour()),
                    JqNumber.of(zdt.getMinute()),
                    seconds,
                    JqNumber.of(zdt.getDayOfWeek().getValue() % 7),
                    JqNumber.of(zdt.getDayOfYear() - 1)
            )));
        });

        register("mktime", 0, (input, args, env, eval, out) -> {
            if (!(input instanceof JqArray arr) || arr.arrayValue().isEmpty()) {
                throw new JqException("mktime requires parsed datetime inputs");
            }
            // Validate all elements are numeric
            for (int i = 0; i < arr.arrayValue().size(); i++) {
                if (!(arr.arrayValue().get(i) instanceof JqNumber)) {
                    throw new JqException("mktime requires parsed datetime inputs");
                }
            }
            out.accept(JqNumber.of(brokenDownTimeToZdt(arr).toEpochSecond()));
        });

        register("dateadd", 2, (input, args, env, eval, out) -> {
            String unit = eval.eval(args.get(0), input, env).getFirst().stringValue();
            long amount = eval.eval(args.get(1), input, env).getFirst().longValue();
            long epoch = input.longValue();
            var instant = java.time.Instant.ofEpochSecond(epoch);
            instant = switch (unit) {
                case "years" -> instant.atZone(java.time.ZoneOffset.UTC).plusYears(amount).toInstant();
                case "months" -> instant.atZone(java.time.ZoneOffset.UTC).plusMonths(amount).toInstant();
                case "days" -> instant.plusSeconds(amount * 86400);
                case "hours" -> instant.plusSeconds(amount * 3600);
                case "minutes" -> instant.plusSeconds(amount * 60);
                case "seconds" -> instant.plusSeconds(amount);
                default -> throw new JqException("Unknown time unit: " + unit);
            };
            out.accept(JqNumber.of(instant.getEpochSecond()));
        });

        register("datesub", 2, (input, args, env, eval, out) -> {
            String unit = eval.eval(args.get(0), input, env).getFirst().stringValue();
            long amount = eval.eval(args.get(1), input, env).getFirst().longValue();
            long epoch = input.longValue();
            var instant = java.time.Instant.ofEpochSecond(epoch);
            instant = switch (unit) {
                case "years" -> instant.atZone(java.time.ZoneOffset.UTC).minusYears(amount).toInstant();
                case "months" -> instant.atZone(java.time.ZoneOffset.UTC).minusMonths(amount).toInstant();
                case "days" -> instant.minusSeconds(amount * 86400);
                case "hours" -> instant.minusSeconds(amount * 3600);
                case "minutes" -> instant.minusSeconds(amount * 60);
                case "seconds" -> instant.minusSeconds(amount);
                default -> throw new JqException("Unknown time unit: " + unit);
            };
            out.accept(JqNumber.of(instant.getEpochSecond()));
        });

        // strflocaltime — same as strftime but with local time zone (simplified: use UTC)
        register("strflocaltime", 1, (input, args, env, eval, out) -> {
            eval.eval(args.getFirst(), input, env, fmtVal -> {
            if (!(fmtVal instanceof JqString)) throw new JqException("strflocaltime/1 requires a string format");
            String format = fmtVal.stringValue();
            java.time.ZonedDateTime zdt;
            if (input instanceof JqNumber n) {
                zdt = java.time.ZonedDateTime.ofInstant(
                        java.time.Instant.ofEpochSecond(n.longValue()), java.time.ZoneOffset.UTC);
            } else if (input instanceof JqArray arr && !arr.arrayValue().isEmpty()) {
                for (int i = 0; i < arr.arrayValue().size(); i++) {
                    if (!(arr.arrayValue().get(i) instanceof JqNumber)) {
                        throw new JqException("strflocaltime/1 requires parsed datetime inputs");
                    }
                }
                zdt = brokenDownTimeToZdt(arr);
            } else {
                throw new JqException("strflocaltime/1 requires parsed datetime inputs");
            }
            var formatter = java.time.format.DateTimeFormatter.ofPattern(jqFormatToJava(format));
            out.accept(JqString.of(formatter.format(zdt)));
            });
        });

        // strptime — parse a date string according to a format, returning broken-down time
        register("strptime", 1, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("strptime/1 requires string input");
            JqValue fmtVal = eval.eval(args.getFirst(), input, env).getFirst();
            if (!(fmtVal instanceof JqString)) throw new JqException("strptime/1 requires a string format");
            String format = fmtVal.stringValue();
            var formatter = java.time.format.DateTimeFormatter.ofPattern(jqFormatToJava(format));
            var parsed = java.time.LocalDateTime.parse(s.stringValue(), formatter);
            var zdt = parsed.atZone(java.time.ZoneOffset.UTC);
            out.accept(JqArray.of(List.of(
                    JqNumber.of(zdt.getYear()),
                    JqNumber.of(zdt.getMonthValue() - 1),
                    JqNumber.of(zdt.getDayOfMonth()),
                    JqNumber.of(zdt.getHour()),
                    JqNumber.of(zdt.getMinute()),
                    JqNumber.of(zdt.getSecond()),
                    JqNumber.of(zdt.getDayOfWeek().getValue() % 7),
                    JqNumber.of(zdt.getDayOfYear() - 1)
            )));
        });

        // ascii - convert number to character
        register("ascii", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqNumber n) {
                out.accept(JqString.of(String.valueOf((char) n.longValue())));
            } else {
                throw new JqException("ascii requires number input");
            }
        });

        // splits(regex) - like split but outputs a stream
        register("splits", 1, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("splits requires string input");
            String regex = eval.eval(args.getFirst(), input, env).getFirst().stringValue();
            var parts = Pattern.compile(regex).split(s.stringValue(), -1);
            for (String part : parts) {
                out.accept(JqString.of(part));
            }
        });

        register("splits", 2, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("splits requires string input");
            String regex = eval.eval(args.get(0), input, env).getFirst().stringValue();
            String flags = eval.eval(args.get(1), input, env).getFirst().stringValue();
            int pFlags = parseRegexFlags(flags);
            var parts = Pattern.compile(regex, pFlags).split(s.stringValue(), -1);
            for (String part : parts) {
                out.accept(JqString.of(part));
            }
        });

        // test with flags
        // (test/2 already registered above)

        // match with flags
        register("match", 2, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("match requires string input");
            String regex = eval.eval(args.get(0), input, env).getFirst().stringValue();
            String flags = eval.eval(args.get(1), input, env).getFirst().stringValue();
            int pFlags = parseRegexFlags(flags);
            boolean global = flags.contains("g");
            var pattern = Pattern.compile(regex, pFlags);
            var matcher = pattern.matcher(s.stringValue());
            if (global) {
                while (matcher.find()) {
                    out.accept(buildMatchResult(matcher));
                }
            } else {
                if (matcher.find()) {
                    out.accept(buildMatchResult(matcher));
                } else {
                    out.accept(JqNull.NULL);
                }
            }
        });

        // limit(n; expr) already registered above

        // nth(n; expr)
        register("nth", 2, (input, args, env, eval, out) -> {
            eval.eval(args.get(0), input, env, nVal -> {
                long n = nVal.longValue();
                if (n < 0) throw new JqException("nth doesn't support negative indices");
                int[] count = {0};
                boolean[] found = {false};
                try {
                    eval.eval(args.get(1), input, env, val -> {
                        if (count[0] == n) {
                            found[0] = true;
                            out.accept(val);
                            throw EmptyException.INSTANCE;
                        }
                        count[0]++;
                    });
                } catch (EmptyException ignored) {
                } catch (JqException | JqTypeError e) {
                    if (!found[0]) throw e;
                }
            });
        });

        // getpath already registered, setpath already registered

        // path(expr) already registered

        // type_error helper
        register("type_error", 0, (input, args, env, eval, out) -> {
            throw new JqException(input.type().jqName() + " is not valid");
        });

        // length already handles null=0, but ensure strings count codepoints
        // (already correct - String.length() counts chars which matches jq for BMP)

        // ltrimstr/rtrimstr already registered

        // @base32 and @base32d formats
        // (handled in evaluator's evalFormat)

        // infinite/nan already registered

        // object operations
        register("keys_unsorted", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqObject obj) {
                var keys = obj.objectValue().keySet().stream()
                        .map(k -> (JqValue) JqString.of(k))
                        .toList();
                out.accept(JqArray.of(keys));
            } else {
                throw new JqException("keys_unsorted requires object input");
            }
        });

        // limit/first/last for generators - already registered above

        // label-$out | ... break $out - handled in evaluator

        // abs
        register("abs", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqNumber n) {
                out.accept(JqNumber.of(n.decimalValue().abs()));
            } else if (input instanceof JqNull) {
                out.accept(JqNull.NULL);
            } else {
                // jq: abs on non-number returns the value itself
                out.accept(input);
            }
        });

        // min_by/max_by already registered

        // group_by already registered

        // unique_by already registered

        // ascii_downcase/ascii_upcase already registered

        // @text format (identity for strings, tostring for others)
        // handled in evalFormat

        // getpath for nested paths already works

        // with_entries already registered

        // combinations
        register("combinations", 0, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "combinations");
            var arrays = new ArrayList<List<JqValue>>();
            for (JqValue v : arr.arrayValue()) {
                if (v instanceof JqArray a) {
                    arrays.add(a.arrayValue());
                } else {
                    throw new JqException("combinations requires array of arrays");
                }
            }
            generateCombinations(arrays, 0, new ArrayList<>(), out);
        });

        register("combinations", 1, (input, args, env, eval, out) -> {
            int n = (int) eval.eval(args.getFirst(), input, env).getFirst().longValue();
            JqArray arr = requireArray(input, "combinations");
            var arrays = new ArrayList<List<JqValue>>();
            for (int i = 0; i < n; i++) {
                arrays.add(arr.arrayValue());
            }
            generateCombinations(arrays, 0, new ArrayList<>(), out);
        });

        // tojson/fromjson already registered

        // to_entries/from_entries/with_entries already registered

        // walk(f) - recursively apply f to all values bottom-up
        register("walk", 1, (input, args, env, eval, out) -> {
            // walk recurses into children, then applies filter at top level
            // For children, only the first result is used; at top level, all results are emitted
            JqValue transformed = walkTransform(input, args.getFirst(), env, eval);
            eval.eval(args.getFirst(), transformed, env, out);
        });

        // bsearch(x) - binary search in sorted array
        register("bsearch", 1, (input, args, env, eval, out) -> {
            JqArray arr = requireArray(input, "bsearch");
            eval.eval(args.getFirst(), input, env, target -> {
                List<JqValue> list = arr.arrayValue();
                int lo = 0, hi = list.size() - 1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    int cmp = list.get(mid).compareTo(target);
                    if (cmp < 0) lo = mid + 1;
                    else if (cmp > 0) hi = mid - 1;
                    else { out.accept(JqNumber.of(mid)); return; }
                }
                out.accept(JqNumber.of(-lo - 1));
            });
        });

        // skip(n; generator) - skip the first n outputs of a generator
        register("skip", 2, (input, args, env, eval, out) -> {
            eval.eval(args.get(0), input, env, nVal -> {
                int n = (int) nVal.longValue();
                if (n < 0) throw new JqException("skip doesn't support negative count");
                int[] count = {0};
                try {
                    eval.eval(args.get(1), input, env, val -> {
                        if (count[0] >= n) out.accept(val);
                        count[0]++;
                    });
                } catch (EmptyException ignored) {}
            });
        });

        // INDEX(stream; idx_expr) - build object from stream
        register("INDEX", 2, (input, args, env, eval, out) -> {
            var map = new LinkedHashMap<String, JqValue>();
            try {
                eval.eval(args.get(0), input, env, val -> {
                    var keys = eval.eval(args.get(1), val, env);
                    if (!keys.isEmpty()) {
                        String key = keys.getFirst() instanceof JqString s ? s.stringValue() : keys.getFirst().toJsonString();
                        map.put(key, val);
                    }
                });
            } catch (EmptyException ignored) {}
            out.accept(JqObject.of(map));
        });

        // env already registered

        // @base32 and @base32d - simple Base32 encoding
        // (handled via evalFormat in evaluator)

        // getpath with nested already works

        // input_line_number (stub)
        register("input_line_number", 0, (input, args, env, eval, out) -> {
            out.accept(JqNull.NULL);
        });

        // indices already registered

        // inside already registered

        // Numeric builtins
        register("significand", 0, (input, args, env, eval, out) -> {
            double d = input.doubleValue();
            int exp = Math.getExponent(d);
            out.accept(JqNumber.of(d / Math.pow(2, exp)));
        });

        register("exponent", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Math.getExponent(input.doubleValue())));
        });

        register("drem", 2, (input, args, env, eval, out) -> {
            double x = eval.eval(args.get(0), input, env).getFirst().doubleValue();
            double y = eval.eval(args.get(1), input, env).getFirst().doubleValue();
            out.accept(JqNumber.of(Math.IEEEremainder(x, y)));
        });

        register("lgamma", 0, (input, args, env, eval, out) -> {
            // Log of gamma function - approximation
            double x = input.doubleValue();
            // Use Stirling's approximation for lgamma
            out.accept(JqNumber.of(logGamma(x)));
        });

        register("tgamma", 0, (input, args, env, eval, out) -> {
            double x = input.doubleValue();
            out.accept(JqNumber.of(Math.exp(logGamma(x))));
        });

        register("j0", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(besselJ0(input.doubleValue())));
        });

        register("j1", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(besselJ1(input.doubleValue())));
        });

        register("rint", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Math.rint(input.doubleValue())));
        });

        register("trunc", 0, (input, args, env, eval, out) -> {
            double d = input.doubleValue();
            out.accept(JqNumber.of(d >= 0 ? Math.floor(d) : Math.ceil(d)));
        });

        register("nearbyint", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Math.rint(input.doubleValue())));
        });

        register("logb", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Math.getExponent(input.doubleValue())));
        });

        register("cbrt", 0, (input, args, env, eval, out) -> {
            out.accept(JqNumber.of(Math.cbrt(input.doubleValue())));
        });

        register("sin", 0, (input, args, env, eval, out) ->
                out.accept(JqNumber.of(Math.sin(input.doubleValue()))));
        register("cos", 0, (input, args, env, eval, out) ->
                out.accept(JqNumber.of(Math.cos(input.doubleValue()))));
        register("tan", 0, (input, args, env, eval, out) ->
                out.accept(JqNumber.of(Math.tan(input.doubleValue()))));
        register("asin", 0, (input, args, env, eval, out) ->
                out.accept(JqNumber.of(Math.asin(input.doubleValue()))));
        register("acos", 0, (input, args, env, eval, out) ->
                out.accept(JqNumber.of(Math.acos(input.doubleValue()))));
        register("atan", 0, (input, args, env, eval, out) ->
                out.accept(JqNumber.of(Math.atan(input.doubleValue()))));
        register("atan2", 2, (input, args, env, eval, out) -> {
            double y = eval.eval(args.get(0), input, env).getFirst().doubleValue();
            double x = eval.eval(args.get(1), input, env).getFirst().doubleValue();
            out.accept(JqNumber.of(Math.atan2(y, x)));
        });
        register("sinh", 0, (input, args, env, eval, out) ->
                out.accept(JqNumber.of(Math.sinh(input.doubleValue()))));
        register("cosh", 0, (input, args, env, eval, out) ->
                out.accept(JqNumber.of(Math.cosh(input.doubleValue()))));
        register("tanh", 0, (input, args, env, eval, out) ->
                out.accept(JqNumber.of(Math.tanh(input.doubleValue()))));
        register("asinh", 0, (input, args, env, eval, out) -> {
            double d = input.doubleValue();
            out.accept(JqNumber.of(Math.log(d + Math.sqrt(d * d + 1))));
        });
        register("acosh", 0, (input, args, env, eval, out) -> {
            double d = input.doubleValue();
            out.accept(JqNumber.of(Math.log(d + Math.sqrt(d * d - 1))));
        });
        register("atanh", 0, (input, args, env, eval, out) -> {
            double d = input.doubleValue();
            out.accept(JqNumber.of(0.5 * Math.log((1 + d) / (1 - d))));
        });

        register("remainder", 2, (input, args, env, eval, out) -> {
            double x = eval.eval(args.get(0), input, env).getFirst().doubleValue();
            double y = eval.eval(args.get(1), input, env).getFirst().doubleValue();
            out.accept(JqNumber.of(Math.IEEEremainder(x, y)));
        });

        register("hypot", 2, (input, args, env, eval, out) -> {
            double x = eval.eval(args.get(0), input, env).getFirst().doubleValue();
            double y = eval.eval(args.get(1), input, env).getFirst().doubleValue();
            out.accept(JqNumber.of(Math.hypot(x, y)));
        });

        register("fma", 3, (input, args, env, eval, out) -> {
            double x = eval.eval(args.get(0), input, env).getFirst().doubleValue();
            double y = eval.eval(args.get(1), input, env).getFirst().doubleValue();
            double z = eval.eval(args.get(2), input, env).getFirst().doubleValue();
            out.accept(JqNumber.of(Math.fma(x, y, z)));
        });

        // String operations — trim family (Unicode-aware)
        register("trim", 0, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("trim input must be a string");
            out.accept(JqString.of(unicodeTrim(s.stringValue())));
        });
        register("ltrim", 0, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("trim input must be a string");
            out.accept(JqString.of(unicodeLtrim(s.stringValue())));
        });
        register("rtrim", 0, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("trim input must be a string");
            out.accept(JqString.of(unicodeRtrim(s.stringValue())));
        });
        register("trimstr", 1, (input, args, env, eval, out) -> {
            if (!(input instanceof JqString s)) throw new JqException("trimstr requires string");
            String fix = eval.eval(args.getFirst(), input, env).getFirst().stringValue();
            String str = s.stringValue();
            // Trim from both ends
            if (fix.isEmpty()) { out.accept(input); return; }
            while (str.startsWith(fix)) str = str.substring(fix.length());
            while (str.endsWith(fix)) str = str.substring(0, str.length() - fix.length());
            out.accept(JqString.of(str));
        });

        // toboolean
        register("toboolean", 0, (input, args, env, eval, out) -> {
            if (input instanceof JqBoolean) { out.accept(input); return; }
            if (input instanceof JqString s) {
                String v = s.stringValue();
                if (v.equals("true")) { out.accept(JqBoolean.TRUE); return; }
                if (v.equals("false")) { out.accept(JqBoolean.FALSE); return; }
                throw new JqException("string (" + JqString.of(v).toJsonString() + ") cannot be parsed as a boolean");
            }
            throw new JqException(input.type().jqName() + " (" + input.toJsonString() + ") cannot be parsed as a boolean");
        });

        // pick/1 — select paths from input
        register("pick", 1, (input, args, env, eval, out) -> {
            // Evaluate the path expression to get paths
            var paths = new ArrayList<List<JqValue>>();
            eval.eval(new JqExpr.FuncCallExpr("path", args), input, env, pathVal -> {
                if (pathVal instanceof JqArray arr) {
                    paths.add(arr.arrayValue());
                }
            });
            JqValue result = JqNull.NULL;
            for (List<JqValue> path : paths) {
                JqValue val = getPath(input, path, 0);
                result = setPath(result, path, 0, val);
            }
            out.accept(result);
        });

        // IN/1 and IN/2 — membership test
        register("IN", 1, (input, args, env, eval, out) -> {
            // input | IN(generator) — tests if input is in the generator's outputs
            var values = eval.eval(args.getFirst(), input, env);
            for (JqValue v : values) {
                if (input.equals(v)) { out.accept(JqBoolean.TRUE); return; }
            }
            out.accept(JqBoolean.FALSE);
        });
        register("IN", 2, (input, args, env, eval, out) -> {
            // IN(stream; filter) — check if any output of stream is in filter's outputs
            var filterOutputs = eval.eval(args.get(1), input, env);
            var filterSet = new java.util.HashSet<>(filterOutputs);
            var streamOutputs = eval.eval(args.get(0), input, env);
            boolean found = false;
            for (JqValue v : streamOutputs) {
                if (filterSet.contains(v)) { found = true; break; }
            }
            out.accept(JqBoolean.of(found));
        });

        // JOIN(idx; idx_expr) — for each element $x in input array, output [$x, idx[$x | idx_expr]]
        register("JOIN", 2, (input, args, env, eval, out) -> {
            JqValue idx = eval.eval(args.get(0), input, env).getFirst();
            JqArray arr = requireArray(input, "JOIN");
            var results = new java.util.ArrayList<JqValue>();
            for (JqValue elem : arr.arrayValue()) {
                JqValue key = eval.eval(args.get(1), elem, env).getFirst();
                JqValue looked = (idx instanceof JqObject obj) ? obj.objectValue().get(key.stringValue()) : null;
                results.add(JqArray.of(java.util.List.of(elem, looked != null ? looked : JqNull.NULL)));
            }
            out.accept(JqArray.of(results));
        });

        // tostream/fromstream - streaming representation
        register("tostream", 0, (input, args, env, eval, out) -> {
            toStream(input, new ArrayList<>(), out);
        });

        register("fromstream", 1, (input, args, env, eval, out) -> {
            JqValue[] result = {null};
            eval.eval(args.getFirst(), input, env, streamEvent -> {
                if (streamEvent instanceof JqArray arr) {
                    var items = arr.arrayValue();
                    if (items.size() == 2 && items.get(0) instanceof JqArray path) {
                        // [[path], value] - set
                        JqValue val = items.get(1);
                        if (result[0] == null) result[0] = JqNull.NULL;
                        result[0] = setPath(result[0], path.arrayValue(), 0, val);
                    } else if (items.size() == 2 && items.get(0) instanceof JqArray path2
                               && items.get(1) instanceof JqBoolean truncate) {
                        // [[path], boolean] - truncate (end marker)
                        if (truncate.booleanValue() && path2.arrayValue().isEmpty()) {
                            // End of stream
                            if (result[0] != null) {
                                out.accept(result[0]);
                                result[0] = null;
                            }
                        }
                    }
                }
            });
            if (result[0] != null) {
                out.accept(result[0]);
            }
        });

        // truncate_stream(expr)
        register("truncate_stream", 1, (input, args, env, eval, out) -> {
            eval.eval(args.getFirst(), input, env, streamEvent -> {
                if (streamEvent instanceof JqArray arr) {
                    var items = arr.arrayValue();
                    if (items.size() == 2 && items.get(0) instanceof JqArray path) {
                        if (path.arrayValue().size() > 1) {
                            // Remove first path component
                            var newPath = path.arrayValue().subList(1, path.arrayValue().size());
                            out.accept(JqArray.of(List.of(JqArray.of(newPath), items.get(1))));
                        }
                    }
                }
            });
        });

        // getpath/setpath/delpaths already registered

        // label($out) | ... break $out - handled by evaluator

        // try-catch already handled by evaluator

        // foreach already handled by evaluator

        // if-then-elif-else-end already handled by evaluator

        // reduce already handled by evaluator

        // string multiplication: "ab" * 3 = "ababab" - handled in JqValue.multiply
        // (need to add to JqValue)

        // @json format already registered

        // min_by/max_by already registered

        // group_by already registered

        // indices with string arg for substring search already registered

        // `not` already a zero-arg builtin

        // ascii_downcase/ascii_upcase already registered

        // gsub/sub already registered
    }

    public Set<String> keySet() {
        return builtins.keySet();
    }

    // Helper methods

    private boolean containsValue(JqValue container, JqValue contained) {
        if (container.equals(contained)) return true;
        return switch (container) {
            case JqArray arr when contained instanceof JqArray contArr -> {
                for (JqValue v : contArr.arrayValue()) {
                    boolean found = false;
                    for (JqValue cv : arr.arrayValue()) {
                        if (containsValue(cv, v)) { found = true; break; }
                    }
                    if (!found) yield false;
                }
                yield true;
            }
            case JqObject obj when contained instanceof JqObject contObj -> {
                for (var entry : contObj.objectValue().entrySet()) {
                    JqValue val = obj.get(entry.getKey());
                    if (!containsValue(val, entry.getValue())) yield false;
                }
                yield true;
            }
            case JqString s when contained instanceof JqString cs ->
                    s.stringValue().contains(cs.stringValue());
            default -> false;
        };
    }

    private void flattenArray(JqArray arr, List<JqValue> result, int depth) {
        for (JqValue v : arr.arrayValue()) {
            if (v instanceof JqArray inner && depth > 0) {
                flattenArray(inner, result, depth - 1);
            } else {
                result.add(v);
            }
        }
    }

    private void recurseValue(JqValue input, Consumer<JqValue> output) {
        output.accept(input);
        switch (input) {
            case JqArray arr -> {
                for (JqValue v : arr.arrayValue()) recurseValue(v, output);
            }
            case JqObject obj -> {
                for (JqValue v : obj.objectValue().values()) recurseValue(v, output);
            }
            default -> {}
        }
    }

    private void recurseWithFilter(JqValue input, JqExpr filter, Environment env,
                                    Evaluator eval, Consumer<JqValue> output, Set<String> seen) {
        String key = input.toJsonString();
        if (!seen.add(key)) return;
        output.accept(input);
        try {
            for (JqValue v : eval.eval(filter, input, env)) {
                recurseWithFilter(v, filter, env, eval, output, seen);
            }
        } catch (JqException | JqTypeError ignored) {}
    }

    private void recurseWithCondition(JqValue input, JqExpr filter, JqExpr cond,
                                      Environment env, Evaluator eval, Consumer<JqValue> output) {
        // Check condition
        try {
            var condResult = eval.eval(cond, input, env);
            if (condResult.isEmpty() || !condResult.getFirst().isTruthy()) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        output.accept(input);
        try {
            for (JqValue v : eval.eval(filter, input, env)) {
                recurseWithCondition(v, filter, cond, env, eval, output);
            }
        } catch (JqException | JqTypeError ignored) {}
    }

    private JqValue getPath(JqValue current, List<JqValue> path, int idx) {
        if (idx >= path.size()) return current;
        JqValue key = path.get(idx);
        if (key instanceof JqString s) {
            if (current instanceof JqObject obj) {
                JqValue child = obj.objectValue().get(s.stringValue());
                return getPath(child != null ? child : JqNull.NULL, path, idx + 1);
            }
            return JqNull.NULL;
        } else if (key instanceof JqNumber n) {
            if (current instanceof JqArray arr) {
                int i = (int) n.longValue();
                var list = arr.arrayValue();
                if (i >= 0 && i < list.size()) {
                    return getPath(list.get(i), path, idx + 1);
                }
            }
            return JqNull.NULL;
        }
        return JqNull.NULL;
    }

    private JqValue setPath(JqValue current, List<JqValue> path, int idx, JqValue value) {
        if (idx >= path.size()) return value;
        JqValue key = path.get(idx);
        if (key instanceof JqString s) {
            var map = current instanceof JqObject obj
                    ? new LinkedHashMap<>(obj.objectValue())
                    : new LinkedHashMap<String, JqValue>();
            map.put(s.stringValue(), setPath(map.getOrDefault(s.stringValue(), JqNull.NULL), path, idx + 1, value));
            return JqObject.of(map);
        } else if (key instanceof JqNumber n) {
            if (current instanceof JqObject) {
                throw new JqException("Cannot index object with number (" + n.toJsonString() + ")");
            }
            int i = (int) n.longValue();
            var list = current instanceof JqArray arr
                    ? new ArrayList<>(arr.arrayValue())
                    : new ArrayList<JqValue>();
            // Resolve negative indices
            if (i < 0) {
                i = list.size() + i;
                if (i < 0) throw new JqException("Out of bounds negative array index");
            }
            if (i > 536870911) throw new JqException("Array index too large");
            while (list.size() <= i) list.add(JqNull.NULL);
            list.set(i, setPath(list.get(i), path, idx + 1, value));
            return JqArray.of(list);
        }
        throw new JqException("Cannot update field at " + key.type().jqName() + " index of " + current.type().jqName());
    }

    private JqValue deletePath(JqValue current, List<JqValue> path, int idx) {
        if (path.isEmpty()) return JqNull.NULL; // del(.) — delete root
        if (idx >= path.size()) return current;
        JqValue key = path.get(idx);
        if (idx == path.size() - 1) {
            // Last element: delete it
            if (key instanceof JqString s && current instanceof JqObject obj) {
                var map = new LinkedHashMap<>(obj.objectValue());
                map.remove(s.stringValue());
                return JqObject.of(map);
            } else if (key instanceof JqNumber n && current instanceof JqArray arr) {
                if (n.isNaN()) return current; // NaN index: no-op
                var list = new ArrayList<>(arr.arrayValue());
                int i = (int) n.longValue();
                if (i >= 0 && i < list.size()) list.remove(i);
                return JqArray.of(list);
            }
            return current;
        }
        // Recurse
        if (key instanceof JqString s && current instanceof JqObject obj) {
            if (!obj.objectValue().containsKey(s.stringValue())) return current; // path doesn't exist
            var map = new LinkedHashMap<>(obj.objectValue());
            map.put(s.stringValue(), deletePath(map.get(s.stringValue()), path, idx + 1));
            return JqObject.of(map);
        } else if (key instanceof JqNumber n && current instanceof JqArray arr) {
            if (n.isNaN()) return current; // NaN index: no-op
            var list = new ArrayList<>(arr.arrayValue());
            int i = (int) n.longValue();
            if (i >= 0 && i < list.size()) {
                list.set(i, deletePath(list.get(i), path, idx + 1));
            }
            return JqArray.of(list);
        }
        return current;
    }

    private void leafPaths(JqValue value, List<JqValue> currentPath, Consumer<JqValue> output) {
        switch (value) {
            case JqArray arr -> {
                for (int i = 0; i < arr.arrayValue().size(); i++) {
                    currentPath.add(JqNumber.of(i));
                    leafPaths(arr.arrayValue().get(i), currentPath, output);
                    currentPath.removeLast();
                }
            }
            case JqObject obj -> {
                for (var entry : obj.objectValue().entrySet()) {
                    currentPath.add(JqString.of(entry.getKey()));
                    leafPaths(entry.getValue(), currentPath, output);
                    currentPath.removeLast();
                }
            }
            default -> output.accept(JqArray.of(new ArrayList<>(currentPath)));
        }
    }

    private int parseRegexFlags(String flags) {
        int result = 0;
        for (char c : flags.toCharArray()) {
            result |= switch (c) {
                case 'x' -> Pattern.COMMENTS;
                case 's' -> Pattern.DOTALL;
                case 'm' -> Pattern.MULTILINE;
                case 'i' -> Pattern.CASE_INSENSITIVE;
                case 'g' -> 0; // global handled separately
                default -> 0;
            };
        }
        return result;
    }

    private JqValue buildMatchResult(java.util.regex.Matcher matcher) {
        var map = new LinkedHashMap<String, JqValue>();
        map.put("offset", JqNumber.of(matcher.start()));
        map.put("length", JqNumber.of(matcher.end() - matcher.start()));
        map.put("string", JqString.of(matcher.group()));
        var captures = new ArrayList<JqValue>();
        for (int i = 1; i <= matcher.groupCount(); i++) {
            var captureMap = new LinkedHashMap<String, JqValue>();
            String g = matcher.group(i);
            captureMap.put("offset", JqNumber.of(g != null ? matcher.start(i) : -1));
            captureMap.put("length", JqNumber.of(g != null ? g.length() : 0));
            captureMap.put("string", g != null ? JqString.of(g) : JqNull.NULL);
            captureMap.put("name", JqNull.NULL);
            captures.add(JqObject.of(captureMap));
        }
        map.put("captures", JqArray.of(captures));
        return JqObject.of(map);
    }

    private JqValue parseJson(String json) {
        json = json.trim();
        if (json.equals("null")) return JqNull.NULL;
        if (json.equals("true")) return JqBoolean.TRUE;
        if (json.equals("false")) return JqBoolean.FALSE;
        if (json.startsWith("\"")) {
            // Simple string parsing
            return JqString.of(json.substring(1, json.length() - 1));
        }
        if (json.startsWith("[") || json.startsWith("{")) {
            // Delegate to a simple recursive parser
            return parseJsonValue(json, new int[]{0});
        }
        try {
            if (json.contains(".") || json.contains("e") || json.contains("E")) {
                return JqNumber.of(new BigDecimal(json));
            }
            return JqNumber.of(Long.parseLong(json));
        } catch (NumberFormatException e) {
            return JqNumber.of(new BigDecimal(json));
        }
    }

    private JqValue parseJsonValue(String json, int[] pos) {
        skipWs(json, pos);
        if (pos[0] >= json.length()) return JqNull.NULL;
        char c = json.charAt(pos[0]);
        return switch (c) {
            case '{' -> parseJsonObject(json, pos);
            case '[' -> parseJsonArray(json, pos);
            case '"' -> parseJsonString(json, pos);
            case 't' -> { pos[0] += 4; yield JqBoolean.TRUE; }
            case 'f' -> { pos[0] += 5; yield JqBoolean.FALSE; }
            case 'n' -> { pos[0] += 4; yield JqNull.NULL; }
            default -> parseJsonNumber(json, pos);
        };
    }

    private JqObject parseJsonObject(String json, int[] pos) {
        pos[0]++; // skip {
        var map = new LinkedHashMap<String, JqValue>();
        skipWs(json, pos);
        if (json.charAt(pos[0]) == '}') { pos[0]++; return JqObject.of(map); }
        while (true) {
            skipWs(json, pos);
            JqValue keyVal = parseJsonString(json, pos);
            String key = ((JqString) keyVal).stringValue();
            skipWs(json, pos);
            pos[0]++; // skip :
            JqValue value = parseJsonValue(json, pos);
            map.put(key, value);
            skipWs(json, pos);
            if (json.charAt(pos[0]) == '}') { pos[0]++; break; }
            pos[0]++; // skip ,
        }
        return JqObject.of(map);
    }

    private JqArray parseJsonArray(String json, int[] pos) {
        pos[0]++; // skip [
        var list = new ArrayList<JqValue>();
        skipWs(json, pos);
        if (json.charAt(pos[0]) == ']') { pos[0]++; return JqArray.of(list); }
        while (true) {
            list.add(parseJsonValue(json, pos));
            skipWs(json, pos);
            if (json.charAt(pos[0]) == ']') { pos[0]++; break; }
            pos[0]++; // skip ,
        }
        return JqArray.of(list);
    }

    private JqString parseJsonString(String json, int[] pos) {
        pos[0]++; // skip opening "
        var sb = new StringBuilder();
        while (json.charAt(pos[0]) != '"') {
            if (json.charAt(pos[0]) == '\\') {
                pos[0]++;
                char esc = json.charAt(pos[0]);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        String hex = json.substring(pos[0] + 1, pos[0] + 5);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos[0] += 4;
                    }
                }
            } else {
                sb.append(json.charAt(pos[0]));
            }
            pos[0]++;
        }
        pos[0]++; // skip closing "
        return JqString.of(sb.toString());
    }

    private JqNumber parseJsonNumber(String json, int[] pos) {
        int start = pos[0];
        if (json.charAt(pos[0]) == '-') pos[0]++;
        while (pos[0] < json.length() && Character.isDigit(json.charAt(pos[0]))) pos[0]++;
        if (pos[0] < json.length() && json.charAt(pos[0]) == '.') {
            pos[0]++;
            while (pos[0] < json.length() && Character.isDigit(json.charAt(pos[0]))) pos[0]++;
        }
        if (pos[0] < json.length() && (json.charAt(pos[0]) == 'e' || json.charAt(pos[0]) == 'E')) {
            pos[0]++;
            if (pos[0] < json.length() && (json.charAt(pos[0]) == '+' || json.charAt(pos[0]) == '-')) pos[0]++;
            while (pos[0] < json.length() && Character.isDigit(json.charAt(pos[0]))) pos[0]++;
        }
        String numStr = json.substring(start, pos[0]);
        return JqNumber.of(new BigDecimal(numStr));
    }

    private void skipWs(String json, int[] pos) {
        while (pos[0] < json.length() && Character.isWhitespace(json.charAt(pos[0]))) pos[0]++;
    }

    // --- Phase 2 helper methods ---

    private void collectPaths(JqExpr pathExpr, JqValue input, Environment env,
                               Evaluator eval, List<List<JqValue>> paths) {
        // Use path() evaluation for all expressions — handles compound paths like .foo[0]
        try {
            eval.eval(new JqExpr.PathExpr(pathExpr), input, env, pathVal -> {
                if (pathVal instanceof JqArray arr) {
                    paths.add(arr.arrayValue());
                }
            });
        } catch (EmptyException ignored) {
            // del(empty) — no paths, input unchanged
        } catch (Exception ignored) {
            throw new JqException("Cannot extract path from expression for del()");
        }
    }

    private void flattenComma(JqExpr expr, List<JqExpr> result) {
        if (expr instanceof JqExpr.CommaExpr c) {
            flattenComma(c.left(), result);
            flattenComma(c.right(), result);
        } else {
            result.add(expr);
        }
    }

    private boolean containsSlice(JqExpr expr) {
        return switch (expr) {
            case JqExpr.SliceExpr ignored -> true;
            case JqExpr.CommaExpr c -> containsSlice(c.left()) || containsSlice(c.right());
            case JqExpr.PipeExpr p -> containsSlice(p.left()) || containsSlice(p.right());
            default -> false;
        };
    }

    private JqValue getAtPath(JqValue value, List<JqValue> path) {
        JqValue current = value;
        for (JqValue key : path) {
            current = switch (current) {
                case JqObject obj when key instanceof JqString s -> obj.get(s.stringValue());
                case JqArray arr when key instanceof JqNumber n -> arr.get((int) n.longValue());
                case JqNull ignored -> JqNull.NULL;
                default -> JqNull.NULL;
            };
        }
        return current;
    }

    private void allPaths(JqValue value, List<JqValue> currentPath, Consumer<JqValue> output) {
        // Don't emit the root empty path - jq's paths does not include []
        if (!currentPath.isEmpty()) {
            output.accept(JqArray.of(new ArrayList<>(currentPath)));
        }
        switch (value) {
            case JqArray arr -> {
                for (int i = 0; i < arr.arrayValue().size(); i++) {
                    currentPath.add(JqNumber.of(i));
                    allPaths(arr.arrayValue().get(i), currentPath, output);
                    currentPath.removeLast();
                }
            }
            case JqObject obj -> {
                for (var entry : obj.objectValue().entrySet()) {
                    currentPath.add(JqString.of(entry.getKey()));
                    allPaths(entry.getValue(), currentPath, output);
                    currentPath.removeLast();
                }
            }
            default -> {}
        }
    }

    private void allPathsFiltered(JqValue value, List<JqValue> currentPath,
                                   JqExpr filter, Environment env, Evaluator eval,
                                   Consumer<JqValue> output) {
        // Check if the value at this path matches the filter
        try {
            var results = eval.eval(filter, value, env);
            if (results.stream().anyMatch(JqValue::isTruthy)) {
                output.accept(JqArray.of(new ArrayList<>(currentPath)));
            }
        } catch (Exception ignored) {}

        switch (value) {
            case JqArray arr -> {
                for (int i = 0; i < arr.arrayValue().size(); i++) {
                    currentPath.add(JqNumber.of(i));
                    allPathsFiltered(arr.arrayValue().get(i), currentPath, filter, env, eval, output);
                    currentPath.removeLast();
                }
            }
            case JqObject obj -> {
                for (var entry : obj.objectValue().entrySet()) {
                    currentPath.add(JqString.of(entry.getKey()));
                    allPathsFiltered(entry.getValue(), currentPath, filter, env, eval, output);
                    currentPath.removeLast();
                }
            }
            default -> {}
        }
    }

    private void generateCombinations(List<List<JqValue>> arrays, int idx,
                                       List<JqValue> current, Consumer<JqValue> output) {
        if (idx >= arrays.size()) {
            output.accept(JqArray.of(new ArrayList<>(current)));
            return;
        }
        for (JqValue v : arrays.get(idx)) {
            current.add(v);
            generateCombinations(arrays, idx + 1, current, output);
            current.removeLast();
        }
    }

    private JqValue walkValue(JqValue value, JqExpr filter, Environment env, Evaluator eval) {
        JqValue transformed = walkTransform(value, filter, env, eval);
        var results = eval.eval(filter, transformed, env);
        return results.isEmpty() ? null : results.getFirst();
    }

    private JqValue walkTransform(JqValue value, JqExpr filter, Environment env, Evaluator eval) {
        return switch (value) {
            case JqArray arr -> {
                var list = new ArrayList<JqValue>();
                for (JqValue elem : arr.arrayValue()) {
                    JqValue walked = walkValue(elem, filter, env, eval);
                    if (walked != null) list.add(walked);
                }
                yield JqArray.of(list);
            }
            case JqObject obj -> {
                var map = new LinkedHashMap<String, JqValue>();
                for (var entry : obj.objectValue().entrySet()) {
                    JqValue walked = walkValue(entry.getValue(), filter, env, eval);
                    if (walked != null) map.put(entry.getKey(), walked);
                }
                yield JqObject.of(map);
            }
            default -> value;
        };
    }

    private void toStream(JqValue value, List<JqValue> path, Consumer<JqValue> output) {
        switch (value) {
            case JqArray arr -> {
                for (int i = 0; i < arr.arrayValue().size(); i++) {
                    var newPath = new ArrayList<>(path);
                    newPath.add(JqNumber.of(i));
                    toStream(arr.arrayValue().get(i), newPath, output);
                }
                // Truncate marker
                output.accept(JqArray.of(List.of(JqArray.of(new ArrayList<>(path)), JqBoolean.of(path.isEmpty()))));
            }
            case JqObject obj -> {
                for (var entry : obj.objectValue().entrySet()) {
                    var newPath = new ArrayList<>(path);
                    newPath.add(JqString.of(entry.getKey()));
                    toStream(entry.getValue(), newPath, output);
                }
                output.accept(JqArray.of(List.of(JqArray.of(new ArrayList<>(path)), JqBoolean.of(path.isEmpty()))));
            }
            default -> {
                // Leaf: emit [[path], value]
                output.accept(JqArray.of(List.of(JqArray.of(new ArrayList<>(path)), value)));
            }
        }
    }

    private java.time.ZonedDateTime brokenDownTimeToZdt(JqArray arr) {
        var items = arr.arrayValue();
        int year = items.size() > 0 ? (int) items.get(0).longValue() : 1970;
        int month = items.size() > 1 ? (int) items.get(1).longValue() + 1 : 1;
        int day = items.size() > 2 ? (int) items.get(2).longValue() : 1;
        int hour = items.size() > 3 ? (int) items.get(3).longValue() : 0;
        int minute = items.size() > 4 ? (int) items.get(4).longValue() : 0;
        int second = items.size() > 5 ? (int) items.get(5).longValue() : 0;
        return java.time.ZonedDateTime.of(year, month, day, hour, minute, second, 0,
                java.time.ZoneOffset.UTC);
    }

    private String jqFormatToJava(String format) {
        // Convert strftime directives to Java DateTimeFormatter patterns.
        // Literal characters must be quoted in single quotes for DateTimeFormatter.
        var directives = Map.ofEntries(
                Map.entry('Y', "yyyy"), Map.entry('m', "MM"), Map.entry('d', "dd"),
                Map.entry('H', "HH"), Map.entry('M', "mm"), Map.entry('S', "ss"),
                Map.entry('Z', "z"), Map.entry('z', "Z"), Map.entry('j', "DDD"),
                Map.entry('a', "EEE"), Map.entry('A', "EEEE"), Map.entry('b', "MMM"),
                Map.entry('B', "MMMM"), Map.entry('p', "a"), Map.entry('I', "hh"),
                Map.entry('e', " d")
        );
        var sb = new StringBuilder();
        var literal = new StringBuilder();
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '%' && i + 1 < format.length()) {
                char next = format.charAt(i + 1);
                if (next == '%') {
                    literal.append('%');
                    i++;
                } else {
                    String replacement = directives.get(next);
                    if (replacement != null) {
                        if (!literal.isEmpty()) {
                            sb.append('\'').append(literal.toString().replace("'", "''")).append('\'');
                            literal.setLength(0);
                        }
                        sb.append(replacement);
                        i++;
                    } else {
                        literal.append(c);
                    }
                }
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            sb.append('\'').append(literal.toString().replace("'", "''")).append('\'');
        }
        return sb.toString();
    }

    // Unicode-aware whitespace trimming (matches jq's trim behavior)
    private static boolean isUnicodeWhitespace(int cp) {
        return Character.isWhitespace(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR
                || cp == 0x0085 || cp == 0x00A0 || cp == 0x2007 || cp == 0x202F;
    }

    private static String unicodeTrim(String s) {
        return unicodeRtrim(unicodeLtrim(s));
    }

    private static String unicodeLtrim(String s) {
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (!isUnicodeWhitespace(cp)) break;
            i += Character.charCount(cp);
        }
        return s.substring(i);
    }

    private static String unicodeRtrim(String s) {
        int i = s.length();
        while (i > 0) {
            int cp = s.codePointBefore(i);
            if (!isUnicodeWhitespace(cp)) break;
            i -= Character.charCount(cp);
        }
        return s.substring(0, i);
    }

    private static String asciiUpperCase(String s) {
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 'a' && chars[i] <= 'z') chars[i] -= 32;
        }
        return new String(chars);
    }

    private static String asciiLowerCase(String s) {
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 'A' && chars[i] <= 'Z') chars[i] += 32;
        }
        return new String(chars);
    }

    private static double logGamma(double x) {
        // Lanczos approximation
        double[] c = {76.18009172947146, -86.50532032941677, 24.01409824083091,
                -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x, tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double sum = 1.000000000190015;
        for (int j = 0; j < 6; j++) sum += c[j] / ++y;
        return -tmp + Math.log(2.5066282746310005 * sum / x);
    }

    private static double besselJ0(double x) {
        // Approximation for Bessel J0
        if (Math.abs(x) < 8.0) {
            double y = x * x;
            double r = 1.0;
            double s = 1.0;
            for (int k = 1; k <= 20; k++) {
                r *= -y / (4.0 * k * k);
                s += r;
            }
            return s;
        }
        double ax = Math.abs(x);
        double z = 8.0 / ax;
        double y = z * z;
        double xx = ax - 0.785398164;
        return Math.sqrt(0.636619772 / ax) * Math.cos(xx);
    }

    private static double besselJ1(double x) {
        if (Math.abs(x) < 8.0) {
            double y = x * x;
            double r = x / 2.0;
            double s = r;
            for (int k = 1; k <= 20; k++) {
                r *= -y / (4.0 * k * (k + 1));
                s += r;
            }
            return s;
        }
        double ax = Math.abs(x);
        double z = 8.0 / ax;
        double xx = ax - 2.356194491;
        double result = Math.sqrt(0.636619772 / ax) * Math.cos(xx);
        return x < 0 ? -result : result;
    }

    /** Truncate a value string for error messages, matching jq's behavior (25 chars + "...") */
    private static String truncateValue(String s) {
        if (s.length() > 25) {
            return s.substring(0, 25) + "...";
        }
        return s;
    }
}
