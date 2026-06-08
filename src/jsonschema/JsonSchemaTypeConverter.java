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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks a JSON document against a JSON schema and replaces string values
 * with native Java instances when the schema specifies one of these
 * (type, format) pairs:
 *
 *   { "type": "string", "format": "date-time" } -> java.util.Date
 *   { "type": "string", "format": "decimal"   } -> java.math.BigDecimal
 *   { "type": "string", "format": "boolean"   } -> java.lang.Boolean
 *
 * Schema features such as $ref, properties, items, additionalProperties,
 * allOf/anyOf/oneOf are handled by delegating schema traversal to the
 * networknt json-schema-validator library: $ref nodes are resolved before
 * the listener sees them, so we always observe the effective subschema.
 *
 * The document and any nested Lists must be mutable: the conversion is
 * performed in place via Map.put and List.set. Map.of(...) / List.of(...)
 * and Arrays.asList(array) are not supported.
 */
public final class JsonSchemaTypeConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSchemaTypeConverter() {
    }

    /** Mutates {@code document} in place. */
    public static void convert(Map<String, Object> document, JsonNode schema) {
        convert(document, schema, JsonSchemaFactory.getInstance(VersionFlag.V202012));
    }

    /** Same as {@link #convert(Map, JsonNode)} but with a caller-supplied factory
     *  (useful when the schema needs custom URI resolvers, meta-schema, etc.). */
    public static void convert(Map<String, Object> document, JsonNode schema, JsonSchemaFactory factory) {
        // Build a throwaway JsonNode shadow so networknt has something to walk.
        // The shadow is structurally parallel to `document`, so InstanceLocation
        // paths produced by the walker map directly onto Map keys / List indices.
        JsonNode shadow = MAPPER.valueToTree(document);

        List<Conversion> conversions = new ArrayList<>();
        SchemaValidatorsConfig config = new SchemaValidatorsConfig();
        // We listen on "type" because every subschema we care about declares one,
        // and the walker fires this listener with the resolved (post-$ref) schema node.
        config.addKeywordWalkListener("type", new ConversionListener(conversions));

        JsonSchema jsonSchema = factory.getSchema(schema, config);
        jsonSchema.walk(shadow, false);

        for (Conversion c : conversions) {
            applyConversion(document, c.path, c.value);
        }
    }

    /** Convenience overload that parses both inputs from JSON strings. */
    public static Map<String, Object> convertJson(String documentJson, String schemaJson) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> document = MAPPER.readValue(documentJson, Map.class);
        JsonNode schema = MAPPER.readTree(schemaJson);
        convert(document, schema);
        return document;
    }

    private record Conversion(JsonNodePath path, Object value) {
    }

    private static final class ConversionListener implements JsonSchemaWalkListener {
        private final List<Conversion> conversions;

        ConversionListener(List<Conversion> conversions) {
            this.conversions = conversions;
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
            if (formatNode == null || !formatNode.isTextual()) return WalkFlow.CONTINUE;

            String format = formatNode.asText();
            String value = dataNode.asText();
            Object converted;
            try {
                converted = switch (format) {
                    case "date-time" -> parseDateTime(value);
                    case "decimal"   -> new BigDecimal(value);
                    case "boolean"   -> parseBoolean(value);
                    default          -> null;
                };
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "cannot convert value \"" + value + "\" at "
                                + walkEvent.getInstanceLocation()
                                + " to format \"" + format + "\": " + e.getMessage(), e);
            }

            if (converted != null) {
                conversions.add(new Conversion(walkEvent.getInstanceLocation(), converted));
            }
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

    private static Boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value))  return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        throw new IllegalArgumentException("not a boolean: " + value);
    }

    private static Date parseDateTime(String value) {
        // RFC 3339 date-time: must carry a "Z" suffix or an explicit numeric offset.
        // OffsetDateTime.parse() uses ISO_OFFSET_DATE_TIME, which requires that.
        return Date.from(OffsetDateTime.parse(value).toInstant());
    }

    @SuppressWarnings("unchecked")
    private static void applyConversion(Map<String, Object> root, JsonNodePath path, Object value) {
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
