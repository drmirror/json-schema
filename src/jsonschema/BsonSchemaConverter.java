package jsonschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Converts a standard JSON schema to a MongoDB-flavoured BSON schema:
 *
 *   - Subschemas matching {"type":"string","format":"date-time"} become
 *     {"bsonType":"date"}.
 *   - {"type":"string","format":"decimal"} becomes {"bsonType":"decimal"}.
 *   - {"type":"string","format":"boolean"} becomes {"bsonType":"boolean"}.
 *   - All $ref pointers (internal, e.g. "#/$defs/foo") are resolved and
 *     inlined; the $defs / definitions blocks are dropped from the output.
 *   - Meta keywords ($schema, $id, $comment, $anchor, $dynamicAnchor) are
 *     dropped because MongoDB's $jsonSchema validator does not accept them.
 *
 * Only internal pointer-style $refs are supported. External $ref URIs throw.
 * Cyclic $ref chains throw — they cannot be inlined into a finite tree.
 */
public final class BsonSchemaConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Keywords whose value is a single subschema (or boolean schema). */
    private static final Set<String> SINGLE_SUBSCHEMA = Set.of(
            "items", "additionalItems", "additionalProperties",
            "unevaluatedItems", "unevaluatedProperties",
            "contains", "not", "if", "then", "else",
            "propertyNames", "contentSchema"
    );

    /** Keywords whose value is an array of subschemas. */
    private static final Set<String> SUBSCHEMA_ARRAY = Set.of(
            "allOf", "anyOf", "oneOf", "prefixItems"
    );

    /** Keywords whose value is a name -> subschema map. */
    private static final Set<String> SUBSCHEMA_MAP = Set.of(
            "properties", "patternProperties", "dependentSchemas"
    );

    /** Keywords stripped from the output entirely. */
    private static final Set<String> DROP = Set.of(
            "$schema", "$id", "$comment", "$anchor", "$dynamicAnchor",
            "$defs", "definitions",
            "example", "format"
    );

    /** (type=string, format=X) -> bsonType. */
    private static final Map<String, String> FORMAT_TO_BSON_TYPE = Map.of(
            "date-time", "date",
            "decimal",   "decimal",
            "boolean",   "bool"
    );

    /** type=X -> bsonType (applied when "type" is a single textual value). */
    private static final Map<String, String> TYPE_TO_BSON_TYPE = Map.of(
            "integer", "int"
    );

    /** (type=string, format=X) -> regex pattern.
     *  BSON has no native "date-only" type, so we keep the value as a string
     *  and add a pattern constraint. */
    private static final Map<String, String> FORMAT_TO_PATTERN = Map.of(
            "date", "^\\d{4}-\\d{2}-\\d{2}$"
    );

    private BsonSchemaConverter() {
    }

    /** Default number of times each $ref is inlined on a single path before truncation. */
    public static final int DEFAULT_MAX_REF_DEPTH = 3;

    public static JsonNode toBsonSchema(JsonNode schema) {
        return toBsonSchema(schema, DEFAULT_MAX_REF_DEPTH);
    }

    /**
     * Convert {@code schema} to BSON. {@code maxRefDepth} caps how many times a
     * given $ref is inlined on a single root-to-leaf path; the next would-be
     * expansion is replaced with {@code {}} (the empty schema, which matches
     * any value). This lets recursive definitions terminate in a finite tree.
     */
    public static JsonNode toBsonSchema(JsonNode schema, int maxRefDepth) {
        if (maxRefDepth < 0) {
            throw new IllegalArgumentException("maxRefDepth must be >= 0, got " + maxRefDepth);
        }
        JsonNode result = new Walker(schema, maxRefDepth).walk(schema, new HashMap<>());
        if (result instanceof ObjectNode top) {
            injectIdForMongoInsert(top);
        }
        return result;
    }

    /**
     * MongoDB always materialises an "_id" on insert (an ObjectId by default,
     * or whatever type the caller supplied). If the top-level schema declares
     * "additionalProperties": false but does not mention "_id", every insert
     * would fail validation. Inject a permissive "_id" subschema (the empty
     * schema {} matches any value) so inserts succeed regardless of the type
     * the caller chose for _id.
     */
    private static void injectIdForMongoInsert(ObjectNode top) {
        JsonNode additional = top.get("additionalProperties");
        if (additional == null || !additional.isBoolean() || additional.asBoolean()) {
            return;
        }
        ObjectNode properties;
        JsonNode existing = top.get("properties");
        if (existing instanceof ObjectNode obj) {
            properties = obj;
        } else {
            properties = MAPPER.createObjectNode();
            top.set("properties", properties);
        }
        if (!properties.has("_id")) {
            properties.set("_id", MAPPER.createObjectNode());
        }
    }

    public static JsonNode toBsonSchema(String schemaJson) throws Exception {
        return toBsonSchema(MAPPER.readTree(schemaJson));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: BsonSchemaConverter <input-schema.json> [output-schema.json]");
            System.exit(2);
        }
        JsonNode bson = toBsonSchema(MAPPER.readTree(new File(args[0])));
        ObjectWriter writer = MAPPER.writerWithDefaultPrettyPrinter();
        String output = writer.writeValueAsString(bson) + System.lineSeparator();
        if (args.length == 2) {
            Files.writeString(Path.of(args[1]), output);
        } else {
            System.out.print(output);
        }
    }

    private static final class Walker {
        private final JsonNode root;
        private final int maxRefDepth;
        private final Set<String> truncatedRefs = new HashSet<>();

        Walker(JsonNode root, int maxRefDepth) {
            this.root = root;
            this.maxRefDepth = maxRefDepth;
        }

        JsonNode walk(JsonNode node, Map<String, Integer> refDepth) {
            if (node == null) return null;
            if (!node.isObject()) return node.deepCopy(); // boolean schemas, etc.

            JsonNode refNode = node.get("$ref");
            if (refNode != null && refNode.isTextual()) {
                String ref = refNode.asText();
                int current = refDepth.getOrDefault(ref, 0);
                JsonNode inlined;
                if (current >= maxRefDepth) {
                    // Recursion (or just deep $ref chain) reached the budget.
                    // Emit {} — matches any value — so the document tree can terminate.
                    if (truncatedRefs.add(ref)) {
                        System.err.println("WARNING: cyclic $ref \"" + ref
                                + "\" truncated after " + maxRefDepth
                                + " level(s) of inlining; emitting empty schema {}");
                    }
                    inlined = MAPPER.createObjectNode();
                } else {
                    Map<String, Integer> next = new HashMap<>(refDepth);
                    next.put(ref, current + 1);
                    inlined = walk(resolveRef(ref), next);
                }

                // Sibling keywords next to $ref override / extend the inlined target.
                if (node.size() > 1 && inlined.isObject()) {
                    ObjectNode merged = (ObjectNode) inlined.deepCopy();
                    for (Map.Entry<String, JsonNode> e : node.properties()) {
                        String k = e.getKey();
                        if (k.equals("$ref") || DROP.contains(k)) continue;
                        merged.set(k, walkValue(k, e.getValue(), refDepth));
                    }
                    return merged;
                }
                return inlined;
            }

            JsonNode typeNode = node.get("type");
            JsonNode formatNode = node.get("format");
            String formatText = (formatNode != null && formatNode.isTextual()) ? formatNode.asText() : null;
            if (formatText != null
                    && (FORMAT_TO_BSON_TYPE.containsKey(formatText) || FORMAT_TO_PATTERN.containsKey(formatText))
                    && !isTypeOrTypeNullable(typeNode, "string")) {
                throw new IllegalArgumentException(
                        "format \"" + formatText + "\" requires type \"string\" or [\"string\", \"null\"], "
                                + "but schema declares type "
                                + (typeNode == null ? "(missing)" : typeNode.toString()));
            }
            // Reject mixed type arrays where a TYPE_TO_BSON_TYPE key appears with non-null partners.
            for (String typeKey : TYPE_TO_BSON_TYPE.keySet()) {
                if (arrayContainsType(typeNode, typeKey) && !isTypeOrTypeNullable(typeNode, typeKey)) {
                    throw new IllegalArgumentException(
                            "type \"" + typeKey + "\" can only appear alone or paired with \"null\", "
                                    + "but schema declares type " + typeNode);
                }
            }

            String bsonType = formatText != null ? FORMAT_TO_BSON_TYPE.get(formatText) : null;
            if (bsonType != null && isTypeOrTypeNullable(typeNode, "string")) {
                ObjectNode result = MAPPER.createObjectNode();
                if (declaresType(typeNode, "null")) {
                    ArrayNode arr = MAPPER.createArrayNode();
                    arr.add(bsonType);
                    arr.add("null");
                    result.set("bsonType", arr);
                } else {
                    result.put("bsonType", bsonType);
                }
                for (Map.Entry<String, JsonNode> e : node.properties()) {
                    String k = e.getKey();
                    // "pattern" only constrains string instances; once the value is a
                    // Date / Decimal128 / Boolean it would never match anyway.
                    if (k.equals("type") || k.equals("pattern") || DROP.contains(k)) continue;
                    result.set(k, walkValue(k, e.getValue(), refDepth));
                }
                return result;
            }

            String mappedType = null;
            for (Map.Entry<String, String> entry : TYPE_TO_BSON_TYPE.entrySet()) {
                if (isTypeOrTypeNullable(typeNode, entry.getKey())) {
                    mappedType = entry.getValue();
                    break;
                }
            }
            if (mappedType != null) {
                ObjectNode result = MAPPER.createObjectNode();
                if (declaresType(typeNode, "null")) {
                    ArrayNode arr = MAPPER.createArrayNode();
                    arr.add(mappedType);
                    arr.add("null");
                    result.set("bsonType", arr);
                } else {
                    result.put("bsonType", mappedType);
                }
                for (Map.Entry<String, JsonNode> e : node.properties()) {
                    String k = e.getKey();
                    // "pattern" only constrains string instances; once the value is a
                    // non-string BSON type (e.g. int) it would never match anyway.
                    if (k.equals("type") || k.equals("pattern") || DROP.contains(k)) continue;
                    result.set(k, walkValue(k, e.getValue(), refDepth));
                }
                return result;
            }

            if (declaresType(typeNode, "string") && formatNode != null && formatNode.isTextual()) {
                String pattern = FORMAT_TO_PATTERN.get(formatNode.asText());
                if (pattern != null) {
                    ObjectNode result = MAPPER.createObjectNode();
                    for (Map.Entry<String, JsonNode> e : node.properties()) {
                        String k = e.getKey();
                        if (DROP.contains(k)) continue; // drops "format"
                        result.set(k, walkValue(k, e.getValue(), refDepth));
                    }
                    // Caller's own pattern wins; only inject if none was supplied.
                    if (!result.has("pattern")) {
                        result.put("pattern", pattern);
                    }
                    return result;
                }
            }

            ObjectNode result = MAPPER.createObjectNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String k = e.getKey();
                if (DROP.contains(k)) continue;
                result.set(k, walkValue(k, e.getValue(), refDepth));
            }
            return result;
        }

        private JsonNode walkValue(String keyword, JsonNode value, Map<String, Integer> refDepth) {
            if (SUBSCHEMA_ARRAY.contains(keyword) && value.isArray()) {
                ArrayNode arr = MAPPER.createArrayNode();
                for (JsonNode item : value) arr.add(walk(item, refDepth));
                return arr;
            }
            if (SUBSCHEMA_MAP.contains(keyword) && value.isObject()) {
                ObjectNode obj = MAPPER.createObjectNode();
                for (Map.Entry<String, JsonNode> e : value.properties()) {
                    obj.set(e.getKey(), walk(e.getValue(), refDepth));
                }
                return obj;
            }
            if (SINGLE_SUBSCHEMA.contains(keyword)) {
                if (value.isBoolean()) return value.deepCopy();
                if (value.isArray()) {
                    // Legacy "items" tuple form.
                    ArrayNode arr = MAPPER.createArrayNode();
                    for (JsonNode item : value) arr.add(walk(item, refDepth));
                    return arr;
                }
                return walk(value, refDepth);
            }
            return value.deepCopy();
        }

        private JsonNode resolveRef(String ref) {
            if (ref.equals("#")) return root;
            if (!ref.startsWith("#/")) {
                throw new IllegalArgumentException("Only internal $ref pointers are supported: " + ref);
            }
            JsonNode resolved = root.at(ref.substring(1));
            if (resolved.isMissingNode()) {
                throw new IllegalArgumentException("Unresolvable $ref: " + ref);
            }
            return resolved;
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

    /** True iff type is {@code name}, or an array containing only {@code name} and (optionally) "null". */
    private static boolean isTypeOrTypeNullable(JsonNode typeNode, String name) {
        if (typeNode == null) return false;
        if (typeNode.isTextual()) return name.equals(typeNode.asText());
        if (!typeNode.isArray()) return false;
        boolean hasName = false;
        for (JsonNode t : typeNode) {
            if (!t.isTextual()) return false;
            String s = t.asText();
            if (name.equals(s)) hasName = true;
            else if (!"null".equals(s)) return false;
        }
        return hasName;
    }

    /** True iff {@code typeNode} is an array that contains {@code name} as one of its values. */
    private static boolean arrayContainsType(JsonNode typeNode, String name) {
        if (typeNode == null || !typeNode.isArray()) return false;
        for (JsonNode t : typeNode) {
            if (t.isTextual() && name.equals(t.asText())) return true;
        }
        return false;
    }
    
}
