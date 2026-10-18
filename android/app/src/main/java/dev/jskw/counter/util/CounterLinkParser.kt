package dev.jskw.counter.util

import dev.jskw.counter.data.model.Counter
import java.net.URI

data class ParsedCounterLink(
    val label: String?,
    val uuids: List<String>
)

enum class ShareRole {
    ADMIN,
    READ_WRITE,
    READ_ONLY
}

object CounterLinkParser {
    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
    private val EMBEDDED_UUID_REGEX = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )
    private val ROLE_KEYWORDS = setOf(
        "read", "write", "admin", "read_only", "read_write", "ro", "rw"
    )

    private fun decodeUriComponent(component: String): String {
        return runCatching {
            URI("http://localhost/#$component").fragment
        }.getOrNull() ?: component
    }

    private fun encodeUriComponent(component: String): String {
        return runCatching {
            URI(null, null, null, component).rawFragment
        }.getOrNull() ?: component
    }

    fun parse(rawInput: String?): ParsedCounterLink {
        if (rawInput.isNullOrBlank()) return ParsedCounterLink(null, emptyList())

        val trimmed = rawInput.trim()

        // Extract fragment anchor using java.net.URI if valid URL, or substring after '#'
        val rawAnchor = runCatching {
            URI(trimmed).rawFragment
        }.getOrNull() ?: if (trimmed.contains("#")) trimmed.substringAfter("#") else trimmed

        val cleanAnchor = rawAnchor.trimStart('#').trim()
        if (cleanAnchor.isEmpty()) return ParsedCounterLink(null, emptyList())

        // Split by structural delimiter '#' before URL-decoding individual components
        val rawSections = cleanAnchor.split("#").map { it.trim() }.filter { it.isNotEmpty() }
        val decodedSections = rawSections.map { decodeUriComponent(it) }

        var parsedLabel: String? = null

        if (decodedSections.isNotEmpty()) {
            val first = decodedSections[0]
            val match = EMBEDDED_UUID_REGEX.find(first)
            val candidate = if (match != null) {
                if (match.range.first == 0) null
                else first.substring(0, match.range.first).trim().trimEnd('-', '—', '!', ' ', ':', '/')
            } else {
                first
            }

            val cleaned = candidate
                ?.replace(Regex("(?:^|[-–—\\s/!:#])(read_only|read_write|read|write|admin|ro|rw)$", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.trimEnd('-', '—', '!', ' ', ':', '/')

            if (!cleaned.isNullOrBlank() &&
                !UUID_REGEX.matches(cleaned) &&
                cleaned.lowercase() !in ROLE_KEYWORDS
            ) {
                parsedLabel = cleaned
            }
        }

        // Extract up to 3 valid UUIDs from the anchor
        val fullDecodedAnchor = decodedSections.joinToString("#")
        val rawParts = fullDecodedAnchor.split(Regex("[^0-9a-fA-F-]+|--+"))
        val uuids = mutableListOf<String>()

        for (part in rawParts) {
            val candidate = part.trim('-', ' ')
            if (candidate.isNotEmpty() && UUID_REGEX.matches(candidate)) {
                uuids.add(candidate.lowercase())
                if (uuids.size == 3) break
            }
        }

        return ParsedCounterLink(parsedLabel, uuids)
    }

    fun buildShareUrl(
        baseUrl: String,
        title: String,
        viewUuid: String?,
        updateUuid: String?,
        deleteUuid: String?,
        role: ShareRole? = null
    ): String {
        val activeRole = role ?: when {
            deleteUuid != null -> ShareRole.ADMIN
            updateUuid != null -> ShareRole.READ_WRITE
            viewUuid != null -> ShareRole.READ_ONLY
            else -> ShareRole.READ_ONLY
        }

        val encodedTitle = encodeUriComponent(title.trim())
        val anchorBuilder = StringBuilder("#").append(encodedTitle)

        when (activeRole) {
            ShareRole.ADMIN -> {
                if (viewUuid != null) {
                    anchorBuilder.append("#read#").append(viewUuid)
                }
                if (updateUuid != null) {
                    anchorBuilder.append("#write#").append(updateUuid)
                }
                if (deleteUuid != null) {
                    anchorBuilder.append("#admin#").append(deleteUuid)
                }
            }
            ShareRole.READ_WRITE -> {
                val effectiveWrite = updateUuid ?: deleteUuid
                if (viewUuid != null) {
                    anchorBuilder.append("#read#").append(viewUuid)
                }
                if (effectiveWrite != null) {
                    anchorBuilder.append("#write#").append(effectiveWrite)
                }
            }
            ShareRole.READ_ONLY -> {
                val effectiveView = viewUuid ?: updateUuid ?: deleteUuid
                if (effectiveView != null) {
                    anchorBuilder.append("#read#").append(effectiveView)
                }
            }
        }

        return "${baseUrl.trimEnd('/')}/$anchorBuilder"
    }

    fun buildShareUrl(
        baseUrl: String,
        counter: Counter,
        role: ShareRole? = null
    ): String {
        return buildShareUrl(
            baseUrl = baseUrl,
            title = counter.title,
            viewUuid = counter.viewUuid,
            updateUuid = counter.updateUuid,
            deleteUuid = counter.deleteUuid,
            role = role
        )
    }
}
