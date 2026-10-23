package com.example.aiexercise.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** ISO-8601 时间格式化（练习卷 JSON 的 generatedAtIso 字段用）。 */
object TimeFormat {
    fun iso(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        format.timeZone = TimeZone.getDefault()
        return format.format(Date(millis))
    }
}
