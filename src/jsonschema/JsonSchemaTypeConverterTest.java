package jsonschema;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

/**
 * Lightweight self-check for {@link JsonSchemaTypeConverter}.
 *
 * Verifies that valid string values get converted to the expected Java
 * type, and that malformed values for our three special formats now
 * surface as an {@link IllegalArgumentException} (previously they were
 * silently left as strings — see the no-offset date-time case below).
 *
 * Run with: java jsonschema.JsonSchemaTypeConverterTest
 * Exits with status 0 on success, 1 if any case fails.
 */
public final class JsonSchemaTypeConverterTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // --- date-time --------------------------------------------------
        expectConverts("createdAt", "2026-04-27T12:00:00Z",      "date-time", Date.class);
        expectConverts("createdAt", "2026-04-27T12:00:00+02:00", "date-time", Date.class);

        // The case that motivated this change: no zone offset, no "Z" suffix.
        // Previously silently left as a String; now must throw.
        expectThrows("createdAt", "2026-04-27T12:00:00",   "date-time", "missing offset");
        expectThrows("createdAt", "2026-04-27T12:00:00.0", "date-time", "missing offset");
        expectThrows("createdAt", "not-a-date",            "date-time", "garbage");

        // --- decimal ----------------------------------------------------
        expectConverts("amount", "12345.6789", "decimal", BigDecimal.class);
        expectConverts("amount", "0",          "decimal", BigDecimal.class);
        expectThrows  ("amount", "twelve",     "decimal", "non-numeric");

        // --- boolean ----------------------------------------------------
        expectConverts("active", "true",  "boolean", Boolean.class);
        expectConverts("active", "False", "boolean", Boolean.class);
        expectThrows  ("active", "yes",   "boolean", "not a boolean literal");

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    /** Expects conversion to succeed and produce an instance of {@code expectedType}. */
    private static void expectConverts(String field, String value, String format, Class<?> expectedType) {
        String schemaJson = schema(field, format);
        String docJson    = document(field, value);
        try {
            Map<String, Object> doc = JsonSchemaTypeConverter.convertJson(docJson, schemaJson);
            Object actual = doc.get(field);
            if (actual != null && expectedType.isInstance(actual)) {
                pass("[%s] %-30s -> %s(%s)", format, value, expectedType.getSimpleName(), actual);
            } else {
                fail("[%s] %-30s expected %s but got %s",
                        format, value, expectedType.getSimpleName(),
                        actual == null ? "null" : actual.getClass().getName() + "(" + actual + ")");
            }
        } catch (Exception e) {
            fail("[%s] %-30s expected conversion to %s but threw: %s",
                    format, value, expectedType.getSimpleName(), e.getMessage());
        }
    }

    /** Expects conversion to throw {@link IllegalArgumentException}. */
    private static void expectThrows(String field, String value, String format, String reasonHint) {
        String schemaJson = schema(field, format);
        String docJson    = document(field, value);
        try {
            JsonSchemaTypeConverter.convertJson(docJson, schemaJson);
            fail("[%s] %-30s expected to throw (%s) but succeeded", format, value, reasonHint);
        } catch (IllegalArgumentException e) {
            pass("[%s] %-30s rejected (%s)", format, value, reasonHint);
        } catch (Exception e) {
            fail("[%s] %-30s expected IllegalArgumentException, got %s: %s",
                    format, value, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private static String schema(String field, String format) {
        return "{\"type\":\"object\",\"properties\":{\"" + field
                + "\":{\"type\":\"string\",\"format\":\"" + format + "\"}}}";
    }

    private static String document(String field, String value) {
        return "{\"" + field + "\":\"" + value + "\"}";
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
