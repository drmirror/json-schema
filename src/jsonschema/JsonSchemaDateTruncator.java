package jsonschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonNodePath;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion.VersionFlag;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.walk.JsonSchemaWalkListener;
import com.networknt.schema.walk.WalkEvent;
import com.networknt.schema.walk.WalkFlow;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Walks a JSON document against its schema and, for every value declared as
 * { "type": "string", "format": "date" }, drops the time-of-day if the value
 * is a full RFC 3339 timestamp. Strings that are already date-only (or that
 * do not look like timestamps at all) are left untouched.
 *
 * The document and any nested Lists must be mutable: the truncation is
 * performed in place via Map.put and List.set.
 */
public final class JsonSchemaDateTruncator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Captures the YYYY-MM-DD date portion of a value that begins with a timestamp. */
    private static final Pattern DATE_PREFIX = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})T");

    private JsonSchemaDateTruncator() {
    }

    /** Mutates {@code document} in place. */
    public static void convert(Map<String, Object> document, JsonNode schema) {
        convert(document, schema, JsonSchemaFactory.getInstance(VersionFlag.V202012));
    }

    public static void convert(Map<String, Object> document, JsonNode schema, JsonSchemaFactory factory) {
        // Build a throwaway JsonNode shadow so networknt has something to walk.
        // InstanceLocation paths produced by the walker map directly onto Map
        // keys / List indices in the original document.
        JsonNode shadow = MAPPER.valueToTree(document);

        List<Truncation> truncations = new ArrayList<>();
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        config.addKeywordWalkListener("type", new TruncationListener(truncations));

        JsonSchema jsonSchema = factory.getSchema(schema, config);
        jsonSchema.walk(shadow, false);

        for (Truncation t : truncations) {
            applyTruncation(document, t.path, t.value);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: JsonSchemaDateTruncator <schema.json> <document.json>");
            System.exit(2);
        }
        JsonNode schema = MAPPER.readTree(new File(args[0]));
        @SuppressWarnings("unchecked")
        Map<String, Object> document = MAPPER.readValue(new File(args[1]), Map.class);
        convert(document, schema);
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document));
    }

    private record Truncation(JsonNodePath path, String value) {
    }

    private static final class TruncationListener implements JsonSchemaWalkListener {
        private final List<Truncation> truncations;

        TruncationListener(List<Truncation> truncations) {
            this.truncations = truncations;
        }

        @Override
        public WalkFlow onWalkStart(WalkEvent walkEvent) {
            JsonNode dataNode = walkEvent.getInstanceNode();
            if (dataNode == null || !dataNode.isTextual()) {
                return WalkFlow.CONTINUE;
            }
            JsonNode schemaNode = walkEvent.getSchema().getSchemaNode();
            if (schemaNode == null || !schemaNode.isObject()) {
                return WalkFlow.CONTINUE;
            }
            JsonNode typeNode = schemaNode.get("type");
            JsonNode formatNode = schemaNode.get("format");
            if (!declaresType(typeNode, "string")) return WalkFlow.CONTINUE;
            if (formatNode == null || !formatNode.isTextual()
                    || !"date".equals(formatNode.asText())) return WalkFlow.CONTINUE;

            Matcher matcher = DATE_PREFIX.matcher(dataNode.asText());
            if (!matcher.find()) return WalkFlow.CONTINUE; // already date-only, or unrecognized

            truncations.add(new Truncation(walkEvent.getInstanceLocation(), matcher.group(1)));
            return WalkFlow.CONTINUE;
        }

        @Override
        public void onWalkEnd(WalkEvent walkEvent, Set<ValidationMessage> validationMessages) {
        }
    }

    private static boolean declaresType(JsonNode typeNode, String name) {
        if (typeNode == null) return false;
        if (typeNode.isTextual()) return name.equals(typeNode.asText());
        if (typeNode.isArray()) {
            for (JsonNode t : typeNode) {
                if (t.isTextual() && name.equals(t.asText())) return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void applyTruncation(Map<String, Object> root, JsonNodePath path, Object value) {
        int count = path.getNameCount();
        if (count == 0) return;

        Object current = root;
        for (int i = 0; i < count - 1; i++) {
            Object element = path.getElement(i);
            if (current instanceof Map<?, ?> map && element instanceof String name) {
                current = map.get(name);
            } else if (current instanceof List<?> list && element instanceof Integer idx) {
                current = list.get(idx);
            } else {
                return;
            }
            if (current == null) return;
        }

        Object last = path.getElement(count - 1);
        if (current instanceof Map<?, ?> map && last instanceof String name) {
            ((Map<String, Object>) map).put(name, value);
        } else if (current instanceof List<?> list && last instanceof Integer idx) {
            ((List<Object>) list).set(idx, value);
        }
    }
}
