package com.lia.app.data.sample

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object LiaFormatters {
    private val currencyFormat = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale("es", "BO")))
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM · HH:mm", Locale("es", "BO"))

    fun formatBs(amount: Double): String = "Bs ${currencyFormat.format(amount)}"

    fun formatDateTime(dateTime: LocalDateTime): String = dateTime.format(dateTimeFormatter)
}
