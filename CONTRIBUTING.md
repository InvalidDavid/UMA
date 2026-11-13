# Contributing to UMA

This guide provides practical instructions for contributing to UMA, adding and maintaining source parsers, and using the utilities available in the repository and its Tsuki dependency. It is intended for new contributors as well as people familiar with Kotlin parser projects.

UMA is a Kotlin plugin for the Usagi App and compatible Kotatsu forks. Its parsers are built on the Tsuki library. Contributions are welcome for parser fixes and additions, build/test improvements, and documentation.

This is a living document. If you find incorrect or missing information, open an [issue](https://github.com/InvalidDavid/UMA/issues) or submit a pull request.

## Table of Contents

- [Prerequisites](#prerequisites)
  - [Required knowledge](#required-knowledge)
  - [Tools](#tools)
  - [Clone and configure](#clone-and-configure)
- [Project overview](#project-overview)
  - [Project layout](#project-layout)
  - [Architecture and parser types](#architecture-and-parser-types)
- [Writing or updating a parser](#writing-or-updating-a-parser)
  - [Parser organization and registration](#parser-organization-and-registration)
  - [Listing and search](#listing-and-search)
  - [Details and chapters](#details-and-chapters)
  - [Chapter pages](#chapter-pages)
  - [Filters and capabilities](#filters-and-capabilities)
- [Utilities and helpers](#utilities-and-helpers)
  - [Tsuki utilities](#tsuki-utilities)
    - [HTTP requests and responses](#http-requests-and-responses)
    - [URL building and conversion](#url-building-and-conversion)
    - [HTML and Jsoup helpers](#html-and-jsoup-helpers)
    - [Chapter mapping and number extraction](#chapter-mapping-and-number-extraction)
    - [JSON helpers](#json-helpers)
    - [String and date helpers](#string-and-date-helpers)
    - [IDs and common model helpers](#ids-and-common-model-helpers)
  - [UMA utilities](#uma-utilities)
    - [Next.js data extraction](#nextjs-data-extraction)
    - [Protocol Buffers](#protocol-buffers)
    - [Turnstile integration](#turnstile-integration)
    - [Decryption helpers](#decryption-helpers)
- [History and favourites synchronization](#history-and-favourites-synchronization)
- [Source configuration and required user input](#source-configuration-and-required-user-input)
- [Code quality and testing](#code-quality-and-testing)
- [Building the plugin](#building-the-plugin)
- [Submitting changes](#submitting-changes)
- [Troubleshooting](#troubleshooting)
- [License](#license)

## Prerequisites

### Required knowledge

- Kotlin fundamentals, including collections and extension functions
- Basic HTTP request/response concepts
- HTML structure, CSS selectors, and JSON
- Basic Git usage

Android UI development is not required for ordinary parser work. Familiarity with coroutines, OkHttp, and Jsoup is helpful.

### Tools

- Git
- JDK 17 or newer for local development (the Kotlin/JVM toolchain is configured for Java 11)
- Android SDK with Android build-tools installed for DEX packaging
- Android Studio or IntelliJ IDEA (recommended)
- A Usagi-compatible app for installing and testing the plugin
- Browser developer tools and a JSON/HTML viewer (recommended)

### Clone and configure

```bash
git clone https://github.com/InvalidDavid/UMA.git
cd UMA
```

The `dexJar` task locates the Android SDK using `sdk.dir` in `local.properties` or the `ANDROID_HOME` environment variable. If needed, create a local `local.properties` file (do not commit it):

```properties
sdk.dir=/path/to/your/Android/Sdk
```

Never commit `local.properties`, API tokens, passwords, cookies, or personal account data.

## Project overview

### Project layout

```text
UMA/
├── src/main/kotlin/tsuki/
│   ├── parsers/          # Shared parser base classes
│   ├── site/             # Source parsers, organized by language/region
│   └── util/             # UMA-specific helpers and integrations
│       ├── decrypt/      # Cryptographic primitives used by some sources
│       └── protobuf/     # Internal streaming protobuf implementation
├── plugins-ksp/          # KSP processor for parser registration
├── buildSrc/             # Custom Gradle tasks, including DEX packaging
├── gradle/               # Wrapper and dependency version catalog
├── .github/workflows/    # Release build and CodeQL workflows
├── build.gradle.kts
├── README.md
├── CONTRIBUTING.md
└── LICENSE
```

Source implementations are under `src/main/kotlin/tsuki/site/`. Current language folders include `all`, `ar`, `de`, `en`, `es`, `fr`, `id`, `pt`, `ru`, `vi`, and `zh`. Shared parser implementations are under `tsuki/parsers/`; project-specific helpers are under `tsuki/util/`.

### Architecture and parser types

The project uses Tsuki parser base classes, KSP registration, and source-specific Kotlin implementations. Different base classes support different listing patterns. For example, `PagedMangaParser` provides pagination and delegates each page to `getListPage`; `SinglePageMangaParser` is suitable for sources without page-based listing. Shared UMA parser classes (such as Madara- or MangaReader-style parsers) are in `tsuki/parsers/`.

Choose a base class by inspecting current parsers with similar site behavior. Do not rely on old examples using `org.koitharu.kotatsu.parsers` as the active source package: UMA source implementations currently use `tsuki`.

## Writing or updating a parser

### Parser organization and registration

Put a parser in the appropriate directory under `src/main/kotlin/tsuki/site/`, usually grouped by language or region. For example:

```text
src/main/kotlin/tsuki/site/en/ExampleSource.kt
```

Reuse an existing shared parser in `tsuki/parsers/` where its behavior fits. Register source implementations with `@MangaSourceParser` and use the matching `MangaParserSource` identifier. Match annotation arguments, package names, source IDs, and parser base-class usage to nearby current code. Keep source IDs stable because users' saved data can depend on them.

### Listing and search

For paginated HTML sources, a `PagedMangaParser` commonly implements `getListPage(page, order, filter)`. Build a URL using the configured domain, request and parse the page, then map valid cards to `Manga` values. Adapt pagination, ordering, filters, and selectors to the website; do not claim support for options the parser ignores.

```kotlin
override suspend fun getListPage(
    page: Int,
    order: SortOrder,
    filter: MangaListFilter,
): List<Manga> {
    val url = urlBuilder()
        .addPathSegment("catalog")
        .addQueryParameter("page", page.toString())
        .apply {
            filter.query?.takeIf(String::isNotBlank)?.let { addQueryParameter("q", it) }
        }
        .build()
    val document = webClient.httpGet(url).parseHtml()

    return document.select(".manga-card").mapNotNull { card ->
        val link = card.selectFirst("a") ?: return@mapNotNull null
        val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
        val title = link.text().takeIf(String::isNotBlank) ?: return@mapNotNull null
        Manga(
            id = generateUid(href),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            title = title,
            source = source,
        )
    }
}
```

This is a pattern, not a drop-in implementation. Use the actual method signature required by the selected base class and the current Tsuki version.

### Details and chapters

`getDetails(manga)` should normally return `manga.copy(...)`, preserving the existing manga identity (`id`, `url`, and `source`) while filling in information fetched from the detail page. Use `mapChapters` for chapter collections where appropriate; see [Chapter mapping and number extraction](#chapter-mapping-and-number-extraction).

```kotlin
import tsuki.util.extractChapterNumber
import tsuki.util.mapChapters

override suspend fun getDetails(manga: Manga): Manga {
    val document = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
    val chapters = document.select(".chapter-list a").mapChapters(reversed = true) { _, link ->
        val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
        val title = link.text().ifBlank { null }
        MangaChapter(
            id = generateUid(href),
            title = title,
            number = (title ?: "").extractChapterNumber(),
            volume = 0,
            url = href,
            uploadDate = 0L,
            scanlator = null,
            branch = null,
            source = source,
        )
    }
    return manga.copy(chapters = chapters)
}
```

The example's model constructor shape must be checked against the Tsuki version resolved by this repository. Current UMA parsers often specify all required fields explicitly.

### Chapter pages

`getPages(chapter)` should return pages in reading order. Give each page a stable ID, provide image URLs in the form expected by nearby parsers, and set `source = source`. Some sites require custom request headers or data from the chapter page before the image list can be parsed.

### Filters and capabilities

Use `getFilterOptions()` to expose choices actually available on the site and `filterCapabilities` to describe which combinations the parser handles. Do not advertise multi-tag selection, year filtering, or search-with-filters unless requests correctly implement those behaviors. Check the `MangaListFilterCapabilities` declaration in the Tsuki version resolved by the project; its properties vary between versions.

## Utilities and helpers

There are two utility layers: shared Tsuki APIs in `tsuki.util` (and related packages), and project-specific helpers in UMA's `src/main/kotlin/tsuki/util/`. Import only what you use. The repository currently pins Tsuki `1.0.5` in `gradle/libs.versions.toml`; some newer APIs shown here may require a dependency update. Verify symbols against the resolved version and actual parser examples before adopting them.

### Tsuki utilities

#### HTTP requests and responses

UMA parsers commonly use `webClient.httpGet(...)`, `httpPost(...)`, `parseHtml()`, `parseJson()`, and `parseRaw()`. Use the response parsing helpers when they fit the response, and close raw responses after use. Avoid reading a response body more than once; response bodies are streams.

```kotlin
val document = webClient.httpGet(url).parseHtml()
val json = webClient.httpGet(apiUrl).parseJson()
val rawHtml = webClient.httpGet(pageUrl).parseRaw()
```

Use the client and headers already used by the parser. Add custom headers only when the source needs them, and do not log authentication values.

#### URL building and conversion

Useful Tsuki URL helpers include `urlBuilder()`, `toAbsoluteUrl(domain)`, and `toRelativeUrl(domain)`. `urlBuilder()` uses the parser's configured domain; builder methods safely encode path segments and query parameters.

```kotlin
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlBuilder

val url = urlBuilder(subdomain = "api")
    .addPathSegments("v1/user/history")
    .addQueryParameter("page", "1")
    .build()
val browserUrl = "/manga/example".toAbsoluteUrl(domain)
```

Use `addPathSegment` for one path component and `addPathSegments` for slash-separated components. Avoid concatenating untrusted query values manually. Store URLs consistently with existing UMA parsers, usually relative URLs for parser-internal model fields and absolute URLs for browser-facing links.

#### HTML and Jsoup helpers

Common Tsuki Jsoup extensions include:

- `selectFirstOrThrow(cssQuery)` / `selectOrThrow(cssQuery)` for required selections
- `attrOrNull(name)` / `attrOrThrow(name)` for optional or required attributes
- `attrAsRelativeUrl(name)` / `attrAsRelativeUrlOrNull(name)` for relative links
- `attrAsAbsoluteUrl(name)` / `attrAsAbsoluteUrlOrNull(name)` for absolute links
- `textOrNull()` / `ownTextOrNull()` for optional text
- `src()` / `requireSrc()` for common lazy-loaded image attributes

```kotlin
import tsuki.util.attrAsRelativeUrlOrNull
import tsuki.util.requireSrc
import tsuki.util.selectFirstOrThrow

val titleLink = card.selectFirstOrThrow("a.title")
val mangaPath = titleLink.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
val cover = card.selectFirst("img")?.requireSrc()
```

Use nullable helpers when a missing value is expected. Use throwing helpers only where missing markup means the page cannot be parsed; descriptive parse failures are easier to diagnose than silently creating invalid models.

#### Chapter mapping and number extraction

Tsuki's `mapChapters` extension maps a collection to `MangaChapter` values, supports reversing the collection, skips null results, and deduplicates entries by ID. Its mapper receives an index that advances for accepted chapters.

```kotlin
import tsuki.util.mapChapters

val chapters = elements.mapChapters(reversed = true) { index, element ->
    val link = element.selectFirst("a") ?: return@mapChapters null
    val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
    MangaChapter(
        id = generateUid(href),
        title = link.text().ifBlank { null },
        number = (index + 1).toFloat(),
        volume = 0,
        url = href,
        uploadDate = 0L,
        scanlator = null,
        branch = null,
        source = source,
    )
}
```

UMA adds `String.extractChapterNumber()`. It extracts numbers after common labels such as “Chapter”, “Ch.”, “Episode”, “Ep.”, and several translations; if no label is found, it uses the first number, and returns `0f` when no number is found.

```kotlin
import tsuki.util.extractChapterNumber

val chapterNumber = "Chapter 12.5 - The visitor".extractChapterNumber() // 12.5f
```

#### JSON helpers

Tsuki has helpers in `tsuki.util.json`, including safe getters and collection mappers. Depending on the response and pinned dependency, useful methods include `getStringOrNull`, `getIntOrDefault`, `getFloatOrDefault`, `mapJSON`, `mapJSONNotNull`, and `mapJSONNotNullToSet`.

```kotlin
import tsuki.util.json.getStringOrNull
import tsuki.util.json.mapJSONNotNull

val results = json.optJSONArray("results") ?: return emptyList()
return results.mapJSONNotNull { item ->
    val href = item.getStringOrNull("url") ?: return@mapJSONNotNull null
    val title = item.getStringOrNull("title")?.takeIf(String::isNotBlank)
        ?: return@mapJSONNotNull null
    Manga(
        id = generateUid(href),
        url = href,
        publicUrl = href.toAbsoluteUrl(domain),
        title = title,
        source = source,
    )
}
```

For larger or evolving API responses, deserialize only the fields needed and use the Kotlin serialization support configured in this project. Validate required IDs and titles rather than silently inventing them.

#### String and date helpers

Tsuki's `tsuki.util` includes helpers such as `urlEncoded()`, `urlDecode()`, `nullIfEmpty()`, `findGroupValue(regex)`, `toTitleCase(locale)`, and `nl2br()`. Use URL-builder methods for query parameters where possible, and use locale-aware helpers for translated text.

For date parsing, follow current neighboring parsers and use the helper available in the resolved Tsuki version. Many existing UMA parsers use `parseSafe()` with a site-specific `SimpleDateFormat` and locale. Specify the timezone when the site defines one. Missing or unparseable dates should remain unknown (`0L`), not be replaced with a guessed date.

#### IDs and common model helpers

Use `generateUid(...)` for stable IDs. Current UMA code commonly calls `generateUid(relativeUrl)`; the explicit form `generateUid(source, relativeUrl)` is also available in Tsuki. Use a stable URL or source ID, not a random value or list index.

Tsuki model constructors change across releases. Follow the actual model declaration and current parsers for required fields. In this repository's pinned version, do not assume the newer short `Manga(title, url, source, ...)` or `MangaChapter(title, url, number, source, ...)` constructors are available unless the dependency actually provides them.

### UMA utilities

These are helpers implemented in UMA rather than general Tsuki APIs. Their sources are in `src/main/kotlin/tsuki/util/`. The `decrypt/` and `protobuf/` subpackages contain cryptographic or codec implementations; use public entry points rather than depending on internal implementation classes.

#### Next.js data extraction

`NextJsData` extracts JSON payloads from Next.js Pages Router (`__NEXT_DATA__`) and App Router (React Flight) HTML. Its public operations are:

- `extractPayloads(html)`: return top-level parsed payloads.
- `findValue(html, predicate)`: find the first matching node in the payload graph.
- `findObject(html, key, value, vararg requiredKeys)`: find a JSON object with a matching key/value and required fields.
- `findArray(html, validator?)`: find the first non-empty JSON array, optionally validating its first object.
- `findLargestArray(html, validator?)`: find the largest matching non-empty array when the page contains several arrays of the same shape.

```kotlin
import org.json.JSONObject
import tsuki.util.NextJsData

val html = webClient.httpGet(pageUrl).parseRaw()
val mangaData = NextJsData.findObject(html, "slug", slug, "title", "chapters")
    ?: throw ParseException("Manga data not found", pageUrl)
val chapterArray = NextJsData.findLargestArray(html) { first ->
    first.has("id") && first.has("number")
}
val readerData = NextJsData.findValue(html) { node ->
    node is JSONObject && node.has("pages")
} as? JSONObject
```

Use DOM selectors when the HTML is stable and sufficient. Next.js payload formats can change; validate values and retain a reasonable fallback when the source has one.

#### Protocol Buffers

UMA's `tsuki.util.Protobuf` helpers use Kotlin serialization Protobuf and a shared `protoInstance`. Public helpers cover byte arrays and streaming sources/sinks, OkHttp response parsing, request-body encoding, and Base64 strings:

- `ByteArray.decodeProto<T>()`, `T.encodeProto()`
- `BufferedSource.decodeProto<T>(byteCount = -1L)`, `BufferedSink.encodeProto(value, encodeDefaults = false)`
- `Response.parseAsProto<T>()`, `Response.parseAsProto(transform)`, `ResponseBody.parseAsProto<T>()`
- `T.toRequestBodyProto(mediaType, encodeDefaults)`
- `String.decodeProtoBase64<T>()`, `T.encodeProtoBase64()`

```kotlin
import tsuki.util.parseAsProto
import tsuki.util.toRequestBodyProto

@Serializable
class CatalogRequest(val page: Int)

// Parse a protobuf HTTP response (the helper closes the response).
val result: CatalogResponse = webClient.httpGet(apiUrl).parseAsProto()

// Encode a serializable request DTO for a protobuf endpoint.
val requestBody = CatalogRequest(page = 1).toRequestBodyProto()
```

The repository also has a `tsuki.util.protobuf` package containing the streaming encoder/decoder implementation. Treat those implementation classes as internals; use the extensions above. MangaPlus is an in-repository example of `parseAsProto()`.

#### Turnstile integration

UMA provides `getTurnstileToken(context, url, siteKey, action?, cData?, theme, timeoutMs)` in `tsuki.util`. It is a source-specific integration for a site that explicitly requires Turnstile. It can fail on timeouts, widget errors, or challenges requiring interactive input. Respect the website's terms and access controls; do not use this helper to evade a site's protections or access restrictions. Prefer the site's supported access method and do not add it unless the source's authorized flow requires it.

#### Decryption helpers

UMA's `tsuki.util.decrypt` package contains public primitives for source formats that require authenticated decryption. Only use the primitive matching the site's documented/wire format; the key, nonce, tag, associated data, and payload layout must come from the source protocol. Never invent cryptographic parameters or disable authentication checks.

- `AesSiv.decrypt(key, ciphertext, vararg aad)` — AES-SIV; ciphertext format is tag followed by encrypted content.
- `AesGcmSiv.decrypt(key, nonce, ciphertext, aad = emptyByteArray())` — AES-GCM-SIV; ciphertext contains the authentication tag at the end.
- `ChaCha20Poly1305.decrypt(key, nonce, ct, tag, aad)` — IETF ChaCha20-Poly1305.
- `ChaCha20Poly1305.xdecrypt(key, nonce, ct, tag, aad)` — XChaCha20-Poly1305.
- `Ece.decrypt(payload, ikm)` — RFC 8188 `aes128gcm` payload decryption.
- `Ece.hkdf(ikm, salt, info, length, algorithm)` — HKDF key derivation.
- `Ece.publicKeyToRaw(publicKey)` and `Ece.rawToPublicKey(raw)` — P-256 uncompressed public-key conversion.

A minimal example of the shape only (supply protocol-derived values; do not use placeholder keys in production):

```kotlin
import tsuki.util.decrypt.AesGcmSiv

val plaintext = AesGcmSiv.decrypt(
    key = keyBytes,
    nonce = nonceBytes,
    ciphertext = ciphertextWithTag,
    aad = associatedData,
)
```

`decrypt.Bytes` and `decrypt.SecretStream` are internal implementation details, not parser-facing utility APIs. Add or modify cryptographic behavior only when the format is understood and covered by tests.

## History and favourites synchronization

Some sites expose a user's reading history or favourites. If both the site and the Tsuki version used by UMA support sync, advertise the capability and override the appropriate methods. Return `Manga` records containing the information the site provides; some services only provide a title, URL, and chapter name/number, and the app can reconcile partial metadata with local records.

The following shows the newer API shape from Tsuki. Check that the resolved dependency contains `isSyncSupported`, `getHistoryList()`, and `getFavouritesList()` before using it. UMA currently declares Tsuki `1.0.5` in `gradle/libs.versions.toml`, so newer APIs may require a dependency update.

```kotlin
override val filterCapabilities: MangaListFilterCapabilities
    get() = super.filterCapabilities.copy(isSyncSupported = true)

override suspend fun getHistoryList(): List<Manga> {
    val url = urlBuilder().addPathSegments("user/history").build()
    val document = webClient.httpGet(url).parseHtml()

    return document.select("div.item-read").mapNotNull { item ->
        val mangaLink = item.selectFirst(".image a") ?: return@mapNotNull null
        val mangaUrl = mangaLink.attr("href").toAbsoluteUrl(domain)
        val title = mangaLink.attr("title").ifBlank { mangaLink.text() }

        Manga(
            title = title,
            url = mangaUrl,
            source = source,
            chapters = item.select("ul.comic-item li.chapter").mapChapters { _, element ->
                val chapterLink = element.selectFirst("a") ?: return@mapChapters null
                val chapterTitle = chapterLink.attr("title").ifBlank { chapterLink.text() }
                MangaChapter(
                    title = chapterTitle,
                    url = chapterLink.attr("href").toAbsoluteUrl(domain),
                    number = chapterTitle.substringAfter("Chapter ").toFloatOrNull() ?: 0f,
                    source = source,
                )
            },
        )
    }
}

override suspend fun getFavouritesList(): List<Manga> {
    // Fetch and map this site's favourites using the same model conventions.
    return super.getFavouritesList()
}
```

The short model constructors above are from the newer Tsuki API example and may not compile against the pinned dependency. Adapt them to the actual constructor requirements if using the current version. Implement sync only when supported by the site; do not advertise placeholder methods.

## Source configuration and required user input

Use `ConfigKey` for user-configurable source settings and register each key in `onCreateConfig`, calling `super.onCreateConfig(keys)`. `ConfigKey.UserInput` is suitable for a required value such as an API token. When input is missing, Tsuki's `InputRequiredException` asks the app to show an input dialog; its `key` identifies the value to save and retrieve on a later attempt.

```kotlin
import tsuki.config.ConfigKey
import tsuki.exception.InputRequiredException

private val tokenKey = ConfigKey.UserInput("api_token")

override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
    super.onCreateConfig(keys)
    keys.add(tokenKey)
}

override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
    val token = config[tokenKey]
    if (token.isNullOrBlank()) {
        throw InputRequiredException(
            source = source,
            message = "Use your API token to read this locked chapter",
            key = "api_token",
        )
    }
    // Use the token only for the intended request, then parse the page list.
    TODO("Implement the site's page request")
}
```

Keep the config identifier and exception `key` consistent. Do not log or commit credentials, and do not send them to unrelated hosts.

## Code quality and testing

- Keep changes focused and follow formatting and patterns in nearby Kotlin files.
- Parse only data the source actually provides; preserve unknown values as unknown.
- Use stable IDs and consistent URL forms.
- Handle missing or changed site markup deliberately. Skip invalid optional records and report useful parse errors for required elements.
- Be respectful of source servers: avoid unnecessary requests, uncontrolled concurrency, or aggressive retry loops.
- Do not advertise capabilities that are not implemented.
- Do not include account data, cookies, or credentials in logs or pull requests.

Run relevant checks before submitting:

```bash
./gradlew compileKotlin
./gradlew test
```

If you change DEX packaging or need to verify the installable artifact, also run `./gradlew buildJar` with Android SDK build-tools installed.

## Building the plugin

Use the checked-in wrapper:

```bash
# Linux/macOS
chmod +x gradlew
./gradlew buildJar

# Windows
.\gradlew.bat buildJar
```

`buildJar` runs the custom DEX packaging task and creates `build/libs/uma.jar`. It requires an Android SDK. The regular `jar` task produces `build/libs/raw.jar` but does not DEX the plugin. The release workflow builds the installable artifact from source changes on `master`; CodeQL compiles Kotlin as part of analysis.

## Submitting changes

Open an issue for a broken parser, a missing source request, a build problem, or a documentation correction. Include the source name, site URL, language, NSFW status if applicable, expected behavior, and reproduction steps. Do not include passwords, API tokens, cookies, or private account data.

For pull requests:

1. Keep the change focused and explain the user-visible effect.
2. Mention which parser/source is affected and relevant behavior (listing, details, chapter order, or page loading).
3. Describe tests/build commands run, or explain why they could not be run.
4. Update this guide or the README when setup or contributor workflows change.
5. Ensure new source parser code follows current UMA conventions and contains no hardcoded test credentials.

## Troubleshooting

**Kotlin symbols are unresolved:** Check imports and the actual Tsuki version resolved by Gradle; sync or reimport the Gradle project and run `./gradlew compileKotlin`.

**`buildJar` cannot find the Android SDK:** Set `sdk.dir` in untracked `local.properties` or set `ANDROID_HOME`, and ensure Android build-tools are installed.

**A parser is not registered:** Confirm its `@MangaSourceParser` annotation and `MangaParserSource` identifier, then rebuild so KSP can generate registration code.

**A selector no longer matches:** Inspect the current website markup with browser developer tools and update the selector. Prefer stable semantic selectors over generated CSS class names.

## License

Contributions are subject to the repository's [GPL-3.0 license](LICENSE).
