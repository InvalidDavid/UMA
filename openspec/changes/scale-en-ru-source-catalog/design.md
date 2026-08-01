## Context

The repository currently compiles 57 annotated parser implementations into one generated `MangaParserSource` enum, one parser factory, and a hard-coded `vn.jar`. Forty-three declarations are explicitly Vietnamese. The root project declares JUnit dependencies but has no source tests. KSP writes declarations in resolver order, and the release workflow watches a source path that no longer exists.

Tsuki 1.0.1 already provides the required production seam: parsers are consumed through `MangaParser`, while `MangaLoaderContext` supplies an OkHttp client. Tests can therefore replace external HTTP at the context seam without changing the production interface or mocking parser internals.

## Goals / Non-Goals

**Goals:**

- Establish `en-ru` as the single bundle identifier and installable artifact name.
- Remove all 43 explicitly Vietnamese adapters and any family implementation left with no adapters.
- Verify source behavior through deterministic fixtures and the public `MangaParser` interface.
- Make KSP catalog validation deterministic and directly testable.
- Reduce site-specific inheritance overrides where a retained family has real variation.
- Align release triggers, generated metadata, and contributor documentation.
- Expand the catalog with the approved 49 English and 27 Russian adapters while preserving one parser declaration per identifier.
- Port shared engines as deep family modules, prove catalog construction for every adapter, and cover representative English and Russian reading flows through the public `MangaParser` interface.

**Non-Goals:**

- Add websites outside the approved inventory in `source-inventory.md`.
- Contact live websites from automated tests.
- Split the bundle into multiple artifacts before size and load-time measurements justify it.
- Remove locale-neutral or Chinese sources without an explicit curation decision.
- Add fallback parsing or placeholder source data.

## Decisions

### Keep the production parser interface unchanged

Contract tests will instantiate real parsers with a test `MangaLoaderContext`. An OkHttp interceptor will serve registered fixture responses and reject unknown requests. This tests observable behavior through `MangaParser` and uses the existing external HTTP seam.

Alternative considered: expose or inject Tsuki's protected `WebClient`. Rejected because it would widen the production interface solely for tests and couple tests to parser implementation details.

### Introduce contract coverage vertically

The first RED-GREEN slice will verify one retained source from list through pages. A second retained source will then prove that shared fixture infrastructure has leverage before it is extracted into a reusable contract module. Each family refactor follows a passing contract and returns to green before the next behavior.

Alternative considered: create a generic contract framework before any concrete source passes. Rejected because it would encode imagined behavior and violate the test-first vertical-slice workflow.

### Use a validated Gradle property for plugin identity

`plugin.id=en-ru` will be the source of truth. Gradle will validate the identifier and derive the dex JAR path from it; the release workflow will upload the same path. Generated summary metadata will include the bundle identifier and catalog count.

Alternative considered: rename only `vn.jar`. Rejected because the old name would remain duplicated in workflow and documentation.

### Separate catalog validation from KSP I/O

KSP declarations will be converted into immutable source descriptors. A pure catalog function will validate identifiers, locales, constructors, and duplicates, then sort by identifier. KSP file emission will consume only the validated list. Processor tests will exercise the pure function before processor wiring changes.

Alternative considered: use Kotlin compile-testing and KSP integration for every validation. Rejected for the first slice because it adds a large test dependency and makes diagnostics slower; one generated-source smoke test can be added later if the pure seam proves insufficient.

### Curate by declared locale, then apply the deletion test

All declarations explicitly using `vi` will be removed. After references are recalculated, family modules with zero adapters will be deleted. Retained family modules will only be deepened where active adapters and fixtures demonstrate real variation.

Alternative considered: delete every file under a `vi` directory. Rejected because declared annotation locale is the catalog contract and catches Vietnamese adapters outside that directory.

### Deepen only fixture-backed family variation

After Vietnamese curation, the `MadaraParser`, `GalleryParser`, and `GalleryAdultsParser` families still have active adapters and pass the deletion test. `MadaraParser` and `GalleryParser` do not expose selector variation that justifies another configuration type. `GalleryAdultsParser` does, so HentaiEnvy receives a vertical fixture contract before selectors, locale capabilities, sort capabilities, domain, and page size move into `GalleryAdultsSiteConfig` and `GalleryAdultsSelectors`.

Site-specific list request overrides remain explicit where URL semantics differ materially rather than being represented as a large conditional strategy enum. This keeps shared parsing in the family while avoiding a configuration field that merely selects one copied algorithm.

Alternative considered: migrate GalleryAdults selectors without a concrete source contract. Rejected because compilation alone would not prove list, details, chapters, pages, and page-image resolution behavior.

### Import approved sources by parser family

The source inventory is an acceptance contract, not a request to copy an upstream tree wholesale. Shared multilingual files retain only the approved English and Russian declarations. Existing identifiers are never duplicated, and imported code is translated to the local `tsuki` API and package layout.

English adapters are imported from the local UMA checkout at commit `1b0db5ae2de9480b4fcb2b8d0b2ee0537870d7e1`. Russian adapters use Kotatsu parser commit `4d1e521aef7e4d9f41c65adda5e274509e93807c`, with `MANGABALL_RU` sourced from UMA. Source-family slices are ordered by shared leverage: multilingual adapters, Madara-compatible adapters, MangaBox-style adapters, LibSocial, Grouple, Chan, then independent parsers.

Alternative considered: include every locale declared in a shared upstream file. Rejected because it would silently broaden the `en-ru` artifact and reintroduce unrelated locales.

### Preserve explicit broken-source metadata

`ACOMICS`, `BEST_MANGA`, `HENTAILIB`, and `ZENMANGA` were marked broken in the selected Russian upstream snapshot. Read-only probes confirmed that their required catalog or API paths are currently blocked, unavailable, or no longer resolve. They remain discoverable with `isBroken=true`, and removing `@Broken` is prohibited until fixture-backed list, details, chapters, and pages behavior is green against a viable endpoint.

Alternative considered: omit these identifiers entirely. Rejected because the catalog model already has explicit broken-source metadata, and preserving the approved inventory with an honest unavailable state is more informative than silently dropping it.

## Risks / Trade-offs

- [The bundle remains temporarily mixed because one `zh` and locale-neutral sources remain] → Keep their declarations unchanged and perform further curation only with explicit approval.
- [Fixture HTML can drift away from live sites] → Treat fixtures as regression evidence for supported parser behavior; live smoke checks remain a separate manual concern.
- [Removing 43 identifiers can break stored consumer references] → Mark the migration breaking and document that old Vietnamese entries no longer resolve in `en-ru.jar`.
- [Local Java 25 cannot run the current Gradle Kotlin runtime] → Validate with a temporary Java 17 runtime matching CI without changing the user's system Java installation.
- [A family refactor may expose hidden behavior] → Refactor only after its concrete contract is green and keep each slice independently verifiable.

## Migration Plan

1. Establish a Java 17 validation runtime and record the pre-change build result.
2. Add one failing source contract scenario and implement only the fixture infrastructure required to make it pass.
3. Add catalog validation tests, then make generation deterministic.
4. Remove `vi` declarations and unreferenced family implementations; regenerate the catalog and assert no `vi` entries remain.
5. Set `plugin.id=en-ru`, derive the artifact name, and update release paths and documentation.
6. Deepen retained family modules one vertical contract slice at a time.
7. Run all tests and `buildJar` with Java 17; verify `build/libs/en-ru.jar` exists.
8. Add catalog expectations for one approved family, port that family, and return to green before starting the next family.
9. Preserve upstream-broken Russian adapters as broken and repair them only through independent contract slices.
10. Verify the final catalog contains the 49 approved new English and 27 approved Russian identifiers, constructs every generated parser, and completes representative English and Russian reading contracts.

Rollback is a normal Git revert before publication. The old `vn.jar` remains recoverable from prior GitHub Releases.

## Open Questions

- Whether the remaining `zh` adapter belongs in the long-term `en-ru` catalog is intentionally deferred.
- Authentication and anti-bot behavior for active sources remains subject to manual live-site verification outside fixture tests.
