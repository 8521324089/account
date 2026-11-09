package com.kb.ledger.backup.partition

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Enforces the date partitioning and timezone policy for local backups.
 *
 * ## Timezone Policy
 * All date partitions use the **UTC timezone** (`ZoneOffset.UTC`) consistently.
 *
 * Rationale:
 * A mobile device frequently moves across timezones or adjusts for Daylight Saving Time.
 * If local device timezone were used, an identical transaction creation timestamp (`createdAt`)
 * could resolve to "2026/10/24" in one timezone and "2026/10/25" in another, leading to
 * duplicate chunks, fractured partitions, or misplaced records.
 * By anchoring partitions strictly to UTC:
 * 1. A transaction's `createdAt` timestamp maps to exactly one deterministic `yyyy/MM/dd` directory.
 * 2. Timezone shifts, travel, or OS settings will never move existing transactions between partitions.
 * 3. Partition paths are stable and identical across any Android device.
 */
object PartitionDateUtils {

    val TIMEZONE: ZoneOffset = ZoneOffset.UTC

    private val PARTITION_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US).withZone(TIMEZONE)

    private val YEAR_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy", Locale.US).withZone(TIMEZONE)

    private val MONTH_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM", Locale.US).withZone(TIMEZONE)

    private val DAY_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd", Locale.US).withZone(TIMEZONE)

    /**
     * Converts a millisecond epoch timestamp into a partition key in "yyyy/MM/dd" format.
     */
    fun toPartitionKey(epochMillis: Long): String {
        return PARTITION_FORMATTER.format(Instant.ofEpochMilli(epochMillis))
    }

    /**
     * Returns the year component string (e.g. "2026") in UTC.
     */
    fun toYear(epochMillis: Long): String {
        return YEAR_FORMATTER.format(Instant.ofEpochMilli(epochMillis))
    }

    /**
     * Returns the month component string (e.g. "10") in UTC.
     */
    fun toMonth(epochMillis: Long): String {
        return MONTH_FORMATTER.format(Instant.ofEpochMilli(epochMillis))
    }

    /**
     * Returns the day component string (e.g. "25") in UTC.
     */
    fun toDay(epochMillis: Long): String {
        return DAY_FORMATTER.format(Instant.ofEpochMilli(epochMillis))
    }

    /**
     * Returns the relative directory path for transactions created at [epochMillis],
     * e.g. "transactions/2026/10/25".
     */
    fun toTransactionPartitionPath(epochMillis: Long): String {
        return "transactions/${toPartitionKey(epochMillis)}"
    }
}
