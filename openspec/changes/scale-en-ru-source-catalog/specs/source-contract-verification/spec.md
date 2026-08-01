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
