## ADDED Requirements

### Requirement: Bundle identity has one source of truth
The build SHALL define the plugin identifier as `en-ru` and SHALL use that identifier for the installable JAR filename and release artifact path.

#### Scenario: Build creates the installable plugin
- **WHEN** the dex plugin task completes
- **THEN** the installable artifact is written as `build/libs/en-ru.jar` and no build or workflow path expects `vn.jar`

### Requirement: Source catalog generation is deterministic
The KSP catalog module SHALL validate source declarations and order generated enum entries and factory mappings by source identifier.

#### Scenario: Resolver returns declarations in a different order
- **WHEN** the same valid declarations are supplied in any iteration order
- **THEN** catalog validation returns the same source order and generated catalog content

### Requirement: Invalid source declarations fail generation
The catalog module MUST reject duplicate identifiers, invalid identifier formats, invalid locale tags, and parser constructors that do not accept exactly one required `MangaLoaderContext` parameter.

#### Scenario: Two sources use one identifier
- **WHEN** two declarations use the same source identifier
- **THEN** catalog generation fails with a diagnostic that names the duplicate identifier and both declarations

### Requirement: Vietnamese adapters are excluded
The `en-ru` catalog SHALL contain no source adapter declared with locale `vi`.

#### Scenario: Catalog is generated after migration
- **WHEN** KSP discovers all source declarations in the repository
- **THEN** none of the generated source entries has locale `vi`

### Requirement: Release workflow follows catalog changes
The release workflow SHALL run for changes to source adapters, source-family modules, KSP catalog generation, build identity, and release configuration.

#### Scenario: A source adapter changes
- **WHEN** a commit to the release branch changes a file under `src/main/kotlin/tsuki/site/`
- **THEN** the build-and-publish workflow is eligible to create `en-ru.jar`

### Requirement: Approved English and Russian inventory is exact
The generated catalog SHALL contain every identifier recorded in `source-inventory.md` exactly once and SHALL NOT import additional locale declarations from multilingual upstream files.

#### Scenario: Expanded catalog is generated
- **WHEN** KSP processes the completed English and Russian source migration
- **THEN** the catalog contains 49 new English identifiers and 27 Russian identifiers from the approved inventory
- **AND** each identifier maps to one parser constructor

### Requirement: Known-broken sources remain explicit
An adapter marked broken by the selected upstream snapshot MUST retain `isBroken=true` in the generated catalog until its fixture-backed public reading contract passes against a viable endpoint.

#### Scenario: Broken source code is included for visibility
- **WHEN** the adapter has not completed list, details, chapters, and pages verification
- **THEN** its identifier remains present with `isBroken=true`
- **AND** the source is not represented as operational
