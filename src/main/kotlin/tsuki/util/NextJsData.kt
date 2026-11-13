package tsuki.util

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.util.Base64

/**
 * Extracts embedded data from Next.js HTML pages and provides search helpers.
 * Supports both routing systems and both response shapes.
 *
 * ## App Router (Next.js 13+)
 *
 * Reads the Flight stream from inline `self.__next_f.push(...)` scripts:
 * - Joins all pushes into one stream first — rows can be split across tags.
 * - Handles `[1, "text"]` and `[3, "base64"]` push types.
 * - Parses `<hex-id>:` rows:
 *     - Text chunks: `T<hexLen>,<content>` (length in UTF-8 bytes).
 *     - Outlined JSON rows, cached by id.
 * - Resolves React Flight `$`-references:
 *     - `$$`                          escaped `$`
 *     - `$undefined`                  JSON null
 *     - `$Infinity`, `$NaN`, `$-0`    token without `$`
 *     - `$D`                          Date (ISO string)
 *     - `$n`                          BigInt
 *     - `$Q`                          Map
 *     - `$W`                          Set
 *     - `$L`, `$@`                    lazy / promise
 *     - `$<id>[:<path>]`              outlined model
 * - Cycles are guarded; unresolved references are left as-is.
 *
 * ## Pages Router (Next.js 12 and earlier)
 *
 * Reads `<script id="__NEXT_DATA__">` and returns both `props.pageProps` and the
 * root object. Uses Jsoup so quote style, attributes, and spacing don't matter.
 *
 * ## Public API
 *
 * - [extractPayloads]   - all top-level JSON nodes
 * - [extractRscPayloads] - same, from a raw `text/x-component` body
 * - [findValue]         - first node matching a predicate
 * - [findObject]        - first object with a key=value and required keys
 * - [findObjectInPayloads] - same, over an already-parsed payload list
 * - [findArray]         - first non-empty array, optionally validating the head
 * - [findLargestArray]  - biggest matching array across all payloads
 * - [findPage]          - first object with all required keys, plus text chunks
 * - [findPageRsc]       - same, from a raw `text/x-component` body
 *
 * App Router is tried first; Pages Router is the fallback.
 */
object NextJsData {

    private const val NEXT_F_PREFIX = "self.__next_f.push("

    /**
     * Returns every top-level JSON node found in [html], with `$`-references
     * resolved.
     */
    fun extractPayloads(html: String): List<Any> {
        val flight = extractAppRouter(html)
        if (flight.results.isNotEmpty()) {
            val resolved = flight.results.mapNotNull { item ->
                resolve(item, flight.chunks, flight.models)
            }
            if (resolved.isNotEmpty()) return resolved
        }
        return extractNextDataPayloads(html)
    }

    /**
     * Parses a raw RSC (React Server Components) flight body, as returned with
     * `Content-Type: text/x-component` when the request carries `RSC: 1`.
     *
     * Flight-body counterpart to [extractPayloads]. Shares [parseRscRows] and
     * [resolve]; only the "where do we find the flight stream" step differs.
     */
    fun extractRscPayloads(body: String): List<Any> {
        val flight = parseRscRows(body)
        if (flight.results.isEmpty()) return emptyList()
        return flight.results.mapNotNull { item ->
            resolve(item, flight.chunks, flight.models)
        }
    }

    /**
     * Finds the first object in [html] that has **all** of [keys], using
     * Kotatsu-Redo-style byte-level row parsing.
     *
     * Returns the raw object (with `$`-references still intact) plus the text
     * chunk cache, so callers can resolve text refs on demand via
     * [FlightPage.text] — e.g. `synopsis: "$27"` → `page.text(data, "synopsis")`.
     *
     * Use this instead of [findValue] when:
     * - You need to resolve text-chunk refs (`$<id>`) exactly; [findValue]
     *   resolves everything, but the char-based [parseRscRows] can drift on
     *   multi-byte text rows.
     * - You want keys to be checked within a single row (faster than a full
     *   graph walk for pages with many rows).
     *
     * All [keys] must appear in the **same row**. If a page splits them across
     * rows, call [findPage] twice with one key each, or fall back to [findValue].
     */
    fun findPage(html: String, vararg keys: String): FlightPage? {
        val stream = extractAppRouterStream(html) ?: return null
        return parseFlightPage(stream, keys)
    }

    /** Raw flight-body variant of [findPage]. */
    fun findPageRsc(body: String, vararg keys: String): FlightPage? =
        parseFlightPage(body, keys)

    /** Finds the first object in [payloads] that has every one of [keys]. */
    fun findObjectInPayloads(payloads: List<Any>, vararg keys: String): JSONObject? {
        for (payload in payloads) {
            findValueIn(payload) { node ->
                node is JSONObject && keys.all { node.has(it) }
            }?.let { return it as JSONObject }
        }
        return null
    }

    /** Finds the first node anywhere in the payload graph that matches [predicate]. */
    fun findValue(html: String, predicate: (Any) -> Boolean): Any? {
        for (payload in extractPayloads(html)) {
            findValueIn(payload, predicate)?.let { return it }
        }
        return null
    }

    /** Finds the first object where `[key] == value` and every [requiredKeys] is present. */
    fun findObject(
        html: String,
        key: String,
        value: String,
        vararg requiredKeys: String,
    ): JSONObject? = findValue(html) { node ->
        node is JSONObject &&
                node.optString(key) == value &&
                requiredKeys.all { node.has(it) }
    } as? JSONObject

    /**
     * Finds the first non-empty array, optionally validating the first element.
     */
    fun findArray(
        html: String,
        validator: ((JSONObject) -> Boolean)? = null,
    ): JSONArray? = findValue(html) { node ->
        node is JSONArray && node.length() > 0 &&
                (validator == null || node.optJSONObject(0)?.let(validator) == true)
    } as? JSONArray

    /**
     * Finds the **largest** non-empty array across every payload, optionally
     * validating the first element.
     *
     * Use this instead of [findArray] when a page can embed several arrays of
     * the same shape with very different sizes and the big one is the one you
     * actually want.
     *
     * Typical case: a sidebar or "trending" block near the top
     * of the stream carries a handful of small arrays (2–3 items each) with
     * the exact same element shape as the main list (100+ items) further down,
     * so [findArray]'s first-match behavior returns one of the small ones.
     *
     * Ties are broken by first-seen order, matching [findArray]'s behavior.
     */
    fun findLargestArray(
        html: String,
        validator: ((JSONObject) -> Boolean)? = null,
    ): JSONArray? {
        var best: JSONArray? = null
        for (payload in extractPayloads(html)) {
            collectMatchingArrays(payload, validator) { arr ->
                if (best == null || arr.length() > best!!.length()) best = arr
            }
        }
        return best
    }

    /**
     * Depth-first walk over [node] calling [action] for every array whose first
     * element passes [validator] (or every non-empty array when [validator] is
     * `null`). Descends through [JSONObject] values and [JSONArray] elements —
     * same traversal rules as [findValueIn], so the `JSONArray`-only invariant
     * still holds.
     */
    private fun collectMatchingArrays(
        node: Any,
        validator: ((JSONObject) -> Boolean)?,
        action: (JSONArray) -> Unit,
    ) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectMatchingArrays(node.opt(keys.next()) ?: continue, validator, action)
                }
            }
            is JSONArray -> {
                if (node.length() > 0 &&
                    (validator == null || node.optJSONObject(0)?.let(validator) == true)
                ) {
                    action(node)
                }
                for (i in 0 until node.length()) {
                    collectMatchingArrays(node.opt(i) ?: continue, validator, action)
                }
            }
        }
    }

    /** Everything we scraped from the App Router side of the page. */
    private data class FlightData(
        /** Top-level JSON rows, in the order they appeared in the stream. */
        val results: List<Any>,
        /** Text chunks (`T` rows) keyed by their hex id. */
        val chunks: Map<String, String>,
        /** Outlined JSON rows keyed by their hex id. */
        val models: Map<String, Any>,
    )

    /**
     * Walks every inline `<script>`, finds the `self.__next_f.push(...)` calls, joins
     * their payloads into one big stream, and parses that.
     */
    private fun extractAppRouter(html: String): FlightData {
        val stream = extractAppRouterStream(html)
            ?: return FlightData(emptyList(), emptyMap(), emptyMap())
        return parseRscRows(stream)
    }

    /**
     * Joins all `self.__next_f.push([...])` payloads into one flight stream
     * (handles `[1, "text"]` and `[3, "base64"]`). Returns `null` if the page
     * has no App Router flight data.
     */
    private fun extractAppRouterStream(html: String): String? {
        val doc = Jsoup.parse(html)
        val stream = StringBuilder()
        var any = false

        for (script in doc.select("script:not([src])")) {
            val data = script.data()
            if (data.isEmpty() || !data.contains(NEXT_F_PREFIX)) continue

            var from = 0
            while (true) {
                val idx = data.indexOf(NEXT_F_PREFIX, from)
                if (idx == -1) break
                val openBracket = data.indexOf('[', idx)
                if (openBracket == -1) { from = idx + 1; continue }

                val (arr, end) = parseJsonArrayAt(data, openBracket)
                from = end
                if (arr == null) continue

                try {
                    val type = arr.optInt(0)
                    val content = arr.optString(1)
                    when (type) {
                        1 -> { stream.append(content); any = true }
                        3 -> runCatching {
                            stream.append(String(Base64.getDecoder().decode(content), Charsets.UTF_8))
                            any = true
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        return if (any) stream.toString() else null
    }

    /**
     * Byte-level flight parser (Kotatsu-Redo style).
     *
     * Reads `<hex-id>:` headers, decodes `T<hexLen>,<content>` text rows by
     * exact UTF-8 byte count, and returns the first JSON row that contains
     * every one of [keys] in a single object.
     */
    private fun parseFlightPage(body: String, keys: Array<out String>): FlightPage? {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val texts = HashMap<String, String>()
        var pos = 0

        while (pos < bytes.size) {
            val colon = bytes.indexOfByte(':'.code.toByte(), pos)
            if (colon < 0) break

            // Text row: <id>:T<hexLen>,<content>
            if (bytes.getOrNull(colon + 1) == 'T'.code.toByte()) {
                val comma = bytes.indexOfByte(','.code.toByte(), colon)
                if (comma < 0) break
                val length = String(bytes, colon + 2, comma - colon - 2).toIntOrNull(16)
                if (length == null || comma + 1 + length > bytes.size) break
                texts[String(bytes, pos, colon - pos, Charsets.UTF_8)] =
                    String(bytes, comma + 1, length, Charsets.UTF_8)
                pos = comma + 1 + length
                continue
            }

            // JSON row: <id>:<json>\n
            val end = bytes.indexOfByte('\n'.code.toByte(), colon)
                .let { if (it < 0) bytes.size else it }
            val row = String(bytes, colon + 1, end - colon - 1, Charsets.UTF_8)
            pos = end + 1

            // Cheap pre-filter: skip rows that don't even mention every key.
            if (keys.any { "\"$it\":" !in row }) continue

            val value = runCatching { JSONTokener(row).nextValue() }.getOrNull() ?: continue
            findObjectByKeys(value, keys)?.let { return FlightPage(it, texts) }
        }

        return null
    }

    /** Recursive search for an object containing every one of [keys]. */
    private fun findObjectByKeys(node: Any?, keys: Array<out String>): JSONObject? = when (node) {
        is JSONObject -> if (keys.all(node::has)) node
        else node.keys().asSequence()
            .firstNotNullOfOrNull { findObjectByKeys(node.opt(it), keys) }
        is JSONArray -> (0 until node.length())
            .firstNotNullOfOrNull { findObjectByKeys(node.opt(it), keys) }
        else -> null
    }

    /** Byte-level `indexOf`, negative if not found. */
    private fun ByteArray.indexOfByte(byte: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == byte) return i
        return -1
    }

    /**
     * Turns the joined RSC stream into top-level rows plus the chunk and model
     * caches used later to resolve `$`-references.
     *
     * Rows look roughly like:
     *   `<hex-id>:<json>`                  outlined model row
     *   `<hex-id>:T<hexLen>,<content>`     text chunk
     *
     * Note: this char-level parser is retained for backward compatibility. It
     * can drift on long multi-byte text rows; prefer [findPage] for exact
     * behavior.
     */
    private fun parseRscRows(body: String): FlightData {
        val chunks = mutableMapOf<String, String>()
        val models = mutableMapOf<String, Any>()
        val results = mutableListOf<Any>()

        var pos = 0
        while (pos < body.length) {
            // Look for the next "<hex-id>:" header.
            val colon = body.indexOf(':', pos)
            if (colon == -1) break
            val id = body.substring(pos, colon)
            if (id.isEmpty() || !id.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                // Not actually a row header, nudge forward and keep looking.
                pos++
                continue
            }

            pos = colon + 1
            if (pos >= body.length) break

            if (body[pos] == 'T') {
                // Text chunk: T<hexLen>,<content>
                pos++
                val comma = body.indexOf(',', pos)
                if (comma == -1) break
                val byteLen = body.substring(pos, comma).toIntOrNull(16) ?: break
                pos = comma + 1

                var bytes = 0
                val start = pos
                while (pos < body.length && bytes < byteLen) {
                    bytes += when {
                        body[pos].code < 0x80 -> 1
                        body[pos].code < 0x800 -> 2
                        Character.isHighSurrogate(body[pos]) -> { pos++; 4 }   // consume the pair
                        else -> 3
                    }
                    pos++
                }

                val text = body.substring(start, pos)
                chunks[id] = text

                runCatching {
                    val t = text.trim()
                    if (t.startsWith("{")) results.add(JSONObject(t))
                    else if (t.startsWith("[")) results.add(JSONArray(t))
                }
            } else {
                val (element, end) = parseJsonAt(body, pos)
                if (element != null) {
                    results.add(element)
                    models[id] = element
                }
                pos = end
            }
        }

        return FlightData(results, chunks, models)
    }

    /**
     * Grabs the Pages Router `__NEXT_DATA__` payload, if the page has one.
     *
     * Uses Jsoup rather than searching for the literal string, so we don't break on
     * different quote styles, extra attributes, or whitespace inside the tag.
     */
    private fun extractNextDataPayloads(html: String): List<Any> {
        val doc = Jsoup.parse(html)
        val script = doc.selectFirst("script#__NEXT_DATA__") ?: return emptyList()
        val data = script.data().trim()
        if (data.isEmpty()) return emptyList()
        return runCatching {
            val root = JSONObject(data)
            listOfNotNull(root.optJSONObject("props")?.optJSONObject("pageProps"), root)
        }.getOrDefault(emptyList())
    }

    /**
     * Walks [node] recursively and replaces every Flight `$`-reference with whatever
     * it points at. References we can't resolve are left alone as their original
     * strings.
     *
     * [resolving] keeps track of ids we're currently expanding so a model that points
     * at itself (or two models that point at each other) can't loop forever.
     */
    private fun resolve(
        node: Any?,
        chunks: Map<String, String>,
        models: Map<String, Any>,
        resolving: Set<String> = emptySet(),
    ): Any? = when (node) {
        is JSONObject -> JSONObject().also { out ->
            val keys = node.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out.put(k, resolve(node.opt(k), chunks, models, resolving) ?: JSONObject.NULL)
            }
        }
        is JSONArray -> JSONArray().also { out ->
            for (i in 0 until node.length()) {
                out.put(resolve(node.opt(i), chunks, models, resolving) ?: JSONObject.NULL)
            }
        }
        is String -> resolveString(node, chunks, models, resolving)
        else -> node
    }

    /**
     * Hands a single `$`-prefixed string off to the right handler.
     *
     * Returning `null` here means "couldn't resolve" and we deliberately fall back
     * to the original string in that case so callers can still see it.
     */
    private fun resolveString(
        s: String,
        chunks: Map<String, String>,
        models: Map<String, Any>,
        resolving: Set<String>,
    ): Any {
        if (!s.startsWith("$") || s.length < 2) return s
        return when {
            s == "\$undefined" -> JSONObject.NULL
            // JSON can't hold Infinity / NaN / -0 as numbers, so we strip the `$` and
            // let the caller decide how to parse the raw token.
            s == "\$Infinity" || s == "\$-Infinity" || s == "\$NaN" || s == "\$-0" -> s.substring(1)
            s[1] == '$' -> s.substring(1)                 // $$ → escaped '$'
            s[1] == 'D' -> s.substring(2)                 // $D<iso> → ISO date string
            s[1] == 'n' -> s.substring(2)                 // $n<digits> → BigInt string
            s[1] == 'Q' -> resolveMapRef(s.substring(2), chunks, models, resolving)
            s[1] == 'W' -> resolveSetRef(s.substring(2), chunks, models, resolving)
            s[1] == 'L' || s[1] == '@' -> resolveModelRef(s.substring(2), chunks, models, resolving)
            else -> resolveModelRef(s.substring(1), chunks, models, resolving)
        } ?: s
    }

    /**
     * Resolves `$<id>` or `$<id>:<seg>:<seg>...` by looking up `<id>` in the text
     * chunks first, then in the model cache, and walking any `:<seg>` pieces into
     * the result.
     *
     * - Bare `$<id>` prefers a text chunk if one exists under that id.
     * - `<id>:<seg>...` starts from the model cache and walks each segment. If the
     *   current value is itself a `$`-reference (this happens with lazily-outlined
     *   sub-paths), we resolve it before applying the next segment.
     *
     * Returns `null` if the reference can't be resolved, the caller then falls back
     * to the raw string.
     */
    private fun resolveModelRef(
        reference: String,
        chunks: Map<String, String>,
        models: Map<String, Any>,
        resolving: Set<String>,
    ): Any? {
        val segments = reference.split(':')
        val id = segments[0]

        if (segments.size == 1) {
            chunks[id]?.let { return it }
        }
        if (id in resolving) return null // cycle guard
        val guard = resolving + id

        var value: Any = models[id] ?: return null
        for (i in 1 until segments.size) {
            if (value is String && value.startsWith("$")) {
                value = resolve(value, chunks, models, guard) ?: return null
            }
            value = walkRefSegment(value, segments[i]) ?: return null
        }
        return resolve(value, chunks, models, guard)
    }

    /**
     * Indexes [value] by one path [segment].
     *
     * React elements come over the wire as `["$", type, key, props]` tuples, and the
     * client turns them into objects with named fields. We mimic that here so paths
     * like `$id:props:children` do what you'd expect. Plain objects index by key,
     * plain arrays by numeric index.
     */
    private fun walkRefSegment(value: Any, segment: String): Any? = when (value) {
        is JSONObject -> if (value.has(segment)) value.opt(segment) else null
        is JSONArray -> {
            val isTuple = value.length() >= 4 && value.optString(0) == "$"
            when {
                isTuple && segment == "type" -> value.opt(1)
                isTuple && segment == "key" -> value.opt(2)
                isTuple && segment == "props" -> value.opt(3)
                else -> segment.toIntOrNull()?.let {
                    if (it in 0 until value.length()) value.opt(it) else null
                }
            }
        }
        else -> null
    }

    /**
     * `$Q<id>` Map. The model at [id] is an array of `[key, value]` pairs; resolve
     * it and turn the pairs into a single object. Anything that isn't a 2-element
     * array gets skipped.
     */
    private fun resolveMapRef(
        id: String,
        chunks: Map<String, String>,
        models: Map<String, Any>,
        resolving: Set<String>,
    ): Any? {
        if (id in resolving) return null
        val entries = models[id] as? JSONArray ?: return null
        val resolved = resolve(entries, chunks, models, resolving + id) as? JSONArray ?: return null
        return JSONObject().also { out ->
            for (i in 0 until resolved.length()) {
                val pair = resolved.optJSONArray(i) ?: continue
                if (pair.length() != 2) continue
                out.put(pair.optString(0), pair.opt(1))
            }
        }
    }

    /**
     * `$W<id>` Set. The model at [id] is an array of values; we resolve it and
     * return it. JSON has no Set type so an array is the closest we can get.
     */
    private fun resolveSetRef(
        id: String,
        chunks: Map<String, String>,
        models: Map<String, Any>,
        resolving: Set<String>,
    ): Any? {
        if (id in resolving) return null
        val values = models[id] as? JSONArray ?: return null
        return resolve(values, chunks, models, resolving + id)
    }

    /**
     * Depth-first search: returns the first node in [node] (including [node] itself)
     * that satisfies [predicate]. Descends through [JSONObject] values and
     * [JSONArray] elements, nothing else.
     *
     * The "nothing else" part is load-bearing: if results ever contain a plain
     * `List` or `Collection`, this function won't see inside it, so we always keep
     * arrays as [JSONArray].
     */
    private fun findValueIn(node: Any, predicate: (Any) -> Boolean): Any? {
        if (predicate(node)) return node
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val child = node.opt(keys.next()) ?: continue
                    findValueIn(child, predicate)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val child = node.opt(i) ?: continue
                    findValueIn(child, predicate)?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * Parses one JSON value starting at [start] in [body] and returns it alongside
     * the position right after it (or the last position we consumed, if parsing
     * failed). Handles bracketed objects/arrays and bare scalars (which end at the
     * first whitespace).
     */
    private fun parseJsonAt(body: String, start: Int): Pair<Any?, Int> {
        if (start >= body.length) return null to start
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < body.length) {
            val c = body[i++]
            if (escaped) { escaped = false; continue }
            if (c == '\\' && inString) { escaped = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{', '[' -> depth++
                '}', ']' -> if (--depth == 0) {
                    return runCatching { parseJson(body.substring(start, i)) }.getOrNull() to i
                }
            }
            if (depth == 0 && c.isWhitespace()) {
                return runCatching { parseJson(body.substring(start, i - 1)) }.getOrNull() to i
            }
        }
        return null to i
    }

    /** Parses a JSON object or array, anything else is rejected. */
    private fun parseJson(text: String): Any {
        val t = text.trim()
        return when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            else -> throw IllegalArgumentException("Not JSON")
        }
    }

    /**
     * Parses a JSON array starting at [start] in [s] and returns it alongside the
     * position right after the closing `]`. Returns `(null, start)` if there's no
     * array at that spot, and `(null, end-of-input)` if the array never closes.
     *
     * Used by [extractAppRouterStream] to find where each `self.__next_f.push([...])`
     * argument ends, so we don't need a regex (the regex version blew up on
     * `DOT_MATCHES_ALL` with multi-megabyte scripts).
     */
    private fun parseJsonArrayAt(s: String, start: Int): Pair<JSONArray?, Int> {
        if (start >= s.length || s[start] != '[') return null to start
        var depth = 0
        var inString = false
        var escape = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (escape) { escape = false; i++; continue }
            if (inString) {
                if (c == '\\') escape = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            val arr = runCatching { JSONArray(s.substring(start, i + 1)) }.getOrNull()
                            return arr to (i + 1)
                        }
                    }
                }
            }
            i++
        }
        return null to i
    }
}

/**
 * A parsed Next.js flight page: the matched [JSONObject] plus the text-chunk
 * cache needed to resolve `$<id>` text references.
 *
 * Produced by [NextJsData.findPage] and [NextJsData.findPageRsc]. The object's
 * `$`-references are **not** pre-resolved — use [text] to pull them out on
 * demand, which is what Voratoon-style `"synopsis":"$27"` payloads need.
 */
class FlightPage internal constructor(
    val data: JSONObject,
    private val texts: Map<String, String>,
) {
    /**
     * Resolves [key] on [obj]. Handles:
     * - `$$...` → strip the first `$` (escaped)
     * - `$undefined` → `null`
     * - `$<id>` → lookup in the text-chunk cache
     * - any other value → returned as-is
     *
     * Returns `null` if the key is missing, blank, or the reference is
     * unresolved.
     */
    fun text(obj: JSONObject, key: String): String? {
        val value = obj.optString(key).takeIf { it.isNotBlank() } ?: return null
        return when {
            !value.startsWith("$") -> value
            value.startsWith("$$") -> value.substring(1)
            value == "\$undefined" -> null
            else -> texts[value.substring(1)]?.takeIf { it.isNotBlank() }
        }
    }

    /** Convenience: resolves [key] on [data]. */
    fun text(key: String): String? = text(data, key)
}
