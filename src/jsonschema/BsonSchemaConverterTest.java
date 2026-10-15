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

        // --- nullable format fields: ["string","null"] is the absent-value convention
        expectEquals("nullable date-time -> [date,null]",
                field("{\"type\":[\"string\",\"null\"],\"format\":\"date-time\"}"),
                "/properties/f", "{\"bsonType\":[\"date\",\"null\"]}");
        expectEquals("nullable date keeps type, strict pattern",
                field("{\"type\":[\"string\",\"null\"],\"format\":\"date\"}"),
                "/properties/f", "{\"type\":[\"string\",\"null\"],\"pattern\":\"^\\\\d{4}-\\\\d{2}-\\\\d{2}$\"}");
        expectEquals("nullable decimal -> [decimal,null]",
                field("{\"type\":[\"string\",\"null\"],\"format\":\"decimal\"}"),
                "/properties/f", "{\"bsonType\":[\"decimal\",\"null\"]}");

        // --- empty regex branch on a format field is an error -------------
        expectThrows("empty branch + date-time rejected",
                field("{\"type\":\"string\",\"format\":\"date-time\",\"pattern\":\"^(\\\\d{4}-\\\\d{2}-\\\\d{2}T\\\\d{2}:\\\\d{2}:\\\\d{2}Z|)$\"}"));
        expectThrows("empty branch + date rejected",
                field("{\"type\":\"string\",\"format\":\"date\",\"pattern\":\"^(\\\\d{4}-\\\\d{2}-\\\\d{2}|)$\"}"));
        expectThrows("optional-quantifier pattern + decimal rejected",
                field("{\"type\":\"string\",\"format\":\"decimal\",\"pattern\":\"^\\\\d*$\"}"));

        // --- unchanged behaviour around it --------------------------------
        expectEquals("strict pattern + date-time -> date, pattern dropped",
                field("{\"type\":\"string\",\"format\":\"date-time\",\"pattern\":\"^\\\\d{4}-\\\\d{2}-\\\\d{2}T.*Z$\"}"),
                "/properties/f", "{\"bsonType\":\"date\"}");
        expectEquals("empty branch on plain string passes through",
                field("{\"type\":\"string\",\"pattern\":\"^([A-Z0-9]{11}|)$\"}"),
                "/properties/f", "{\"type\":\"string\",\"pattern\":\"^([A-Z0-9]{11}|)$\"}");

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    /** Wraps a property subschema in an object schema under the name "f". */
    private static String field(String subschemaJson) {
        return "{\"type\":\"object\",\"properties\":{\"f\":" + subschemaJson + "}}";
    }

    private static void expectThrows(String label, String schemaJson) {
        try {
            BsonSchemaConverter.toBsonSchema(schemaJson);
            fail("%-40s expected IllegalArgumentException but conversion succeeded", label);
        } catch (IllegalArgumentException e) {
            pass("%-40s rejected: %s", label, e.getMessage());
        } catch (Exception e) {
            fail("%-40s expected IllegalArgumentException, got %s: %s", label, e.getClass().getSimpleName(), e.getMessage());
        }
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
