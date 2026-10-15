# json-schema

A small Java toolkit for adapting standard JSON Schema and JSON documents to
shapes that MongoDB's `$jsonSchema` validator and Java BSON drivers expect.

Built on the [networknt json-schema-validator](https://github.com/networknt/json-schema-validator)
library (for `$ref` resolution and schema walking) and the official
[mongodb-driver-sync](https://www.mongodb.com/docs/drivers/java/sync/current/).

## Requirements

- Java 25 (the pom pins `--release 25`)
- Maven 3.8+
- A local MongoDB on `mongodb://localhost:27017` — only needed for `SchemaValidator`

## Build and run

```bash
mvn compile
```

To launch the bundled `Demo` (showcases `JsonSchemaTypeConverter` and `BsonSchemaConverter`):

```bash
mvn exec:java
```

To launch one of the other `main` classes, either change the `<mainClass>` in the
exec-maven-plugin block of `pom.xml`, or assemble the classpath once and use plain `java`:

```bash
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
CP="target/classes:$(cat /tmp/cp.txt)"
java -cp "$CP" jsonschema.SchemaValidator sample-schema.json sample-document.json
```

## Classes

### `BsonSchemaConverter`

Converts a standard JSON Schema into a MongoDB-flavoured `$jsonSchema`
document. The conversions it performs:

| Input schema fragment | Output |
|---|---|
| `{"type":"string","format":"date-time"}` | `{"bsonType":"date"}` |
| `{"type":"string","format":"decimal"}`   | `{"bsonType":"decimal"}` |
| `{"type":"string","format":"boolean"}`   | `{"bsonType":"bool"}` |
| `{"type":"integer"}`                     | `{"bsonType":"int"}` |
| `{"type":"string","format":"date"}`      | `{"type":"string","pattern":"^\\d{4}-\\d{2}-\\d{2}$"}` |

Plus these structural behaviours:

- `$ref` is resolved via internal JSON Pointer (only `#/...` form supported); `$defs` / `definitions` blocks are dropped from the output.
- Recursive `$refs` are inlined to **3 levels by default** (configurable via `toBsonSchema(JsonNode, int)`). The next would-be expansion is replaced with `{}` (the empty schema, matches any value) so the tree terminates. A one-line `WARNING: cyclic $ref ...` is printed to `stderr` per affected ref.
- Meta keywords `$schema`, `$id`, `$comment`, `$anchor`, `$dynamicAnchor`, plus `example` and (after conversion) `format`, are stripped — MongoDB's `$jsonSchema` doesn't accept them.
- An empty `"required": []` is dropped. JSON Schema allows it since draft-06 (it constrains nothing), but MongoDB's draft-04 based validator rejects it at collection-creation time with `keyword 'required' cannot be an empty array`. Non-empty `required` arrays pass through unchanged.
- `pattern` is dropped from any field whose `type` becomes a non-string `bsonType` — once the value is a `Date` / `Decimal128` / `Boolean` / `int`, a regex would never match anyway.
- Nullable variants like `{"type":["string","null"],"format":"decimal"}` are accepted and emit `{"bsonType":["decimal","null"]}`. Mixed arrays such as `["string","integer"]` are rejected with an `IllegalArgumentException` — the BSON equivalent would be genuinely ambiguous.
- If the top-level schema declares `"additionalProperties": false` but does not mention `_id`, a permissive `"_id": {}` is auto-injected so MongoDB inserts (which always materialise an `_id`) aren't rejected by the validator.

**API**

```java
JsonNode bson  = BsonSchemaConverter.toBsonSchema(schema);            // default depth 3
JsonNode bson5 = BsonSchemaConverter.toBsonSchema(schema, 5);          // explicit depth
JsonNode bson  = BsonSchemaConverter.toBsonSchema(schemaJsonString);   // String overload
```

**CLI**

```bash
java jsonschema.BsonSchemaConverter <schema.json> [output.json]
```

Pretty-prints the BSON schema to stdout, or writes it to the second-argument file (with a trailing newline so the file is well-formed).

### `JsonSchemaTypeConverter`

Walks a JSON document against its schema and, where the schema declares one of
the three string-formats above, replaces the document's string value with a
native Java instance **in place** on the `Map<String,Object>`:

| Schema | Java type produced |
|---|---|
| `{"type":"string","format":"date-time"}` | `java.util.Date` (RFC 3339 — must carry `Z` or a numeric offset) |
| `{"type":"string","format":"decimal"}`   | `java.math.BigDecimal` |
| `{"type":"string","format":"boolean"}`   | `java.lang.Boolean` |

The document and any nested `List`s must be mutable; `Map.of(...)` /
`List.of(...)` cannot be modified. Values that are already typed (e.g. a `Date`
parsed from EJSON's `{"$date":...}`) are left alone — the converter only acts
on textual values.

**API**

```java
JsonSchemaTypeConverter.convert(document, schema);                       // mutates in place
Map<String,Object> doc = JsonSchemaTypeConverter.convertJson(docJson, schemaJson);
```

### `JsonSchemaDateTruncator`

Same walker pattern, applied to `{"type":"string","format":"date"}` fields:
if the document value is a full RFC 3339 timestamp (contains `T`), the
time-of-day is stripped, leaving just `YYYY-MM-DD`. Already-date-only strings
and values that don't look like timestamps are left untouched.

Useful as a preprocessing step before `BsonSchemaConverter`'s
`format: date` → `pattern: ^\d{4}-\d{2}-\d{2}$` validator: callers that
supply full timestamps for date-only fields will pass validation rather than
get rejected.

**API**

```java
JsonSchemaDateTruncator.convert(document, schema);   // mutates in place
```

**CLI**

```bash
java jsonschema.JsonSchemaDateTruncator <schema.json> <document.json>
```

Prints the converted document as pretty JSON.

### `SchemaValidator`

End-to-end pipeline against a local MongoDB. Each step is logged as it runs:

1. Parse the JSON schema.
2. Convert it to a BSON schema via `BsonSchemaConverter`.
3. Drop and recreate the collection `bson_schema_check.validation_target` on `mongodb://localhost:27017`, installing the BSON schema as a `$jsonSchema` validator.
4. Read the sample document — parsed as **Extended JSON**, so EJSON syntax such as `{"$numberDecimal":"123.45"}` or `{"$oid":"…"}` is honoured.
5. Run `JsonSchemaTypeConverter` to upgrade any remaining plain-string fields to native Java types.
6. Insert the document, which triggers MongoDB's validator.

On success: `SUCCESS: document was accepted by the MongoDB validator.`. On
rejection: a pretty-printed multi-line version of MongoDB's
`schemaRulesNotSatisfied` detail is written to stderr, pinpointing the failing
property and rule.

Relative paths are resolved against `/Users/andre.spiegel/Downloads/cvs`
(this base directory is a `private static final` in the source — adjust to
your own root if you fork). Absolute paths starting with `/` are used verbatim.

**CLI**

```bash
java jsonschema.SchemaValidator <schema.json> <document.json>
```

### `Demo`

The main class wired to `mvn exec:java`. Runs `JsonSchemaTypeConverter` over a
hard-coded sample document, prints the upgraded values, then runs
`BsonSchemaConverter` over the same schema and prints the resulting BSON form.

## Sample inputs

`sample-schema.json` and `sample-document.json` in the project root drive the
`Demo` and are good starting points for `SchemaValidator`. `date-schema.json`
and `date-document.json` are larger realistic samples used in performance
checks of `JsonSchemaDateTruncator`.
