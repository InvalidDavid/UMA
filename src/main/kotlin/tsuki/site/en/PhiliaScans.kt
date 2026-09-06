package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
import tsuki.network.OkHttpWebClient
import tsuki.network.WebClient

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.json.getStringOrNull
import tsuki.util.json.mapJSONNotNull
import tsuki.util.nullIfEmpty
import tsuki.util.parseJson
import tsuki.util.parseJsonArray
import tsuki.util.parseSafe
import tsuki.util.runCatchingCancellable
import tsuki.util.toAbsoluteUrl

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO

@MangaSourceParser("PHILIASCANS", "Philia Scans", "en")
internal class PhiliaScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.PHILIASCANS, pageSize = 20), Interceptor {

    override val configKeyDomain = ConfigKey.Domain("philiascans.org")
    private val apiUrl get() = "https://$domain/api"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val webClient: WebClient by lazy {
        OkHttpWebClient(
            context.httpClient.newBuilder()
                .addInterceptor(this)
                .build(),
            source
        )
    }

    override fun getRequestHeaders(): Headers {
        val builder = super.getRequestHeaders().newBuilder()
            .set("Referer", "https://$domain/")
        val ua = config[userAgentKey].takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"
        builder["User-Agent"] = ua
        return builder.build()
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.POPULARITY_WEEK,
        SortOrder.RATING, SortOrder.NEWEST, SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags(),
        availableStates = EnumSet.of(
            MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA, ContentType.COMICS,
        ),
    )
    
    @Volatile
    private var tagsCache: Set<MangaTag>? = null

    private suspend fun fetchTags(): Set<MangaTag> {
        tagsCache?.let { return it }
        val array = webClient.httpGet("$apiUrl/genres").parseJsonArray()
        val tags = array.mapJSONNotNull { item ->
            val key = item.getStringOrNull("slug") ?: return@mapJSONNotNull null
            val title = item.getStringOrNull("name") ?: return@mapJSONNotNull null
            MangaTag(key = key, title = title, source = source)
        }.toSet()
        tagsCache = tags
        return tags
    }
    
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "$apiUrl/manga".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("perPage", pageSize.toString())
            filter.query?.nullIfEmpty()?.let { addQueryParameter("q", it) }

            val (orderBy, direction) = when (order) {
                SortOrder.UPDATED -> null to "desc"
                SortOrder.POPULARITY -> "views" to "desc"
                SortOrder.POPULARITY_WEEK -> "trending" to "desc"
                SortOrder.RATING -> "rating" to "desc"
                SortOrder.NEWEST -> "added" to "desc"
                SortOrder.ALPHABETICAL -> "title" to "asc"
                SortOrder.ALPHABETICAL_DESC -> "title" to "desc"
                else -> null to "desc"
            }
            orderBy?.let { addQueryParameter("orderby", it) }
            addQueryParameter("order", direction)

            filter.tags.forEach { addQueryParameter("genres", it.key) }
            filter.states.forEach { state ->
                stateToApi(state)?.let { addQueryParameter("statuses", it) }
            }
            filter.types.forEach { type ->
                typeToApi(type)?.let { addQueryParameter("types", it) }
            }
        }.build()

        val items = webClient.httpGet(url).parseJson().optJSONArray("items") ?: return emptyList()
        return items.mapJSONNotNull { item ->
            val slug = item.getStringOrNull("slug") ?: return@mapJSONNotNull null
            Manga(
                id = generateUid(slug),
                url = "/series/$slug",
                publicUrl = "https://$domain/series/$slug",
                title = item.getStringOrNull("title") ?: return@mapJSONNotNull null,
                altTitles = emptySet(),
                coverUrl = item.getStringOrNull("coverImageUrl")?.toAbsoluteUrl(domain),
                largeCoverUrl = null,
                authors = emptySet(),
                description = null,
                tags = item.optJSONArray("genres")?.toTags().orEmpty(),
                state = parseState(item.getStringOrNull("status")),
                rating = item.getStringOrNull("ratingAvg")?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN,
                contentRating = parseContentRating(item.getStringOrNull("contentRating")),
                source = source,
            )
        }
    }
    
    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.slug
        val details = webClient.httpGet("$apiUrl/manga/$slug").parseJson()
        val chapters = webClient.httpGet("$apiUrl/manga/$slug/chapters").parseJson()
            .optJSONArray("items")
            .parseChapters(slug)
        return manga.copy(
            title = details.getStringOrNull("title") ?: manga.title,
            altTitles = details.optJSONArray("alternativeTitles")?.let { array ->
                (0 until array.length()).mapNotNullTo(LinkedHashSet()) { array.optString(it).nullIfEmpty() }
            }.orEmpty(),
            coverUrl = details.getStringOrNull("coverImageUrl")?.toAbsoluteUrl(domain)
                ?: manga.coverUrl,
            description = details.getStringOrNull("synopsis"),
            tags = details.optJSONArray("genres")?.toTags().orEmpty(),
            authors = buildSet {
                addAll(details.optJSONArray("authors").toNames())
                addAll(details.optJSONArray("artists").toNames())
            },
            state = parseState(details.getStringOrNull("status")),
            contentRating = parseContentRating(details.getStringOrNull("contentRating")),
            chapters = chapters,
        )
    }

    private fun JSONArray?.parseChapters(mangaSlug: String): List<MangaChapter> {
        if (this == null) return emptyList()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return mapJSONNotNull { item ->
            val slug = item.getStringOrNull("slug") ?: return@mapJSONNotNull null
            val number = item.optString("number")
            val isLocked = !item.optBoolean("purchased") && item.optInt("coinPrice") != 0
            val rawTitle = item.getStringOrNull("title")
                ?.takeIf { it != "null" && it != number }
            val name = if (rawTitle != null) "Chapter $number - $rawTitle" else "Chapter $number"
            MangaChapter(
                id = generateUid("$mangaSlug/$slug"),
                title = if (isLocked) "🔒 $name" else name,
                number = number.toFloatOrNull() ?: 0f,
                volume = 0,
                url = "/series/$mangaSlug/$slug",
                scanlator = item.optJSONObject("team")?.optString("name")?.nullIfEmpty(),
                uploadDate = dateFormat.parseSafe(item.optString("publishedAt")),
                branch = null,
                source = source,
            )
        }.reversed()
    }


    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val segments = chapter.url.trim('/').split('/')
        if (segments.size < 3) throw ParseException("Unexpected chapter url: ${chapter.url}", chapter.url)
        val mangaSlug = segments[1]
        val chapterSlug = segments[2]

        val viewerResponse = webClient.httpGet("$apiUrl/manga/$mangaSlug/chapters/$chapterSlug")
        val viewer = viewerResponse.parseJson()
        if (!viewer.optBoolean("hasAccess", true))
            throw ParseException("This chapter has to be unlocked with coins. Sign in through the browser and buy it first.", chapter.url)
        val chapterJson = viewer.optJSONObject("chapter")
            ?: throw ParseException("No chapter data in response", chapter.url)
        val chapterId = chapterJson.optLong("id")
        val isScrambled = chapterJson.optBoolean("scrambled")
        System.err.println("PhiliaScans: chapterId=$chapterId, scrambled=$isScrambled")

        val token = try {
            val content = ByteArray(0)
            val request = okhttp3.Request.Builder()
                .url("$apiUrl/reader/access-token")
                .post(content.toRequestBody(null, 0, content.size))
                .headers(readerHeaders())
                .build()
            val response = context.httpClient.newCall(request).execute()
            val bodyString = response.body?.string() ?: throw ParseException("Empty response", chapter.url)
            val json = JSONObject(bodyString)
            json.optString("token").nullIfEmpty()
                ?: throw ParseException("Token missing", chapter.url)
        } catch (e: Exception) {
            System.err.println("PhiliaScans: failed to get reader access token")
            e.printStackTrace()
            throw ParseException("Could not obtain reader token: ${e.message}", chapter.url)
        }

        val headers = readerHeaders().newBuilder()
            .add(HEADER_READER_TOKEN, token)
            .build()

        val keysResponse = webClient.httpGet("$apiUrl/chapters/$chapterId/page-keys", headers)
        val keys = keysResponse.parseJson()
        val chapterKeyB64 = keys.optString("chapterKeyB64")
        val gridSize = keys.optInt("gridSize", 1)
        System.err.println("PhiliaScans: chapterKeyB64=$chapterKeyB64, gridSize=$gridSize")

        var payloadA: String? = null
        var payloadB: String? = null
        if (keys.optBoolean("sessionDefault")) {
            try {
                val open = webClient.httpPost("$apiUrl/chapters/$chapterId/open".toHttpUrl(), JSONObject(), headers).parseJson()
                payloadA = open.getStringOrNull("payloadA")
                val sessionId = open.getStringOrNull("sessionId")
                if (sessionId != null) {
                    payloadB = runCatchingCancellable {
                        webClient.httpGet("$apiUrl/chapters/$chapterId/get-drm?session=$sessionId", headers)
                            .parseJson().optString("payloadB").nullIfEmpty()
                    }.getOrNull()
                }
            } catch (e: Exception) {
                System.err.println("PhiliaScans: DRM key retrieval failed")
                e.printStackTrace()
            }
        }

        val pages = chapterJson.optJSONArray("pages") ?: return emptyList()
        return (0 until pages.length())
            .mapNotNull { pages.optJSONObject(it) }
            .sortedBy { it.optInt("position") }
            .mapIndexed { index, page ->
                val url = page.optString("url").toAbsoluteUrl(domain)
                val fragment = listOf(
                    if (isScrambled) "1" else "0",
                    page.getStringOrNull("mime") ?: "image/webp",
                    chapterKeyB64,
                    gridSize.toString(),
                    payloadA.orEmpty(),
                    payloadB.orEmpty(),
                    index.toString(),
                ).joinToString(";")
                MangaPage(
                    id = generateUid(url),
                    url = "$url#$fragment",
                    preview = null,
                    source = source,
                )
            }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            interceptInternal(chain)
        } catch (e: Exception) {
            System.err.println("PhiliaScans: unhandled exception in interceptor")
            e.printStackTrace()
            chain.proceed(chain.request())
        }
    }

    private fun interceptInternal(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val fragment = request.url.fragment
        if (fragment.isNullOrEmpty() || !response.isSuccessful ||
            !PROTECTED_IMAGE_REGEX.matches(request.url.pathSegments.last())) {
            return response
        }

        System.err.println("PhiliaScans: decrypting ${request.url}")
        val parts = fragment.split(';')
        if (parts.size < 7) {
            System.err.println("PhiliaScans: fragment too short: $fragment")
            return response
        }
        val isScrambled = parts[0] == "1"
        val mimeType = parts[1]
        val gridSize = parts[3].toIntOrNull() ?: return response
        val pageIndex = parts[6].toIntOrNull() ?: return response
        val chapterKey = try {
            resolveChapterKey(parts[2], parts[4], parts[5])
        } catch (e: Exception) {
            System.err.println("PhiliaScans: failed to resolve chapter key")
            e.printStackTrace()
            return response
        } ?: return response

        val body = response.body
        val raw = body.bytes()
        if (raw.size < 6) {
            System.err.println("PhiliaScans: response too small (${raw.size} bytes)")
            return response
        }

        val scheme = when {
            raw[0] == MAGIC_HIGH && raw[1] == MAGIC_AES -> "aesctr:"
            raw[0] == MAGIC_HIGH && raw[1] == MAGIC_CHACHA -> "chacha"
            raw[0] == MAGIC_HIGH && raw[1] == MAGIC_AES4 -> "aesctr4:"
            else -> null
        }
        System.err.println("PhiliaScans: detected scheme=$scheme")
        val offset = if (scheme != null) 2 else 0
        val header = ByteBuffer.wrap(raw, offset, 4).order(ByteOrder.BIG_ENDIAN)
        val originalWidth = header.short.toInt() and 0xFFFF
        val originalHeight = header.short.toInt() and 0xFFFF
        val payload = raw.copyOfRange(offset + 4, raw.size)

        val plain = try {
            when (scheme) {
                "aesctr:", "aesctr4:" -> aesCtrDecrypt(payload, chapterKey, pageIndex, scheme)
                "chacha" -> chaCha20(payload, hmacSha256(chapterKey, "cc:$pageIndex"))
                else -> xorKeystream(payload, chapterKey, pageIndex)
            }
        } catch (e: Exception) {
            System.err.println("PhiliaScans: decryption failed for page $pageIndex")
            e.printStackTrace()
            return response
        }

        val contentType = mimeType.toMediaTypeOrNull() ?: body.contentType()

        if (isScrambled && scheme == null && gridSize >= 2) {
            return try {
                val unscrambledBytes = unscrambleWithAwt(
                    plain, chapterKey, pageIndex, gridSize,
                    originalWidth, originalHeight, formatNameFromMime(mimeType)
                )
                response.newBuilder()
                    .body(unscrambledBytes.toResponseBody(contentType))
                    .build()
            } catch (e: Exception) {
                System.err.println("PhiliaScans: unscramble failed for page $pageIndex")
                e.printStackTrace()
                response.newBuilder()
                    .body(plain.toResponseBody(contentType))
                    .build()
            }
        }

        return response.newBuilder()
            .body(plain.toResponseBody(contentType))
            .build()
    }

    private fun unscrambleWithAwt(
        imageBytes: ByteArray,
        chapterKey: ByteArray,
        pageIndex: Int,
        gridSize: Int,
        originalWidth: Int,
        originalHeight: Int,
        formatName: String
    ): ByteArray {
        return try {
            val source = ImageIO.read(ByteArrayInputStream(imageBytes))
                ?: throw RuntimeException("Cannot decode image – format may be unsupported")
            val tileCount = gridSize * gridSize
            val order = IntArray(tileCount) { it }

            val tilesSignature = hmacSha256(chapterKey, "tiles:$pageIndex")
            var counter = 0
            var wordIndex = 8
            var randomBlock = ByteArray(0)
            fun nextRandom(): Long {
                if (wordIndex >= 8) {
                    randomBlock = hmacSha256(tilesSignature, "perm:${counter++}")
                    wordIndex = 0
                }
                val value = ByteBuffer.wrap(randomBlock, wordIndex * 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                wordIndex++
                return value
            }
            for (i in tileCount - 1 downTo 1) {
                val j = (nextRandom() % (i + 1)).toInt()
                val tmp = order[i]; order[i] = order[j]; order[j] = tmp
            }

            val inverse = IntArray(tileCount)
            for (i in 0 until tileCount) inverse[order[i]] = i

            val tileWidth = source.width / gridSize
            val tileHeight = source.height / gridSize
            val outW = if (originalWidth > 0) originalWidth else source.width
            val outH = if (originalHeight > 0) originalHeight else source.height

            val output = BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB)
            val g = output.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

            for (target in 0 until tileCount) {
                val from = inverse[target]
                val srcX = (from % gridSize) * tileWidth
                val srcY = (from / gridSize) * tileHeight
                val dstX = (target % gridSize) * tileWidth
                val dstY = (target / gridSize) * tileHeight
                g.drawImage(
                    source,
                    dstX, dstY, dstX + tileWidth, dstY + tileHeight,
                    srcX, srcY, srcX + tileWidth, srcY + tileHeight,
                    null
                )
            }
            g.dispose()

            val bos = ByteArrayOutputStream()
            ImageIO.write(output, formatName, bos)
            bos.toByteArray()
        } catch (e: Exception) {
            System.err.println("PhiliaScans: unscrambleWithAwt internal error")
            e.printStackTrace()
            throw e
        }
    }

    private fun formatNameFromMime(mime: String): String = when {
        mime.contains("webp", ignoreCase = true) -> "webp"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpeg"
        mime.contains("png") -> "png"
        else -> "webp"
    }

    private fun resolveChapterKey(keyB64: String, payloadA: String, payloadB: String): ByteArray? {
        if (payloadA.isNotBlank() && payloadB.isNotBlank()) {
            val a = runCatching { context.decodeBase64(payloadA) }.getOrNull()
            val b = runCatching { context.decodeBase64(payloadB) }.getOrNull()
            if (a != null && b != null && a.size >= 32 && b.size >= 32)
                return ByteArray(32) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
        }
        return runCatching { context.decodeBase64(keyB64) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun hmacSha256(key: ByteArray, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun aesCtrDecrypt(data: ByteArray, chapterKey: ByteArray, pageIndex: Int, prefix: String): ByteArray {
        val derived = hmacSha256(chapterKey, "$prefix$pageIndex")
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), IvParameterSpec(ByteArray(16)))
        return cipher.doFinal(data)
    }

    private fun xorKeystream(data: ByteArray, chapterKey: ByteArray, pageIndex: Int): ByteArray {
        val result = data.copyOf()
        var blockIndex = 0
        var blockOffset = 0
        var block = hmacSha256(chapterKey, "page:$pageIndex:${0}")
        for (i in result.indices) {
            if (blockOffset == block.size) {
                blockIndex++
                block = hmacSha256(chapterKey, "page:$pageIndex:$blockIndex")
                blockOffset = 0
            }
            result[i] = (result[i].toInt() xor block[blockOffset].toInt()).toByte()
            blockOffset++
        }
        return result
    }

    private fun chaCha20(data: ByteArray, key: ByteArray): ByteArray {
        val result = data.copyOf()
        val nonce = ByteArray(12)
        var counter = 0
        var block = chaCha20Block(key, nonce, counter)
        var offset = 0
        for (i in result.indices) {
            if (offset == block.size) {
                counter++
                block = chaCha20Block(key, nonce, counter)
                offset = 0
            }
            result[i] = (result[i].toInt() xor block[offset].toInt()).toByte()
            offset++
        }
        return result
    }

    private fun chaCha20Block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val state = IntArray(16)
        state[0] = 0x61707865; state[1] = 0x3320646e; state[2] = 0x79622d32; state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = key.readIntLe(i * 4)
        state[12] = counter; state[13] = nonce.readIntLe(0); state[14] = nonce.readIntLe(4); state[15] = nonce.readIntLe(8)
        val working = state.copyOf()
        repeat(10) {
            quarterRound(working, 0, 4, 8, 12); quarterRound(working, 1, 5, 9, 13)
            quarterRound(working, 2, 6, 10, 14); quarterRound(working, 3, 7, 11, 15)
            quarterRound(working, 0, 5, 10, 15); quarterRound(working, 1, 6, 11, 12)
            quarterRound(working, 2, 7, 8, 13); quarterRound(working, 3, 4, 9, 14)
        }
        val block = ByteArray(64)
        for (i in 0 until 16) block.writeIntLe(i * 4, working[i] + state[i])
        return block
    }

    private fun quarterRound(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
        state[a] += state[b]; state[d] = Integer.rotateLeft(state[d] xor state[a], 16)
        state[c] += state[d]; state[b] = Integer.rotateLeft(state[b] xor state[c], 12)
        state[a] += state[b]; state[d] = Integer.rotateLeft(state[d] xor state[a], 8)
        state[c] += state[d]; state[b] = Integer.rotateLeft(state[b] xor state[c], 7)
    }

    private fun ByteArray.readIntLe(offset: Int) = (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.writeIntLe(offset: Int, value: Int) {
        this[offset] = value.toByte(); this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte(); this[offset + 3] = (value ushr 24).toByte()
    }

    private fun readerHeaders() = getRequestHeaders().newBuilder()
        .set("Accept", "application/json")
        .set("Referer", "https://$domain/")
        .set("X-Requested-With", "XMLHttpRequest")
        .build()

    private val Manga.slug get() = url.trim('/').substringAfterLast('/')

    private fun JSONArray.toTags(): Set<MangaTag> = mapJSONNotNull { item ->
        val key = item.getStringOrNull("slug") ?: return@mapJSONNotNull null
        val title = item.getStringOrNull("name") ?: return@mapJSONNotNull null
        MangaTag(key = key, title = title, source = source)
    }.toSet()

    private fun JSONArray?.toNames(): Set<String> {
        if (this == null) return emptySet()
        return mapJSONNotNull { it.getStringOrNull("name") }.toSet()
    }

    private fun parseState(value: String?): MangaState? = when (value?.uppercase(Locale.ROOT)) {
        "ON_GOING", "ONGOING", "RELEASING" -> MangaState.ONGOING
        "COMPLETED" -> MangaState.FINISHED
        "ON_HOLD", "HIATUS" -> MangaState.PAUSED
        "CANCELED", "CANCELLED", "DROPPED" -> MangaState.ABANDONED
        else -> null
    }

    private fun stateToApi(state: MangaState): String? = when (state) {
        MangaState.ONGOING -> "on_going"
        MangaState.FINISHED -> "completed"
        MangaState.PAUSED -> "on_hold"
        MangaState.ABANDONED -> "canceled"
        else -> null
    }

    private fun typeToApi(type: ContentType): String? = when (type) {
        ContentType.MANGA -> "manga"
        ContentType.MANHWA -> "manhwa"
        ContentType.MANHUA -> "manhua"
        ContentType.COMICS -> "comic"
        else -> null
    }

    private fun parseContentRating(value: String?): ContentRating? = when (value?.lowercase(Locale.ROOT)) {
        "safe" -> ContentRating.SAFE
        "suggestive" -> ContentRating.SUGGESTIVE
        "adult", "erotica", "pornographic" -> ContentRating.ADULT
        else -> null
    }

    private companion object {
        private const val HEADER_READER_TOKEN = "X-Reader-Access-Token"
        private val PROTECTED_IMAGE_REGEX = Regex(""".*_s\.[^.]+$""")
        private const val MAGIC_HIGH = 0xFF.toByte()
        private const val MAGIC_AES = 0x02.toByte()
        private const val MAGIC_CHACHA = 0x03.toByte()
        private const val MAGIC_AES4 = 0x04.toByte()
    }
}
