package io.hyperfoil.tools.jjq.examples;

/**
 * Reference guide for jjq CLI usage. This class prints example commands
 * that demonstrate the CLI features.
 *
 * <p>Run with: {@code mvn -pl jjq-examples exec:exec -Dexec.mainClass=io.hyperfoil.tools.jjq.examples.CliExamples}
 */
public class CliExamples {

    public static void main(String[] args) {
        System.out.println("""
                ============================================================
                  jjq CLI Examples
                ============================================================

                These examples assume jjq is available on your PATH.
                Build the CLI with: mvn package -pl jjq-cli

                ============================================================
                  BASIC USAGE
                ============================================================

                # Identity — pass through input unchanged
                echo '{"name":"Alice","age":30}' | jjq '.'

                # Field access
                echo '{"name":"Alice","age":30}' | jjq '.name'
                # => "Alice"

                # Nested field access
                echo '{"a":{"b":{"c":42}}}' | jjq '.a.b.c'
                # => 42

                # Array indexing
                echo '["a","b","c","d"]' | jjq '.[2]'
                # => "c"

                # Array slicing
                echo '[1,2,3,4,5]' | jjq '.[1:3]'
                # => [2,3]

                ============================================================
                  ITERATION & FILTERING
                ============================================================

                # Iterate array elements
                echo '[1,2,3]' | jjq '.[]'
                # => 1
                # => 2
                # => 3

                # Iterate and filter
                echo '[1,2,3,4,5,6]' | jjq '[.[] | select(. > 3)]'
                # => [4,5,6]

                # Iterate objects and access fields
                echo '{"users":[{"name":"Alice"},{"name":"Bob"}]}' | jjq '.users[].name'
                # => "Alice"
                # => "Bob"

                # Map — transform each element
                echo '[1,2,3]' | jjq 'map(. * 10)'
                # => [10,20,30]

                ============================================================
                  OBJECT CONSTRUCTION
                ============================================================

                # Pick specific fields
                echo '{"name":"Alice","age":30,"email":"a@b.com","role":"admin"}' | jjq '{name, email}'
                # => {"name":"Alice","email":"a@b.com"}

                # Rename fields
                echo '{"first":"Alice","last":"Smith"}' | jjq '{full_name: (.first + " " + .last)}'
                # => {"full_name":"Alice Smith"}

                # Computed keys
                echo '{"key":"color","value":"blue"}' | jjq '{(.key): .value}'
                # => {"color":"blue"}

                # Transform array of objects
                echo '[{"name":"A","score":90},{"name":"B","score":85}]' | jjq \\
                  '[.[] | {name, grade: (if .score >= 90 then "A" else "B" end)}]'
                # => [{"name":"A","grade":"A"},{"name":"B","grade":"B"}]

                ============================================================
                  AGGREGATION & REDUCTION
                ============================================================

                # Sum an array
                echo '[1,2,3,4,5]' | jjq 'add'
                # => 15

                # Reduce with accumulator
                echo '[1,2,3,4,5]' | jjq 'reduce .[] as $x (0; . + $x)'
                # => 15

                # Min, max
                echo '[3,1,4,1,5,9]' | jjq '[min, max]'
                # => [1,9]

                # Group and count
                echo '[{"t":"a"},{"t":"b"},{"t":"a"},{"t":"c"},{"t":"b"},{"t":"a"}]' | jjq \\
                  'group_by(.t) | map({type: .[0].t, count: length})'
                # => [{"type":"a","count":3},{"type":"b","count":2},{"type":"c","count":1}]

                # Sort by field
                echo '[{"name":"Carol","age":35},{"name":"Alice","age":30},{"name":"Bob","age":25}]' | jjq \\
                  'sort_by(.age)'

                ============================================================
                  STRING OPERATIONS
                ============================================================

                # String interpolation
                echo '{"name":"Alice","age":30}' | jjq '"\\(.name) is \\(.age) years old"'
                # => "Alice is 30 years old"

                # Split and join
                echo '"hello world foo bar"' | jjq 'split(" ") | join(", ")'
                # => "hello, world, foo, bar"

                # Case conversion
                echo '"Hello World"' | jjq 'ascii_downcase'
                # => "hello world"

                # Regex test
                echo '["apple","banana","avocado","cherry"]' | jjq '[.[] | select(test("^a"))]'
                # => ["apple","avocado"]

                # Regex substitution
                echo '"2024-01-15"' | jjq 'gsub("-"; "/")'
                # => "2024/01/15"

                ============================================================
                  OUTPUT OPTIONS
                ============================================================

                # Compact output (no pretty-printing)
                echo '{"a": 1, "b": 2}' | jjq -c '.'
                # => {"a":1,"b":2}

                # Raw string output (no JSON quotes)
                echo '{"name":"Alice"}' | jjq -r '.name'
                # => Alice

                # Sort keys
                echo '{"z":1,"a":2,"m":3}' | jjq -S '.'
                # => {"a":2,"m":3,"z":1}

                # Colored output
                echo '{"name":"Alice"}' | jjq -C '.'

                # Tab indentation
                echo '{"a":1}' | jjq --tab '.'

                # Custom indentation (4 spaces)
                echo '{"a":1}' | jjq --indent 4 '.'

                # Join output (no newlines between values)
                echo '[1,2,3]' | jjq -j '.[]'
                # => 123

                ============================================================
                  INPUT OPTIONS
                ============================================================

                # Null input (no file/stdin needed)
                jjq -n '1 + 2'
                # => 3

                # Null input with array construction
                jjq -n '[range(5)]'
                # => [0,1,2,3,4]

                # Slurp multiple inputs into array
                echo -e '1\\n2\\n3' | jjq -s '.'
                # => [1,2,3]

                # Raw input (each line becomes a JSON string)
                echo -e 'hello\\nworld' | jjq -R '.'
                # => "hello"
                # => "world"

                # Slurp + raw input (all lines as array of strings)
                echo -e 'hello\\nworld' | jjq -Rs 'split("\\n") | map(select(. != ""))'
                # => ["hello","world"]

                ============================================================
                  VARIABLES
                ============================================================

                # Pass string variables
                echo '{"users":["alice","bob","carol"]}' | jjq --arg target alice \\
                  '[.users[] | select(. == $target)]'
                # => ["alice"]

                # Pass JSON variables
                echo '[1,2,3,4,5]' | jjq --argjson min 2 --argjson max 4 \\
                  '[.[] | select(. >= $min and . <= $max)]'
                # => [2,3,4]

                ============================================================
                  READING FROM FILES
                ============================================================

                # Read filter from a file
                # echo '.users[] | {name, email}' > filter.jq
                # jjq -f filter.jq data.json

                # Process a file directly
                # jjq '.users | length' users.json

                # Process multiple files
                # jjq '.name' file1.json file2.json file3.json

                ============================================================
                  ADVANCED PATTERNS
                ============================================================

                # Recursive descent — find all numbers anywhere in the structure
                echo '{"a":1,"b":{"c":2,"d":[3,4]}}' | jjq '[.. | numbers]'
                # => [1,2,3,4]

                # Path expressions
                echo '{"a":{"b":1},"c":2}' | jjq '[paths(type == "number")]'
                # => [["a","b"],["c"]]

                # Try-catch for error handling
                echo '{"a":1}' | jjq 'try .b.c.d catch "not found"'
                # => "not found"

                # Optional operator (suppress errors)
                echo '[1,"two",3]' | jjq '[.[] | (.+1)?]'
                # => [2,4]

                # Define and use functions
                echo '[1,2,3,4,5]' | jjq 'def double(x): x * 2; map(double(.))'
                # => [2,4,6,8,10]

                # Alternative operator (default values)
                echo '{"a":null}' | jjq '.a // "default"'
                # => "default"

                # Update nested values
                echo '{"a":{"b":1}}' | jjq '.a.b |= . + 10'
                # => {"a":{"b":11}}

                # Format strings
                echo '[[1,"Alice"],[2,"Bob"]]' | jjq '.[] | @csv'
                # => "1,\\"Alice\\""
                # => "2,\\"Bob\\""

                echo '{"a":1}' | jjq '. | @base64'
                # => "eyJhIjoxfQ=="

                ============================================================
                """);
    }
}
