Contributing to Pyzh
====================

Pyzh is a fork of [Leaf](https://github.com/Winds-Studio/Leaf), which forks Paper, which forks
Minecraft. Almost nothing in this repository is source code — it is a set of patches that
paperweight applies on top of Leaf.

## Requirements

- `git`
- JDK 25
- `curl` and `jq` (only for `scripts/upstreamCommit.sh`)

The Gradle wrapper is included.

## How this repository is put together

`gradle.properties` pins `leafCommit`. paperweight clones Leaf at that commit into
`.gradle/caches/paperweight/upstreams/leaf`, runs **Leaf's own** Paper application as a nested
build, and then applies Pyzh's patches on top. Nothing of Leaf's is vendored here.

The chain is `Minecraft -> Paper -> Leaf -> Pyzh`.

Tracked patch sets:

| Path | Patches what |
| --- | --- |
| `pyzh-server/minecraft-patches/features` | decompiled Minecraft sources |
| `pyzh-server/paper-patches/features` | CraftBukkit/Paper server sources |
| `pyzh-server/leaf-patches/features` | Leaf's own source (`org.dreeam.leaf.*`) |
| `pyzh-api/paper-patches/features` | the Paper API |
| `pyzh-api/leaf-patches/features` | Leaf's API source (`org.leavesmc.leaves.*`) |

`pyzh-server/src/test` is ordinary tracked source — Leaf has no `src/test`, so nothing needs
patching there.

Everything else (`paper-api`, `paper-server`, `pyzh-*/src/main`, `pyzh-server/src/minecraft`,
`pyzh-*/build.gradle.kts`) is generated and gitignored.

## Applying patches

```bash
./gradlew applyAllPatches
./gradlew :pyzh-server:applyAllServerPatches
```

Two invocations are required and the order matters. The first generates
`pyzh-server/build.gradle.kts` and `pyzh-api/build.gradle.kts`; those files must exist on disk
before Gradle can configure the `:pyzh-server` project, so the server patches cannot run in the
same invocation.

## Making a change

Each generated directory is its own Git repository. Edit the generated source, commit **inside
that repository**, then rebuild the corresponding patch set.

Minecraft sources:

```bash
git -C pyzh-server/src/minecraft/java add <files>
git -C pyzh-server/src/minecraft/java commit -m "Describe the change"
./gradlew :pyzh-server:rebuildMinecraftFeaturePatches
```

Paper/CraftBukkit server sources:

```bash
git -C paper-server add <files>
git -C paper-server commit -m "Describe the change"
./gradlew :pyzh-server:rebuildPaperServerFeaturePatches
```

Paper API:

```bash
git -C paper-api add <files>
git -C paper-api commit -m "Describe the change"
./gradlew rebuildLeafApiPatches
```

Leaf's own source:

```bash
git -C pyzh-server/src/main add <files>
git -C pyzh-server/src/main commit -m "Describe the change"
./gradlew rebuildLeafServerSourcePatches
```

One commit in the generated repository becomes one patch file. Feature patches are generated
**from commits** — uncommitted working-tree changes are silently ignored by the rebuild.

Run the API and server rebuilds in separate Gradle invocations; combining them can trip an
undeclared task-dependency validation error in the current paperweight version.

## Patch comment style

Mark every changed hunk so it survives rebases and is greppable:

```java
// Pyzh start - short description
...
// Pyzh end - short description
```

or, for a single line, a trailing `// Pyzh - short description`. Explain *why* in the patch
message or in the comment; a diff alone rarely survives an upstream refactor.

## Updating Leaf

```bash
# edit leafCommit in gradle.properties
./gradlew applyAllPatches
./gradlew :pyzh-server:applyAllServerPatches
./gradlew build createPaperclipJar
./scripts/upstreamCommit.sh
```

If a patch fails to apply, resolve it in the generated repository and rebuild that patch set.
Rejected hunks land in the patch set's `rejected/` directory.

## Things that will bite you

### Gradle version

The wrapper pins **9.8.0** and paperweight-patcher **2.0.0-beta.24**, matching Leaf. Bump them
together with Leaf, never separately.

paperweight-patcher runs nested upstreams by reflectively calling the Gradle internal
`NestedRootBuildRunner.runNestedRootBuild`. Its signature changes between Gradle releases
(9.6.1 added a `ClassPath` parameter, which broke beta.21), so a Gradle version the patcher
was not built for makes every nested upstream fail with `NoSuchMethodException`.

### patchRepo vs patchDir

`paper-api` is wired with `patchRepo`, not `patchDir`. Leaf's generated `paper-api` is already a
Git repository, and `FilterRepo` copies its input wholesale — including `.git` — then copies
`gitDir` onto `.git` and fails with `FileAlreadyExistsException`. `excludes` cannot help because
they are applied after the copy. Use `patchRepo` for any upstream path that is already a repo.

### The fork chain

`pyzh-server/build.gradle.kts.patch` registers two forks, `leaf` then `pyzh`, with
`activeFork = pyzh`. The `leaf` fork's `rootDirectory`/`serverDirectory` point into the upstream
checkout so Leaf's own `paper-server` and Minecraft patches still apply before ours. If you change
this, keep `leaf.patchedRepo("paperServer")` as the `pyzh` fork's `upstreamRepo` — that is what
chains the two.

### You cannot remove an upstream patch

Downstream cannot un-apply one of Leaf's patches. If you need to change Leaf's behaviour, write a
Pyzh patch against Leaf's *post-patch* state (see
`pyzh-server/minecraft-patches/features/0007-Cache-global-fluid-threshold-in-NoiseBasedChunkGener.patch`),
or upstream the fix to Leaf.

## Upstreaming

If a change is a general fix rather than Pyzh policy, send it to Leaf. Anything Leaf accepts is a
patch Pyzh no longer has to carry across every rebase.

## License

Pyzh is a derivative of Leaf and Paper and inherits their licensing. Patch files retain their
original `From:` authorship headers — do not strip them. See [LICENSE.md](LICENSE.md) and
[licenses/](licenses).
