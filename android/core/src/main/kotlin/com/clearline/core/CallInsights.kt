package com.clearline.core

import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.serialization.Serializable

/** A topic mentioned in text, never an inferred diagnosis or emotion classification. */
@Serializable enum class CallConcern { MEMORY, WORD_FINDING, WORRY, LONELINESS, SLEEP, HEARING, GENERAL }

@Serializable data class QueryBasis(
    val concern: CallConcern,
    val transcriptExcerpt: String? = null,
    val topKeyword: String? = null,
    val topMetric: String? = null,
    val deltaPercent: Double? = null,
    val baselineSessionCount: Int = 0,
    val fallback: Boolean = false,
) {
    init {
        require(transcriptExcerpt == null || transcriptExcerpt.length in 1..200)
        require(topKeyword == null || topKeyword.length in 1..40)
        require(topMetric == null || topMetric in setOf("recording_wpm", "energy_rms"))
        require(deltaPercent == null || deltaPercent.isFinite())
        require(baselineSessionCount >= 0)
        require(fallback == (concern == CallConcern.GENERAL))
    }
}

/** Local-only proposal. Approval belongs to ApprovedResourceRequest, not this value. */
@Serializable data class CallQueryDraft(
    val query: String,
    val city: String,
    val category: ResourceCategory,
    val basis: QueryBasis,
    val transcriptHash: String,
    val builderVersion: String = "transcript-topics-v1",
) {
    init {
        require(query.length in 1..400 && query == query.trim() && query.none { it.isISOControl() })
        require(city.length in 1..120 && city == city.trim() && city.none { it.isISOControl() })
        require(transcriptHash.matches(Regex("[0-9a-f]{64}")))
        require(builderVersion == "transcript-topics-v1")
    }
}

object CallInsights {
    private data class Rule(val concern: CallConcern, val topic: String, val keyword: String, val pattern: Regex)
    private data class Hit(val rule: Rule, val range: IntRange)
    private val rules = listOf(
        Rule(CallConcern.WORD_FINDING, "word finding difficulty communication support", "word finding",
            Regex("\\b(?:trouble|difficulty|struggle|struggling)\\s+(?:(?:with|to)\\s+)?(?:finding|find|remembering|remember)\\s+(?:the\\s+|my\\s+)?words?\\b|\\b(?:can['’]?t|cannot)\\s+(?:find|remember)\\s+(?:the\\s+|my\\s+)?words?\\b", RegexOption.IGNORE_CASE)),
        Rule(CallConcern.MEMORY, "everyday forgetfulness memory support", "forgetting",
            Regex("\\b(?:forget|forgetting|forgetful|forgot)\\b|\\b(?:can['’]?t|cannot)\\s+remember\\b|\\b(?:memory\\s+(?:trouble|problems?|loss)|trouble\\s+remembering)\\b", RegexOption.IGNORE_CASE)),
        Rule(CallConcern.WORRY, "worry and emotional support", "worry",
            Regex("\\b(?:worried|worrying|anxious|afraid|fearful|sad)\\b", RegexOption.IGNORE_CASE)),
        Rule(CallConcern.LONELINESS, "loneliness social connection support", "loneliness",
            Regex("\\b(?:lonely|loneliness|isolated)\\b", RegexOption.IGNORE_CASE)),
        Rule(CallConcern.SLEEP, "sleep difficulties support", "sleep",
            Regex("\\b(?:(?:can['’]?t|cannot)\\s+sleep|trouble\\s+sleeping|not\\s+sleeping|difficulty\\s+sleeping)\\b", RegexOption.IGNORE_CASE)),
        Rule(CallConcern.HEARING, "hearing and communication support", "hearing",
            Regex("\\b(?:(?:can['’]?t|cannot)\\s+hear|trouble\\s+hearing|difficulty\\s+hearing|hearing\\s+(?:loss|problems?))\\b", RegexOption.IGNORE_CASE)),
    )

    fun transcriptFor(snapshot: SessionSnapshot): String = snapshot.clips
        .filter { it.supersededBy == null && it.result?.metrics?.quality == AudioQuality.ACCEPTED }
        .sortedWith(compareBy<StoredClip> { it.clip.createdAtMs }.thenBy { it.clip.clipId.value })
        .mapNotNull { it.result?.transcript?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString("\n")

    fun transcriptHash(transcript: String): String = MessageDigest.getInstance("SHA-256")
        .digest(transcript.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Bounded preview only: export still requires separate snippet consent. */
    fun memorySnippet(transcript: String): String? = transcript.trim().takeIf(String::isNotEmpty)
        ?.let { text -> bestHit(text, null)?.let { excerpt(text, it.range) } ?: text.take(200) }

    fun topKeyword(transcript: String): String? = bestHit(transcript, null)?.rule?.keyword

    fun buildQuery(
        transcript: String,
        city: String,
        category: ResourceCategory,
        comparison: DescriptiveComparison? = null,
    ): CallQueryDraft {
        val cleanCity = city.trim()
        require(cleanCity.length in 1..120 && cleanCity.none { it.isISOControl() })
        val differences = if (comparison?.baseline?.status == BaselineStatus.AVAILABLE)
            listOf("recording_wpm" to comparison.recordingWpm, "energy_rms" to comparison.energyRms)
        else emptyList()
        // Zero means cannot yield a percentage. Missing/unmeasured values never become zero.
        val dominant = differences.mapNotNull { (name, difference) ->
            difference?.takeIf { it.mean > 0 && it.mean.isFinite() && it.delta.isFinite() }
                ?.let { name to (100 * it.delta / it.mean) }
                ?.takeIf { it.second.isFinite() && it.second != 0.0 }
        }.maxByOrNull { abs(it.second) }
        val hit = bestHit(transcript, dominant?.first)
        val categoryText = when (category) {
            ResourceCategory.CAREGIVER_SUPPORT -> "caregiver support groups"
            ResourceCategory.RESPITE_CARE -> "respite care"
            ResourceCategory.CAREGIVER_EDUCATION -> "caregiver education"
        }
        // Public topic is selected from actual text. Raw quotes and personal details stay in the local basis.
        val query = listOfNotNull(hit?.rule?.topic, categoryText, "near $cleanCity").joinToString(" ")
        return CallQueryDraft(query, cleanCity, category, QueryBasis(
            concern = hit?.rule?.concern ?: CallConcern.GENERAL,
            transcriptExcerpt = hit?.let { excerpt(transcript, it.range) },
            topKeyword = hit?.rule?.keyword,
            topMetric = dominant?.first,
            deltaPercent = dominant?.second,
            baselineSessionCount = comparison?.baseline?.previousSessions?.size ?: 0,
            fallback = hit == null,
        ), transcriptHash(transcript))
    }

    private fun bestHit(text: String, topMetric: String?): Hit? = rules.flatMap { rule ->
        rule.pattern.findAll(text).filter { !negated(text, it.range.first) }.map { Hit(rule, it.range) }.toList()
    }.maxWithOrNull(compareBy<Hit> {
        when {
            it.rule.concern == CallConcern.WORD_FINDING && topMetric == "recording_wpm" -> 4
            it.rule.concern == CallConcern.MEMORY || it.rule.concern == CallConcern.WORD_FINDING -> 3
            else -> 1
        }
    }.thenBy { -it.range.first })

    /** Conservative clause-local handling, not a claim to general language understanding. */
    private fun negated(text: String, start: Int): Boolean {
        val prefix = text.substring(maxOf(0, start - 80), start)
            .split(Regex("[.!?;\\n]|\\b(?:but|however)\\b", RegexOption.IGNORE_CASE)).last()
        val denialFillers = "really|very|feeling|having|have|feel|been|had|any|much|that|at|all|worried|worrying|anxious|afraid|fearful|sad|lonely|loneliness|isolated|forgetful|or|and"
        return Regex("\\b(?:not|never|no|no longer|don['’]?t|doesn['’]?t|isn['’]?t|wasn['’]?t|aren['’]?t)\\s+(?:(?:$denialFillers)\\s+){0,8}$", RegexOption.IGNORE_CASE).containsMatchIn(prefix)
    }

    private fun excerpt(text: String, range: IntRange): String {
        val previousBreak = (range.first - 1 downTo 0).firstOrNull { text[it] in ".!?\n" } ?: -1
        val nextBreak = (range.last + 1 until text.length).firstOrNull { text[it] in ".!?\n" }
        val sentenceStart = previousBreak + 1
        val sentenceEnd = nextBreak?.plus(1) ?: text.length
        val start = maxOf(sentenceStart, range.last + 1 - 200, range.first - 80)
        return text.substring(start, minOf(sentenceEnd, start + 200)).trim()
    }
}
