package jsonschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public class Demo {

    public static void main(String[] args) throws Exception {
        // Schema exercises $ref, nested objects and arrays.
        String schemaJson = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "properties": {
                "name":      { "type": "string" },
                "createdAt": { "$ref": "#/$defs/dateTime" },
                "amount":    { "$ref": "#/$defs/decimal"  },
                "active":    { "$ref": "#/$defs/booleanString" },
                "tags": {
                  "type":  "array",
                  "items": { "$ref": "#/$defs/decimal" }
                },
                "address": {
                  "type": "object",
                  "properties": {
                    "since": { "$ref": "#/$defs/dateTime" }
                  }
                }
              },
              "$defs": {
                "dateTime":      { "type": "string", "format": "date-time" },
                "decimal":       { "type": "string", "format": "decimal"   },
                "booleanString": { "type": "string", "format": "boolean"   }
              }
            }
            """;

        String documentJson = """
            {
              "name":      "Widget",
              "createdAt": "2026-04-27T12:00:00Z",
              "amount":    "12345.6789",
              "active":    "true",
              "tags":      ["1.5", "2.75", "3"],
              "address":   { "since": "2020-01-01T00:00:00+02:00" }
            }
            """;

        Map<String, Object> document = JsonSchemaTypeConverter.convertJson(documentJson, schemaJson);

        printValue("name",          document.get("name"));
        printValue("createdAt",     document.get("createdAt"));
        printValue("amount",        document.get("amount"));
        printValue("active",        document.get("active"));
        List<?> tags = (List<?>) document.get("tags");
        printValue("tags[0]",       tags.get(0));
        printValue("tags[1]",       tags.get(1));
        printValue("tags[2]",       tags.get(2));
        Map<?, ?> address = (Map<?, ?>) document.get("address");
        printValue("address.since", address.get("since"));

        System.out.println();
        System.out.println("--- BSON schema (refs inlined, special formats rewritten) ---");
        JsonNode bsonSchema = BsonSchemaConverter.toBsonSchema(schemaJson);
        ObjectMapper pretty = new ObjectMapper();
        System.out.println(pretty.writerWithDefaultPrettyPrinter().writeValueAsString(bsonSchema));
    }

    private static void printValue(String label, Object value) {
        String typeName = value == null ? "null" : value.getClass().getName();
        System.out.printf("%-15s -> [%s] %s%n", label, typeName, value);
    }
}
