package jsonschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema-level checks against the CVS data-modelling guidelines. These run on
 * the standard JSON schema, before any BSON translation, and do not alter it.
 *
 * Rule 1: a field declaration must not carry both "format" and "pattern".
 *         The format already implies the textual shape (and, for date-time /
 *         decimal / boolean, the value stops being a string in BSON), so a
 *         pattern next to it is at best redundant and at worst contradictory.
 *
 * The walker visits every subschema position: properties, patternProperties,
 * additionalProperties, items / prefixItems, contains, allOf / anyOf / oneOf,
 * not, if / then / else, dependentSchemas, propertyNames, $defs and
 * definitions. Keyword values that hold data rather than schemas (enum,
 * const, default, examples) are not descended into. $ref targets are reported
 * where they are declared, not where they are referenced.
 */
public final class SchemaGuidelineChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Keywords whose value is a single subschema (or a boolean schema). */
    private static final Set<String> SINGLE_SUBSCHEMA = Set.of(
            "items", "additionalItems", "additionalProperties",
            "unevaluatedItems", "unevaluatedProperties",
            "contains", "not", "if", "then", "else",
            "propertyNames", "contentSchema"
    );

    /** Keywords whose value is an array of subschemas (plus tuple-form items). */
    private static final Set<String> SUBSCHEMA_ARRAY = Set.of(
            "allOf", "anyOf", "oneOf", "prefixItems"
    );

    /** Keywords whose value is a name -> subschema map. */
    private static final Set<String> SUBSCHEMA_MAP = Set.of(
            "properties", "patternProperties", "dependentSchemas",
            "$defs", "definitions"
    );

    public static final String RULE_1_MESSAGE =
            "both \"format\" and \"pattern\" are set; use one or the other (guideline rule 1)";

    /** One offending field: its JSON pointer within the schema and the rule message. */
    public record Violation(String pointer, String message) {
        @Override
        public String toString() {
            return (pointer.isEmpty() ? "#" : "#" + pointer) + ": " + message;
        }
    }

    private SchemaGuidelineChecker() {
    }

    /** Returns every violation found in {@code schema}, in document order; empty if the schema is clean. */
    public static List<Violation> check(JsonNode schema) {
        List<Violation> out = new ArrayList<>();
        walk(schema, "", out);
        return out;
    }

    /** Convenience overload that parses the schema from a JSON string. */
    public static List<Violation> check(String schemaJson) throws Exception {
        return check(MAPPER.readTree(schemaJson));
    }

    private static void walk(JsonNode node, String pointer, List<Violation> out) {
        if (node == null || !node.isObject()) return; // boolean schemas, nulls

        if (node.has("format") && node.has("pattern")) {
            out.add(new Violation(pointer, RULE_1_MESSAGE));
        }

        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String keyword = e.getKey();
            JsonNode value = e.getValue();
            String childPointer = pointer + "/" + escape(keyword);

            if (SUBSCHEMA_MAP.contains(keyword) && value.isObject()) {
                for (Map.Entry<String, JsonNode> m : value.properties()) {
                    walk(m.getValue(), childPointer + "/" + escape(m.getKey()), out);
                }
            } else if (SUBSCHEMA_ARRAY.contains(keyword) && value.isArray()) {
                walkArray(value, childPointer, out);
            } else if (SINGLE_SUBSCHEMA.contains(keyword)) {
                if (value.isArray()) {
                    walkArray(value, childPointer, out); // legacy tuple-form "items"
                } else {
                    walk(value, childPointer, out);
                }
            } else if (keyword.equals("dependencies") && value.isObject()) {
                // draft-04/07: value is either a property-name array or a subschema.
                for (Map.Entry<String, JsonNode> m : value.properties()) {
                    if (m.getValue().isObject()) {
                        walk(m.getValue(), childPointer + "/" + escape(m.getKey()), out);
                    }
                }
            }
        }
    }

    private static void walkArray(JsonNode array, String pointer, List<Violation> out) {
        int i = 0;
        for (JsonNode item : array) {
            walk(item, pointer + "/" + i++, out);
        }
    }

    /** RFC 6901 token escaping. */
    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    /** CLI: prints one line per violation; exit status 1 if any were found. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: SchemaGuidelineChecker <schema.json>");
            System.exit(2);
        }
        List<Violation> violations = check(MAPPER.readTree(new File(args[0])));
        if (violations.isEmpty()) {
            System.out.println("OK: no guideline violations in " + args[0]);
            return;
        }
        System.err.println(violations.size() + " guideline violation(s) in " + args[0] + ":");
        for (Violation v : violations) {
            System.err.println("  " + v);
        }
        System.exit(1);
    }
}
