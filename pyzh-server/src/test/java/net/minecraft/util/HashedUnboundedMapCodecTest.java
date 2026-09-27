package net.minecraft.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.codecs.BaseMapCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;
import ru.turbovadim.pyzh.codec.HashedUnboundedMapCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;

@LeafTest
class HashedUnboundedMapCodecTest {

    private static final Codec<Map<String, Integer>> VANILLA = Codec.unboundedMap(Codec.STRING, Codec.INT);
    private static final Codec<Map<String, Integer>> HASHED = HashedUnboundedMapCodec.of(Codec.STRING, Codec.INT);

    @Test
    void decodesLikeUnboundedMap() {
        final JsonObject ordered = new JsonObject();
        for (int i = 999; i >= 0; i--) {
            ordered.addProperty("key" + i, i);
        }
        assertSameDecode(ordered);
        assertEquals(List.copyOf(VANILLA.parse(JsonOps.INSTANCE, ordered).getOrThrow().keySet()), List.copyOf(HASHED.parse(JsonOps.INSTANCE, ordered).getOrThrow().keySet()));

        final JsonObject withErrors = new JsonObject();
        withErrors.addProperty("a", 1);
        withErrors.addProperty("b", "not a number");
        withErrors.addProperty("c", 3);
        assertSameDecode(withErrors);
        assertSameDecode(new JsonObject());
        assertSameDecode(new JsonPrimitive(5));

        final JsonObject roundTrip = new JsonObject();
        roundTrip.addProperty("x", 7);
        assertEquals(VANILLA.encodeStart(JsonOps.INSTANCE, Map.of("x", 7)).getOrThrow(), HASHED.encodeStart(JsonOps.INSTANCE, Map.of("x", 7)).getOrThrow());
    }

    @Test
    void duplicateKeysKeepFirstValueAndReportTheSameError() {
        final MapLike<JsonElement> duplicates = mapLike(List.of(
            Pair.of(new JsonPrimitive("a"), new JsonPrimitive(1)),
            Pair.of(new JsonPrimitive("b"), new JsonPrimitive(2)),
            Pair.of(new JsonPrimitive("a"), new JsonPrimitive(3))
        ));
        final DataResult<Map<String, Integer>> vanilla = ((BaseMapCodec<String, Integer>) VANILLA).decode(JsonOps.INSTANCE, duplicates);
        final DataResult<Map<String, Integer>> hashed = ((BaseMapCodec<String, Integer>) HASHED).decode(JsonOps.INSTANCE, duplicates);
        assertEquals(describe(vanilla), describe(hashed));
        assertEquals(Map.of("a", 1, "b", 2), hashed.resultOrPartial().orElseThrow());
    }

    private static void assertSameDecode(final JsonElement input) {
        assertEquals(describe(VANILLA.parse(JsonOps.INSTANCE, input)), describe(HASHED.parse(JsonOps.INSTANCE, input)), input.toString());
    }

    private static String describe(final DataResult<Map<String, Integer>> result) {
        final List<String> parts = new ArrayList<>();
        parts.add(String.valueOf(result.isSuccess()));
        parts.add(String.valueOf(result.resultOrPartial().map(map -> new ArrayList<>(map.entrySet())).orElse(null)));
        parts.add(result.error().map(DataResult.Error::message).orElse(""));
        parts.add(String.valueOf(result.lifecycle()));
        return String.join(" | ", parts);
    }

    private static MapLike<JsonElement> mapLike(final List<Pair<JsonElement, JsonElement>> entries) {
        return new MapLike<>() {
            @Override
            public JsonElement get(final JsonElement key) {
                return entries.stream().filter(e -> e.getFirst().equals(key)).map(Pair::getSecond).findFirst().orElse(null);
            }

            @Override
            public JsonElement get(final String key) {
                return this.get(new JsonPrimitive(key));
            }

            @Override
            public Stream<Pair<JsonElement, JsonElement>> entries() {
                return entries.stream();
            }
        };
    }
}
