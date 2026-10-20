package com.bbmurloc.victoriaeconomics.server.storage.sqlite;

import com.bbmurloc.victoriaeconomics.server.inventory.domain.GoodsInventory;
import com.bbmurloc.victoriaeconomics.server.production.domain.*;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/** Schema v2's existing, untagged JSON contract. No migration, numeric coercion or future-format reader. */
public final class EconomyStateCodec {
    private static final Gson GSON = new Gson();
    private static final int V2_FULL_SPEED_TICKS = 1200;
    private EconomyStateCodec() {}

    /** A future semantic change must replace the schema/codec together, never reinterpret v2 in place. */
    public static void requireCurrentFormat() {
        if (EconomySchema.VERSION != 2 || ProductionBatch.FULL_SPEED_TICKS != V2_FULL_SPEED_TICKS)
            throw new IllegalStateException("Schema v2 codec requires Double quantities, UUID/equipment snapshots and 1200-tick batches; introduce a new schema and codec before changing persistence semantics");
    }

    public static ProductionBatchConfiguration decodeBatchConfiguration(String json) {
        requireCurrentFormat();
        try {
            var root = fields(parse(json), "productionMethodSelections", "resolvedRecipe", "installedEquipment", "equipmentCapacity", "workforcePlan", "effectiveRevision");
            var recipe = fields(root.get("resolvedRecipe"), "inputs", "outputs", "requiredWorkers");
            var workforce = fields(root.get("workforcePlan"), "employees", "speedNumerator", "speedDenominator");
            var employees = map(workforce.get("employees"), value -> {
                if (!value.isJsonArray()) throw invalid("Employee UUID list required");
                List<UUID> ids = new ArrayList<>();
                value.getAsJsonArray().forEach(employee -> ids.add(uuid(employee)));
                return ids;
            });
            return new ProductionBatchConfiguration(map(root.get("productionMethodSelections"), EconomyStateCodec::string),
                    new ResolvedProductionRecipe(quantities(recipe.get("inputs")), quantities(recipe.get("outputs")),
                            map(recipe.get("requiredWorkers"), value -> number(value).intValueExact())),
                    number(root.get("installedEquipment")).intValueExact(), number(root.get("equipmentCapacity")).intValueExact(),
                    new WorkforcePlan(employees, number(workforce.get("speedNumerator")).intValueExact(),
                            number(workforce.get("speedDenominator")).intValueExact()),
                    number(root.get("effectiveRevision")).longValueExact());
        } catch (RuntimeException failure) {
            throw incompatible("Batch configuration", failure);
        }
    }

    public static ProductionBatch decodeBatch(UUID batchId, UUID buildingId, String json, long progress, String status) {
        try {
            return new ProductionBatch(batchId, buildingId, decodeBatchConfiguration(json), progress, ProductionBatch.Status.valueOf(status));
        } catch (RuntimeException failure) {
            throw incompatible("Batch " + batchId, failure);
        }
    }

    public static String encodeBatchConfiguration(ProductionBatchConfiguration configuration) {
        requireCurrentFormat();
        String json = GSON.toJson(configuration);
        decodeBatchConfiguration(json); // Also reject an accidental record-shape change under v2.
        return json;
    }

    public static GoodsInventory decodeInventory(UUID expectedLocation, String json) {
        requireCurrentFormat();
        try {
            var root = fields(parse(json), "location", "capacity", "quantities", "reservations", "settlements");
            UUID location = uuid(root.get("location"));
            if (!location.equals(expectedLocation)) throw invalid("Inventory location differs from its row key");
            Map<UUID, Map<String, Double>> reservations = uuidMap(root.get("reservations"), EconomyStateCodec::quantities);
            Map<UUID, GoodsInventory.Settlement> settlements = uuidMap(root.get("settlements"), value -> {
                var receipt = fields(value, "batchId", "consumed", "produced");
                return new GoodsInventory.Settlement(uuid(receipt.get("batchId")), quantities(receipt.get("consumed")), quantities(receipt.get("produced")));
            });
            settlements.forEach((batch, receipt) -> {
                if (!batch.equals(receipt.batchId())) throw invalid("Settlement batch differs from its map key");
            });
            return new GoodsInventory(new GoodsInventory.State(location, finiteDouble(root.get("capacity")),
                    quantities(root.get("quantities")), reservations, settlements));
        } catch (RuntimeException failure) {
            throw incompatible("Inventory " + expectedLocation, failure);
        }
    }

    public static String encodeInventory(GoodsInventory.State state) {
        requireCurrentFormat();
        String json = GSON.toJson(state);
        decodeInventory(state.location(), json);
        return json;
    }

    private static Map<String, Double> quantities(JsonElement element) {
        return map(element, value -> {
            double quantity = finiteDouble(value);
            if (quantity < 0) throw invalid("Negative quantity");
            return quantity;
        });
    }

    private static double finiteDouble(JsonElement value) {
        number(value); // Require a JSON number, but retain Double's signed zero and original parsing.
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) throw invalid("Finite Double required");
        return result;
    }

    private static BigDecimal number(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw invalid("JSON number required (numeric strings are not accepted)");
        return value.getAsBigDecimal();
    }

    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid("JSON string required");
        return value.getAsString();
    }

    private static UUID uuid(JsonElement value) { return uuid(string(value)); }
    private static UUID uuid(String text) {
        UUID id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw invalid("Canonical UUID required");
        return id;
    }

    private static JsonObject fields(JsonElement value, String... names) {
        JsonObject result = object(value);
        if (!result.keySet().equals(Set.of(names))) throw invalid("Unexpected or missing fields; expected " + Arrays.toString(names));
        return result;
    }

    private static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw invalid("JSON object required");
        return value.getAsJsonObject();
    }

    private static <T> Map<String, T> map(JsonElement value, Function<JsonElement, T> decode) {
        Map<String, T> result = new LinkedHashMap<>();
        object(value).entrySet().forEach(entry -> {
            if (entry.getKey().isBlank()) throw invalid("Blank map key");
            result.put(entry.getKey(), decode.apply(entry.getValue()));
        });
        return result;
    }

    private static <T> Map<UUID, T> uuidMap(JsonElement value, Function<JsonElement, T> decode) {
        Map<UUID, T> result = new LinkedHashMap<>();
        object(value).entrySet().forEach(entry -> result.put(uuid(entry.getKey()), decode.apply(entry.getValue())));
        return result;
    }

    private static JsonElement parse(String json) {
        try (var reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonElement result = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("Trailing JSON data");
            return result;
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid JSON", failure); }
    }

    // Gson 2.10.1 JsonParser/fromJson enable lenient parsing and discard duplicate object names.
    // Read strictly before typed decoding; these two fixed formats need no extensible serializer system.
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 16) throw invalid("Snapshot nesting exceeds the v2 format");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                var result = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (result.has(name)) throw invalid("Duplicate JSON field: " + name);
                    result.add(name, read(reader, depth + 1));
                }
                reader.endObject();
                yield result;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                var result = new JsonArray();
                while (reader.hasNext()) result.add(read(reader, depth + 1));
                reader.endArray();
                yield result;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            // This token has already passed the strict stream reader. Parse just the number to
            // preserve its lexical value (notably -0.0), without lenient object parsing.
            case NUMBER -> JsonParser.parseString(reader.nextString());
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw invalid("Unexpected JSON token");
        };
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static IllegalStateException incompatible(String kind, RuntimeException failure) {
        return new IllegalStateException("Invalid or incompatible Schema v2 " + kind + "; no conversion was attempted", failure);
    }
}
