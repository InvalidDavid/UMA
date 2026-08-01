## ADDED Requirements

### Requirement: Source behavior is verified through MangaParser
The test suite SHALL exercise source behavior through the public `MangaParser` interface using deterministic HTTP fixtures.

#### Scenario: Complete source reading flow
- **WHEN** a contract scenario requests a source list item, its details, its chapters, and the pages of one chapter
- **THEN** every returned model belongs to the selected source and the details operation preserves the manga identity required by `MangaParser`

### Requirement: Contract tests do not use live websites
Source contract tests MUST replace external HTTP responses at the `MangaLoaderContext.httpClient` seam and MUST fail on any unregistered request.

#### Scenario: Parser requests an unknown endpoint
- **WHEN** a parser makes an HTTP request that is not declared by its fixture scenario
- **THEN** the test fails with the request method and URL instead of contacting the live website or returning fallback data

### Requirement: Expanded catalog provides layered contract evidence
Every generated source entry SHALL construct through the public catalog factory, and the expanded catalog SHALL include deterministic complete reading-flow contracts for representative new English and Russian adapters.

#### Scenario: Expanded catalog is verified
- **WHEN** the approved bulk source migration is tested
- **THEN** every generated entry constructs a parser through `MangaParserSource.newParser`
- **AND** representative new English and Russian adapters complete list, details, chapters, and pages fixture flows

### Requirement: Live smoke diagnostics are opt-in
The test suite SHALL provide an explicitly enabled live-source flow for diagnosing current website behavior without making the default test suite depend on external HTTP.

#### Scenario: Developer selects a live source
- **WHEN** `LIVE_SOURCE` is set for the live smoke test
- **THEN** the selected parser runs through the configured list, details, chapters, and pages boundary
- **AND** a failure identifies the source operation that failed

#### Scenario: Default tests run
- **WHEN** `LIVE_SOURCE` is not set
- **THEN** the live smoke test is skipped without contacting external websites

### Requirement: Catalog-wide live audits remain diagnostic
The test suite SHALL provide an explicitly enabled catalog audit that records the furthest successful reading stage for every selected source without changing the deterministic default test suite.

#### Scenario: Developer audits selected locales
- **WHEN** the live catalog audit is enabled for English and Russian sources
- **THEN** every selected source records a bounded list, details, chapters, and pages outcome in a machine-readable report
- **AND** browser challenges, Android runtime dependencies, TLS trust failures, and confirmed broken markers remain distinguishable outcomes
