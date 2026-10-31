# Pyzh

Pyzh is a Minecraft 26.2 server fork built on [Leaf](https://github.com/Winds-Studio/Leaf), which in turn builds on Paper and includes performance, gameplay, and compatibility patches from projects such as Purpur, Gale, and Pufferfish.

Leaf is consumed as a **paperweight upstream**, pinned by `leafCommit` in `gradle.properties`. Leaf's patches are not vendored here — paperweight clones Leaf at that commit, runs Leaf's own Paper application, and applies Pyzh's patches on top. Updating Leaf is a one-line property bump.

## Requirements

- Git
- JDK 25

The Gradle wrapper is included; a separate Gradle installation is not required. It pins Gradle 9.4.1 to match Leaf — see [CONTRIBUTING.md](CONTRIBUTING.md#gradle-version) for why this matters.

## Build

```bash
./gradlew applyAllPatches
./gradlew :pyzh-server:applyAllServerPatches
./gradlew build createPaperclipJar
```

Two apply invocations are required: the first materialises the generated project build files, which must exist before `:pyzh-server` can configure.

The distributable server JAR is written to:

```text
pyzh-server/build/libs/pyzh-paperclip-26.2.local-SNAPSHOT.jar
```

`Bukkit.getBukkitVersion()` reports `26.2-R0.1-SNAPSHOT` for plugin compatibility.
Artifact versions stay separate: local builds use `26.2.local-SNAPSHOT`; CI builds use
`26.2.build.<number>-alpha`, with `BUILD_NUMBER` set to the GitHub Actions run number.
The server manifest records the build number and Git commit.

New `server.properties` files default `bug-report-link` to
`https://github.com/Bisquit-Host/pyzh-core/issues`. Existing files keep their configured value.

## Repository layout

Only Pyzh's own patches are tracked. Everything else is generated and gitignored.

```text
pyzh-server/minecraft-patches/features   patches to Minecraft sources
pyzh-server/paper-patches/features       patches to CraftBukkit/Paper server sources
pyzh-server/leaf-patches/features        patches to Leaf's own source (org.dreeam.leaf.*)
pyzh-server/src/test                     Pyzh tests (Leaf has none)
pyzh-api/paper-patches/features          patches to the Paper API
pyzh-api/leaf-patches/features           patches to Leaf's API source
```

## Development workflow

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full patch workflow. In short: apply patches, edit the generated source, commit inside the generated Git repository, then rebuild that patch set.

## Updating Leaf

Set `leafCommit` in `gradle.properties` to the new Leaf commit, reapply, and verify:

```bash
./gradlew applyAllPatches
./gradlew :pyzh-server:applyAllServerPatches
./gradlew build createPaperclipJar
./scripts/upstreamCommit.sh
```

If a Pyzh patch no longer applies, resolve it in the generated source repository and rebuild that patch set.

## API coordinates

Local builds use:

```kotlin
compileOnly("ru.turbovadim.pyzh:pyzh-api:26.2.local-SNAPSHOT")
```

No public Maven repository is configured yet. Publish locally for development with `./gradlew publishToMavenLocal`.

## License and upstream credits

Pyzh is a derivative of Leaf and Paper and inherits their licensing. Patch files retain their original `From:` authorship headers. See [LICENSE.md](LICENSE.md) and [licenses/](licenses) for the licenses applying to Paper, Leaf, and their incorporated upstream work.
