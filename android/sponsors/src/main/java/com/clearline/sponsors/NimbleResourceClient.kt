package com.clearline.sponsors

import com.clearline.core.*
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/**
 * Phone-to-Nimble Search/Extract. Approval and committed-source membership stay
 * with the coordinator; this adapter owns no queue and never changes Room.
 *
 * Contracts: https://docs.nimbleway.com/api-reference/search/search and
 * https://docs.nimbleway.com/api-reference/extract/extract.
 * One HTTPS attempt per call; retry decisions belong to the coordinator.
 */
class NimbleResourceClient internal constructor(
    private val http: SponsorHttp,
    private val authorization: SponsorAuthorization,
    private val dns: PublicSourceDns,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : PublicResourceClient {
    override suspend fun search(request: ApprovedResourceRequest): SearchResult {
        authorization.requireResearch(request)
        validateRequest(request)
        val phrase = when (request.category) {
            ResourceCategory.CAREGIVER_SUPPORT -> "caregiver support groups"
            ResourceCategory.RESPITE_CARE -> "respite care"
            ResourceCategory.CAREGIVER_EDUCATION -> "caregiver education"
        }
        val response = http.post(Sponsor.NIMBLE, "/v2/search", buildJsonObject {
            put("query", request.queryDraft?.query ?: "$phrase in ${request.city}")
            put("country", "US")
            put("max_results", 3)
            put("full_content", false)
        }) { authorization.requireResearch(request) }
        currentCoroutineContext().ensureActive()
        val rows = response["results"] as? JsonArray ?: badResponse("Nimble Search returned no result list.")
        val requestId = optionalText(response, "request_id", 256)
        val retrievedAt = nowMs()
        val candidates = rows.take(3).mapNotNull { element ->
            val row = element as? JsonObject ?: badResponse("Nimble Search returned an invalid result.")
            val originalUrl = optionalText(row, "url", 4096) ?: return@mapNotNull null
            val url = try { PublicSourceUrls.normalize(originalUrl) }
                catch (_: ClearLineException) { return@mapNotNull null }
            val title = optionalText(row, "title", 500) ?: return@mapNotNull null
            ResourceCandidate(
                candidateId = sha256(url), title = title, url = url,
                description = searchDescription(row),
                requestId = requestId, retrievedAtMs = retrievedAt,
            )
        }.distinctBy { it.candidateId }
        return SearchResult(request.approvalId, request.inputRevision, candidates, retrievedAt, requestId)
    }

    override suspend fun extract(source: ApprovedSource): SourceEvidence =
        withTimeoutOrNull(45_000) { extractApproved(source) }
            ?: sponsorFailure(ErrorCode.TIMEOUT, "Public source extraction exceeded its action budget.", true)

    private suspend fun extractApproved(source: ApprovedSource): SourceEvidence {
        authorization.requireSource(source)
        validateRequest(source.request)
        val candidate = source.candidate
        val url = PublicSourceUrls.normalize(candidate.url)
        if (candidate.candidateId != sha256(url))
            sponsorFailure(ErrorCode.INVALID_INPUT, "Selected source identity does not match its URL.")
        PublicSourceUrls.verifyDns(url, dns)
        // The transport invokes this again after loading its credential, so a
        // revoked/deleted/stale request does not dispatch after a preceding await.
        val response = http.post(Sponsor.NIMBLE, "/v2/extract", buildJsonObject {
            put("url", url)
            put("render", true)
        }) { authorization.requireSource(source) }
        currentCoroutineContext().ensureActive()
        if (response.containsKey("status") && optionalText(response, "status", 40) != "success") {
            val pending = optionalText(response, "status", 40) in setOf("pending", "queued", "running", "processing")
            sponsorFailure(ErrorCode.BAD_RESPONSE, "Nimble extraction is not completed evidence.", pending)
        }
        response["status_code"]?.takeUnless { it is JsonNull }?.let { element ->
            val status = (element as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (status == null || status !in 200..299)
                sponsorFailure(ErrorCode.BAD_RESPONSE, "The selected source did not return a successful fetch.", status in setOf(408, 429, 500, 502, 503, 504))
        }
        val data = response["data"] as? JsonObject ?: badResponse("Nimble returned no completed page content.", true)
        val redirectUrls = mutableListOf<String>()
        optionalText(response, "url", 4096)?.let(redirectUrls::add)
        data["redirects"]?.takeUnless { it is JsonNull }?.let { element ->
            val redirects = element as? JsonArray ?: badResponse("Nimble returned an invalid redirect chain.")
            if (redirects.size > 5) badResponse("Nimble returned too many source redirects.")
            redirects.forEach {
                val redirect = it as? JsonObject ?: badResponse("Nimble returned an invalid source redirect.")
                redirectUrls += optionalText(redirect, "url", 4096) ?: badResponse("Nimble returned an empty source redirect.")
            }
        }
        redirectUrls.distinct().forEach { target ->
            val normalized = PublicSourceUrls.normalize(target)
            if (PublicSourceUrls.organizationHost(normalized) != PublicSourceUrls.organizationHost(url))
                badResponse("Source redirected to an unselected organization.")
            PublicSourceUrls.verifyDns(normalized, dns)
        }
        val markdown = optionalText(data, "markdown", MAX_CONTENT_CHARS)
        val content = markdown ?: optionalText(data, "html", MAX_HTML_CHARS)?.let(NimbleHtmlText::clean)
            ?: badResponse("Nimble returned no readable page content.", true)
        if (content.isBlank()) badResponse("The source page contains no readable text.", true)
        if (content.length > MAX_CONTENT_CHARS) badResponse("Extracted evidence exceeds the content limit.")
        val contentHash = sha256(content)
        val evidenceId = EvidenceId(UUID.nameUUIDFromBytes(
            "${source.request.approvalId.value}\n$url\n$contentHash".toByteArray(Charsets.UTF_8),
        ).toString())
        val passages = mutableListOf<SupportingPassage>()
        fun passage(start: Int, end: Int): SupportingPassage {
            val id = "${evidenceId.value}#chars=$start-$end"
            return passages.firstOrNull { it.passageId == id } ?: SupportingPassage(id, content.substring(start, end)).also(passages::add)
        }
        var excerptEnd = minOf(content.length, 1500)
        if (excerptEnd < content.length && content[excerptEnd - 1].isHighSurrogate()) excerptEnd--
        passage(0, excerptEnd)
        fun fact(pattern: Regex): SourcedFact? = pattern.find(content)?.let { match ->
            val supporting = passage(match.range.first, match.range.last + 1)
            SourcedFact(match.groupValues[1].trim(), listOf(supporting.passageId))
        }
        val facts = PublicFacts(phone = fact(PHONE), address = fact(ADDRESS), hours = fact(HOURS))
        return SourceEvidence(
            evidenceId = evidenceId, version = 1,
            approvalId = source.request.approvalId, inputRevision = source.request.inputRevision,
            title = candidate.title, description = candidate.description, sourceUrl = url,
            retrievedAtMs = nowMs(), contentHash = contentHash,
            passages = passages.toList(), facts = facts, verificationStatus = VerificationStatus.EXTRACTED,
            requestId = optionalText(response, "request_id", 256),
            taskId = optionalText(response, "task_id", 256), searchRequestId = candidate.requestId,
        )
    }

    private fun validateRequest(request: ApprovedResourceRequest) {
        if (request.city.length !in 1..120 || !CITY.matches(request.city) || request.city.none { it.isLetter() })
            sponsorFailure(ErrorCode.INVALID_INPUT, "Public research requires a bounded city name.")
        request.queryDraft?.let { draft ->
            if (draft.city != request.city || draft.category != request.category)
                sponsorFailure(ErrorCode.INVALID_INPUT, "Reviewed query must match the approved city and category.")
        }
    }

    companion object {
        private const val MAX_CONTENT_CHARS = 150_000
        private const val MAX_HTML_CHARS = 1_000_000
        private val CITY = Regex("[\\p{L}\\p{M}\\p{N} .,()'’\\-]+")
        private val PHONE = Regex("\\b(?:Phone|Telephone|Tel)[ \\t]*:[ \\t]*((?:\\+1[ .-]?)?(?:\\([0-9]{3}\\)|[0-9]{3})[ .-]?[0-9]{3}[ .-]?[0-9]{4}(?:[ \\t]*(?:ext\\.?|x)[ \\t]*[0-9]{1,6})?)(?![0-9])", RegexOption.IGNORE_CASE)
        private val ADDRESS = Regex("\\b(?:Street address|Address)[ \\t]*:[ \\t]*([0-9]{1,6}[ \\t]+[^\\r\\n<>]{5,180})", RegexOption.IGNORE_CASE)
        private val HOURS = Regex("\\b(?:Office hours|Opening hours|Hours)[ \\t]*:[ \\t]*([^\\r\\n<>]{3,160})", RegexOption.IGNORE_CASE)
    }
}

private fun badResponse(message: String, retryable: Boolean = false): Nothing = sponsorFailure(ErrorCode.BAD_RESPONSE, message, retryable)

private fun optionalText(objectValue: JsonObject, key: String, max: Int): String? {
    val value = objectValue[key] ?: return null
    if (value is JsonNull) return null
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: badResponse("Nimble returned an invalid text field.")
    if (text.length > max) badResponse("Nimble text exceeds the field limit.")
    return text.takeIf { it.isNotBlank() }
}

/** Search descriptions can contain long page excerpts even with full_content=false.
 * Keep only a display preview; source evidence still comes from a separate extract.
 */
private fun searchDescription(row: JsonObject): String? {
    val value = row["description"] ?: return null
    if (value is JsonNull) return null
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: badResponse("Nimble returned an invalid description field.")
    var end = minOf(text.length, 4000)
    if (end < text.length && text[end - 1].isHighSurrogate()) end--
    return text.substring(0, end).takeIf { it.isNotBlank() }
}

internal fun nimbleCandidateId(url: String): String = sha256(PublicSourceUrls.normalize(url))
private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun interface PublicSourceDns { suspend fun resolve(host: String): List<InetAddress> }

/** Phone DNS checks cannot pin Nimble's remote DNS or prevent its remote redirects. */
internal object PublicSourceUrls {
    fun normalize(value: String): String {
        if (value.length !in 1..4096 || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) invalid()
        val parsed = try { URI(value) } catch (_: Exception) { invalid() }
        val scheme = parsed.scheme?.lowercase()
        val host = parsed.host?.lowercase()?.removeSurrounding("[", "]")?.trimEnd('.') ?: invalid()
        if (scheme !in setOf("http", "https") || parsed.rawUserInfo != null || '%' in host || parsed.port !in setOf(-1, 80, 443)) invalid()
        if (host == "localhost" || listOf(".localhost", ".local", ".internal", ".home", ".lan").any(host::endsWith)) invalid()
        if (host.isBlank() || ('.' !in host && ':' !in host)) invalid()
        if (':' in host || host.all { it.isDigit() || it == '.' }) {
            if (':' !in host && !Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}").matches(host)) invalid()
            if (':' !in host && host.split('.').any { it.length > 1 && it.startsWith('0') }) invalid()
            val literal = try { InetAddress.getByName(host) } catch (_: Exception) { invalid() }
            if (!isPublic(literal)) invalid()
        }
        return "$scheme://${parsed.rawAuthority}${parsed.rawPath.ifEmpty { "/" }}${parsed.rawQuery?.let { "?$it" } ?: ""}"
    }

    fun organizationHost(url: String): String = URI(url).host.lowercase().trimEnd('.').removePrefix("www.")

    suspend fun verifyDns(url: String, resolver: PublicSourceDns) {
        val host = URI(url).host.removeSurrounding("[", "]").trimEnd('.')
        // Android DnsResolver.query expects a DNS name, not an IP literal.
        // normalize() has already rejected alternate numeric encodings/scopes.
        if (':' in host || host.all { it.isDigit() || it == '.' }) {
            val address = try { InetAddress.getByName(host) } catch (_: Exception) { invalid() }
            if (!isPublic(address)) invalid()
            return
        }
        val addresses = withTimeoutOrNull(5_000) { resolver.resolve(host) }
            ?: sponsorFailure(ErrorCode.TIMEOUT, "Public source DNS verification timed out.", true)
        if (addresses.isEmpty() || addresses.any { !isPublic(it) }) invalid()
    }

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        if (address is Inet4Address) {
            val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
            return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                a == 100 && b in 64..127 || a == 169 && b == 254 || a == 172 && b in 16..31 ||
                a == 192 && (b == 168 || b == 0 && c in setOf(0, 2) || b == 88 && c == 99) ||
                a == 198 && (b in 18..19 || b == 51 && c == 100) || a == 203 && b == 0 && c == 113)
        }
        // Global IPv6 unicast only; reject tunnelling, documentation and special ranges.
        if (bytes.size != 16 || (bytes[0] and 0xe0) != 0x20) return false
        if (bytes[0] == 0x20 && bytes[1] == 0x02) return false
        if (bytes[0] == 0x20 && bytes[1] == 0x01 && (
                bytes[2] == 0 && (bytes[3] <= 2 || bytes[3] in 0x10..0x2f) ||
                bytes[2] == 0x0d && bytes[3] == 0xb8)) return false
        return !(bytes[0] == 0x3f && bytes[1] == 0xff && (bytes[2] and 0xf0) == 0)
    }

    private fun invalid(): Nothing = sponsorFailure(ErrorCode.INVALID_INPUT, "Only credential-free public HTTP(S) source URLs are allowed.")
}

/** Inert text only, never a WebView or HTML renderer. Removes active-tag bodies. */
internal object NimbleHtmlText {
    private val blocked = setOf("script", "style", "noscript", "template", "iframe", "object", "svg")
    private val breaks = setOf("p", "div", "br", "li", "h1", "h2", "h3", "h4", "tr", "section", "address")
    private val entity = Regex("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[A-Za-z]{2,8});")

    fun clean(html: String): String {
        val text = StringBuilder()
        val hidden = mutableListOf<String>()
        var index = 0
        while (index < html.length) {
            if (html.startsWith("<!--", index)) {
                val end = html.indexOf("-->", index + 4)
                index = if (end < 0) html.length else end + 3
            } else if (html[index] == '<') {
                var end = index + 1
                var quote: Char? = null
                while (end < html.length) {
                    val c = html[end]
                    if (quote != null) { if (quote == c) quote = null }
                    else if (c == '\'' || c == '"') quote = c
                    else if (c == '>') break
                    end++
                }
                if (end == html.length) break
                val token = html.substring(index + 1, end).trim()
                val closing = token.startsWith('/')
                val tag = token.removePrefix("/").takeWhile { it.isLetterOrDigit() }.lowercase()
                if (hidden.isNotEmpty()) {
                    if (closing && hidden.last() == tag) hidden.removeAt(hidden.lastIndex)
                    else if (!closing && tag in blocked) hidden += tag
                } else if (!closing && tag in blocked) hidden += tag
                else if (tag in breaks) text.append('\n')
                index = end + 1
            } else {
                if (hidden.isEmpty()) text.append(html[index])
                index++
            }
        }
        val decoded = entity.replace(text) { match ->
            val value = match.groupValues[1]
            when (value.lowercase()) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos", "#39" -> "'"; "nbsp" -> " "
                else -> {
                    val code = when { value.startsWith("#x", true) -> value.substring(2).toIntOrNull(16); value.startsWith('#') -> value.substring(1).toIntOrNull(); else -> null }
                    if (code != null && Character.isValidCodePoint(code) && code !in 0xd800..0xdfff && code >= 32) String(Character.toChars(code)) else match.value
                }
            }
        }
        return decoded.lineSequence().map { it.trim().replace(Regex("[ \\t\\r]+"), " ") }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
