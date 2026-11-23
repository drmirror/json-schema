package jsonschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion.VersionFlag;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.walk.JsonSchemaWalkListener;
import com.networknt.schema.walk.WalkEvent;
import com.networknt.schema.walk.WalkFlow;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Performance test for {@link JsonSchemaTypeConverter}.
 *
 * It answers two questions:
 *
 *   1. What does it cost just to walk the schema and document trees?
 *      Measured by converting a document against a schema that declares no
 *      formats at all (nothing gets converted), and by timing the converter's
 *      internal stages one by one through the same public library calls:
 *      building the JsonNode shadow, compiling the schema, and walking.
 *
 *   2. What do the type conversions add on top?
 *      Measured by switching on one format at a time (date-time, decimal,
 *      boolean) in the schema for the very same document and subtracting the
 *      walk-only baseline. The raw parse cost of each type (no walking, no
 *      bookkeeping) is measured separately for comparison.
 *
 * The document is synthetic: one object holding an array of {@code rows}
 * items, each with two plain strings, two date-times, two decimals and two
 * booleans (all as strings). Every timed conversion gets a fresh copy of the
 * document, built outside the timed region, because the converter mutates its
 * input and an already-converted document would have nothing left to convert.
 *
 * Run with: java jsonschema.JsonSchemaTypeConverterPerfTest [rows] [docsPerRound] [warmupRounds] [measuredRounds]
 * Defaults: 100 rows, 20 documents per round, 150 warm-up rounds, 200 measured rounds
 * (about 6 seconds). Within each part the scenarios take turns round by round, so
 * JIT warm-up and machine noise affect them equally. Reported figures are the
 * median over the measured rounds (minimum in brackets).
 */
public final class JsonSchemaTypeConverterPerfTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private enum Kind {
        DATE_TIME("date-time"), DECIMAL("decimal"), BOOLEAN("boolean");

        final String format;

        Kind(String format) {
            this.format = format;
        }
    }

    /** Fields carrying values of each kind, per row. */
    private static final int FIELDS_PER_KIND = 2;

    private static int rows = 100;
    private static int docsPerRound = 20;
    private static int warmupRounds = 150;
    private static int measuredRounds = 200;

    /** Keeps results alive so the JIT cannot discard the measured work. */
    private static long sink;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) rows = Integer.parseInt(args[0]);
        if (args.length > 1) docsPerRound = Integer.parseInt(args[1]);
        if (args.length > 2) warmupRounds = Integer.parseInt(args[2]);
        if (args.length > 3) measuredRounds = Integer.parseInt(args[3]);

        String documentJson = buildDocumentJson(rows);
        int leafValues = 1 + rows * 4 * FIELDS_PER_KIND;
        int perKind = rows * FIELDS_PER_KIND;

        System.out.printf("Document: %d rows, %d string values (%d date-time, %d decimal, %d boolean, %d plain), %d bytes of JSON%n",
                rows, leafValues, perKind, perKind, perKind, leafValues - 3 * perKind, documentJson.length());
        System.out.printf("Rounds: %d warm-up + %d measured, %d documents each. Java %s%n%n",
                warmupRounds, measuredRounds, docsPerRound, System.getProperty("java.version"));

        verifyConversions(documentJson, perKind);

        // ---- Part 1: end-to-end convert(), one format switched on at a time ----
        JsonNode walkOnlySchema = buildSchema(EnumSet.noneOf(Kind.class));
        Stats[] part1 = time(
                convertTask(documentJson, walkOnlySchema),
                convertTask(documentJson, buildSchema(EnumSet.of(Kind.DATE_TIME))),
                convertTask(documentJson, buildSchema(EnumSet.of(Kind.DECIMAL))),
                convertTask(documentJson, buildSchema(EnumSet.of(Kind.BOOLEAN))),
                convertTask(documentJson, buildSchema(EnumSet.allOf(Kind.class))));
        Stats walkOnly = part1[0];

        System.out.println("1. JsonSchemaTypeConverter.convert(), full call per document");
        System.out.println("   scenario                             us/doc      (min)   conversions   added us/doc   ns/conversion");
        printScenario("walk only (no formats in schema)", walkOnly, walkOnly, 0);
        printScenario("date-time only", part1[1], walkOnly, perKind);
        printScenario("decimal only", part1[2], walkOnly, perKind);
        printScenario("boolean only", part1[3], walkOnly, perKind);
        printScenario("all three formats", part1[4], walkOnly, 3 * perKind);

        // ---- Part 2: where the walk-only time goes -----------------------------
        JsonNode shadowTree = MAPPER.valueToTree(parseDocument(documentJson));
        JsonSchema reused = newSchema(walkOnlySchema);
        Stats[] part2 = time(
                new Task<Map<String, Object>>(() -> parseDocument(documentJson),
                        doc -> sink += MAPPER.valueToTree(doc).size()),
                new Task<Object>(() -> null,
                        unused -> sink += newSchema(walkOnlySchema).hashCode()),
                new Task<JsonSchema>(() -> newSchema(walkOnlySchema),
                        schema -> sink += schema.walk(shadowTree, false).getValidationMessages().size()),
                new Task<JsonSchema>(() -> reused,
                        schema -> sink += schema.walk(shadowTree, false).getValidationMessages().size()));
        Stats shadow = part2[0], compile = part2[1], walkFresh = part2[2], walkReused = part2[3];

        System.out.println();
        System.out.println("2. Stages of the walk-only call, timed separately");
        System.out.println("   stage                                             us/doc      (min)   ns/value");
        printStage("build JsonNode shadow of the document", shadow, leafValues);
        printStage("create JsonSchema object (factory.getSchema)", compile, leafValues);
        printStage("walk, schema object new for each document", walkFresh, leafValues);
        printStage("walk, schema object reused across documents", walkReused, leafValues);
        System.out.printf("   %-46s %9.1f%n", "sum of first three stages",
                (shadow.median + compile.median + walkFresh.median) / 1000.0);
        System.out.printf("   %-46s %9.1f%n", "convert(), walk only (from part 1)", walkOnly.median / 1000.0);

        // ---- Part 3: raw parsing, no walking and no bookkeeping ---------------
        String[] dates = new String[perKind];
        String[] decimals = new String[perKind];
        String[] booleans = new String[perKind];
        for (int i = 0; i < perKind; i++) {
            dates[i] = dateValue(i);
            decimals[i] = decimalValue(i);
            booleans[i] = booleanValue(i);
        }
        Stats[] part3 = time(
                new Task<String[]>(() -> dates, a -> {
                    for (String s : a) sink += Date.from(OffsetDateTime.parse(s).toInstant()).getTime();
                }),
                new Task<String[]>(() -> decimals, a -> {
                    for (String s : a) sink += new BigDecimal(s).scale();
                }),
                new Task<String[]>(() -> booleans, a -> {
                    for (String s : a) sink += "true".equalsIgnoreCase(s) ? 1 : 0;
                }));
        Stats rawDate = part3[0], rawDecimal = part3[1], rawBoolean = part3[2];

        System.out.println();
        System.out.println("3. Raw parse cost per value (no schema, no walking)");
        System.out.println("   conversion                                   ns/value      (min)");
        printRaw("String -> OffsetDateTime -> java.util.Date", rawDate, perKind);
        printRaw("String -> java.math.BigDecimal", rawDecimal, perKind);
        printRaw("String -> Boolean", rawBoolean, perKind);

        System.out.println();
        System.out.println("(sink " + sink + ")");
    }

    // ------------------------------------------------------------------ timing

    private record Stats(double median, double min) {
    }

    /** One thing to time: {@code prepare} runs outside the clock, {@code op} inside it. */
    private record Task<T>(Supplier<T> prepare, Consumer<T> op) {
    }

    /** A full convert() call; each call gets a fresh, unconverted document. */
    private static Task<Map<String, Object>> convertTask(String documentJson, JsonNode schema) {
        return new Task<>(() -> parseDocument(documentJson), doc -> {
            JsonSchemaTypeConverter.convert(doc, schema);
            sink += doc.size();
        });
    }

    /**
     * Times the tasks in turn, round by round. In each round a task runs its
     * op on {@code docsPerRound} inputs prepared before the clock starts.
     * Returns nanoseconds per op for each task, in the order given.
     */
    private static Stats[] time(Task<?>... tasks) {
        double[][] perOp = new double[tasks.length][measuredRounds];
        for (int round = -warmupRounds; round < measuredRounds; round++) {
            for (int t = 0; t < tasks.length; t++) {
                double nanos = runRound(tasks[t]);
                if (round >= 0) perOp[t][round] = nanos;
            }
        }
        Stats[] stats = new Stats[tasks.length];
        for (int t = 0; t < tasks.length; t++) {
            Arrays.sort(perOp[t]);
            stats[t] = new Stats(perOp[t][measuredRounds / 2], perOp[t][0]);
        }
        return stats;
    }

    private static <T> double runRound(Task<T> task) {
        @SuppressWarnings("unchecked")
        T[] inputs = (T[]) new Object[docsPerRound];
        for (int i = 0; i < docsPerRound; i++) inputs[i] = task.prepare().get();

        long start = System.nanoTime();
        for (T input : inputs) task.op().accept(input);
        return (double) (System.nanoTime() - start) / docsPerRound;
    }

    private static void printScenario(String label, Stats s, Stats baseline, int conversions) {
        if (conversions == 0) {
            System.out.printf("   %-34s %9.1f  (%7.1f)   %11d              -               -%n",
                    label, s.median / 1000.0, s.min / 1000.0, conversions);
            return;
        }
        double added = s.median - baseline.median;
        System.out.printf("   %-34s %9.1f  (%7.1f)   %11d   %12.1f   %13.0f%n",
                label, s.median / 1000.0, s.min / 1000.0, conversions, added / 1000.0, added / conversions);
    }

    private static void printStage(String label, Stats s, int leafValues) {
        System.out.printf("   %-46s %9.1f  (%7.1f)   %8.0f%n",
                label, s.median / 1000.0, s.min / 1000.0, s.median / leafValues);
    }

    private static void printRaw(String label, Stats s, int values) {
        System.out.printf("   %-44s %8.1f  (%7.1f)%n", label, s.median / values, s.min / values);
    }

    // ------------------------------------------------------- schema + document

    private static JsonSchema newSchema(JsonNode schemaNode) {
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        config.addKeywordWalkListener("type", NO_OP_LISTENER);
        return JsonSchemaFactory.getInstance(VersionFlag.V202012).getSchema(schemaNode, config);
    }

    /** Listens on the same keyword as the converter but does nothing. */
    private static final JsonSchemaWalkListener NO_OP_LISTENER = new JsonSchemaWalkListener() {
        @Override
        public WalkFlow onWalkStart(WalkEvent walkEvent) {
            return WalkFlow.CONTINUE;
        }

        @Override
        public void onWalkEnd(WalkEvent walkEvent, Set<ValidationMessage> validationMessages) {
        }
    };

    /** Same shape for every scenario; only the listed kinds get a "format". */
    private static JsonNode buildSchema(Set<Kind> enabled) throws Exception {
        StringBuilder props = new StringBuilder();
        for (int f = 0; f < FIELDS_PER_KIND; f++) {
            props.append("\"text").append(f).append("\":{\"type\":\"string\"},");
            for (Kind kind : Kind.values()) {
                props.append('"').append(fieldName(kind, f)).append("\":{\"type\":\"string\"");
                if (enabled.contains(kind)) props.append(",\"format\":\"").append(kind.format).append('"');
                props.append("},");
            }
        }
        props.setLength(props.length() - 1);
        return MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},"
                + "\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" + props + "}}}}}");
    }

    private static String buildDocumentJson(int rowCount) {
        StringBuilder sb = new StringBuilder("{\"id\":\"doc-1\",\"items\":[");
        for (int r = 0; r < rowCount; r++) {
            if (r > 0) sb.append(',');
            sb.append('{');
            for (int f = 0; f < FIELDS_PER_KIND; f++) {
                int n = r * FIELDS_PER_KIND + f;
                if (f > 0) sb.append(',');
                sb.append("\"text").append(f).append("\":\"plain text value ").append(n).append('"');
                sb.append(",\"").append(fieldName(Kind.DATE_TIME, f)).append("\":\"").append(dateValue(n)).append('"');
                sb.append(",\"").append(fieldName(Kind.DECIMAL, f)).append("\":\"").append(decimalValue(n)).append('"');
                sb.append(",\"").append(fieldName(Kind.BOOLEAN, f)).append("\":\"").append(booleanValue(n)).append('"');
            }
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    private static String fieldName(Kind kind, int index) {
        return kind.name().toLowerCase().replace('_', '-') + index;
    }

    /** Mix of "Z" and numeric-offset timestamps, with and without milliseconds. */
    private static String dateValue(int n) {
        String base = String.format("2026-%02d-%02dT%02d:%02d:%02d", 1 + n % 12, 1 + n % 28, n % 24, n % 60, (n * 7) % 60);
        return switch (n % 3) {
            case 0 -> base + "Z";
            case 1 -> base + String.format(".%03d-05:00", n % 1000);
            default -> base + String.format(".%03dZ", n % 1000);
        };
    }

    private static String decimalValue(int n) {
        return (n * 37 % 100000) + "." + String.format("%04d", n * 13 % 10000);
    }

    private static String booleanValue(int n) {
        return n % 2 == 0 ? "true" : "false";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseDocument(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Guards against measuring a converter that silently converts nothing. */
    @SuppressWarnings("unchecked")
    private static void verifyConversions(String documentJson, int perKind) throws Exception {
        Map<String, Object> doc = parseDocument(documentJson);
        JsonSchemaTypeConverter.convert(doc, buildSchema(EnumSet.allOf(Kind.class)));
        int dates = 0, decimals = 0, booleans = 0;
        for (Map<String, Object> row : (List<Map<String, Object>>) doc.get("items")) {
            for (Object v : row.values()) {
                if (v instanceof Date) dates++;
                else if (v instanceof BigDecimal) decimals++;
                else if (v instanceof Boolean) booleans++;
            }
        }
        if (dates != perKind || decimals != perKind || booleans != perKind) {
            throw new IllegalStateException("converter did not convert as expected: " + dates + " dates, "
                    + decimals + " decimals, " + booleans + " booleans, expected " + perKind + " of each");
        }
    }
}
