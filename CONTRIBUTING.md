# Contributing to UMA

Thanks for contributing to UMA. This guide describes the repository as it exists today and how to build, test, and extend its parsers.

UMA is a Kotlin plugin for the Usagi App and compatible Kotatsu forks. Its parsers are built on the Tsuki library.

## Project layout

```text
UMA/
├── src/main/kotlin/tsuki/
│   ├── parsers/          # Shared parser base classes
│   ├── site/             # Source parsers, organized by language/region
│   └── util/             # Project-specific helpers
├── plugins-ksp/          # KSP processor for parser registration
├── buildSrc/             # Custom Gradle tasks, including DEX packaging
├── gradle/               # Wrapper and dependency version catalog
├── .github/workflows/    # Build/release and CodeQL workflows
├── build.gradle.kts
├── README.md
└── CONTRIBUTING.md
```

Source implementations are under `src/main/kotlin/tsuki/site/`. Current language folders include `all`, `ar`, `de`, `en`, `es`, `fr`, `id`, `pt`, `ru`, `vi`, and `zh`. Shared parser implementations and helpers live under `tsuki/parsers/` and `tsuki/util/`.

## Prerequisites

- Git
- JDK 17 or newer for local development (the Kotlin/JVM toolchain is configured for Java 11)
- Android SDK with Android build-tools installed for the DEX task
- Android Studio or IntelliJ IDEA is recommended

The `dexJar` task locates the SDK using `sdk.dir` in `local.properties` or the `ANDROID_HOME` environment variable. For example:

```properties
sdk.dir=/path/to/your/Android/Sdk
```

Do not commit `local.properties` or credentials.

## Build and test

Use the checked-in Gradle wrapper:

```bash
# Linux/macOS
chmod +x gradlew
./gradlew compileKotlin
./gradlew test
./gradlew buildJar
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

`buildJar` runs the custom DEX packaging task and creates the installable plugin at `build/libs/uma.jar`. It requires Android SDK build-tools. The regular `jar` task produces `build/libs/raw.jar` and does not perform the DEX step.

CI currently runs Kotlin compilation through CodeQL. The release workflow builds `uma.jar` from changes under `src/main/kotlin/tsuki/` on `master` and publishes it as a GitHub Release asset.

## Adding or updating a parser

1. Put a source parser in the appropriate directory under `src/main/kotlin/tsuki/site/`, usually grouped by language.
2. Reuse an existing shared parser in `tsuki/parsers/` when its behavior matches the site.
3. Register the source with `@MangaSourceParser` and use the matching `MangaParserSource` identifier.
4. Implement the parser methods needed by the site, such as listing, details, and chapter pages.
5. Use Tsuki and UMA utilities rather than duplicating common URL, HTML, and chapter handling.
6. Run `./gradlew compileKotlin` and relevant tests. Run `./gradlew buildJar` when Android SDK tooling is available.

A source parser is generally placed in a file such as:

```text
src/main/kotlin/tsuki/site/en/ExampleSource.kt
```

Use the actual parser base classes and signatures available in the Tsuki version resolved by this repository. Do not copy examples based on older Kotatsu parser package paths; the current source package is `tsuki`.

## Useful Tsuki utilities

Tsuki utilities are available from `tsuki.util`. Common examples in this repository include `urlBuilder`, `parseHtml`, `mapChapters`, `toAbsoluteUrl`, `generateUid`, and safe HTML selection/attribute helpers. Import only the helpers you use.

For example, `urlBuilder()` builds URLs using the parser's configured domain, while `mapChapters` provides consistent chapter indexing and skips invalid entries:

```kotlin
import tsuki.util.mapChapters
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlBuilder

override suspend fun getHistoryList(): List<Manga> {
    val url = urlBuilder()
        .addPathSegments("user/history")
        .build()
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
                val chapterUrl = chapterLink.attr("href").toAbsoluteUrl(domain)
                val number = chapterTitle.substringAfter("Chapter ").toFloatOrNull() ?: 0f

                MangaChapter(
                    title = chapterTitle,
                    url = chapterUrl,
                    number = number,
                    source = source,
                )
            },
        )
    }
}
```

The sample demonstrates the newer sync-style model constructors and utility usage. Constructor requirements can vary with the Tsuki version: this repository currently declares Tsuki `1.0.5` in `gradle/libs.versions.toml`. If a shown API does not resolve, check the version actually selected by Gradle and the matching Tsuki API before adapting the example.

## History and favourites synchronization

Some sites expose a user's reading history or favourites. If the Tsuki version used by the parser provides the synchronization methods, advertise support through `filterCapabilities` and override the relevant list methods. Return `Manga` items with the information the site provides; details such as chapters may be incomplete, and the app can reconcile them with its local records.

```kotlin
override val filterCapabilities: MangaListFilterCapabilities
    get() = super.filterCapabilities.copy(
        isSyncSupported = true,
    )

override suspend fun getHistoryList(): List<Manga> {
    // Fetch the account's history and map its entries to Manga objects.
    return super.getHistoryList()
}

override suspend fun getFavouritesList(): List<Manga> {
    // Fetch the account's favourites and map its entries to Manga objects.
    return super.getFavouritesList()
}
```

Implement only the sync methods supported by the site and the resolved Tsuki API. Do not advertise synchronization if the site does not provide the required data or the implementation is only a placeholder.

### Asking the user for a source token

Use a `ConfigKey.UserInput` for a source-specific value, register it in `onCreateConfig`, and raise `InputRequiredException` when a required value is missing. This asks the app to prompt the user; do not hardcode personal tokens or credentials.

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

    // Use the token only as required for the site's request, then parse pages.
    TODO("Implement the site's page request")
}
```

The exception's `key` must match the input identifier expected by the app so the supplied value can be stored and read on the next attempt. Verify this behavior against the Tsuki version and app integration in use.

## Parser and data quality guidance

- Build URLs with the configured `domain` and Tsuki URL helpers; avoid scattering hard-coded host names through request code.
- Keep manga and chapter URLs consistent with the parser's existing conventions and the app's expected identifiers.
- Use `mapChapters` when parsing chapter collections, and return `null` for malformed entries rather than constructing invalid records.
- Preserve source identity by setting `source = source` on returned models.
- Parse only data the site actually provides; use unknown/default values where appropriate rather than inventing metadata.
- Be respectful of source servers, handle failures clearly, and avoid logging credentials or private user data.

## Pull requests and issue reports

Keep changes focused and explain what changed and how you tested it. For parser changes, include the source name and relevant behavior (for example, listing, details, chapter order, or page loading). Do not include private account data or API tokens in issue reports or pull requests.

For new source requests or parser bugs, open an issue in [InvalidDavid/UMA](https://github.com/InvalidDavid/UMA/issues) and include the source name, site URL, language, NSFW status if applicable, and steps or examples to reproduce the issue.

## License

Contributions are subject to the repository's [GPL-3.0 license](LICENSE).
