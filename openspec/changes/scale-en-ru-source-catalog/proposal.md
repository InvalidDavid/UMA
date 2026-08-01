## Why

The fork needs a safe foundation for adding English and Russian manga sources. The current catalog has no source-level contract tests, publishes a hard-coded `vn.jar`, and contains 43 Vietnamese adapters that are outside the new bundle scope.

## What Changes

- **BREAKING** Remove every source adapter explicitly declared with the `vi` locale; keep locale-neutral, English, and currently unrelated non-Vietnamese adapters until separately curated.
- Rename the installable plugin artifact from `vn.jar` to `en-ru.jar` through one validated plugin identity used by the build and release workflow.
- Add fixture-backed source contract verification for observable list, details, chapters, and pages behavior without live-site dependencies.
- Deepen reusable source-family modules behind smaller configuration seams as each behavior is covered by a contract test.
- Make generated source catalog output deterministic and reject duplicate or invalid source declarations with focused processor tests.
- Correct the release workflow so source, catalog, build, and metadata changes trigger publication of the same named artifact.
- Document the workflow for adding future English and Russian sources.
- Add the approved English inventory from InvalidDavid/UMA: 49 new adapters after excluding the existing `HENTAINEXUS` declaration and upstream-broken `HIPERDEX`.
- Add the approved Russian inventory: 27 adapters drawn from the maintained Kotatsu parser set plus `MANGABALL_RU` from UMA.
- Preserve the broken status of every unavailable Russian adapter and prohibit presenting it as healthy until its public reading contract is green.

## Capabilities

### New Capabilities

- `source-contract-verification`: Defines strict fixture-backed reading flows for representative adapters and catalog-construction coverage for every generated source.
- `plugin-catalog-identity`: Defines the `en-ru` bundle identity, deterministic generated catalog, locale curation, and release artifact contract.
- `source-family-adapters`: Defines how related sites reuse deep family modules while site-specific variation remains explicit and testable.

### Modified Capabilities

None. This repository had no existing OpenSpec capabilities.

## Impact

- Affects Gradle build configuration, KSP catalog generation, GitHub Actions publication, parser-family modules, source adapters, tests, and contributor documentation.
- Removes 43 Vietnamese `MangaSourceParser` declarations and their source-specific code.
- Changes the published artifact filename, which requires consumers to install `en-ru.jar` instead of `vn.jar`.
- Adds only the English and Russian identifiers recorded in `source-inventory.md`; additional locales and websites remain out of scope.
