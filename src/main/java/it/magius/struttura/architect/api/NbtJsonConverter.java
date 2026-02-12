package it.magius.struttura.architect.api;

import com.google.gson.*;
import net.minecraft.nbt.*;

/**
 * Bidirectional converter between Minecraft NBT CompoundTag and Gson JsonObject
 * with type-hint suffixes to preserve NBT type information.
 *
 * Type hint format (serialization):
 *   ByteTag    -> "3b"
 *   ShortTag   -> "100s"
 *   IntTag     -> "42i"
 *   LongTag    -> "1000L"
 *   FloatTag   -> "0.5f"
 *   DoubleTag  -> "1.5d"
 *   StringTag  -> plain string (no suffix)
 *   CompoundTag -> JSON object {}
 *   ListTag    -> JSON array []
 *   ByteArrayTag -> {"_nbtType": "byteArray", "value": [0, 1, 2]}
 *   IntArrayTag  -> {"_nbtType": "intArray", "value": [0, 1, 2]}
 *   LongArrayTag -> {"_nbtType": "longArray", "value": ["0L", "1L"]}
 */
public class NbtJsonConverter {

    // --- Serialization: CompoundTag -> JsonObject ---

    /**
     * Converts a CompoundTag to a JsonObject with type-hint suffixes.
     */
    public static JsonObject compoundTagToJson(CompoundTag tag) {
        JsonObject json = new JsonObject();
        for (String key : tag.keySet()) {
            Tag child = tag.get(key);
            if (child != null) {
                json.add(key, tagToJson(child));
            }
        }
        return json;
    }

    /**
     * Dispatches a single NBT Tag to the appropriate JsonElement.
     */
    private static JsonElement tagToJson(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            return compoundTagToJson(compound);
        }
        if (tag instanceof ListTag list) {
            return listTagToJson(list);
        }
        if (tag instanceof ByteTag byteTag) {
            return new JsonPrimitive(byteTag.byteValue() + "b");
        }
        if (tag instanceof ShortTag shortTag) {
            return new JsonPrimitive(shortTag.shortValue() + "s");
        }
        if (tag instanceof IntTag intTag) {
            return new JsonPrimitive(intTag.intValue() + "i");
        }
        if (tag instanceof LongTag longTag) {
            return new JsonPrimitive(longTag.longValue() + "L");
        }
        if (tag instanceof FloatTag floatTag) {
            return new JsonPrimitive(floatTag.floatValue() + "f");
        }
        if (tag instanceof DoubleTag doubleTag) {
            return new JsonPrimitive(doubleTag.doubleValue() + "d");
        }
        if (tag instanceof StringTag stringTag) {
            return new JsonPrimitive(stringTag.value());
        }
        if (tag instanceof ByteArrayTag byteArrayTag) {
            return byteArrayToJson(byteArrayTag);
        }
        if (tag instanceof IntArrayTag intArrayTag) {
            return intArrayToJson(intArrayTag);
        }
        if (tag instanceof LongArrayTag longArrayTag) {
            return longArrayToJson(longArrayTag);
        }
        // Fallback: treat as string
        return new JsonPrimitive(tag.toString());
    }

    private static JsonArray listTagToJson(ListTag list) {
        JsonArray array = new JsonArray();
        for (int i = 0; i < list.size(); i++) {
            Tag element = list.get(i);
            array.add(tagToJson(element));
        }
        return array;
    }

    private static JsonObject byteArrayToJson(ByteArrayTag tag) {
        JsonObject obj = new JsonObject();
        obj.addProperty("_nbtType", "byteArray");
        JsonArray values = new JsonArray();
        for (byte b : tag.getAsByteArray()) {
            values.add(b);
        }
        obj.add("value", values);
        return obj;
    }

    private static JsonObject intArrayToJson(IntArrayTag tag) {
        JsonObject obj = new JsonObject();
        obj.addProperty("_nbtType", "intArray");
        JsonArray values = new JsonArray();
        for (int v : tag.getAsIntArray()) {
            values.add(v);
        }
        obj.add("value", values);
        return obj;
    }

    private static JsonObject longArrayToJson(LongArrayTag tag) {
        JsonObject obj = new JsonObject();
        obj.addProperty("_nbtType", "longArray");
        JsonArray values = new JsonArray();
        for (long v : tag.getAsLongArray()) {
            values.add(v + "L");
        }
        obj.add("value", values);
        return obj;
    }

    // --- Deserialization: JsonObject -> CompoundTag ---

    /**
     * Converts a JsonObject with type-hint suffixes back to a CompoundTag.
     */
    public static CompoundTag jsonToCompoundTag(JsonObject json) {
        CompoundTag tag = new CompoundTag();
        for (var entry : json.entrySet()) {
            String key = entry.getKey();
            Tag nbtTag = jsonElementToTag(entry.getValue());
            if (nbtTag != null) {
                tag.put(key, nbtTag);
            }
        }
        return tag;
    }

    /**
     * Converts a JsonElement back to an NBT Tag, detecting type from suffixes.
     */
    private static Tag jsonElementToTag(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            // Check for special array types
            if (obj.has("_nbtType")) {
                return jsonToSpecialArray(obj);
            }
            // Regular compound
            return jsonToCompoundTag(obj);
        }
        if (element.isJsonArray()) {
            return jsonArrayToListTag(element.getAsJsonArray());
        }
        if (element.isJsonPrimitive()) {
            return jsonPrimitiveToTag(element.getAsJsonPrimitive());
        }
        if (element.isJsonNull()) {
            return null;
        }
        return null;
    }

    private static Tag jsonToSpecialArray(JsonObject obj) {
        String type = obj.get("_nbtType").getAsString();
        JsonArray values = obj.getAsJsonArray("value");

        return switch (type) {
            case "byteArray" -> {
                byte[] bytes = new byte[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    bytes[i] = values.get(i).getAsByte();
                }
                yield new ByteArrayTag(bytes);
            }
            case "intArray" -> {
                int[] ints = new int[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    ints[i] = values.get(i).getAsInt();
                }
                yield new IntArrayTag(ints);
            }
            case "longArray" -> {
                long[] longs = new long[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    String longStr = values.get(i).getAsString();
                    // Remove 'L' suffix
                    longs[i] = Long.parseLong(removeSuffix(longStr));
                }
                yield new LongArrayTag(longs);
            }
            default -> null;
        };
    }

    private static ListTag jsonArrayToListTag(JsonArray array) {
        ListTag list = new ListTag();
        for (int i = 0; i < array.size(); i++) {
            Tag tag = jsonElementToTag(array.get(i));
            if (tag != null) {
                list.add(tag);
            }
        }
        return list;
    }

    /**
     * Parses a JsonPrimitive back to the correct NBT Tag type using suffix detection.
     * Suffix order check: b, s, i, L, f, d. Plain string if no suffix matches.
     */
    private static Tag jsonPrimitiveToTag(JsonPrimitive primitive) {
        if (!primitive.isString()) {
            // Plain number without suffix (should not normally occur in our format)
            if (primitive.isNumber()) {
                Number num = primitive.getAsNumber();
                if (num instanceof Double || num instanceof Float) {
                    return DoubleTag.valueOf(num.doubleValue());
                }
                return IntTag.valueOf(num.intValue());
            }
            if (primitive.isBoolean()) {
                return ByteTag.valueOf(primitive.getAsBoolean() ? (byte) 1 : (byte) 0);
            }
            return StringTag.valueOf(primitive.getAsString());
        }

        String s = primitive.getAsString();
        if (s.isEmpty()) {
            return StringTag.valueOf(s);
        }

        char lastChar = s.charAt(s.length() - 1);
        String numPart = s.substring(0, s.length() - 1);

        // Try to parse as typed numeric value
        try {
            return switch (lastChar) {
                case 'b' -> {
                    Byte.parseByte(numPart);
                    yield ByteTag.valueOf(Byte.parseByte(numPart));
                }
                case 's' -> {
                    Short.parseShort(numPart);
                    yield ShortTag.valueOf(Short.parseShort(numPart));
                }
                case 'i' -> {
                    Integer.parseInt(numPart);
                    yield IntTag.valueOf(Integer.parseInt(numPart));
                }
                case 'L' -> {
                    Long.parseLong(numPart);
                    yield LongTag.valueOf(Long.parseLong(numPart));
                }
                case 'f' -> {
                    Float.parseFloat(numPart);
                    yield FloatTag.valueOf(Float.parseFloat(numPart));
                }
                case 'd' -> {
                    Double.parseDouble(numPart);
                    yield DoubleTag.valueOf(Double.parseDouble(numPart));
                }
                default -> StringTag.valueOf(s);
            };
        } catch (NumberFormatException e) {
            // Not a valid number with that suffix, treat as plain string
            return StringTag.valueOf(s);
        }
    }

    private static String removeSuffix(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, s.length() - 1);
    }
}
