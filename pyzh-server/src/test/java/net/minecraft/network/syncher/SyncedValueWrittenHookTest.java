package net.minecraft.network.syncher;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.support.environment.LeafTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@LeafTest
class SyncedValueWrittenHookTest {

    private static final class Holder implements SyncedDataHolder {
        static final EntityDataAccessor<Integer> COUNT = SynchedEntityData.defineId(Holder.class, EntityDataSerializers.INT);
        final List<Object[]> writes = new ArrayList<>();

        @Override
        public void onSyncedDataUpdated(final EntityDataAccessor<?> accessor) {
        }

        @Override
        public void onSyncedDataUpdated(final List<SynchedEntityData.DataValue<?>> updatedItems) {
        }

        @Override
        public void pyzh$onSyncedValueWritten(final EntityDataAccessor<?> accessor, final Object value) {
            this.writes.add(new Object[]{accessor, value});
        }
    }

    @Test
    void reportsEveryValueWriteWithTheDefiningAccessor() {
        final Holder holder = new Holder();
        final SynchedEntityData data = new SynchedEntityData.Builder(holder).define(Holder.COUNT, 0).build();
        final EntityDataAccessor<Integer> lookalike = new EntityDataAccessor<>(Holder.COUNT.id(), EntityDataSerializers.INT);

        data.set(Holder.COUNT, 0);
        assertEquals(0, holder.writes.size(), "unchanged value is not a write");

        data.set(lookalike, 5);
        data.set(Holder.COUNT, 5, true);
        data.assignValues(List.of(SynchedEntityData.DataValue.create(Holder.COUNT, 7)));

        assertEquals(3, holder.writes.size());
        for (final Object[] write : holder.writes) {
            assertSame(Holder.COUNT, write[0]);
        }
        assertEquals(List.of(5, 5, 7), holder.writes.stream().map(w -> w[1]).toList());
        assertEquals(7, data.get(Holder.COUNT));
    }
}
