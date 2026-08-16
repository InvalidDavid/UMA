# en-ru manga sources plugin

This repository builds the `en-ru` Tsuki manga sources plugin. It contains reusable parser families and source adapters intended for English, Russian, and locale-neutral catalogs.

## Requirements

- Android Studio or IntelliJ IDEA (Community Edition is enough)
- Android SDK 35 or later (if not using IDE)
- Java 17 is required to run the build; a Java 11 compiler toolchain is also required

## Usage

1. Open Terminal on root folder, build this project:

    On Linux & Unix system:
	```bash
	chmod +x gradlew && ./gradlew buildJar
 	```

    On Windows system:
    ```cmd
    .\gradlew.bat buildJar
    ```

**More simply, just run `buildJar` task in Android Studio / IntelliJ IDEA and dex it after building.**

The installable artifact is written to `build/libs/en-ru.jar`. The generated catalog contains 90 sources: 51 English, 27 Russian, 11 locale-neutral, and 1 retained Chinese source.

## Plugin identity

`plugin.id` in `gradle.properties` is the single plugin identity. It must use lowercase kebab-case. The Gradle project name, raw and installable JAR filenames, generated catalog summary, and GitHub release upload path are derived from this value.

## Adding a source

1. Add a `@MangaSourceParser` adapter under `src/main/kotlin/tsuki/site/` with an explicit `en` or `ru` locale, or an empty locale only when the source genuinely serves multiple languages.
2. Reuse a source-family parser when the target site shares its engine. Keep site variation in cohesive configuration instead of copying the reading flow.
3. Add deterministic fixtures for list, details, chapters, and pages under `src/test/resources/fixtures/`.
4. Add a source contract test that exercises the public `MangaParser` interface through `FixtureMangaLoaderContext`.
5. Run `gradlew test` and `gradlew buildJar` with Java 17 plus a Java 11 compiler toolchain.

Tests never contact live source websites. Every unregistered fixture request fails explicitly.

### Opt-in live source smoke test

Live diagnostics are disabled unless `LIVE_SOURCE` is set. The smoke test can run list, details, chapters, and pages against the current website without making the normal test suite network-dependent.

```powershell
$env:LIVE_SOURCE="MANGALIB"
$env:LIVE_SORT="NEWEST"
$env:LIVE_STAGE="PAGES"
./gradlew.bat :test --tests tsuki.LiveSourceSmokeTest
```

Optional variables:

- `LIVE_QUERY` sends a source search query.
- `LIVE_EXPECT_TITLE` selects one exact title from the returned list.
- `LIVE_STAGE` stops after `LIST`, `DETAILS`, `CHAPTERS`, or `PAGES`.

The JVM smoke context cannot evaluate JavaScript, redraw images, or complete interactive browser challenges. `LiveCatalogAuditTest` can crawl every source for selected locales and write a TSV report under `build/reports/`; use an Android consumer and ADB logs for sources that require browser or Android runtime capabilities.

## Manual consumer verification

Automated checks do not install the plugin into a consumer application. Before publishing, install `build/libs/en-ru.jar` through the consumer's normal plugin installation flow, reload the plugin catalog, confirm that all 90 entries are listed without Vietnamese sources, and open representative English and Russian sources through list, details, chapter, and page-image loading.


## Credits

- Thanks to [KotatsuApp](https://github.com/KotatsuApp) for providing some parsers and the core library.
- Thanks to [Keiyoushi](https://github.com/Keiyoushi) for providing some extensions code on GitHub.

### License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

<div align="left">

You may copy, distribute and modify the software as long as you track changes/dates in source files. Any modifications to or software including (via compiler) GPL-licensed code must also be made available under the GPL along with build & install instructions. See [LICENSE](./LICENSE) for more details.

</div>
