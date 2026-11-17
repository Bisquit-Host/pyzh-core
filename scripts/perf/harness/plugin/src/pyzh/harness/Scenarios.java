package pyzh.harness;

import java.util.List;

final class Scenarios {
    private Scenarios() {
    }

    static List<Scenario> all() {
        return List.of(new IdleSurvival(), new MobFarm(), new Villagers(), new ItemsHoppers(), new Redstone(), new ChunkGen(), new PlayersMoving());
    }

    static Scenario byName(String name) {
        return all().stream().filter(s -> s.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown scenario " + name));
    }
}
