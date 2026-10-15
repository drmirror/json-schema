package jsonschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lightweight self-check for {@link BsonSchemaConverter}.
 *
 * Run with: java jsonschema.BsonSchemaConverterTest
 * Exits with status 0 on success, 1 if any case fails.
 */
public final class BsonSchemaConverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // --- empty "required": [] is dropped (MongoDB rejects it) ----------
        expectNoKey("top-level empty required",
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[]}",
                "/required");

        expectNoKey("nested empty required",
                "{\"type\":\"object\",\"properties\":{\"inner\":{\"type\":\"object\","
                        + "\"properties\":{\"x\":{\"type\":\"string\"}},\"required\":[]}}}",
                "/properties/inner/required");

        expectNoKey("empty required inside array items",
                "{\"type\":\"array\",\"items\":{\"type\":\"object\",\"required\":[]}}",
                "/items/required");

        expectNoKey("empty required as $ref sibling",
                "{\"$defs\":{\"t\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}},"
                        + "\"type\":\"object\",\"properties\":{\"p\":{\"$ref\":\"#/$defs/t\",\"required\":[]}}}",
                "/properties/p/required");

        // --- non-empty "required" is preserved verbatim --------------------
        expectEquals("non-empty required kept",
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[\"a\"]}",
                "/required", "[\"a\"]");

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void expectNoKey(String label, String schemaJson, String pointer) throws Exception {
        JsonNode out = BsonSchemaConverter.toBsonSchema(schemaJson);
        if (out.at(pointer).isMissingNode()) {
            pass("%-40s %s absent", label, pointer);
        } else {
            fail("%-40s expected %s to be absent, got %s", label, pointer, out.at(pointer));
        }
    }

    private static void expectEquals(String label, String schemaJson, String pointer, String expectedJson)
            throws Exception {
        JsonNode out = BsonSchemaConverter.toBsonSchema(schemaJson);
        JsonNode expected = MAPPER.readTree(expectedJson);
        if (expected.equals(out.at(pointer))) {
            pass("%-40s %s == %s", label, pointer, expectedJson);
        } else {
            fail("%-40s expected %s at %s, got %s", label, expectedJson, pointer, out.at(pointer));
        }
    }

    private static void pass(String fmt, Object... args) {
        System.out.println("PASS  " + String.format(fmt, args));
        passed++;
    }

    private static void fail(String fmt, Object... args) {
        System.out.println("FAIL  " + String.format(fmt, args));
        failed++;
    }
}
