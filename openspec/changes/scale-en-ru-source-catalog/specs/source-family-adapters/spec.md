## ADDED Requirements

### Requirement: Shared site behavior remains in a family module
Adapters for sites with the same engine SHALL reuse one family module for request construction, parsing, normalization, and explicit failure behavior.

#### Scenario: Two sites use the same engine
- **WHEN** the sites differ only by domain, page size, selectors, or declared capabilities
- **THEN** their adapters provide those values to the family module instead of copying the shared reading flow

### Requirement: Site variation uses a small configuration seam
A family module SHALL expose site variation as cohesive immutable configuration and SHALL keep request ordering and parsing flow inside its implementation.

#### Scenario: A site changes one selector
- **WHEN** one site requires a different page-image selector
- **THEN** its adapter changes one site-profile value without overriding the complete pages operation

### Requirement: Unsupported responses fail explicitly
Family modules MUST NOT return placeholder or fallback models when required source content cannot be parsed.

#### Scenario: Required chapter content is absent
- **WHEN** a fixture omits the required chapter structure
- **THEN** the parser raises an explicit parse failure identifying the source operation

### Requirement: Unused family code is removed
A family module with no remaining adapters SHALL be removed unless an active OpenSpec change requires it for an immediately added source.

#### Scenario: Vietnamese cleanup removes the final adapter
- **WHEN** no source declaration references a family module after locale curation
- **THEN** the unused family module and its source-only implementation are deleted

