package net.minecraft.world.level.storage;

import com.mojang.serialization.Codec;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.ProblemReporter;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@LeafTest
class KeyFilteredValueOutputTest {

    private static final Consumer<ValueOutput> WRITES = out -> {
        out.putInt("A", 1);
        out.putString("B", "x");
        out.store("C", Codec.INT, 5);
        final ValueOutput.TypedOutputList<String> list = out.list("D", Codec.STRING);
        list.add("a");
        out.child("E").putInt("x", 1);
        out.childrenList("F").addChild().putInt("y", 2);
        out.store(Codec.INT.fieldOf("G"), 7);
        final ValueOutput.TypedOutputList<String> empty = out.list("H", Codec.STRING);
        if (empty.isEmpty()) {
            out.discard("H");
        }
        out.discard("B");
    };

    @Test
    void comparisonsMatchFullOutputForEveryCheckedKeySet() throws Exception {
        final TagValueOutput full = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        WRITES.accept(full);
        for (final String expected : List.of(
            "{}", "{A:1}", "{A:2}", "{B:\"x\"}", "{C:5}", "{D:[\"a\"]}", "{D:[\"b\"]}", "{E:{x:1}}", "{F:[{y:2}]}",
            "{G:7}", "{H:[]}", "{A:1,C:5,G:7}", "{A:1,Z:1}"
        )) {
            final CompoundTag tag = TagParser.parseCompoundFully(expected);
            final TagValueOutput filtered = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
            WRITES.accept(filtered.pyzh$onlyKeys(tag.keySet()));
            assertEquals(NbtUtils.compareNbt(tag, full.buildResult(), true), NbtUtils.compareNbt(tag, filtered.buildResult(), true), expected);
        }
    }

    @Test
    void emptinessAccountsForSkippedKeys() {
        final ValueOutput view = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING).pyzh$onlyKeys(Set.of("A"));
        assertEquals(true, view.isEmpty());
        view.putInt("B", 1);
        assertEquals(false, view.isEmpty());
        view.discard("B");
        assertEquals(true, view.isEmpty());
    }
}
