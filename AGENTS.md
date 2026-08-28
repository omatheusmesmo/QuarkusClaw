# Qlawkus - Agent Instructions

## Dev Mode Start

```bash
mvn install -pl client -am -DskipTests   # must run first; client/ is a Quarkus extension
mvn -pl app -am quarkus:dev              # from the root: app/ is the only runnable module, and
                                         # capability resolution needs the reactor (see below)
```

Changing `client/` requires re-running `mvn install -pl client -am` then restarting dev mode. Hot reload does NOT apply to extension code.

## Containerized Run

`./run.sh <local|prod|native> [up|build|down|logs|restart|ps]` wraps Docker Compose (`./run.sh --help` for the full list). Unlike dev mode, this needs only Docker: the `app/src/main/docker/Dockerfile.{jvm,native}-build` images are **multi-stage** and compile the whole reactor in-container (build stage = the `ubi9/openjdk-25` builder, which ships Maven; runtime = `ubi9/openjdk-25-runtime`), so there is no host `mvn install` step and no stale-jar trap. Each build Dockerfile has its own `Dockerfile.*-build.dockerignore` so the standard Quarkus `.dockerignore` (for the COPY-target `Dockerfile.jvm`/`.native-micro`) stays intact. The build runs the **full** reactor (`mvn install`), not `-pl app -am`: a fresh local repo needs every `*-deployment` module built so the `extension-descriptor` check on each extension resolves. `local` -> `docker-compose.local.yml`; `prod`/`native` -> `docker-compose.yml` (native via its `native` profile).

## Architecture

Multi-module Maven monorepo with a Quarkus extension pattern (`client/deployment` + `client/runtime`):

- **`composition-model/`** - Thin, **Quarkus-free** module (plain records + Jackson YAML) holding the `agent.yml` composition manifest model: `CompositionManifest`/`BuildTime`/`Posture`, the single `CompositionManifestParser`, the policy+exceptions `isEnabled` logic, and the path conventions (`CompositionPaths`). Shared by the (future) pom generator that reads `build-time` before the build and the running app that reads `runtime` each boot, so the schema and policy never drift (same single-source-of-truth discipline as `KeystoreSecrets`). The `qlawkus.composition.*` location config (`CompositionConfig`, BUILD_TIME) lives in `client`. At runtime `client` also exposes an authenticated composition control plane (`rest.CompositionAdminResource` + `compose.CompositionAdminService`): `POST /api/admin/composition/manifest` validates a new `agent.yml` (via the shared `CompositionManifestParser`) and stages it under `qlawkus.agent.state.root`; `GET /api/admin/composition` returns the active (baked, read from the classpath `agent.yml`) + staged pair; `DELETE .../manifest` discards it. It only records intent (validate + write), never builds - the seam to an external builder is HTTP, not a shared volume, so even a leaked admin credential can at most stage a validated manifest. The same admin surface generalizes to arbitrary config values via `config.metadata.ConfigMetadataIndex` (reads the `quarkus-config-doc` metadata bundled in every extension jar - the same source that generates `config-reference.adoc` - so the property list is never hand-maintained): `RUN_TIME` properties go through `config.RuntimeToggleWriter` + `PUT`/`DELETE /api/admin/runtime-toggles` (writes straight into the `runtime:` override file, applies on restart); `BUILD_TIME`/`BUILD_AND_RUN_TIME_FIXED` properties go through `compose.ConfigOverridesAdminService` + `PUT`/`POST`/`DELETE /api/admin/config-overrides`, staging a `.properties` document that is fetched and promoted into `config-overrides.properties` alongside `agent.yml` by the same redeploy loop - parallel files, never merged, so the pom generator never has to reconcile `application.properties` lines. `secrets.SecretPropertyCatalog` excludes known-secret property names from the index entirely, so a secret is never rendered or writable through either endpoint.
- **`composition-maven-plugin/`** - Maven plugin (`qlawkus-maven-plugin`, goal prefix `qlawkus`) that turns the manifest into the pom **before** the build (`mvn qlawkus:generate`). Quarkus resolves dependencies before augmentation and Maven does no tree-shaking, so a manifest can only decide the dependencies from a generator that runs ahead of the build, not a `@BuildStep`. `GenerateMojo` reads `agent.yml` (via `composition-model`), resolves the selected capabilities against a `CapabilityCatalog`, and reconciles the pom's `<dependencies>` in place via the Maven Model API - adding selected capabilities, removing deselected ones, preserving the rest (the skeleton). Idempotent. Added deps carry no version (BOM-managed, like `quarkus extension add`). **Quarkus-free** at runtime; needs an ASM>=9.8 override for the plugin descriptor on Java 25. The catalog (#74) is decentralized: each extension module self-describes its capability name in its own `quarkus-extension.yaml` under `metadata.qlawkus.capability`, and the default `ReactorCatalog` reads that key from every reactor module (`${reactorProjects}`), reading the source descriptor template so it works before any module is built. `CapabilityCatalog` stays an interface so a published-artifact strategy (external consumer, outside the monorepo) can be added without touching the generator. Capability names ship namespaced where natural (`messaging.discord`, `cognition.pgvector`) and several modules may share one (`google-workspace` = the 6 google modules); `qlawkus-client` is the always-present skeleton and declares none. Each run logs the **resolved capability set** (selected/excluded) so the policy-and-exceptions shorthand never hides the full state, and an `except` name no module declares **fails the build** (opt-out `-Dqlawkus.composition.fail-on-unknown-capability=false`) - the anti-typo guard, since an unannounced name otherwise silently resolves to nothing. The goal's default phase is `generate-sources`, wired as an `<execution>` in the app pom, so every Maven build reconciles the pom from `agent.yml` before Quarkus resolves dependencies. **It does not re-run inside a live `quarkus:dev` session**: dev mode notices the changed `agent.yml` and logs "Files changed but restart not needed" without re-entering `generate-sources`, so a manifest edit applies on the *next* build - restart dev mode, or run `mvn qlawkus:generate`. Capability resolution reads `${reactorProjects}`, so the generator needs the reactor visible (`mvn -pl app -am quarkus:dev` from the root, not a lone `cd app && mvn quarkus:dev`). End-to-end proof is the `maven-invoker` IT under `src/it/` (a real two-capability reactor + agent app), gated by the `composition-invoker-it` profile (off under `-Pnative`, honors `skipITs`); its post-build hooks are **BeanShell** (`.bsh`), not Groovy, because Groovy's shaded ASM cannot read Java 25 class files.
- **`client/`** - Quarkus extension (core agent, cognition, REST, tools). Not a runnable app. **Database-free**: depends on no datasource/JDBC/Flyway/pgvector - the markdown stores are its `@DefaultBean` backend.
- **`cognition-pgvector/`** - Optional Quarkus extension (`deployment/` + `runtime/`) holding the entire Postgres/pgvector backend for the cognition stores (the `store.pg` package, the 7 Flyway migrations, and the datasource/pgvector config as `META-INF/microprofile-config.properties`). Present in `app/`; absent in a markdown-only distribution. `requires` the `dev.omatheusmesmo.qlawkus.agent` capability.
- **`console/`** - Optional Quarkus extension (`deployment/` + `runtime/`, capability `console`) serving a server-rendered admin UI at `/console` (Qute + HTMX, no Node, `@Authenticated` reusing the app's Basic+Argon2id mechanism). Read views inject the `client` SPIs directly; every mutating action is driven from the page by HTMX against the existing `/api/admin/*` endpoints, never re-implemented in a console resource. Pages: the onboarding wizard (first-run setup), management (`/console/memory`, `/console/skills`, `/console/cognition`), the full configuration editor (`/console/config`, see the `composition-model/` bullet above), and the schedule page (`/console/schedule`, listing the 5 background jobs - memory review/curation, episodic consolidation, skill curation/lifecycle - with cron/last-run/next-run read from the live `io.quarkus.scheduler.Scheduler`, so they always match what is actually registered; each job carries an explicit `@Scheduled(identity = ...)` for this lookup. The job registry itself - label, cron property, trigger endpoint - is static in `ScheduleConsoleResource` by necessity: no framework metadata links a job to the config property holding its cron, so it cannot be derived from `ConfigMetadataIndex`). Cron is `RUN_TIME` like every other scheduler property, so editing it reuses the same `/api/admin/runtime-toggles` endpoint the config editor uses (applies on next restart, not live - `@Scheduled` resolves its cron property expression once at boot); triggering a job now hits its existing `/api/admin/*` endpoint, including a new `POST /api/admin/memory/consolidate` added alongside `/review` and `/curate` so all 5 jobs are triggerable uniformly. `console/deployment`'s `ConsoleProcessor` registers the runtime's `@Path` resources via `AdditionalBeanBuildItem` since the jar is not an implicit CDI bean archive on its own.
- **`app/`** - Deployable Quarkus app (`packaging=quarkus`) and the **reference/example distribution**: one worked example of how to assemble Qlawkus, so it holds **only wiring and configuration** (pom + `application.properties` + `src/main/docker`) and **never implementation code** (no `src/main/java`). Anything an integrator would want to reuse belongs in an extension (`client` or a `*/` module), so other distributions can pick it up; `app/` just composes and configures it. Wires all extensions together; the only module you run.
- **`tools/`** - Optional tool extensions (Google Workspace 6 modules + Brag + Skill Hub). Each is a standalone Quarkus extension with `deployment/` + `runtime/`. `qlawkus-tools-skill-hub` is the remote skill-registry client (search/install from skills.sh); it `requires` the `dev.omatheusmesmo.qlawkus.agent` capability and is the outward-facing counterpart to the in-`client` local skill subsystem.
- **`messaging/`** - Messaging extension (core + Discord/Telegram/Slack/WhatsApp adapters). Same `deployment/` + `runtime/` pattern.
- **`integration-tests/`** - Per-feature integration test modules (smoke, cognition, markdown-only, google, terminal, brag, code-review, messaging). Each is a standalone Quarkus app (`packaging=quarkus`). `markdown-only` is the proof of the database-free distribution (boots with no datasource); `cognition` depends on `cognition-pgvector` to exercise the Postgres path.
- **`testing-internal/`** - Shared test utilities (depends on `qlawkus-client`).

`app/` depends on `client`, `cognition-pgvector`, all `tools/*`, and `messaging` (core + discord + telegram). Slack and WhatsApp adapters are built but not in `app/` pom.

## Cognition & Memory

The memory subsystem (`client/runtime/.../cognition` + `.../store`) is the core of the agent. Its guiding principle: **relevant memory is injected into the prompt automatically each turn, so recall never depends on the model remembering to search** - and the stores stay fully searchable on demand (RAG feeds the automatic injection; the `session_search` tool lets the model deliberately query transcripts). Automatic injection is the reliable default; tool-based search is available on top. Understanding it means reading several files together:

Three layers, all injected each turn via `SoulEngine` (the agent's `systemMessageProviderSupplier`) and the retrieval augmentor:

- **Persona** - `Soul` (a plain singleton domain object) rendered into the system message by `SoulEngine`. Persisted behind the `SoulStore` SPI, backend selected by the same `qlawkus.cognition.backend` switch: `store.markdown.MarkdownSoulStore` (`@DefaultBean`, a `soul.md` file seeded from `META-INF/qlawkus/default-soul.md`, no DB), `store.pg.PgSoulStore` (the `soul` table via `SoulEntity`), `store.pg.HybridSoulStore` (reuses pg).
- **Owner** - `UserProfile` (a plain singleton domain object; the single user this agent serves) injected every turn by `SoulEngine`. Maintained by `UpdateUserProfileTool`, refreshed by `MemoryCurationJob`. Persisted behind the `UserProfileStore` SPI: `MarkdownUserProfileStore` (`@DefaultBean`, an `owner.md` file), `PgUserProfileStore` (the `user_profile` table via `UserProfileEntity`), `HybridUserProfileStore`. Roots for the markdown soul/profile files: `qlawkus.agent.state.root` (default `~/.qlawkus/state`).
- **Facts** - `FactStore` SPI, backend selected at **build time** via `@IfBuildProperty` on `qlawkus.cognition.backend` (`markdown` | `pgvector` | `hybrid`): `store.markdown.MarkdownFactStore` (`@DefaultBean`; `.md` files + in-process `InMemoryEmbeddingStore` with a JSON embedding cache, no DB; shared file logic in `MarkdownFactFiles`), `store.pg.PgFactStore` (pgvector; `@IfBuildProperty pgvector enableIfMissing`, only present when the `cognition-pgvector` extension is on the classpath), `store.pg.HybridFactStore` (files source-of-truth + pg mirror). Root: `qlawkus.agent.facts.root` (default `~/.qlawkus/facts`). The markdown stores are `@DefaultBean` across the module split, so they win whenever the pgvector extension is absent (database-free) and the non-default pg/hybrid beans override them when it is present - regardless of the `backend` value. Every embedding is tagged with a `MemorySource` (`remember-tool`, `semantic-extractor`, `episodic-consolidator`, `transcript`) - the enum exists so producers and purges never drift. **Facts are chunked before embedding** (`store.FactChunker`, char-budget `qlawkus.agent.facts.chunk-*`, default ~1200 chars ≈ 400 tokens): a fact over the embedding model's token limit (512 for `nv-embedqa-e5-v5`) is split into overlapping segments, so one fact becomes N vector rows sharing a parent `factHash` (in metadata; segment 0 also carries the whole `factText` for pg→files reconstruction). Identity/dedup is the `factHash`, not the row text - the pg `embeddings.content_hash` is generated from `metadata->>'factHash'` (not unique, since N segments share it), so `existsByContentHash` dedups at fact granularity. Without this, an oversized fact fails to embed: in `hybrid` the `.md` is written before the embed, so it orphans file-only and `CognitionReconciler.reconcileFacts` aborts the whole 6-store union every boot (the reconcile now also try/catches per fact, so one bad fact never aborts the union).

Stores & retrieval:

- **Active Memory** - `ActiveMemoryAugmentor` is the AiService `retrievalAugmentor`; it runs an `EmbeddingStoreContentRetriever` before each reply and injects query-relevant facts. It filters OUT `source=transcript` so curated facts stay clean.
- **Working memory** - `WorkingMemoryStore` SPI (also a langchain4j `ChatMemoryStore`), windowed to 40 messages. `updateMessages` is append-only (preserves `createdAt`; never reset it) and fires `MessagesAppendedEvent` for every new message on any channel. Backends: `store.markdown.MarkdownWorkingMemoryStore` (`@DefaultBean`, one append-only `<memoryId>.jsonl` per conversation under `qlawkus.agent.working-memory.root`, default `~/.qlawkus/working-memory`), `store.pg.PgWorkingMemoryStore` (the `chat_message_entity` table), `store.pg.HybridWorkingMemoryStore` (reuses pg - the rolling chat log is transient, not file-versioned). The markdown store is the `@DefaultBean`; because upstream ships a `@DefaultBean InMemoryChatMemoryStore`, `ClientProcessor` vetoes it (`ExcludedTypeBuildItem`) so there is a single default and the non-default pg/hybrid beans still override when the pgvector extension is present.
- **Transcripts (session_search)** - `TranscriptArchiveObserver` embeds each message as `source=transcript`; `SearchTranscriptsTool` searches only those.
- **Episodic** - `EpisodicConsolidatorJob` (nightly) summarizes each day into journals (also embedded through the `FactStore` as `source=episodic-consolidator`). The journal record is an `EpisodicStore` SPI, backend selected at build time by the same `qlawkus.cognition.backend`: `store.markdown.MarkdownEpisodicStore` (`@DefaultBean`; dated `<date>.md` files, one per day, no DB; file logic in `MarkdownEpisodicFiles`), `store.pg.PgEpisodicStore` (the `Journal` table), `store.pg.HybridEpisodicStore` (files source-of-truth + pg mirror). Pg/hybrid share Panache access via `JournalRepository` and live in the `cognition-pgvector` extension. Root: `qlawkus.agent.episodic.root` (default `~/.qlawkus/journals`).

Scheduled background jobs, each with a manual `POST /api/admin/memory/*` trigger:

- `MemoryReviewJob` - semantic dedup of near-duplicate facts (`/review`).
- `MemoryCurationJob` - folds facts into the owner profile, reconciling contradictions; leaves facts untouched (`/curate`).
- `SemanticExtractorObserver` - extracts facts from a conversation on `ChatCompletedEvent`. The event is emitted channel-agnostically by `ChatCompletionEmitter`, which observes the store-level `MessagesAppendedEvent` and fires once per turn (when a final assistant message - one without tool-execution requests - is appended). **Passive fact extraction works on every channel** (REST, Discord, Telegram); gate it with `qlawkus.agent.semantic-extractor.enabled` (default `true`). The same event also drives the Brag `AchievementProcessor`, so both run on all channels.

Config knobs: `qlawkus.agent.memory.*`, `qlawkus.agent.active-memory.*`, `qlawkus.agent.transcript-*`, `qlawkus.agent.semantic-extractor.*`, `qlawkus.memory-review.*`, `qlawkus.memory-curation.*`. Admin REST: `GET/DELETE /api/admin/memory` (summary / purge), `POST /api/admin/memory/{review,curate}`. End-to-end check: `scripts/memory-benchmark.sh` (needs a running instance + real LLM).

Gotcha when testing recall: working memory and the system prompt are both sources, so to prove a fact came from the vector store, `TRUNCATE chat_message_entity` first. The embedding model (`nvidia/nv-embedqa-e5-v5`) is retrieval-tuned and scores relevant pairs high, so a cosine `min-score` of 0.9 is not as strict as it looks - `0.7` is the default margin.

## Skills (procedural memory)

Skills are the agent's **procedural** memory (how to do a recurring task), complementing the declarative memory above. Each skill is a `SKILL.md` file (YAML frontmatter `name` + `description` + Markdown body). Same discipline as memory: the index is **injected, not searched** - `SoulEngine` adds a `## Skills` block (name + description only, via `SkillIndexRenderer`, capped by `qlawkus.skills.max-injected`) every turn; the full body is loaded on demand with the `viewSkill` tool (progressive disclosure).

- **SPI**: `skill.SkillStore` (interface) with `Skill`/`SkillSummary` records. SKILL.md **parsing is delegated to the upstream `dev.langchain4j.skills` loaders** (`FileSystemSkillLoader`, agentskills.io spec, BOM-managed `dev.langchain4j:langchain4j-skills`); `SkillFrontmatter` owns only the **write** side (rendering a `Skill` back to frontmatter + body), which upstream does not provide. The backend is selected at **build time** via `@IfBuildProperty` on `qlawkus.cognition.backend` (`markdown` | `pgvector` | `hybrid`): `MarkdownSkillStore` (`@DefaultBean`, no DB, shared file logic in `MarkdownSkillFiles`), `store.pg.PgSkillStore` (the `skill` table, `V6` migration), `store.pg.HybridSkillStore` (files source-of-truth + pg mirror). Reads from all `qlawkus.skills.roots` (first wins); writes only to the first, owned root (default `~/.qlawkus/skills`).
- **Tools** (in `AgentService`): `ViewSkillTool` (load body), `ManageSkillTool` (`createOrUpdateSkill` / `deleteSkill`).
- **Auto-distill**: `SkillExtractorObserver` mines a reusable skill from each `ChatCompletedEvent` (gate `qlawkus.skills.extractor.enabled`), mirroring `SemanticExtractorObserver`.
- **Curation**: `SkillCurationJob` removes redundant skills (scheduled + `POST /api/admin/skills/curate`).
- **Lifecycle**: `viewSkill` calls `recordUse`; `SkillLifecycleJob` ages unused skills `ACTIVE -> STALE -> ARCHIVED` (config `qlawkus.skills.lifecycle.*`, scheduled + `POST /api/admin/skills/lifecycle`). Archived skills leave the injected index but stay loadable; pinned skills (`POST /api/admin/skills/{name}/pin`) never transition. Telemetry: pg columns (migration `V7`) for pgvector/hybrid, a `.qlawkus-usage.json` sidecar for markdown.
- **Admin REST**: `GET/DELETE /api/admin/skills`, `GET /api/admin/skills/{name}`, `POST /api/admin/skills/{curate,lifecycle}`, `POST /api/admin/skills/{name}/pin`.
- **Bundled skills (build-time)**: extensions/apps ship read-only skills as classpath resources under `META-INF/qlawkus-skills/<name>/SKILL.md`. `ClientProcessor.bundledSkills` (a `@BuildStep` + `SkillsRecorder`) scans and parses them (via the same `FileSystemSkillLoader`) at **augmentation** and bakes them into the synthetic `BundledSkills` bean - no runtime classpath scanning. Stores merge bundled (read-only) with owned skills; owned wins on a name clash. The bundling **machinery** lives in `client`; curated default skill **content** is a distribution concern and should ship from `app`, not `client`.
- **Remote registry (Skill Hub)**: everything above is **local** (read/write SKILL.md files, offline). The **outward-facing** registry client lives in the optional `qlawkus-tools-skill-hub` extension (package `tools.skillhub`), kept out of `client` so a locked-down distribution can omit the network capability at the module level (not just a config flag). SPI `SkillHub { search(query, limit); install(source); publish(skill) }`, default impl `HttpSkillHub` (`@DefaultBean`; plain `java.net.http` + Jackson, **no external CLI** - jskills/skills.sh both need extra tooling installed, so the behavior is replicated natively instead). Tools `searchSkillHub`/`installSkill`/`publishSkill` are `@QlawTool` (discovered cross-module by `ClientProcessor`'s `@QlawTool` scan). Config root `qlawkus.skill-hub.*`.
  - **Search**: hits `<base-url>/api/search` plus, for each `well-known-hosts` entry, the `/.well-known/agent-skills/index.json` index (agentskills.io standard, fallback to legacy `/.well-known/skills/`). Best-effort across sources, deduped by source, capped by `max-results`.
  - **Install**: resolves an `owner/repo` slug to the raw GitHub SKILL.md (tries `main`/`master`) or takes a direct URL (well-known hits are direct URLs), validates the name against path traversal, saves via `SkillStore` into the owned root - so it enters the injected index next turn. Only the SKILL.md text is fetched; executable assets are never pulled or run (inert by omission).
  - **Publish**: `publishSkill` renders an owned skill to `<publish.dir>/<name>/SKILL.md` (reusing `SkillFrontmatter.render`, now `public`). It **never runs git** - in a container, mount `publish.dir` to a host git working copy and the owner pushes from the host (keeps credentials out of the container and honors the manual-push rule). Opt-in via `publish.enabled` (default false).
  - **Approval**: install and publish honor `qlawkus.skill-hub.approval-mode` (`yolo` acts immediately; `hitl` previews and requires a confirmed re-call).

Config knobs: `qlawkus.cognition.backend`, `qlawkus.skills.*`, `qlawkus.skill-hub.*`. Facts, episodic journals, skills, working memory, the persona (`Soul`) and the owner profile (`UserProfile`) are all pluggable on this same switch, and the entire Postgres backend now lives in the optional `cognition-pgvector` extension - so a build without it is database-free (validated by `integration-tests/markdown-only`). The remote-registry side of skills is the optional `qlawkus-tools-skill-hub` extension (above).

Backend migration (in `cognition-pgvector`, so only in pgvector/hybrid builds): `store.pg.reconcile.CognitionReconciler` does a bidirectional, idempotent **union** of files <-> pgvector across all six stores (collections keyed by content hash/date/name/conversation id; singletons filled only when one side is empty, never clobbered). It runs once at hybrid boot via `HybridReconcileStartup` (gate `qlawkus.cognition.reconcile-at-start`, default `true`) and on `POST /api/admin/cognition/reconcile`. `CognitionMigrator` (`POST /api/admin/cognition/migrate?direction=files-to-pg|pg-to-files`) is the explicit one-directional copy that **does** overwrite the destination singletons. This is a Qlawkus capability, not an upstream langchain4j one - it's only possible because every store keeps a parallel markdown representation as the bridge. Covered by `CognitionReconcileTest`.

## Testing

```bash
# Single test class (-am builds upstream modules; the failIfNoSpecifiedTests flag
# stops sibling modules in the reactor from erroring when the class isn't theirs)
mvn test -pl client/deployment -am -Dtest=UserProfileTest -Dsurefire.failIfNoSpecifiedTests=false

# Unit tests for a single module (uses WireMock, no LLM needed)
mvn test -pl client/deployment

# Integration tests (LLM-dependent tests auto-skip if no API key)
mvn test -pl integration-tests/smoke

# Integration tests with real LLM (requires NVIDIA_AI_API_KEY env var)
NVIDIA_AI_API_KEY=your-key mvn test -pl integration-tests/smoke

# Full build (skip ITs by default - skipITs=true in parent pom)
mvn verify

# Native ITs (enable with -Pnative)
mvn verify -Pnative

# Composition maven-invoker IT (real reactor + qlawkus:generate). Needs qlawkus-composition-model
# in the local repo, so install first; off by default (skipITs) and under -Pnative.
mvn -pl composition-model install -DskipTests
mvn -pl composition-maven-plugin install -DskipTests -DskipITs=false
```

- `client/deployment/src/test/` - Extension build-time tests with WireMock-mocked LLM
- `integration-tests/*/src/test/` - QuarkusTest integration tests; some call real LLMs, some use `@InjectMock`
- LLM-dependent tests use `@EnabledIf("dev.omatheusmesmo.qlawkus.testing.QlawkusTestUtils#usesLLM")` - auto-skip when `NVIDIA_AI_API_KEY` is absent or "dummy"
- Smoke tests (`integration-tests/smoke/`) hit real LLM: can take 5+ minutes per test

## Adding Tools

Create a CDI bean annotated with `@QlawTool` + `@ApplicationScoped`. Methods annotated with `@Tool` are auto-discovered at build time by `ClientProcessor` (which also registers DTO records in `dev.omatheusmesmo.qlawkus.dto` for reflection). No manual registration needed.

New tool modules must also be added as dependencies in `app/pom.xml`.

A tool that makes outbound HTTP calls follows the retry contract in **Resilience & Health** below: throw `TransientHttpException` for transient failures, and annotate the single HTTP call rather than the tool method.

## Extension Development

Every module here (`client`, `tools/*`, `messaging/*`) is a full Quarkus extension: a `runtime/` + `deployment/` pair. Rules that keep that pattern correct:

- **Dependency direction is one-way.** `deployment/` depends on `runtime/` (plus other extensions' `*-deployment` artifacts); `runtime/` must NEVER depend on any `deployment` artifact. The Maven plugin validates this and fails the build if a required deployment dependency is missing.
- **Three bootstrap phases.** Build-time *augmentation* runs `@BuildStep` processors (e.g. `ClientProcessor`) - it reads the Jandex index, never loads app classes, and records bytecode. `@Record(STATIC_INIT)` bytecode runs in a static initializer (framework boot, bean wiring; do NOT open ports, start threads, or read runtime config here). `@Record(RUNTIME_INIT)` bytecode runs in `main` (ports, runtime config, service start). Push work to STATIC_INIT when possible.
- **Build steps talk via build items, not fields.** A build-step class is instantiated per invocation and discarded; share state only through immutable `*BuildItem`s. Always produce a `FeatureBuildItem`. A step runs only if something consumes what it produces.
- **Recorders bridge build to runtime.** `@Recorder` methods in `runtime/` are *recorded* (not executed) at build time; their calls replay at runtime. Parameters must be bytecode-serializable (primitives, strings, config objects, `RuntimeValue<T>`). Wrap complex objects in `RuntimeValue<T>`.
- **Config is `@ConfigMapping` interfaces.** `@ConfigRoot(phase = RUN_TIME)` + `@ConfigMapping(prefix = "qlawkus...")`, JavaDoc on each accessor (it becomes the generated config-reference description), `@WithDefault` for defaults, `Optional<T>` for optional, `Map<String, X>` for keyed/dynamic config (see `MessagingConfig`). Choose phase by need: `BUILD_TIME` (build only), `BUILD_AND_RUN_TIME_FIXED` (read at build, immutable at runtime), `RUN_TIME` (re-read each run, overridable).
- **CDI registration.** Register extension beans with `AdditionalBeanBuildItem`; use `@DefaultBean` for anything app code should be able to override; `SyntheticBeanBuildItem` for beans built through a recorder.
- **Native image.** Reflection needs registration - `ClientProcessor` already registers `dev.omatheusmesmo.qlawkus.dto` records via `ReflectiveClassBuildItem`; new reflective types (DTOs, models) must be added the same way (or `@RegisterForReflection`). Use `NativeImageResourceBuildItem` to bundle resources. Verify with `mvn verify -Pnative`.
- **A new optional extension must declare its composition capability.** Add `metadata.qlawkus.capability` to the module's `src/main/resources/META-INF/quarkus-extension.yaml` (create the source template if the module only relied on the generated one). That single key is what the `qlawkus-maven-plugin` `ReactorCatalog` maps to the module's coordinates, so the `agent.yml` manifest can compose the extension in or out. A module with no key is treated as always-present skeleton and is never toggled - so forgetting it means the capability silently cannot be selected. Name capabilities namespaced where natural (`messaging.discord`, `cognition.pgvector`); several modules may share one name to move as a unit (`google-workspace`). Keeping it in `quarkus-extension.yaml` (not the pom) is deliberate: that metadata rides into a published Quarkus platform catalog, so the same key serves both the in-repo `ReactorCatalog` and a future published-artifact catalog for consumers building from `agent.yml` without cloning.
- **Before inventing a build item, check the catalog**: [all build items](https://quarkus.io/guides/all-builditems).

### Reference links

Official Quarkus documentation:

- [Writing Your Own Extension](https://quarkus.io/guides/writing-extensions) - canonical guide
- [All build items reference](https://quarkus.io/guides/all-builditems) - the extension SPI
- [Adding extensions to the Quarkus ecosystem / Quarkiverse](https://github.com/quarkusio/quarkus/wiki/Adding-extensions-to-the-Quarkus-ecosystem)

Community tutorials and walkthroughs:

- [Quarkus: Greener, Better, Faster, Stronger](https://jtama.github.io/posts/quarkus-greener-better-faster-stronger/) (Jérôme Tama) - Dev Services, annotation transform, recorders ([source](https://github.com/jtama/quarkus-extension-demo))
- [How NOT to Create a Quarkus Extension](https://www.loicmathieu.fr/wordpress/informatique/quarkus-tip-comment-ne-pas-creer-une-extension-quarkus/) (Loïc Mathieu) - when a simple JAR + Jandex is enough instead
- [Developing a Quarkus Extension](https://matheuscruz.dev/2024/01/12/developing-a-quarkus-extension/) (Matheus Cruz) - recorders, Gizmo, Jandex scanning ([source](https://github.com/mcruzdev/quarkus-useful))
- [Creating a Quarkus Extension](https://blog.sebastian-daschner.com/entries/creating-a-quarkus-extension) (Sebastian Daschner) - video walkthrough ([source](https://github.com/sdaschner/blink-extension))
- [How to Implement a Quarkus Extension](https://www.baeldung.com/quarkus-extension-java) (Baeldung) - Liquibase extension tutorial

Resource collections:

- [Quarkus Extensions Resources](https://hollycummins.com/quarkus-extensions-resources/) (Holly Cummins) - curated guides, talks, and posts

## Secrets

Database-free secret store, owned by `client` (no separate module, no custom crypto). Secrets live in a PKCS12 keystore named by the convention `qlawkus.secrets.keystore-path` (default `~/.qlawkus/secrets.p12`), unlocked by `qlawkus.secrets.keystore-password`. The built-in SmallRye keystore source is fixed at ordinal 100 (below env) with no knob to raise it, so `client` ships its own `secrets.KeystoreSecretConfigSourceFactory` (a `ConfigSourceFactory` registered via `META-INF/services`) that reads the keystore with the plain JDK `KeyStore` API (`getKey().getEncoded()` as UTF-8 - exactly how `keytool -importpass` stores it) and republishes every entry at `qlawkus.secrets.keystore-ordinal` (default 350). Knobs are documented by `SecretsConfig` (`@ConfigMapping(prefix="qlawkus.secrets")`), rendered into the config reference under "Secrets".

- **Alias = property name.** Each keystore alias is the config property it supplies; consumers read a plain `@ConfigProperty` (no code change). Onboard with `keytool -importpass -alias '<config.property.name>' -keystore <path> -storetype PKCS12`.
- **Write side (no keytool).** `secrets.KeystoreSecretWriter` (`@ApplicationScoped`) onboards a secret programmatically via the JDK `KeyStore` API, creating the file on first write and storing entries identically to `keytool -importpass` (PBE `SecretKeyEntry`). The read/write PKCS12 convention is centralized in `secrets.KeystoreSecrets` (the `ConfigSourceFactory` now delegates its read to it, so reader and writer can't drift). REST surface `rest.SecretsAdminResource` (`@Authenticated`, `/api/admin/secrets`): `PUT` (body `{alias,value}`, never logs the value), `GET` (alias names only), `DELETE ?alias=`. A missing `keystore-password` returns `409` (won't create an unprotected store); a written secret takes effect on next boot. Covered by `markdown-only` `SecretWriteEndpointTest` (the read-back asserts keytool-compatibility) + `SecretWriteNoPasswordTest` (the 409 guard).
- **Auth is fail-closed and pluggable.** `app/` drops the old `${API_USER_PASSWORD:qlawkus}` fallback - the bundled distro refuses to boot without a real credential (env, or the matching keystore alias); `%dev` keeps a known password for local dev only. Auth mechanism is the integrator's choice via standard Quarkus security: endpoints use `@Authenticated`, so adding `quarkus-oidc` / `quarkus-smallrye-jwt` to the consuming app swaps the mechanism with zero Qlawkus code (no custom auth extension - that would reinvent `quarkus-oidc`).
- **Authoritative precedence.** Published at ordinal 350: `sysprops (400) > keystore (350) > env (300) > .env (295) > application.properties (250)`. A stored secret wins over the matching env var, so the rule is "drop it in the keystore and it takes effect" with no stray env default able to shadow it; only `-D` system properties still override. Lower `qlawkus.secrets.keystore-ordinal` below 300 to let env override instead. Validated by `integration-tests/markdown-only` `KeystoreSecretTest` (`keystoreSecretOutranksApplicationProperties` + the absent-keystore leniency contract), which boots with no datasource.
- **Lenient.** Resolves the keystore on the filesystem then the classpath; absent file contributes nothing, so a fresh install boots on `.env` and the feature stays inert until a keystore exists.
- **Migrating consumers.** No consumer change is needed: the alias is the property the consumer already reads, and the keystore (350) outranks the `application.properties` `=${ENV:}` line (250). The catalog of secret properties lives in `site/content/secrets.adoc` ("What to store"). The one non-obvious case is the LLM/embedding key: `model.LlmKindConfigSourceFactory` runs at ordinal 90 and reads `quarkus.langchain4j.openai."primary".api-key` / `LLM_API_KEY` back from the context (it only honours sources out-ranking 90), so a keystore-stored key flows through it unchanged. `KeystoreSecretTest.llmApiKeyResolvesFromKeystoreThroughKindFactory` pins this end to end (the value reaches the real OpenAI client's Authorization header).
- **Container.** Mount the keystore path on a persistent volume; pass `QLAWKUS_SECRETS_KEYSTORE_PASSWORD` via env. File and password stay out of the image.
- **Managed alternative.** `quarkus-vault` on the classpath contributes a `CredentialsProvider` and resolves secrets the same way (ordinary config); use it when secrets are managed centrally.
- **Gotcha.** The `.p12` test fixture is a real PKCS12 keystore. The global `pre-commit` hook strips trailing whitespace on non-blacklisted files and will corrupt it (DER `EOFException` at boot); commit keystore binaries with `git commit --no-verify`.

User-facing guide: `site/content/secrets.adoc`.

## Databases

Up to 3 PostgreSQL databases, all managed by Flyway:
- **`qlawkus`** (default) - Main: persona, owner profile, chat history, episodic journal, pgvector embeddings. **Only present when the `cognition-pgvector` extension is on the classpath** (it owns the default datasource, the 7 `V1..V7` migrations, and the pgvector config, contributed via its `META-INF/microprofile-config.properties`). A markdown-only build has no default datasource and never connects to Postgres.
- **`qlawkus_google_auth`** - OAuth credentials + state (Google tools extension)
- **`qlawkus_brag`** - Career brag documents (Brag tool extension; its own named `brag` datasource, independent of cognition)

In Docker, `docker/postgres-init/01-create-databases.sql` creates the extra DBs. In dev mode, Flyway `clean-at-start` resets schema on every restart.

## Documentation

One source, one generated mirror. **Edit only `site/content/`** - everything else is built from it.

- **`site/content/*.adoc`** - hand-written pages (the Roq site): `index`, `architecture`, `config-reference`, `quickstart`, `messaging`, `voice`, `google-workspace`. This is the source of truth.
- **`site/content/includes/*.adoc`** - **generated** config reference. `docs/pom.xml` runs `quarkus-config-doc-maven-plugin` (build-time augmentation, no datasource, depends on every `*-deployment` artifact) which writes one file per config root from the `@ConfigMapping` JavaDoc into `site/content/includes/`. Never edit these by hand - they are overwritten every build.
- **`docs/modules/ROOT/pages/*`** - the Antora copy. The `sync-site-to-antora` execution in `docs/pom.xml` (`maven-resources-plugin`, `verify` phase) copies all of `site/content/` here. It is a generated mirror; **never edit `docs/` directly** (the next build overwrites it). If you must commit the mirror without a full build, copy the changed file from `site/content/` to the matching `docs/modules/ROOT/pages/` path.

Regenerate after a config change:

```bash
mvn install -DskipTests                  # build extensions so config metadata is available
mvn -f docs/pom.xml verify -DskipTests   # generate includes/ into site/, then mirror site/ -> docs/
```

**`config-reference.adoc` is hand-written**, not generated. It surfaces each generated table with an `include::` directive. A `@ConfigMapping(prefix = "qlawkus.foo")` interface produces `qlawkus-client_qlawkus.foo.adoc`; the aggregate `qlawkus-client.adoc` holds **only** the `quarkus.*`-rooted properties. So **adding a new `qlawkus.*` config root requires adding a matching `include::includes/qlawkus-client_qlawkus.foo.adoc[]` line** under "Core agent" in `config-reference.adoc`, in **both** trees, or the new properties never render. (This is exactly how the entire `qlawkus.*` surface was silently dropped after the `@ConfigMapping` migration.)

## LLM Configuration

Two named model configs via `@ModelName`, both provider-agnostic:
- **`primary`** - Primary model (any OpenAI-compatible provider; default endpoint: NVIDIA NIM). Pointed via the neutral `LLM_*` env vars (`LLM_BASE_URL`, `LLM_API_KEY`, `LLM_CHAT_MODEL`, `LLM_EMBEDDING_MODEL`, `LLM_TIMEOUT`).
- **`fallback`** - Fallback (Ollama). Reached through `PrimaryChatGuard`/`EmbeddingGuard` once the primary has exhausted its retries and the circuit breaker opens; see **Resilience & Health**.

Dev mode: Dev Services auto-provisions Ollama (no `.env` needed).
Prod: Requires `LLM_API_KEY` in `.env`.

Embedding dimension is hardcoded to 1024 via `EMBEDDING_DIMENSION` - must match the model output.

Cost estimation is opt-in via `qlawkus.cost.enabled`. `model/TokenPriceCostEstimator` prices the token counts quarkus-langchain4j already reports, which is what turns on its `gen_ai.client.estimated_cost` metric. Off by default on purpose: an estimate is indistinguishable from a measurement once it reaches a dashboard, so a default price would be a confident wrong number. A distribution with tiered or committed pricing supplies its own `CostEstimator` at the default priority and is consulted first.

### Dependency versions (do NOT conflate the two langchain4j namespaces)

| What | Version | Where set |
|------|---------|-----------|
| Quarkus platform | `3.38.1` | `quarkus.platform.version` in root `pom.xml` |
| quarkus-langchain4j (Quarkiverse extension) | `1.12.2` | `io.quarkus.platform:quarkus-langchain4j-bom` - do not set |
| Upstream `dev.langchain4j` core (transitive) | `1.17.2` | same BOM - do not set |
| Upstream beta modules (`langchain4j-skills`, `langchain4j-pgvector`, `langchain4j-agentic`) | `1.17.2-beta27` | same BOM - do not set |

The Quarkiverse **extension** version (`1.12.2`) and the upstream **library** version (`1.17.2`) are different namespaces: `1.12.2` is NOT a langchain4j-core version. The `quarkus-bom` manages neither, so the root pom imports `quarkus-langchain4j-bom` at the platform version; it pins the Quarkiverse artifacts and transitively imports the `langchain4j-bom`, which pins every `dev.langchain4j:*` artifact. So when adding an upstream module (e.g. `langchain4j-skills`, or `dev.langchain4j:langchain4j` for `InMemoryEmbeddingStore`), add it with **no `<version>`** - the BOM resolves it.

To move ahead of the platform's cadence, import `io.quarkiverse.langchain4j:quarkus-langchain4j-bom` at the wanted version *before* the platform one; declaration order decides which wins. Re-verify any time with:

```bash
mvn dependency:tree -pl client/runtime -Dincludes='dev.langchain4j'
```

## Resilience & Health

Fault tolerance is `smallrye-fault-tolerance` throughout; no hand-written retry or breaker remains in the reactor. Where a guard is applied matters far more than which annotation it uses, and there is one hard rule.

**Never put `@Retry`, `@CircuitBreaker` or `@Fallback` on `AgentService`.** The upstream quarkus-langchain4j fault-tolerance guide shows exactly that pattern, and it is wrong for a method that declares `tools`: the annotation wraps the whole ReAct loop, so a transient failure on a later turn replays tool calls that already succeeded on an earlier one, silently duplicating their side effects (quarkiverse/quarkus-langchain4j#2744). Guards belong on the underlying `ChatModel`/`StreamingChatModel`, where a retry replays only the one failed model call. The rule is repeated in the `AgentService` javadoc, but that copy only reaches someone already editing the file the annotation must stay out of.

**Model resilience is programmatic, not annotated.** `model/PrimaryChatGuard` and `model/EmbeddingGuard` build a `TypedGuard` instead of using `@CircuitBreaker`, because a CDI interceptor cannot serve the streaming path: it is callback-based, so the failure never crosses the interceptor boundary. Both are `@Startup`, so the breaker is registered before first use, and both name their breaker through `ModelFallbackConfig.CIRCUIT_BREAKER_CHAT` / `_EMBEDDING`. That name is the lookup key `ModelReadinessCheck` reads.

The programmatic API is also the only caller of `SpiAccess.get()`, and that has a native-image consequence worth knowing before touching either guard. `SpiAccess$Holder` resolves the fault-tolerance SPI through `ServiceLoader` and keeps only an implementation whose `applies()` returns true, which the CDI one answers by calling `CDI.current()`. Quarkus runs `STATIC_INIT` (Arc startup included) at image build time in native, so the holder usually initializes there and the instance is baked in - but whether the analysis reaches it before Arc is up varies between builds, which showed up as an intermittently red native build. `ClientProcessor` therefore pins the holder to runtime initialization **and** registers the `Spi` service provider; both are required, since deferring it without the registration moves the lookup into an image that resolves services only from explicit registrations, and the app then fails at boot. The annotation-driven path never hits any of this: it compiles to generated interceptors that do not call `SpiAccess`.

**HTTP clients retry on one exception type.** Every outbound client (the 6 Google modules, Skill Hub, the messaging clients and media downloader, code review) throws `http.TransientHttpException` for a 429, a 5xx, or a network-level failure, and retries only that:

```java
@Retry(maxRetries = 3, delay = 500, delayUnit = ChronoUnit.MILLIS,
       jitter = 200, jitterDelayUnit = ChronoUnit.MILLIS,
       retryOn = TransientHttpException.class)
@ExponentialBackoff(maxDelay = 8000, maxDelayUnit = ChronoUnit.MILLIS)
```

Two rules keep that correct rather than decorative. It goes on **one HTTP call, not one logical operation**, so a multi-request `install` cannot re-run its first request because the third failed. And a permanent status (401, 403, 404) propagates immediately, since retrying something that will never resolve only delays surfacing it.

**Health checks go through the store SPIs, not through storage.** `health/CognitionReadinessCheck` calls `SoulStore.load()` and `UserProfileStore.load()`, so one check is correct on `markdown`, `pgvector` and `hybrid` with no conditional: each backend resolves its own store. `health/ModelReadinessCheck` reads both breaker states through `CircuitBreakerMaintenance.currentState`, a read-only lookup, so polling readiness can never promote a breaker state as a side effect; it reports DOWN only when a breaker is OPEN **and** no fallback is configured. A check that is meaningful on only one backend belongs in that backend's own module, never as an `if` inside a shared check.

**Readiness and liveness answer different questions.** Liveness deliberately does not consult the model: killing the pod because the provider is down would turn their incident into an outage here, and the restarted pod would meet the same provider. `examples/redeploy/k8s/deployment.yaml` carries the probe trio (the startup probe absorbs the order-of-magnitude gap between JVM and native boot), and the Compose stacks poll the same `/q/health/ready`, so both deploy paths agree on what "serving" means.

## Model workloads

The agent talks to one provider for two different reasons: answering the owner, and doing background work about the owner. Sharing fault-tolerance state between them is the defect - a nightly job that trips the provider's rate limit opens the breaker the owner's next message goes through, so the agent answers from the fallback because it was busy talking to itself.

Workloads are named in configuration (`qlawkus.model.workload.<name>.*`, `RUN_TIME`, so reachable from `/api/admin/runtime-toggles`), keyed like `MessagingConfig`. `interactive` and `batch` always exist; more can be declared without code. `PrimaryChatGuard` and `EmbeddingGuard` build **one `TypedGuard` per workload** at startup, and that is what makes the isolation structural rather than conventional: SmallRye gives each guard object its own instance of every strategy, so two guards cannot share breaker state.

**A `TypedGuard`, not `@ApplyGuard`.** The reusable-guard mechanism is the more elegant fit for named policies, but `Guard` has no `withFallback()` - only `TypedGuard` does - and the fallback to the secondary provider is why these guards exist.

**A thread-scoped marker, not a CDI qualifier.** `WorkloadContext.runAs("batch", ...)` selects the workload. A qualifier member has to be known when beans are wired, which would fix the set of workloads at build time and defeat declaring them in config. The marker is safe for the same reason the per-call fallback bridge is: SmallRye runs a synchronous action and its fallback handler on the calling thread. Work that hops threads must set the marker on the thread that actually calls the model.

**A synchronous bulkhead rejects; it does not queue.** SmallRye queues excess callers only for asynchronous actions, and a chat completion is synchronous here, so exceeding `max-concurrent` raises `BulkheadException` immediately. That is why `fallback-on-reject` exists and is `false` for batch: without it, every throttled job would quietly re-target the fallback provider and do the work anyway, which is the opposite of throttling. Abandoning the run is safe because the jobs are re-runnable, audited when shutdown began abandoning them. `@Scheduled(concurrentExecution = SKIP)` covers the other half - a job overlapping *itself* - which the bulkhead does not address.

**Readiness stays tied to `interactive`.** Other workloads' breakers are reported as data but never flip the status. Letting a background breaker take the instance out of rotation would rebuild, in the health layer, exactly the coupling this removes.

Two findings behind this are recorded as executable tests in `BulkheadSemanticsProbeTest`, because the documentation does not state them: a bulkhead does not offload the fallback off the calling thread, and **duplicate circuit breaker names are accepted silently** on smallrye-fault-tolerance 6.11.2. The second corrects an earlier assumption in this project that the library rejects them; uniqueness is our invariant, which is why `WorkloadGuards.breakerName` derives it instead of accepting one.

## Shutdown

A SIGTERM has to drain what the process is holding, and the drain has to be *waited on*. Both messaging adapters already had `@Observes ShutdownEvent` before this was addressed, and neither waited: Discord called `logout().subscribe(...)` and returned, Telegram interrupted its poll thread without joining it. Code that looks like a drain but returns immediately is worse than none, because it reads as solved.

Drains are registered as `ShutdownListenerBuildItem` from each module's `*-deployment`, never as CDI observers. The platform then supplies what would otherwise be hand-written per subsystem: two ordered phases, an acknowledgement callback, and a single budget (`quarkus.shutdown.timeout`) covering all of them. A `@Observes ShutdownEvent` would need its own timeout in every bean, and they would drift.

The phases carry meaning. `preShutdown` refuses new work (Telegram stops polling); `shutdown` releases resources (PTY sessions closed, Discord logout awaited, poll thread joined). Two rules keep this correct: **`done()` belongs in a `finally`** - a listener that throws before acknowledging costs the full timeout on every rollout, silently - and each listener swallows its own exception, so one broken drain cannot stop the others.

The listener instance is built during augmentation, before any container exists, so it holds no bean reference and resolves what it needs from `Arc.container()` at shutdown, guarded by `isRunning()`.

**Running jobs are abandoned, not awaited.** A nightly consolidation makes model calls and cannot fit any sane grace period, so the scheduler simply stops. That is only safe because the jobs are re-runnable, which was audited rather than assumed: purge, curation, skill lifecycle and cleanup all converge on a second run. `EpisodicConsolidatorJob` was the exception and was fixed here - it wrote the journal *before* embedding it while `existsForDate` guarded on the journal alone, so anything landing between the two steps (a SIGTERM, or an embed failure that only warned) left an entry unreachable by retrieval and skipped that day for good. The guard now re-asserts the embedding, which the fact store dedups by content hash.

The budget is chosen end to end, not per layer: `quarkus.shutdown.delay=3s` plus `quarkus.shutdown.timeout=20s` in `app/`, against `terminationGracePeriodSeconds: 30` in `examples/redeploy/k8s/deployment.yaml`. Raising one without the other converts a clean drain into a kill. The timeout also caps in-flight HTTP, and `/api/chat` is an SSE ReAct turn that can run for minutes, so it deliberately cuts a long turn rather than holding the rollout; working memory is append-only, so a cut turn loses a reply, not state.

## Telemetry

Model and AI-service meters come from upstream and need no code: they appear whenever a registry is present (see the `observability` capability). What `client` instruments itself is the cognition subsystem, which nothing upstream can see.

`metrics/AgentMeters` is the only class that touches a `MeterRegistry` and the one place every metric name is written down. Names are constants there because a metric name is a public contract - it becomes a time series, then a panel, then an alert - so a literal spread across call sites would drift. `client` depends on `io.micrometer:micrometer-core`, which is API plus in-memory accumulators: it opens no socket, so it is not egress and does not belong behind the capability. The registry and exporter that do leave the machine stay in `qlawkus-observability`. With that extension absent the `Instance<MeterRegistry>` is unsatisfied and every meter call is a no-op.

Where each measurement attaches, and why, is the part worth knowing before adding more:

- **Retrieval** - `MeteredContentRetriever` wraps the retriever built in `ActiveMemoryAugmentor.get()`. Scores come from `ContentMetadata.SCORE`. This is the meter that answers "did memory work this turn?", which otherwise requires reading a log.
- **Tools** - decorated in `QlawToolProvider`, **not** intercepted. That provider calls `ClientProxy.unwrap` before building each `DefaultToolExecutor`, so tool calls never cross a CDI proxy and no interceptor, including the project's own `@Logged`, can observe them. `MeteredToolExecutor` overrides *both* `execute` and `executeWithContext`, since `DefaultToolExecutor` implements each separately. Only `@QlawTool` extension tools flow through here; the tools declared on `@RegisterAiService(tools = {...})` are not counted.
- **Stores** - the opposite case: stores are injected as their SPI and reached through a proxy, so interception works. `ClientProcessor.meterCognitionStores` adds `@Metered` at build time to every implementor of the six store SPIs, which covers the `cognition-pgvector` backends without that extension knowing telemetry exists. Annotating by hand would mean a new backend silently reporting nothing. `StoreMeterInterceptor` derives the `backend` tag from the implementation's name prefix, reporting `Pg*` as `pgvector` to match `qlawkus.cognition.backend`.
- **Jobs** - `AgentMeters.timeJob` wraps the body of each `@Scheduled` method. The `job` tag reuses the existing `@Scheduled(identity = ...)`, so metric and console schedule page name the same thing.
- **Fallback** - counted in the guards' fallback handler, which is the exact moment the primary is abandoned, so a switch is recorded even when the breaker never opens. Breaker state is a gauge bound to the `@ApplicationScoped` guard, because Micrometer holds only a weak reference to the gauged object.
- **Embedding** - `AgentMeters.embedFact` wraps the embed loop in each of the three `FactStore` implementations, so the outcome recorded is the embed's own. `FactChunker` stays a pure function: chunking runs before anything reaches the model and has no outcome to report, so metering there could only ever record a success. The convenience store constructors used by tests pass `AgentMeters.disabled()`.

User-facing catalog: `site/content/operations.adoc` under "What the agent measures about itself".

## Key Entry Points

- `client/runtime/.../agent/AgentService.java` - `@RegisterAiService` interface (ReAct agent, `maxSequentialToolInvocations=100`); wires `systemMessageProviderSupplier`, `retrievalAugmentor` (Active Memory), tools, and `toolProviderSupplier`
- `client/runtime/.../rest/ApiResource.java` - `POST /api/chat` (SSE) and `POST /api/chat/sync`
- `client/runtime/.../cognition/SoulEngine.java` - Builds the dynamic system prompt: `Soul` persona + `UserProfile` owner block + execution-bias section, injected every turn
- `client/runtime/.../deployment/ClientProcessor.java` - Build step: discovers `@QlawTool` beans + DTOs for reflection
- `client/runtime/.../model/PrimaryChatGuard.java` - `TypedGuard` around the primary chat model: retry, circuit breaker, and the switch to the fallback provider
- `client/runtime/.../health/` - `ModelReadinessCheck` and `CognitionReadinessCheck`, the two checks behind `/q/health/ready`

## CI / Release

6 GitHub Actions workflows:

- **`build-pull-request.yml`** - On PR: quick build → JVM test matrix (per module) → native IT matrix (per IT module) → build report. No API key passed → LLM tests auto-skip
- **`build-push.yml`** - On push to main: full `mvn clean install` with `NVIDIA_AI_API_KEY` → all tests run
- **`build-nightly.yml`** - Cron seg-sex 02:00 UTC + dispatch: JVM + native build with `NVIDIA_AI_API_KEY` → all tests run
- **`pre-release.yml`** - On PR that changes `.github/project.yml`: validates version is not SNAPSHOT, blocks forks
- **`release-prepare.yml`** - On PR merge that changes `.github/project.yml`: sets POM version, tags (no `v` prefix), pushes tag, bumps to next SNAPSHOT
- **`release-perform.yml`** - On tag push: builds artifacts, creates draft GitHub Release with PR-based changelog

Release flow: edit `current-version` in `.github/project.yml` → open PR (triggers `pre-release` validation) → merge triggers `release-prepare` → tag push triggers `release-perform` → edit/publish draft release.

Tags have no `v` prefix (e.g. `0.7.2`, not `v0.7.2`). Dependabot enabled for GitHub Actions + Maven dependencies (daily, ignores `io.quarkus:*`).

## Gotchas

- `client/` Dev Services for LLM are **disabled** in `client/runtime/application.properties` (`quarkus.langchain4j.devservices.enabled=false`) - they're enabled only in `app/` via profile overrides
- `app/` dev profile sets `flyway.clean-at-start=true` on all datasources: all data is wiped on every `quarkus:dev` restart
- Prod Docker exposes port **8742** (not 8080) for Google OAuth redirect URI compatibility
- `@Logged` on `AgentService` is an interceptor that logs agent interactions
- `SoulEngine` default timezone is `America/Sao_Paulo`
- `pty4j` + JNA are direct dependencies in `client/runtime` for interactive shell tool support
