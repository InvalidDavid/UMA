# Contributing to UMA

This guide provides comprehensive instructions and best practices for contributing to the UMA (Usagi Manga Archive) plugin repository. Please **read it carefully** if you are a new contributor or lack experience with the required languages and technologies.

This is a living document that evolves over time. If you find issues or have suggestions, feel free to open an [Issue](https://github.com/InvalidDavid/UMA/issues) or submit a Pull Request.

## Table of Contents

- [Contributing to UMA](#contributing-to-uma)
  - [Table of Contents](#table-of-contents)
  - [Prerequisites](#prerequisites)
    - [Required Technologies](#required-technologies)
    - [Required Tools](#required-tools)
    - [Recommended Tools](#recommended-tools)
  - [Project Structure](#project-structure)
  - [Setup & Build](#setup--build)
  - [Understanding the Architecture](#understanding-the-architecture)
    - [Core Parser Types](#core-parser-types)
    - [Base Classes](#base-classes)
  - [Creating a New Parser](#creating-a-new-parser)
    - [Parser File Organization](#parser-file-organization)
    - [Annotating Your Parser](#annotating-your-parser)
  - [Available Utilities & Helpers](#available-utilities--helpers)
    - [HTTP Utilities](#http-utilities)
    - [JSON Utilities](#json-utilities)
    - [HTML/Parsing Utilities](#htmlparsing-utilities)
    - [Date & Time Utilities](#date--time-utilities)
    - [String & URL Utilities](#string--url-utilities)
    - [Model Utilities](#model-utilities)
  - [Data Models](#data-models)
    - [Manga Model](#manga-model)
    - [MangaChapter Model](#mangachapter-model)
    - [MangaPage Model](#mangapage-model)
    - [MangaTag Model](#mangatag-model)
  - [Implementing Parser Methods](#implementing-parser-methods)
    - [Popular/Latest Manga](#popularlatest-manga)
    - [Search Functionality](#search-functionality)
    - [Manga Details](#manga-details)
    - [Chapters List](#chapters-list)
    - [Page List (Images)](#page-list-images)
  - [Filters & Search](#filters--search)
    - [Filter Types](#filter-types)
    - [Search Capabilities](#search-capabilities)
  - [Advanced Features](#advanced-features)
    - [Authentication](#authentication)
    - [Pagination](#pagination)
    - [Coroutines & Async](#coroutines--async)
    - [Custom Interceptors](#custom-interceptors)
    - [Configuration Keys](#configuration-keys)
  - [Common Patterns & Best Practices](#common-patterns--best-practices)
    - [URL Handling](#url-handling)
    - [Error Handling](#error-handling)
    - [Testing Your Parser](#testing-your-parser)
  - [Building & Testing](#building--testing)
  - [Submitting a Pull Request](#submitting-a-pull-request)
  - [Troubleshooting](#troubleshooting)

## Prerequisites

Before you start contributing to UMA, ensure you have the following:

### Required Technologies

- **Kotlin** - The primary language for this project (Java 11+ compatible)
  - Learn from: [Kotlin Official Documentation](https://kotlinlang.org/docs/home.html)
  - Key concepts: coroutines, extension functions, DSLs, sealed classes
  
- **Android Development Knowledge**
  - Basic understanding of Android components
  - Familiarity with OkHttp and network requests
  
- **Web Scraping & Parsing**
  - **HTML/CSS** - Understanding DOM structure and CSS selectors
  - **JSoup** - HTML parsing library ([JSoup Documentation](https://jsoup.org/))
  - **JSON** - Parsing and working with JSON APIs
  - **Regex** - Pattern matching for data extraction

- **API Integration**
  - RESTful API concepts
  - HTTP methods (GET, POST, PUT)
  - Headers and request/response handling

### Required Tools

- **Java 11 or higher** - JDK installation required
- **Android Studio** (Community Edition) or **IntelliJ IDEA** (Community Edition)
- **Gradle 8.0+** - Build automation (included in project)
- **Git** - Version control
- **Usagi App** (v0.0.32-beta2 or higher) - For testing locally

### Recommended Tools

- **Browser DevTools** (F12) - Inspect website structure and network requests
- **Postman** or **Insomnia** - Test API endpoints
- **Online JSoup Selector Tester** - Debug CSS selectors
- **Regex Tester** - Test and validate regex patterns
- **JSON Formatter** - Validate and format JSON responses

## Project Structure

```
UMA/
├── src/main/kotlin/org/koitharu/kotatsu/parsers/
│   ├── parsers/                 # Base parser implementations (Madara, Liliana, etc.)
│   ├── site/
│   │   ├── kotatsu/             # Kotatsu-based parsers
│   │   │   ├── all/             # Multi-language sources (MangaDex, MangaPlus, etc.)
│   │   │   ├── en/              # English sources
│   │   │   ├── es/              # Spanish sources
│   │   │   ├── pt/              # Portuguese sources
│   │   │   └── ...              # Other language codes
│   │   └── tachiyomi/           # Tachiyomi-compatible parsers
│   ├── util/                    # Utility functions and helpers
│   ├── model/                   # Data models (Manga, Chapter, Page, etc.)
│   └── core/                    # Core parser base classes
├── plugins-ksp/                 # KSP annotation processor (code generation)
├── buildSrc/                    # Build configuration and custom tasks
├── gradle/                      # Gradle wrapper and configuration
└── README.md, CONTRIBUTING.md

```

## Setup & Build

### Clone the Repository

```bash
git clone https://github.com/InvalidDavid/UMA.git
cd UMA
```

### Build the Project

**Linux/macOS:**
```bash
chmod +x gradlew
./gradlew buildJar
```

**Windows:**
```bash
gradlew.bat buildJar
```

**Using Android Studio:**
1. Open Android Studio
2. File → Open → Select UMA directory
3. Wait for Gradle to sync
4. Run `buildJar` task (Build → Gradle Tasks → buildJar)

### Output

- Raw JAR: `build/libs/raw.jar`
- DEXed JAR (for Usagi): `build/libs/uma.jar`

## Understanding the Architecture

### Core Parser Types

UMA uses several parser base classes to handle different website structures:

#### 1. **FlexibleMangaParser** (API-based sources)
Used for JSON/REST API sources. Example: MangaDex

```kotlin
@MangaSourceParser("MANGADEXORG", "MangaDex")
internal class MangaDexParser(context: MangaLoaderContext) : 
    FlexibleMangaParser(context, MangaParserSource.MANGADEXORG) {
    // Implementation
}
```

#### 2. **PagedMangaParser** (HTML-based sources)
Used for HTML scraping with pagination. Example: Madara CMS sites

```kotlin
internal abstract class MadaraParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String,
    pageSize: Int = 12,
) : PagedMangaParser(context, source, pageSize) {
    // Implementation
}
```

#### 3. **SimpleMangaParser** (Simple HTML sources)
Lightweight parser for straightforward HTML structures.

```kotlin
@MangaSourceParser("MYSOURCE", "My Source")
internal class MyParser(context: MangaLoaderContext) : 
    SimpleMangaParser(context, MangaParserSource.MYSOURCE) {
    // Implementation
}
```

### Base Classes

| Base Class | Use Case | Key Features |
|-----------|----------|--------------|
| `FlexibleMangaParser` | JSON/REST APIs | Advanced filtering, sort orders, async operations |
| `PagedMangaParser` | HTML pagination | Tag-based filtering, search support, standard sorting |
| `SimpleMangaParser` | Simple HTML | Lightweight, minimal configuration |
| `MangaParserAuthProvider` | Authentication | Login support, cookie handling |

## Creating a New Parser

### Parser File Organization

For a new parser, use this directory structure:

```
src/main/kotlin/org/koitharu/kotatsu/parsers/site/kotatsu/en/mysource/
├── MySource.kt          # Main parser class
├── Dto.kt               # (Optional) Data transfer objects for JSON
├── Filters.kt           # (Optional) Custom filters
└── Constants.kt         # (Optional) Constants and helpers
```

### Annotating Your Parser

Always annotate your parser class with `@MangaSourceParser`:

```kotlin
@MangaSourceParser("MYSOURCEID", "My Source Display Name")
internal class MySourceParser(context: MangaLoaderContext) : 
    PagedMangaParser(context, MangaParserSource.MYSOURCEID) {
    
    override val domain = "example.com"
    
    // Required implementations
}
```

**Annotation Parameters:**
- First parameter: Unique source ID (UPPERCASE, no spaces)
- Second parameter: Display name shown in the app

## Available Utilities & Helpers

UMA provides extensive utilities in `util/` and extensions throughout the codebase.

### HTTP Utilities

#### Basic HTTP Requests

```kotlin
// GET request
val response = webClient.httpGet(url)

// POST request with parameters
val response = webClient.httpPost(url, mapOf("key" to "value"))

// POST with form data
val response = webClient.httpPost(url, "raw=body&data")

// Custom headers
val headers = mapOf(
    "User-Agent" to "Mozilla/5.0...",
    "Referer" to domain,
    "X-Requested-With" to "XMLHttpRequest"
)
val response = webClient.httpGet(url, headers)
```

#### Response Handling

```kotlin
// Parse as HTML
val doc = response.parseHtml()

// Parse as JSON
val json = response.parseJson()

// Get raw body
val body = response.body.string()

// Close response properly
response.use {
    // Process response
}
```

### JSON Utilities

#### Parsing JSON

```kotlin
import org.json.JSONObject
import org.json.JSONArray

// Parse response
val json = webClient.httpGet(url).parseJson()

// Access objects and arrays
val data = json.getJSONArray("data")
val item = data.getJSONObject(0)

// Safe access
val title = item.optString("title", "Unknown")
val count = item.optInt("count", 0)

// Get or null
val description = item.getStringOrNull("description")
```

#### Working with JSONArray

```kotlin
val array = json.getJSONArray("items")

// Iterate
repeat(array.length()) { i ->
    val obj = array.getJSONObject(i)
    // Process obj
}

// Map
val list = array.mapJSON { obj ->
    obj.getString("title")
}

// Map to Set
val set = array.mapJSONToSet { obj ->
    MangaTag(
        key = obj.getString("id"),
        title = obj.getString("name"),
        source = source,
    )
}

// Flatten (merge multiple JSON objects)
val flattened = jsonArray.flatten()
```

#### Serialization with Kotlinx

```kotlin
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
class MangaDto(
    val id: Int,
    val title: String,
    @SerialName("cover_url")
    val coverUrl: String,
)

// Parse
val dto = json.decodeFromString<MangaDto>(jsonString)

// Serialize
val jsonStr = Json.encodeToString(dto)
```

### HTML/Parsing Utilities

#### JSoup Selection

```kotlin
// Select first element
val title = doc.selectFirst("h1")?.text()

// Select all matching
val items = doc.select("div.manga-item")

// CSS selectors
val chapters = doc.select("div.chapter > a")

// Complex selectors
val status = doc.selectFirst("span:contains(Status)~div")?.text()

// Safe selection
val element = doc.selectFirstOrThrow("required.selector")
```

#### URL Extraction

```kotlin
// Absolute URL
val absoluteUrl = element.absUrl("href")

// Relative URL conversion
val relativeUrl = element.attrAsRelativeUrl("href")

// Parse and build URLs
val httpUrl = url.toHttpUrl()
val newUrl = httpUrl.newBuilder()
    .addQueryParameter("page", "1")
    .build()
```

#### Text Extraction

```kotlin
// Text with children
val text = element.text()

// Own text only (no children)
val ownText = element.ownText()

// Text or null
val value = element.textOrNull()

// Title case conversion
val title = text.toTitleCase(locale)
```

#### HTML Parsing Patterns

```kotlin
// Parse HTML fragment from JSON
val html = Jsoup.parseBodyFragment(htmlString, baseUrl)

// Get parent elements
val parent = element.parents().firstOrNull { it.id() == "content" }

// Filter elements
val filtered = elements.filter { it.hasAttr("data-id") }

// Map elements
val mangas = elements.mapNotNull { el ->
    val url = el.attr("href") ?: return@mapNotNull null
    Manga(url = url, title = el.text())
}
```

### Date & Time Utilities

#### Simple Date Parsing

```kotlin
import java.text.SimpleDateFormat
import java.util.*

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

// Parse date
val date = dateFormat.parse("2024-01-15")
val timestamp = date?.time ?: 0L

// Safe parsing
val safeDate = dateFormat.parseSafe("invalid date") // Returns 0L

// With timezone
val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.ENGLISH)
val timestamp = formatter.parseSafe(dateString)
```

#### Relative Date Parsing

```kotlin
// Parse relative dates like "2 days ago"
fun parseRelativeDate(text: String): Long {
    val number = Regex("""(\d+)""").find(text)?.value?.toIntOrNull() ?: return 0
    val cal = Calendar.getInstance()
    
    return when {
        text.contains("hour") -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
        text.contains("day") -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
        text.contains("week") -> cal.apply { add(Calendar.DAY_OF_MONTH, -number * 7) }.timeInMillis
        text.contains("month") -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
        else -> 0
    }
}
```

### String & URL Utilities

#### String Encoding/Decoding

```kotlin
// URL encode
val encoded = string.urlEncoded()

// Decode from Base64
val decoded = context.decodeBase64(base64String)

// Encode to Base64
val encoded = context.encodeBase64(byteArray)

// Hex conversion
fun String.decodeHex(): ByteArray {
    check(length % 2 == 0) { "Must have an even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
```

#### URL Manipulation

```kotlin
// Generate unique ID
val uid = generateUid(url)

// Build URLs
val url = buildString {
    append("https://")
    append(domain)
    append("/manga/")
    append(mangaId)
}

// URL normalization
val normalized = url.removeSuffix('/').substringAfterLast('/')

// Relative to absolute
val absoluteUrl = relativeUrl.toAbsoluteUrl(domain)
val relativeUrl = absoluteUrl.toRelativeUrl(domain)
```

### Model Utilities

#### Building Models

```kotlin
// Create Manga
val manga = Manga(
    id = generateUid(url),
    url = url,
    publicUrl = url.toAbsoluteUrl(domain),
    title = title,
    altTitles = setOf(altTitle1, altTitle2),
    coverUrl = coverUrl,
    largeCoverUrl = largeCoverUrl,
    rating = rating,
    tags = tagSet,
    authors = authorSet,
    state = MangaState.ONGOING,
    contentRating = ContentRating.SAFE,
    description = description,
    chapters = chaptersList,
    source = source,
)

// Create MangaChapter
val chapter = MangaChapter(
    id = generateUid(chapterId),
    url = chapterUrl,
    title = chapterTitle,
    number = 1.5f,
    volume = 1,
    branch = "English",
    scanlator = "Scan Team",
    uploadDate = timestamp,
    source = source,
)

// Create MangaPage
val page = MangaPage(
    id = generateUid(imageUrl),
    url = imageUrl,
    preview = thumbnailUrl,
    source = source,
)

// Create MangaTag
val tag = MangaTag(
    key = "action",
    title = "Action",
    source = source,
)
```

## Data Models

### Manga Model

```kotlin
data class Manga(
    val id: Long,                          // Unique identifier
    val title: String,                     // Manga title
    val altTitles: Set<String> = emptySet(), // Alternative titles
    val url: String,                       // Relative URL to manga page
    val publicUrl: String = "",            // Full URL for user access
    val rating: Float = RATING_UNKNOWN,    // 0-10, RATING_UNKNOWN for unknown
    val contentRating: ContentRating? = null, // SAFE, ADULT, SUGGESTIVE
    val coverUrl: String? = null,          // Cover thumbnail
    val largeCoverUrl: String? = null,     // Full resolution cover
    val description: String? = null,       // Manga synopsis
    val tags: Set<MangaTag> = emptySet(),  // Genre/tag set
    val authors: Set<String> = emptySet(), // Author names
    val state: MangaState? = null,         // ONGOING, FINISHED, ABANDONED, etc.
    val chapters: List<MangaChapter>? = null, // Chapter list
    val source: MangaParserSource,         // Source identifier
)
```

### MangaChapter Model

```kotlin
data class MangaChapter(
    val id: Long,                    // Unique ID
    val url: String,                 // Relative URL to chapter
    val title: String? = null,       // Chapter title/name
    val number: Float = 0f,          // Chapter number (can be decimal)
    val volume: Int = 0,             // Volume number
    val branch: String? = null,      // Scanlation branch/translation
    val scanlator: String? = null,   // Scanlation group
    val uploadDate: Long = 0L,       // Unix timestamp in milliseconds
    val source: MangaParserSource,   // Source identifier
)
```

### MangaPage Model

```kotlin
data class MangaPage(
    val id: Long,                    // Unique ID
    val url: String,                 // Image URL
    val preview: String? = null,     // Preview/thumbnail URL
    val source: MangaParserSource,   // Source identifier
)
```

### MangaTag Model

```kotlin
data class MangaTag(
    val key: String,                 // Unique tag key (used in queries)
    val title: String,               // Display title
    val source: MangaParserSource,   // Source identifier
)
```

## Implementing Parser Methods

### Popular/Latest Manga

```kotlin
override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
    val url = buildString {
        append("https://")
        append(domain)
        append("/manga/?page=")
        append(page + 1)
        
        when (order) {
            SortOrder.UPDATED -> append("&sort=latest")
            SortOrder.POPULARITY -> append("&sort=popular")
            SortOrder.NEWEST -> append("&sort=new")
            SortOrder.ALPHABETICAL -> append("&sort=az")
            else -> {}
        }
    }
    
    val doc = webClient.httpGet(url).parseHtml()
    return doc.select("div.manga-item").map { element ->
        val href = element.selectFirstOrThrow("a").attrAsRelativeUrl("href")
        Manga(
            id = generateUid(href),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            title = element.selectFirst("h3")?.text().orEmpty(),
            coverUrl = element.selectFirst("img")?.src(),
            source = source,
        )
    }
}
```

### Search Functionality

```kotlin
override suspend fun getList(
    page: Int,
    query: String,
    filter: MangaListFilter,
): List<Manga> {
    val url = buildString {
        append("https://")
        append(domain)
        append("/search/?q=")
        append(query.urlEncoded())
        append("&page=")
        append(page)
        
        filter.tags.forEach { tag ->
            append("&genre=")
            append(tag.key)
        }
    }
    
    val doc = webClient.httpGet(url).parseHtml()
    return parseMangaList(doc)
}
```

### Manga Details

```kotlin
override suspend fun getDetails(manga: Manga): Manga {
    val fullUrl = manga.url.toAbsoluteUrl(domain)
    val doc = webClient.httpGet(fullUrl).parseHtml()
    
    val chapters = async { getChapters(doc) }
    
    return manga.copy(
        title = doc.selectFirst("h1")?.text() ?: manga.title,
        description = doc.selectFirst("div.synopsis")?.text(),
        coverUrl = doc.selectFirst("img.cover")?.src(),
        tags = doc.select("a.tag").mapNotNullToSet { a ->
            MangaTag(
                key = a.attr("href").substringAfterLast('/'),
                title = a.text().toTitleCase(),
                source = source,
            )
        },
        state = when (doc.selectFirst(".status")?.text()?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            else -> null
        },
        chapters = chapters.await(),
    )
}
```

### Chapters List

```kotlin
suspend fun getChapters(doc: Document): List<MangaChapter> {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)
    
    return doc.select("tr.chapter").mapChapters(reversed = true) { i, tr ->
        val a = tr.selectFirstOrThrow("a")
        val href = a.attrAsRelativeUrl("href")
        
        MangaChapter(
            id = generateUid(href),
            url = href,
            title = a.text(),
            number = i + 1f,
            uploadDate = dateFormat.parseSafe(
                tr.selectFirst("td.date")?.text()
            ),
            source = source,
        )
    }
}
```

### Page List (Images)

```kotlin
override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
    val fullUrl = chapter.url.toAbsoluteUrl(domain)
    val doc = webClient.httpGet(fullUrl).parseHtml()
    
    return doc.select("img.page").mapIndexed { index, img ->
        MangaPage(
            id = generateUid(img.src()),
            url = img.absUrl("src"),
            source = source,
        )
    }
}
```

## Filters & Search

### Filter Types

```kotlin
override suspend fun getFilterOptions(): MangaListFilterOptions {
    return MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.ABANDONED,
            MangaState.PAUSED,
        ),
        availableContentRating = EnumSet.of(
            ContentRating.SAFE,
            ContentRating.ADULT,
        ),
    )
}

suspend fun fetchAvailableTags(): Set<MangaTag> {
    val doc = webClient.httpGet("https://$domain/genres/").parseHtml()
    return doc.select("a.genre-link").mapNotNullToSet { a ->
        MangaTag(
            key = a.attr("data-id"),
            title = a.text().toTitleCase(),
            source = source,
        )
    }
}
```

### Search Capabilities

```kotlin
override val filterCapabilities: MangaListFilterCapabilities
    get() = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isYearSupported = true,
        isAuthorSearchSupported = false,
    )
```

## Advanced Features

### Authentication

```kotlin
internal class AuthenticatedParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MYSOURCE),
    MangaParserAuthProvider {
    
    override val authUrl: String
        get() = "https://$domain/login"
    
    override suspend fun isAuthorized(): Boolean {
        return context.cookieJar.getCookies(domain).any {
            it.name == "auth_token"
        }
    }
    
    override suspend fun getUsername(): String {
        val body = webClient.httpGet("https://$domain/profile/").parseHtml().body()
        return body.selectFirst(".username")?.text()
            ?: throw AuthRequiredException(source)
    }
}
```

### Pagination

```kotlin
// Set pagination starting page
init {
    paginator.firstPage = 0  // Start from page 0
    searchPaginator.firstPage = 1  // Or start from page 1
}

override suspend fun getListPage(page: Int, ...): List<Manga> {
    val pageNumber = page + 1  // If firstPage = 0, convert to 1-based
    // or use page directly if firstPage = 1
}
```

### Coroutines & Async

```kotlin
override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
    val fullUrl = manga.url.toAbsoluteUrl(domain)
    
    // Run multiple requests concurrently
    val docDeferred = async { webClient.httpGet(fullUrl).parseHtml() }
    val chaptersDeferred = async { loadChapters(manga.url) }
    
    val doc = docDeferred.await()
    val chapters = chaptersDeferred.await()
    
    // Process and return
    manga.copy(
        description = doc.selectFirst(".desc")?.text(),
        chapters = chapters,
    )
}
```

### Custom Interceptors

```kotlin
override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
    return addInterceptor { chain ->
        val request = chain.request()
        val newRequest = request.newBuilder()
            .header("User-Agent", "Custom/1.0")
            .header("Accept-Language", "en-US")
            .build()
        chain.proceed(newRequest)
    }
}
```

### Configuration Keys

```kotlin
override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
    super.onCreateConfig(keys)
    
    keys.add(ConfigKey.UserAgent("MySource/1.0"))
    keys.add(ConfigKey.Domain(domain))
    
    val qualityKey = ConfigKey.PreferredImageServer(
        presetValues = mapOf(
            "high" to "High Quality",
            "med" to "Medium Quality",
        ),
        defaultValue = "high",
    )
    keys.add(qualityKey)
}
```

## Common Patterns & Best Practices

### URL Handling

```kotlin
// ✅ CORRECT: Using relative URLs
val manga = Manga(
    url = "/manga/slug-123",
    publicUrl = "/manga/slug-123".toAbsoluteUrl(domain),
)

// ❌ AVOID: Hardcoding full URLs
val manga = Manga(
    url = "https://example.com/manga/slug-123"
)

// ✅ CORRECT: Building complex URLs
val url = buildString {
    append("https://api.")
    append(domain)
    append("/search?q=")
    append(query.urlEncoded())
    filter.tags.forEach { tag ->
        append("&tags[]=")
        append(tag.key)
    }
}
```

### Error Handling

```kotlin
// Handle HTTP errors
try {
    val response = webClient.httpGet(url).parseHtml()
    return parseMangaList(response)
} catch (e: HttpStatusException) {
    when (e.statusCode) {
        404 -> return emptyList()  // No results
        429 -> throw ParseException("Rate limited", url)
        else -> throw ParseException("HTTP ${e.statusCode}", url)
    }
}

// Safe parsing
val title = doc.selectFirst("h1")?.text() ?: "Unknown"
val count = element.attr("data-count").toIntOrNull() ?: 0
```

### Testing Your Parser

```kotlin
// Manual testing with main function
suspend fun main() {
    val context = MangaLoaderContext(...)
    val parser = MySourceParser(context)
    
    val mangas = parser.getListPage(0, SortOrder.UPDATED, MangaListFilter())
    println("Found ${mangas.size} manga")
    
    if (mangas.isNotEmpty()) {
        val details = parser.getDetails(mangas[0])
        println("Title: ${details.title}")
        println("Chapters: ${details.chapters?.size ?: 0}")
    }
}
```

## Building & Testing

### Build Commands

```bash
# Build JAR
./gradlew buildJar

# Build without DEX
./gradlew jar

# Clean build
./gradlew clean buildJar

# Run tests
./gradlew test

# Lint check
./gradlew lintRelease
```

### Installation for Testing

1. Build the project: `./gradlew buildJar`
2. Transfer `build/libs/uma.jar` to your device
3. In Usagi App:
   - Explore → Manage Sources → Manage Plugins
   - Click "+" → Import from local storage
   - Select the JAR file
4. Enable the plugin and test

### Debugging

- Use `println()` and check logcat: `adb logcat | grep "UMA"`
- Add breakpoints in Android Studio debugger
- Check network requests with OkHttp logging

## Submitting a Pull Request

### Before Submitting

1. **Verify your code compiles**
   ```bash
   ./gradlew build
   ```

2. **Test your parser thoroughly**
   - Popular/Latest manga loads
   - Search functionality works
   - Chapter list displays correctly
   - Images load without errors

3. **Follow code style**
   - Use 4-space indentation
   - Remove unused imports
   - Keep lines under 120 characters where possible

4. **Commit message format**
   ```
   feat: add MySource parser
   
   - Supports manga listing and search
   - Handles chapter pagination
   - Full image support
   ```

### PR Checklist

- [ ] Parser compiles without errors
- [ ] Tested on device/emulator
- [ ] New parser follows existing conventions
- [ ] DTOs use @Serializable correctly
- [ ] No hardcoded test URLs or API keys
- [ ] No unnecessary dependencies added
- [ ] Comments added for complex logic
- [ ] Parser name is accurate and follows capitalization

## Troubleshooting

### Common Issues

**"Cannot resolve symbol" errors**
- Run `./gradlew clean build`
- Invalidate Android Studio caches: File → Invalidate Caches

**"Gradle build failed"**
- Update Gradle: `./gradlew wrapper --gradle-version 8.1`
- Delete `build/` and try again

**"Parser not appearing in Usagi"**
- Check if JAR was built: `ls build/libs/uma.jar`
- Verify `@MangaSourceParser` annotation is present
- Check app version (v0.0.32-beta2+)

**"HTTP 403/Cloudflare errors"**
- Add User-Agent header
- Implement interceptor for headers
- Some sites require extra delay between requests

**"Parse failed - element not found"**
- Website HTML structure may have changed
- Use browser DevTools to inspect current structure
- Update CSS selectors accordingly

### Getting Help

- Check existing parsers for patterns
- Review [JSoup Documentation](https://jsoup.org/)
- Ask in [Discord Server](https://discord.gg/CyJeVDP7Cw)
- Open an issue with error logs

---

**Thank you for contributing to UMA!** Your work makes manga reading better for everyone. 🎉
