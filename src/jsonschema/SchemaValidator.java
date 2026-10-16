package jsonschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.ValidationOptions;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * End-to-end check: parse a JSON schema, check it against the modelling
 * guidelines (see {@link SchemaGuidelineChecker}), convert it to a MongoDB BSON
 * schema, install the result as a $jsonSchema validator on a fresh
 * collection, parse a sample EJSON document, upgrade its remaining string
 * fields to native Java types, then attempt to insert. MongoDB enforces
 * the validator at insert time, so a successful insert means the document
 * matches the schema.
 *
 * Usage: SchemaValidator &lt;schema.json&gt; &lt;document.json&gt;
 */
public class SchemaValidator {

    private static final String CONNECTION_STRING = "mongodb://localhost:27017";
    private static final String DB_NAME = "bson_schema_check";
    private static final String COLLECTION_NAME = "validation_target";
    private static final Path BASE_DIR = Path.of("/Users/andrespiegel/Downloads/cvs/models");

    private static int currentStep = 0;

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: SchemaValidator <schema.json> <document.json>");
            System.exit(2);
        }

        Path schemaPath = resolve(args[0]);
        Path documentPath = resolve(args[1]);
        ObjectMapper mapper = new ObjectMapper();

        try (MongoClient client = MongoClients.create(CONNECTION_STRING)) {
            step("Reading and parsing JSON schema: " + schemaPath);
            JsonNode schema = mapper.readTree(schemaPath.toFile());

            step("Checking schema against modelling guidelines");
            List<SchemaGuidelineChecker.Violation> violations = SchemaGuidelineChecker.check(schema);
            if (!violations.isEmpty()) {
                failure(violations.size() + " guideline violation(s) in " + schemaPath);
                for (SchemaGuidelineChecker.Violation v : violations) {
                    System.err.println("  " + v);
                }
                System.exit(1);
            }

            step("Converting JSON schema to BSON schema");
            JsonNode bsonSchema = BsonSchemaConverter.toBsonSchema(schema);

            step("Creating empty collection " + DB_NAME + "." + COLLECTION_NAME
                    + " with $jsonSchema validator");
            MongoDatabase db = client.getDatabase(DB_NAME);
            db.getCollection(COLLECTION_NAME).drop(); // wipe leftovers from previous runs
            BsonDocument validator = new BsonDocument(
                    "$jsonSchema", BsonDocument.parse(bsonSchema.toString()));
            db.createCollection(COLLECTION_NAME, new CreateCollectionOptions()
                    .validationOptions(new ValidationOptions().validator(validator)));

            step("Reading sample document (EJSON): " + documentPath);
            String docJson = Files.readString(documentPath);
            Document document = Document.parse(docJson);

            step("Converting remaining string values via JsonSchemaTypeConverter");
            JsonSchemaTypeConverter.convert(document, schema);

            step("Inserting document into " + COLLECTION_NAME);
            db.getCollection(COLLECTION_NAME).insertOne(document);

            System.out.println();
            System.out.println("SUCCESS: document was accepted by the MongoDB validator.");
        } catch (MongoWriteException e) {
            // MongoDB rejected the document. Surface the validator's structured detail.
            failure("MongoDB rejected the document: " + e.getError().getMessage());
            BsonDocument details = e.getError().getDetails();
            if (details != null && !details.isEmpty()) {
                System.err.println(details.toJson(JsonWriterSettings.builder()
                        .indent(true)
                        .outputMode(JsonMode.RELAXED)
                        .build()));
            }
            System.exit(1);
        } catch (Exception e) {
            failure(e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void step(String message) {
        currentStep++;
        System.out.println("[step " + currentStep + "] " + message);
    }

    private static Path resolve(String name) {
        return name.startsWith("/") ? Path.of(name) : BASE_DIR.resolve(name);
    }

    private static void failure(String message) {
        System.err.println();
        System.err.println("ERROR at step " + currentStep + ": " + message);
    }
}
