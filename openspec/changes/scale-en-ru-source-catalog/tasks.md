## 1. Validation Baseline

- [x] 1.1 Prepare a temporary Java 17 runtime matching CI without changing the system JDK.
- [x] 1.2 Run the pre-change Gradle test baseline and record whether failures come from the repository or environment.

## 2. Source Contract Verification

- [x] 2.1 Add one failing end-to-end `MangaParser` contract test for a retained source using recorded list, details, chapters, and pages fixtures.
- [x] 2.2 Implement the strict fixture HTTP adapter and test `MangaLoaderContext` required to make the first contract green.
- [x] 2.3 Add a second retained source contract and extract only the shared fixture and invariant logic proven by both scenarios.

## 3. Deterministic Catalog

- [x] 3.1 Add failing processor tests for deterministic ordering, duplicate identifiers, invalid identifiers, invalid locales, and invalid constructor declarations.
- [x] 3.2 Extract immutable source descriptors and implement validated deterministic catalog ordering until the processor tests pass.
- [x] 3.3 Wire KSP emission and summary metadata to the validated catalog and verify generated enum and factory output.

## 4. Locale Curation

- [x] 4.1 Add a failing catalog assertion proving that no declared source uses locale `vi`.
- [x] 4.2 Remove the 43 Vietnamese source adapters and delete family modules left with no source adapters.
- [x] 4.3 Regenerate catalog metadata and verify the retained source count and locale distribution.

## 5. Plugin Identity And Release

- [x] 5.1 Define and validate `plugin.id=en-ru` as the single build identity and derive the installable JAR filename from it.
- [x] 5.2 Update KSP summary metadata, README instructions, and GitHub release paths to use `en-ru`.
- [x] 5.3 Correct workflow path filters so source, processor, build, and workflow changes trigger publication.

## 6. Source Family Depth

- [x] 6.1 Apply the deletion test to retained source-family modules after locale curation.
- [x] 6.2 Replace active selector overrides with cohesive immutable site configuration where contract coverage proves the variation.
- [x] 6.3 Re-run all source contracts after the family refactor and keep explicit parse failures without fallback models.

## 7. Final Verification

- [x] 7.1 Run all Gradle tests with Java 17 and fix repository failures.
- [x] 7.2 Run `buildJar` with Java 17 and verify `build/libs/en-ru.jar` and generated catalog metadata.
- [x] 7.3 Validate the OpenSpec change, review the final diff, and document remaining manual consumer installation checks.

## 8. Approved English Inventory

- [x] 8.1 Add a failing catalog expectation for the first multilingual English/Russian family.
- [x] 8.2 Port approved multilingual adapters while excluding unrelated locale declarations.
- [x] 8.3 Port the remaining approved English adapters by shared parser family, returning the catalog and family contracts to green after each slice.
- [x] 8.4 Verify that all 49 new English identifiers in `source-inventory.md` are generated exactly once.

## 9. Approved Russian Inventory

- [x] 9.1 Port the active LibSocial, Grouple, Chan, Madara, and independent Russian adapters through family-level RED-GREEN slices.
- [x] 9.2 Add `MANGABALL_RU` without importing unrelated Manga Ball locale declarations.
- [x] 9.3 Preserve `ACOMICS`, `BEST_MANGA`, `HENTAILIB`, and `ZENMANGA` as broken after read-only endpoint checks.
- [x] 9.4 Verify that all 27 Russian identifiers in `source-inventory.md` are generated exactly once.

## 10. Expanded Catalog Verification

- [x] 10.1 Verify the expanded catalog still contains no Vietnamese declaration and no duplicate identifier.
- [x] 10.2 Run all Gradle tests and `buildJar` with the explicit Java 11/17 toolchains.
- [x] 10.3 Validate OpenSpec, inspect generated summary metadata, and record any live-site checks that remain manual.
- [x] 10.4 Add factory-construction coverage for all generated entries and representative English and Russian reading contracts.

## 11. Live Source Diagnostics

- [x] 11.1 Add an opt-in JVM live smoke context that preserves default source configuration and cookies.
- [x] 11.2 Add configurable list, details, chapters, and pages diagnostics with operation-specific failures.
- [x] 11.3 Reproduce and repair the reported MangaLib and ReadManga failures.
- [x] 11.4 Audit all 51 English and 27 Russian entries through list, details, chapters, and pages with bounded concurrency and a machine-readable report.
- [x] 11.5 Repair viable current contracts, mark only confirmed retired sources broken, and record Android-only verification targets for protected sources.
