package jsonschema;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static jsonschema.SchemaGuidelineChecker.RULE_1_MESSAGE;
import static jsonschema.SchemaGuidelineChecker.Violation;

/**
 * Self-check for {@link SchemaGuidelineChecker}.
 *
 * Run with: java jsonschema.SchemaGuidelineCheckerTest
 * Exits with status 0 on success, 1 if any case fails.
 *
 * The regression cases read real schemas from the ATC data models folder.
 * Override its location with -Datc.models.dir=/path/to/models (default:
 * /Users/andrespiegel/Downloads/cvs/models).
 */
public final class SchemaGuidelineCheckerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path MODELS_DIR = Path.of(
            System.getProperty("atc.models.dir", "/Users/andrespiegel/Downloads/cvs/models"));

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // --- clean schema: no violations ------------------------------------
        expectViolations("clean schema (format only, pattern only)",
                """
                {"type":"object","properties":{
                  "d":{"type":"string","format":"date"},
                  "p":{"type":"string","pattern":"^[A-Z]{2}$"}}}
                """,
                List.of());

        // --- minimal positive case -----------------------------------------
        expectViolations("minimal: format + pattern on one property",
                """
                {"type":"object","properties":{
                  "d":{"type":"string","format":"date","pattern":"^\\\\d{4}-\\\\d{2}-\\\\d{2}$"}}}
                """,
                List.of("/properties/d"));

        // --- nested: inside items, inside an anyOf branch, inside $defs ----
        expectViolations("nested: items, anyOf branch, $defs",
                """
                {"type":"object",
                 "$defs":{"Stamp":{"type":"string","format":"date-time","pattern":"^.*Z$"}},
                 "properties":{
                   "list":{"type":"array","items":{"type":"string","format":"date","pattern":"^.*$"}},
                   "x":{"anyOf":[
                       {"type":"null"},
                       {"type":"string","format":"decimal","pattern":"^\\\\d+$"}]},
                   "ok":{"type":"string","format":"date"},
                   "ref":{"$ref":"#/$defs/Stamp"}}}
                """,
                List.of("/$defs/Stamp", "/properties/list/items", "/properties/x/anyOf/1"));

        // --- regression: real ATC schemas known to violate rule 1 ----------
        expectFileFails("edh/backfeed/ProductPayment_schema_v7.json");
        expectFileFails("edh/memberaccums/MemberAccums_Schema_v4+.json");

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static void expectViolations(String label, String schemaJson, List<String> expectedPointers)
            throws Exception {
        List<Violation> actual = SchemaGuidelineChecker.check(MAPPER.readTree(schemaJson));
        List<String> pointers = actual.stream().map(Violation::pointer).toList();
        boolean messagesOk = actual.stream().allMatch(v -> RULE_1_MESSAGE.equals(v.message()));
        if (pointers.equals(expectedPointers) && messagesOk) {
            pass("%-45s %s", label, expectedPointers.isEmpty() ? "no violations" : pointers);
        } else {
            fail("%-45s expected %s but got %s", label, expectedPointers, actual);
        }
    }

    private static void expectFileFails(String relative) {
        Path file = MODELS_DIR.resolve(relative);
        if (!Files.exists(file)) {
            fail("%-45s file not found under %s", relative, MODELS_DIR);
            return;
        }
        try {
            List<Violation> actual = SchemaGuidelineChecker.check(MAPPER.readTree(file.toFile()));
            if (actual.isEmpty()) {
                fail("%-45s expected rule-1 violations but schema passed", relative);
            } else {
                pass("%-45s %d violation(s), first at %s", relative, actual.size(), actual.get(0).pointer());
            }
        } catch (Exception e) {
            fail("%-45s could not be checked: %s", relative, e.getMessage());
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
