package com.group41.kotlinapp.storage

import android.content.ClipData
import android.content.Context
import android.content.Intent
import com.group41.kotlinapp.AgendaActivity
import com.group41.kotlinapp.AgendaEnrollmentStatus
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Writes confirmed agenda events to a private iCalendar file for offline sharing. */
internal object AgendaCalendarExporter {
    private const val MIME_TYPE = "text/calendar"
    private val utcFormat = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT)
        .withZone(ZoneOffset.UTC)
    private val invalidTextCharacters = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")

    /** Returns a read-only share intent, or null when there are no confirmed events. */
    suspend fun createShareIntent(context: Context, activities: List<AgendaActivity>): Intent? =
        withContext(Dispatchers.IO) {
            val calendar = serialize(activities) ?: return@withContext null
            val directory = File(context.filesDir, "exports")
            if (!directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Unable to create the calendar export directory")
            }

            // Unique names keep earlier shares readable while another export is being written.
            val file = File.createTempFile("mi-agenda-", ".ics", directory)
            try {
                file.writeText(calendar, Charsets.UTF_8)
                val uri = DataExporter.shareUri(context, file)
                Intent(Intent.ACTION_SEND).apply {
                    type = MIME_TYPE
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(context.contentResolver, "Mi agenda", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

    /** Encodes events in UTC so recipients recover the same instant in their own time zone. */
    internal fun serialize(activities: List<AgendaActivity>, createdAt: Instant = Instant.now()): String? {
        val confirmed = activities.filter { it.enrollmentStatus == AgendaEnrollmentStatus.CONFIRMED }
        if (confirmed.isEmpty()) return null

        return buildString {
            appendContentLine("BEGIN:VCALENDAR")
            appendContentLine("VERSION:2.0")
            appendContentLine("PRODID:-//Llamalla//Personal Agenda//ES")
            for (activity in confirmed.sortedBy { it.startsAt.toInstant() }) {
                val start = activity.startsAt.toInstant().truncatedTo(ChronoUnit.SECONDS)
                val end = activity.endsAt?.toInstant()?.truncatedTo(ChronoUnit.SECONDS)
                val uid = UUID.nameUUIDFromBytes(activity.activityId.toByteArray(Charsets.UTF_8))
                appendContentLine("BEGIN:VEVENT")
                appendContentLine("UID:$uid@com.group41.kotlinapp")
                appendContentLine("DTSTAMP:" + utcFormat.format(createdAt))
                appendContentLine("DTSTART:" + utcFormat.format(start))
                // Older Room rows may lack an end time. iCalendar permits an event with only a start.
                if (end != null && end.isAfter(start)) {
                    appendContentLine("DTEND:" + utcFormat.format(end))
                }
                appendContentLine("SUMMARY:" + escapeText(activity.title))
                activity.location?.takeIf { it.isNotBlank() }?.let {
                    appendContentLine("LOCATION:" + escapeText(it))
                }
                appendContentLine("END:VEVENT")
            }
            appendContentLine("END:VCALENDAR")
        }
    }

    private fun escapeText(value: String): String = value
        .replace(invalidTextCharacters, "")
        .replace("\\", "\\\\")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\n", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;")

    /** Folds iCalendar lines at 75 UTF-8 bytes without splitting a Unicode code point. */
    private fun StringBuilder.appendContentLine(line: String) {
        var offset = 0
        var lineBytes = 0
        while (offset < line.length) {
            val nextOffset = line.offsetByCodePoints(offset, 1)
            val character = line.substring(offset, nextOffset)
            val byteCount = character.toByteArray(Charsets.UTF_8).size
            if (lineBytes + byteCount > 75) {
                append("\r\n ")
                lineBytes = 1
            }
            append(character)
            lineBytes += byteCount
            offset = nextOffset
        }
        append("\r\n")
    }
}
