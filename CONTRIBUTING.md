# Contributing to UMA

This guide explains how to contribute to UMA, add or maintain source parsers, use the utilities in UMA and Tsuki, and build and test your work. It is written for both first-time contributors and developers who want more detail about parser behavior and implementation choices.

UMA is a Kotlin plugin for the Usagi App and compatible Kotatsu forks. Its parsers use the Tsuki library. Contributions are welcome for parser fixes and additions, build/test improvements, and documentation.

This is a living guide. If you find incorrect or missing information, open an [issue](https://github.com/InvalidDavid/UMA/issues) or submit a pull request.

## Table of Contents

- [Prerequisites](#prerequisites)
  - [Required knowledge](#required-knowledge)
  - [Tools](#tools)
  - [Clone and configure](#clone-and-configure)
- [Project overview](#project-overview)
  - [Project layout](#project-layout)
  - [Build and dependency configuration](#build-and-dependency-configuration)
  - [Architecture and parser types](#architecture-and-parser-types)
  - [How a parser request flows](#how-a-parser-request-flows)
- [Writing or updating a parser](#writing-or-updating-a-parser)
  - [Choose the right source package](#choose-the-right-source-package)
  - [Register a parser](#register-a-parser)
  - [A very small beginner parser template](#a-very-small-beginner-parser-template)
  - [Parse a manga list](#parse-a-manga-list)
  - [Implement search and sorting](#implement-search-and-sorting)
  - [Load details and chapters](#load-details-and-chapters)
  - [Load chapter pages](#load-chapter-pages)
  - [Add filters and declare capabilities](#add-filters-and-declare-capabilities)
  - [Resolve website links](#resolve-website-links)
- [Utilities and helpers](#utilities-and-helpers)
  - [Choosing between Tsuki and UMA helpers](#choosing-between-tsuki-and-uma-helpers)
  - [Tsuki utilities](#tsuki-utilities)
    - [HTTP requests and response lifetime](#http-requests-and-response-lifetime)
    - [URL building and conversion](#url-building-and-conversion)
    - [HTML selection and URL attributes](#html-selection-and-url-attributes)
    - [Image source extraction](#image-source-extraction)
    - [Chapter mapping and chapter numbers](#chapter-mapping-and-chapter-numbers)
    - [JSON parsing and collection mapping](#json-parsing-and-collection-mapping)
    - [String, date, and common parser helpers](#string-date-and-common-parser-helpers)
    - [Stable IDs and source-aware URLs](#stable-ids-and-source-aware-urls)
  - [UMA utilities](#uma-utilities)
    - [Next.js and React Flight payloads](#nextjs-and-react-flight-payloads)
    - [Protocol Buffers](#protocol-buffers)
    - [Turnstile integration](#turnstile-integration)
    - [Authenticated decryption primitives](#authenticated-decryption-primitives)
- [History and favourites synchronization](#history-and-favourites-synchronization)
- [Source configuration and required user input](#source-configuration-and-required-user-input)
- [Parser quality and reliability](#parser-quality-and-reliability)
  - [URLs and identity](#urls-and-identity)
  - [Missing data and parse errors](#missing-data-and-parse-errors)
  - [Requests, retries, and rate](#requests-retries-and-rate)
  - [Credentials and sensitive information](#credentials-and-sensitive-information)
- [Testing and building](#testing-and-building)
  - [Compile and test](#compile-and-test)
  - [Build the installable plugin](#build-the-installable-plugin)
  - [Test in a Usagi-compatible app](#test-in-a-usagi-compatible-app)
- [Submitting changes](#submitting-changes)
- [Troubleshooting](#troubleshooting)
- [License](#license)

## Prerequisites

Before you start contributing to UMA, ensure you have the following:

### Required knowledge

- Kotlin fundamentals, including collections, extension functions, and null safety
- Basic HTTP request/response concepts
- HTML structure, CSS selectors, and JSON
- Basic Git usage

Android UI development is not required for ordinary parser work. Familiarity with coroutines, OkHttp, Jsoup, and Kotlin serialization is helpful.

### Tools

- Git
- JDK 17 or newer for local development (the Kotlin/JVM toolchain is configured for Java 11)
- Android SDK with Android build-tools installed for DEX packaging
- Android Studio or IntelliJ IDEA (recommended)
- A Usagi-compatible app for installing and testing the plugin
- Browser developer tools and an HTML/JSON viewer (recommended)

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
│       ├── decrypt/      # Authenticated decryption primitives
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

### Build and dependency configuration

The root build is configured in `build.gradle.kts`; included subprojects are configured from `settings.gradle.kts`. Dependency and plugin versions are managed in `gradle/libs.versions.toml`. The current catalog pins Kotlin 2.2.10 and Tsuki 1.0.5. Check this catalog before assuming an API is available: the UMA source tree may be using a newer Tsuki API in examples or discussions than the dependency currently resolved for compilation.

`plugins-ksp` processes `@MangaSourceParser` annotations. `buildSrc` supplies the custom `buildJar`, `dexJar`, and report-generation tasks. Do not edit generated source or build output under `build/`.

### Architecture and parser types

UMA combines Tsuki parser base classes, KSP registration, shared source-family parsers, and individual source implementations. The base class controls how listings are requested and paginated; a concrete source class supplies the site's behavior.

- **`PagedMangaParser`** is for listings divided into pages. It maps the app's offset to a page and calls `getListPage(page, order, filter)`.
- **`SinglePageMangaParser`** is suitable when the source's listing is not conventionally paginated; inspect its current API before overriding methods.
- **Shared UMA parsers** in `tsuki/parsers/` encapsulate common CMS/site behavior, such as Madara- and MangaReader-style sites. Extend one when it genuinely matches the source instead of duplicating its behavior.
- **Other Tsuki base classes** may be available depending on the resolved dependency. Check the dependency API and examples in this checkout before selecting one.

Use the actual method signatures in the checked-out and Gradle-resolved dependency; descriptions here are guidance, not a substitute for the API.

### How a parser request flows

A typical list request follows this path:

1. The app asks the parser for a listing, search result, details, or pages.
2. The parser constructs a source URL and makes a request through `webClient`.
3. A Tsuki response helper parses HTML, JSON, raw text, or protobuf data.
4. Parser code validates each item and maps it into `Manga`, `MangaChapter`, or `MangaPage` models.
5. The app consumes those models; details and chapter data may be fetched later than the initial list.

Keep the request, response parsing, and model mapping easy to follow. Use private helpers when they make a repeated or complicated mapping clearer.

## Writing or updating a parser

### Choose the right source package

Place a parser under `src/main/kotlin/tsuki/site/<language>/`. Use `all` for a source intended for multiple languages. Keep a source with adult content in the repository's existing `nsfw` subdirectory pattern when appropriate. For example:

```text
src/main/kotlin/tsuki/site/en/ExampleSource.kt
src/main/kotlin/tsuki/site/all/WorldwideSource.kt
```

Reuse an existing shared parser in `tsuki/parsers/` where its behavior fits. Do not copy old examples using `org.koitharu.kotatsu.parsers` as the active source package: UMA source implementations currently use `tsuki`.

### Register a parser

Register source implementations with `@MangaSourceParser` and use the matching `MangaParserSource` identifier. Match annotation arguments, package names, source IDs, locale, and content type to nearby current code.

```kotlin
@MangaSourceParser("EXAMPLESOURCE", "Example Source", "en")
internal class ExampleSource(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.EXAMPLESOURCE, pageSize = 20) {
    // Implement the methods this source needs.
}
```

This is a shape example; constructors and annotation parameters must match the Tsuki version and project conventions in the current checkout. Keep source IDs stable because users' saved data can depend on them.

### A very small beginner parser template

If you are new to UMA, start with a tiny example that only does list loading and passes the required source registration. The example below is intentionally simple and shows the usual pattern:

```kotlin
package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.core.PagedMangaParser
import tsuki.model.Manga
import tsuki.model.MangaListFilter
import tsuki.model.MangaParserSource
import tsuki.model.SortOrder
import tsuki.util.attrAsRelativeUrlOrNull
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlBuilder

@MangaSourceParser("EXAMPLE", "Example Source", "en")
internal class ExampleSource(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.EXAMPLE, pageSize = 20) {

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = urlBuilder()
            .addPathSegment("catalog")
            .addQueryParameter("page", page.toString())
            .build()

        val doc = webClient.httpGet(url).parseHtml()
        return doc.select(".manga-item").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = link.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                source = source,
            )
        }
    }
}
```

A good beginner workflow is:

1. Fetch the site homepage.
2. Find one list item and inspect its anchor tag.
3. Copy its relative path and title into a parser.
4. Confirm the source returns the expected titles.
5. Then add details, chapters, and page loading.

If you do not know the site structure, do a quick browser inspection first and look for one `a[href]` inside a product card or manga card.

### Parse a manga list

For paginated HTML sources, a `PagedMangaParser` commonly implements `getListPage(page, order, filter)`. Build a URL using the configured domain, request and parse the page, then map valid cards to `Manga` values. Validate required fields before constructing the model; a malformed card should not become an entry with an empty URL or title.

```kotlin
import tsuki.util.attrAsRelativeUrlOrNull
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlBuilder

override suspend fun getListPage(
    page: Int,
    order: SortOrder,
    filter: MangaListFilter,
): List<Manga> {
    val url = urlBuilder().apply {
        addPathSegment("catalog")
        addQueryParameter("page", page.toString())
        filter.query?.takeIf(String::isNotBlank)?.let { addQueryParameter("q", it) }
        when (order) {
            SortOrder.POPULARITY -> addQueryParameter("sort", "popular")
            SortOrder.UPDATED -> addQueryParameter("sort", "updated")
            else -> Unit
        }
    }.build()

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

This is a pattern, not a drop-in implementation. Use the actual method signature required by the selected base class. Confirm whether the site's first page is zero- or one-based, what an empty result means, and how the source represents sort options. Do not claim support for filters the request ignores.

### Implement search and sorting

In many paginated parsers, search and catalogue requests share `getListPage`; in other parsers, search has a dedicated method or endpoint. Check the base class. Map `filter.query`, tags, status, content type, years, and other fields only when the website supports them.

URL builders encode query parameters safely:

```kotlin
val builder = urlBuilder().addPathSegment("search")
filter.query?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("q", it) }
filter.tags.forEach { tag -> builder.addQueryParameter("genre[]", tag.key) }
val url = builder.build()
```

Do not build query strings by concatenating raw user input. When the site's API uses repeated parameters, call `addQueryParameter` for each value rather than joining them into one ambiguous string.

A beginner debugging tip: if the site works in the browser but not in the parser, print the exact URL and compare it to the browser's network request. This catches missing query parameters and wrong order values very quickly.

### Load details and chapters

`getDetails(manga)` should normally return `manga.copy(...)`, preserving the existing manga identity (`id`, `url`, and `source`) while filling in information fetched from the detail page. Use `mapChapters` for chapter collections where appropriate; see [Chapter mapping and chapter numbers](#chapter-mapping-and-chapter-numbers).

```kotlin
import tsuki.util.extractChapterNumber
import tsuki.util.mapChapters
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl

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

Details and chapters may be on separate pages or API endpoints. Fetch them concurrently only when the requests are independent and concurrency is reasonable for the source. Preserve existing metadata when a detail request omits it; do not overwrite known data with empty strings or fabricated values.

### Load chapter pages

`getPages(chapter)` should return pages in reading order. Give each page a stable ID, provide image URLs in the form expected by nearby parsers, and set `source = source`. Some sites require custom request headers or data from the chapter page before the image list can be parsed.

```kotlin
override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
    val document = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
    return document.select(".reader img").mapNotNull { image ->
        val imageUrl = image.src() ?: return@mapNotNull null
        MangaPage(
            id = generateUid(imageUrl),
            url = imageUrl,
            preview = null,
            source = source,
        )
    }
}
```

Some parsers need `getPageUrl(page)` to resolve a deferred image URL; others return direct URLs. Follow the source's existing approach and check the Tsuki API for the method contract.

A beginner checklist for page loading:

- Does the chapter page contain all image URLs?
- Are image URLs relative or absolute?
- Does the site lazy-load pages via JSON or a second request?
- Are pages in the correct reading order?

### Add filters and declare capabilities

Use `getFilterOptions()` to expose choices actually available on the site and `filterCapabilities` to describe which combinations the parser handles. A filter option describes what the user can choose; a capability describes what the parser can process. Both must agree with the request-building code.

For example, do not set multiple-tag support if the site accepts only one genre, and do not claim search-with-filters support if a search query causes the parser to discard selected filters. Check `MangaListFilterCapabilities` in the Tsuki version resolved by the project; its properties vary between versions.

Beginner example:

```kotlin
override val filterCapabilities: MangaListFilterCapabilities
    get() = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
        isYearSupported = false,
    )
```

If the site does not support year filtering, do not claim it. The app will otherwise create filters that always fail.

### Resolve website links

Override the relevant `resolveLink` method when a source URL should open directly as a manga in the app. Parse and validate the host and path before fetching anything. Use the source's configured domain or incoming URL as appropriate; avoid hard-coded mirror hosts when the source supports configurable domains.

A simple pattern looks like:

```kotlin
override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
    val slug = link.pathSegments.lastOrNull() ?: return null
    return getDetailsImpl(slug)
}
```

If the source is accessed through multiple domains or subdomains, always validate that the URL host matches the source, otherwise your parser may accidentally open a different site or a non-manga page.

## Utilities and helpers

There are two utility layers: shared Tsuki APIs in `tsuki.util` (and related packages), and project-specific helpers in UMA's `src/main/kotlin/tsuki/util/`. Import only what you use. The repository currently pins Tsuki 1.0.5 in `gradle/libs.versions.toml`; some newer APIs shown here may require a dependency update. Verify symbols against the resolved version and actual parser examples before adopting them.

### Choosing between Tsuki and UMA helpers

Prefer a Tsuki helper when it already handles a common parser task such as URL construction, safe HTML selection, chapter mapping, JSON access, or response parsing. Use UMA helpers for the site-specific features they implement, such as Next.js payload extraction, a protobuf endpoint format, or an authenticated encryption format.

The utility reference below covers the public helper surface used by UMA contributors. Files under `tsuki/util/decrypt/` and `tsuki/util/protobuf/` also contain low-level implementation code; those internals are not intended as general parser APIs.

### Tsuki utilities

#### HTTP requests and response lifetime

UMA parsers commonly use `webClient.httpGet(...)`, `httpPost(...)`, `parseHtml()`, `parseJson()`, and `parseRaw()`. Use the response parsing helpers when they fit the response, and close raw responses after use. An HTTP response body is a one-shot stream: read it once, parse it, and do not try to consume it again.

```kotlin
val document = webClient.httpGet(url).parseHtml()
val json = webClient.httpGet(apiUrl).parseJson()
val rawHtml = webClient.httpGet(pageUrl).parseRaw()
```

Use the client and headers already used by the parser. Add custom headers only when the source needs them; reuse `getRequestHeaders()` or existing source helpers where practical. Do not log authorization headers or token values.

Beginner example:

```kotlin
val response = webClient.httpGet("https://example.com/search?q=manga")
val html = response.parseHtml()
val title = html.selectFirst("h1")?.text()
```

That is the simplest possible request+parse flow. If the site pushes data via JSON instead of HTML, replace `parseHtml()` with `parseJson()`.

#### URL building and conversion

Useful Tsuki URL helpers include `urlBuilder()`, `toAbsoluteUrl(domain)`, and `toRelativeUrl(domain)`. `urlBuilder()` uses the parser's configured domain; builder methods safely encode path segments and query parameters.

```kotlin
import tsuki.util.toAbsoluteUrl
import tsuki.util.toRelativeUrl
import tsuki.util.urlBuilder

val url = urlBuilder(subdomain = "api")
    .addPathSegments("v1/user/history")
    .addQueryParameter("page", "1")
    .build()

val browserUrl = "/manga/example".toAbsoluteUrl(domain)
val relativeUrl = browserUrl.toRelativeUrl(domain)
```

Beginner example:

```kotlin
val url = urlBuilder()
    .addPathSegment("search")
    .addQueryParameter("q", "one punch")
    .build()
```

This is usually cleaner than string concatenation and avoids broken URLs when a query contains special characters such as `?`, `&`, or spaces.

Use `addPathSegment` for one path component and `addPathSegments` for slash-separated components. Avoid concatenating untrusted query values manually. Store URLs consistently with existing UMA parsers, usually relative URLs for parser-internal model fields and absolute URLs for browser-facing links. Some sources intentionally use IDs or other URI-like values instead of conventional paths; follow their established conventions.

#### HTML selection and URL attributes

Common Tsuki Jsoup extensions include:

- `selectFirstOrThrow(cssQuery)` / `selectOrThrow(cssQuery)` for required selections
- `selectLast(cssQuery)` / `selectLastOrThrow(cssQuery)` for the last matching element
- `attrOrNull(name)` / `attrOrThrow(name)` for optional or required attributes
- `attrAsRelativeUrl(name)` / `attrAsRelativeUrlOrNull(name)` for relative links
- `attrAsAbsoluteUrl(name)` / `attrAsAbsoluteUrlOrNull(name)` for absolute links
- `textOrNull()` / `ownTextOrNull()` for optional text
- `requireElementById(id)` for a required ID-selected element

```kotlin
import tsuki.util.attrAsRelativeUrlOrNull
import tsuki.util.selectFirstOrThrow
import tsuki.util.textOrNull

val titleLink = card.selectFirstOrThrow("a.title")
val mangaPath = titleLink.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
val subtitle = card.selectFirst(".subtitle")?.textOrNull()
```

Beginner example:

```kotlin
val title = doc.selectFirst("h1")?.text()
val image = doc.selectFirst("img.cover")?.attr("src")
val link = doc.selectFirst("a.series-link")?.attr("href")
```

Use nullable helpers when a missing value is expected. Use throwing helpers only where missing markup means the page cannot be parsed; descriptive parse failures are easier to diagnose than silently creating invalid models. `ownTextOrNull()` is useful when an element contains nested labels or icons whose text should not be included.

#### Image source extraction

Sites frequently place images in lazy-loading attributes (`data-src`, `data-original`, and similar) rather than `src`. Tsuki's `Element.src()` checks common source attributes and resolves the result to an absolute URL. `requireSrc()` provides a required-value variant and fails with a parse error when there is no image URL.

```kotlin
import tsuki.util.requireSrc
import tsuki.util.src

val optionalCover = card.selectFirst("img")?.src()
val pageImage = pageElement.selectFirst("img")?.requireSrc()
    ?: return emptyList()
```

Beginner example:

```kotlin
val cover = doc.selectFirst("img")?.src()
val pageUrl = chapterDoc.selectFirst("img.reader-page")?.requireSrc()
```

Use `src()` when the image may legitimately be absent, such as a missing optional cover; use `requireSrc()` for required reader pages. Check nearby code because `src()` may require an opt-in annotation in the version in use.

#### Chapter mapping and chapter numbers

Tsuki's `mapChapters` extension maps a collection to `MangaChapter` values, supports reversing the collection, skips null results, and deduplicates entries by chapter ID. Its mapper receives an index that advances for accepted chapters.

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

Beginner example:

```kotlin
val chapterLinks = doc.select("a.chapter-link")
val chapters = chapterLinks.mapChapters(reversed = true) { _, link ->
    val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
    MangaChapter(
        id = generateUid(href),
        url = href,
        title = link.text(),
        number = 1f,
        source = source,
    )
}
```

UMA adds `String.extractChapterNumber()`. It extracts a number after common labels such as “Chapter”, “Ch.”, “Episode”, “Ep.”, and several translations; if no label is found, it uses the first number, and returns `0f` when no number is found.

```kotlin
import tsuki.util.extractChapterNumber

val chapterNumber = "Chapter 12.5 - The visitor".extractChapterNumber() // 12.5f
val unknownNumber = "Special extra".extractChapterNumber() // 0f
```

This helper is intentionally heuristic. Sites with unusual numbering (volumes, seasons, ranges, or multiple numbers in titles) may need a source-specific parser.

#### JSON parsing and collection mapping

Tsuki has helpers in `tsuki.util.json`, including safe getters and collection mappers. Depending on the response and pinned dependency, useful methods include `getStringOrNull`, `getIntOrDefault`, `getFloatOrDefault`, `getBooleanOrDefault`, `mapJSON`, `mapJSONIndexed`, `mapJSONNotNull`, and `mapJSONNotNullToSet`.

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

Beginner example:

```kotlin
val array = json.getJSONArray("items")
for (i in 0 until array.length()) {
    val item = array.getJSONObject(i)
    val title = item.optString("title")
    println(title)
}
```

Use safe accessors for optional API fields and fail or skip entries with missing required identifiers. For larger or evolving API responses, deserialize only the fields needed and use the Kotlin serialization support configured in this project. Check actual helper names in the resolved Tsuki version before relying on them.

#### String, date, and common parser helpers

Tsuki's `tsuki.util` includes helpers such as:

- `urlEncoded()` / `urlDecode()` for form-style URL encoding and decoding
- `nullIfEmpty()` / `ifNullOrEmpty { ... }` for empty strings
- `find(regex)` / `findGroupValue(regex)` for concise regex extraction
- `toTitleCase(locale)` for locale-aware display formatting
- `nl2br()` for converting newline characters to HTML breaks
- `parseSafe()` for safe parsing with the date format helpers used in UMA
- `suspendLazy` utilities for caching suspend-loaded values where appropriate

```kotlin
import tsuki.util.findGroupValue
import tsuki.util.nullIfEmpty
import tsuki.util.urlEncoded

val encodedQuery = query.urlEncoded()
val optionalDescription = rawDescription.nullIfEmpty()
val buildId = html.findGroupValue(Regex("/_next/static/([^/]+)/"))
```

Beginner example:

```kotlin
val cleaned = "  hello world  ".trim()
val encoded = cleaned.urlEncoded()
val maybeTitle = titleElement.text().nullIfEmpty()
```

For dates, follow current neighboring parsers and use the helper available in the resolved Tsuki version. Many existing UMA parsers use `parseSafe()` with a site-specific `SimpleDateFormat` and locale. Specify the timezone when the site defines one. Missing or unparseable dates should remain unknown (`0L`), not be replaced with a guessed date.

#### Stable IDs and source-aware URLs

Use `generateUid(...)` for stable IDs. Current UMA code commonly calls `generateUid(relativeUrl)`; the explicit form `generateUid(source, relativeUrl)` is also available in Tsuki. Use a stable URL or source ID, not a random value or list index.

The `MangaParser` extensions also provide source-aware ID generation and `urlBuilder(subdomain?)`. `oneOrThrowIfMany()` helpers can validate that a filter contains no more than one selected value when the site only accepts one. These helpers are useful for enforcing parser assumptions instead of silently choosing an arbitrary filter.

```kotlin
val mangaId = generateUid(mangaPath)
val chapterId = generateUid(chapterPath)
```

Beginner example:

```kotlin
val id = generateUid("/manga/one-piece")
println(id) // stable hash from the manga URL
```

Tsuki model constructors change across releases. Follow the actual model declaration and current parsers for required fields. In this repository's pinned version, do not assume newer short `Manga(title, url, source, ...)` or `MangaChapter(title, url, number, source, ...)` constructors are available unless the dependency actually provides them.

### UMA utilities

These helpers are implemented in UMA rather than general Tsuki APIs. Their sources are in `src/main/kotlin/tsuki/util/`. The `decrypt/` and `protobuf/` subpackages contain cryptographic or codec implementations; use public entry points rather than depending on internal implementation classes.

#### Next.js and React Flight payloads

`NextJsData` extracts data from both Next.js rendering paths:

- **Pages Router:** reads the `__NEXT_DATA__` script and exposes `props.pageProps` and the root JSON object.
- **App Router:** joins inline `self.__next_f.push(...)` scripts, handles text and Base64 Flight chunks, parses outlined model rows, and resolves supported React Flight references such as `$<id>`, `$Q` maps, `$W` sets, dates, and lazy/promise references. Reference cycles are guarded; unresolved references are retained for inspection.
- **Raw RSC response:** `extractRscPayloads(body)` and `findPageRsc(body, ...)` work with a raw `text/x-component` body (typically an RSC request using the `RSC: 1` header).
- **Exact text-chunk lookup:** `findPage`/`findPageRsc` return a `FlightPage` containing a matched object and its text-chunk cache. Use `FlightPage.text(...)` for fields represented as `$<id>` text references.

Public lookup methods:

- `extractPayloads(html)`: all top-level parsed payloads; App Router is tried first and Pages Router is the fallback.
- `extractRscPayloads(body)`: parsed payloads from a raw Flight body.
- `findValue(html, predicate)`: first matching node anywhere in the parsed payload graph.
- `findObject(html, key, value, vararg requiredKeys)`: first object matching a key/value and containing the required keys.
- `findObjectInPayloads(payloads, vararg keys)`: first object with all requested keys in already-extracted payloads.
- `findArray(html, validator?)`: first non-empty array, optionally validating its first object.
- `findLargestArray(html, validator?)`: largest matching non-empty array; useful when a small recommendation array appears before the full chapter list.
- `findPage(html, vararg keys)` / `findPageRsc(body, vararg keys)`: locate an object containing all requested keys in one Flight row and return its text chunks as a `FlightPage`.

For ordinary JSON values or payloads with inlined fields, prefer `findValue` or `findObject`. For example:

```kotlin
import org.json.JSONObject
import tsuki.util.NextJsData

val html = webClient.httpGet(pageUrl).parseRaw()
val manga = NextJsData.findObject(html, "slug", slug, "title", "chapters")
    ?: throw ParseException("Manga data not found", pageUrl)

val chapters = NextJsData.findLargestArray(html) { first ->
    first.has("id") && first.has("number")
}

val reader = NextJsData.findValue(html) { node ->
    node is JSONObject && node.has("pages")
} as? JSONObject
```

Beginner example:

```kotlin
val pageHtml = webClient.httpGet("https://example.com/manga/test").parseRaw()
val payloads = NextJsData.extractPayloads(pageHtml)
val titleObj = NextJsData.findObjectInPayloads(payloads, "title", "slug")
println(titleObj?.optString("title"))
```

Use `findLargestArray` only when the candidate arrays share a known shape; the largest array is not automatically the correct one. A validator should check distinctive fields, not just common keys such as `id`.

Some App Router payloads put long text in Flight text chunks, for example `"synopsis":"$27"`. The general `extractPayloads` path resolves Flight references using a character-oriented row parser. For exact UTF-8 byte handling and lazy text lookup, use `findPage` or `findPageRsc`; these scan Flight rows by their encoded byte lengths and return `FlightPage`.

```kotlin
import tsuki.util.NextJsData

val page = NextJsData.findPage(html, "slug", "title", "synopsis")
    ?: throw ParseException("Manga Flight row not found", pageUrl)

val slug = page.data.getString("slug")
val title = page.data.getString("title")
val synopsis = page.text("synopsis")
```

`FlightPage.text(key)` resolves plain strings, escaped `$$` values, `$undefined`, and `$<id>` text-chunk references. The overload `text(obj, key)` performs the same lookup on a nested object. It returns `null` for missing, blank, undefined, or unresolved values. `findPage` requires all requested keys to occur in the same JSON row; if a site splits fields across rows, use multiple lookups or `findValue` instead.

For an already-parsed payload list, `findObjectInPayloads` avoids parsing the HTML a second time:

```kotlin
val payloads = NextJsData.extractPayloads(html)
val manga = NextJsData.findObjectInPayloads(payloads, "slug", "title", "chapters")
```

The raw RSC body is not HTML, so use the raw-body methods for that response:

```kotlin
val body = webClient.httpGet(rscUrl, rscHeaders).parseRaw()
val payloads = NextJsData.extractRscPayloads(body)
val chapterPage = NextJsData.findPageRsc(body, "chapterId", "pages")
```

Next.js payload layouts can change. Validate extracted nodes and keep a DOM/API fallback when the source provides one. Do not dump whole account-specific payloads or credentials into logs.

#### Protocol Buffers

UMA's `tsuki.util.Protobuf` helpers use Kotlin serialization Protobuf and a shared `protoInstance`. They support byte arrays, streaming Okio sources/sinks, OkHttp response parsing, request-body encoding, and Base64 strings:

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

// Parses the response and closes its body.
val result: CatalogResponse = webClient.httpGet(apiUrl).parseAsProto()

// Creates an application/protobuf RequestBody.
val requestBody = CatalogRequest(page = 1).toRequestBodyProto()
```

Beginner example:

```kotlin
val bytes = webClient.httpGet("https://example.com/data").body.bytes()
val dto = bytes.decodeProto<MyDto>()
```

Use the response overload to avoid buffering the entire message as a `ByteArray`. The transform overload can adapt a stream before decoding, for example when the service wraps the protobuf payload. The `tsuki.util.protobuf` package contains the streaming encoder/decoder implementation; treat those classes as internals and use the public extensions. MangaPlus is an in-repository example of `parseAsProto()`.

#### Turnstile integration

UMA provides `getTurnstileToken(context, url, siteKey, action?, cData?, theme, timeoutMs)` in `tsuki.util`. It is a source-specific integration for a site whose documented access flow requires Turnstile. It can fail on timeouts, widget errors, or challenges requiring interactive input.

Prefer a site's supported access method and follow its terms and access controls. Do not use this helper to evade a site's protections or access restrictions. Do not add it unless the source's authorized flow requires it. See existing usage in the ProComic parser for how a source passes an obtained token to its own request.

#### Authenticated decryption primitives

UMA's `tsuki.util.decrypt` package contains public primitives for source formats that require authenticated decryption. Use only the primitive matching the site's documented or observed wire format. The key, nonce, tag, associated data, and payload layout must come from the source protocol; authentication failures should be surfaced, not ignored.

- `AesSiv.decrypt(key, ciphertext, vararg aad)` — AES-SIV; the ciphertext starts with the 16-byte synthetic IV/tag, followed by encrypted content.
- `AesGcmSiv.decrypt(key, nonce, ciphertext, aad = emptyByteArray())` — AES-GCM-SIV; the ciphertext ends with its 16-byte tag.
- `ChaCha20Poly1305.decrypt(key, nonce, ct, tag, aad)` — IETF ChaCha20-Poly1305; ciphertext and tag are separate inputs.
- `ChaCha20Poly1305.xdecrypt(key, nonce, ct, tag, aad)` — XChaCha20-Poly1305; uses a 24-byte nonce.
- `Ece.decrypt(payload, ikm)` — RFC 8188 `aes128gcm` payload decryption.
- `Ece.hkdf(ikm, salt, info, length, algorithm)` — HKDF key derivation.
- `Ece.publicKeyToRaw(publicKey)` / `Ece.rawToPublicKey(raw)` — P-256 uncompressed public-key conversion.

```kotlin
import tsuki.util.decrypt.AesGcmSiv

// Values must be parsed or derived according to the source protocol.
val plaintext = AesGcmSiv.decrypt(
    key = keyBytes,
    nonce = nonceBytes,
    ciphertext = ciphertextWithTag,
    aad = associatedData,
)
```

Beginner rule: if you are looking at this section, you are probably not writing a normal parser. Most source work is solved with HTML, JSON, and correct URL generation. Only use decryption helpers when a site truly requires it.

`decrypt.Bytes` and `decrypt.SecretStream` are internal implementation details, not parser-facing utility APIs. Add or modify cryptographic behavior only when the format is understood and covered by tests. Never ship hard-coded private keys or secret sample data.

## History and favourites synchronization

Some sites expose a user's reading history or favourites. If both the site and the Tsuki version used by UMA support sync, advertise the capability and override the appropriate methods. Return `Manga` records containing the information the site provides; some services only provide a title, URL, and chapter name/number, and the app can reconcile partial metadata with local records.

The following shows the newer API shape from Tsuki. Check that the resolved dependency contains `isSyncSupported`, `getHistoryList()`, and `getFavouritesList()` before using it. UMA currently declares Tsuki 1.0.5 in `gradle/libs.versions.toml`, so newer APIs may require a dependency update.

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

## Parser quality and reliability

### URLs and identity

- Use configured domains and URL helpers rather than scattering hard-coded hosts through request code.
- Keep manga and chapter URLs consistent with nearby parsers and the app's expected model conventions.
- Generate stable IDs from a source URL or source ID. Do not use random IDs or list positions.
- Preserve source identity by setting `source = source` on models.

### Missing data and parse errors

- Parse only data the source actually provides; preserve unknown values as unknown.
- Skip malformed optional list entries with `mapNotNull` or `mapChapters`.
- Use clear parse errors for required elements or attributes; avoid returning a plausible-looking but invalid manga.
- Preserve existing manga fields if a detail request omits optional information.
- Test selectors against current markup, not only saved examples.

Beginner example:

```kotlin
val title = doc.selectFirst("h1")?.text() ?: return emptyList()
val cover = doc.selectFirst("img.cover")?.attr("src")
```

If `h1` is missing, it is usually better to skip the item rather than returning a broken object with no title.

### Requests, retries, and rate

- Be respectful of source servers: avoid redundant requests, uncontrolled concurrency, and aggressive retries.
- Handle HTTP errors according to their meaning; do not convert every error into an empty result.
- Use retries only when transient failure is plausible and keep retry counts bounded.
- Prefer existing client configuration and source headers. Avoid hard-coding a user agent unless the source truly requires it.

A common beginner mistake is calling the same page multiple times in a single detail fetch. If a site has a list page and a detail page, fetch them only once each and reuse the result.

### Credentials and sensitive information

- Do not commit credentials, tokens, cookies, private API responses, or personal account data.
- Do not print secrets to logs or exception messages.
- Send credentials only to the intended source endpoint over HTTPS.
- Do not use parser helpers to bypass access controls or a site's terms.

## Testing and building

### Compile and test

Run relevant checks before submitting:

```bash
./gradlew compileKotlin
./gradlew test
```

If a test requires no network access, prefer a deterministic fixture or mock response. Avoid adding tests that depend on a live third-party website being available.

A beginner-friendly validation loop is:

1. Compile code.
2. Add a source or fix.
3. Open the app with local plugin installed.
4. Check one list page, one manga detail page, and one chapter page.
5. If it fails, inspect the URL or HTML and fix only the specific parse step.

### Build the installable plugin

Use the checked-in wrapper:

```bash
# Linux/macOS
chmod +x gradlew
./gradlew buildJar

# Windows
.\gradlew.bat buildJar
```

`buildJar` runs the custom DEX packaging task and creates `build/libs/uma.jar`. It requires an Android SDK with build-tools. The regular `jar` task produces `build/libs/raw.jar` but does not DEX the plugin. The release workflow builds the installable artifact from source changes on `master`; CodeQL compiles Kotlin as part of analysis.

### Test in a Usagi-compatible app

When possible, install the generated `build/libs/uma.jar` using the plugin management flow documented in [README.md](README.md). Verify the behavior affected by your change: for example, list and search results, detail fields, chapter ordering, page URLs, filters, or sync. Do not report device testing if you only compiled locally.

## Submitting changes

Open an issue for a broken parser, a missing source request, a build problem, or a documentation correction. Include the source name, site URL, language, NSFW status if applicable, expected behavior, and reproduction steps. Do not include passwords, API tokens, cookies, or private account data.

For pull requests:

1. Keep the change focused and explain the user-visible effect.
2. Mention which parser/source is affected and relevant behavior (listing, details, chapter order, or page loading).
3. Describe tests/build commands run, or explain why they could not be run.
4. Update this guide or the README when setup or contributor workflows change.
5. Ensure new source parser code follows current UMA conventions and contains no hardcoded test credentials.

A standard PR description can look like:

```text
## Summary
Add parser support for Example Site.

## What changed
- implements manga list search and pagination
- parses manga details and chapters
- adds filter support for tags and status

## Verification
- ./gradlew compileKotlin
- built plugin jar and tested in Usagi
```

## Troubleshooting

**Kotlin symbols are unresolved:** Check imports and the actual Tsuki version resolved by Gradle; sync or reimport the Gradle project and run `./gradlew compileKotlin`.

**`buildJar` cannot find the Android SDK:** Set `sdk.dir` in untracked `local.properties` or set `ANDROID_HOME`, and ensure Android build-tools are installed.

**A parser is not registered:** Confirm its `@MangaSourceParser` annotation and `MangaParserSource` identifier, then rebuild so KSP can generate registration code.

**A selector no longer matches:** Inspect the current website markup with browser developer tools and update the selector. Prefer stable semantic selectors over generated CSS class names.

**The app shows a blank list or invalid title:** Usually this means the CSS selector or the generated URL is wrong. Print the final URL and inspect the element structure in the browser.

**Search works in the browser but not in the parser:** Compare the browser's query parameters with the parser URL. The most common bugs are missing `q`, `page`, or wrong sort parameter names.

## License

Contributions are subject to the repository's [GPL-3.0 license](LICENSE).
