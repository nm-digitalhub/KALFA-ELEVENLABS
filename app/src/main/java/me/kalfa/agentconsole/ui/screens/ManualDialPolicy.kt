package me.kalfa.agentconsole.ui.screens

import me.kalfa.agentconsole.domain.error.AppFailure
import me.kalfa.agentconsole.domain.model.CallDispatchStatus
import me.kalfa.agentconsole.domain.model.EventGuest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class ManualDialAvailability(
    val enabled: Boolean,
    val alreadyReached: Boolean,
    val callbackScheduled: Boolean,
    val awaitingResult: Boolean,
    val accessibilityDescription: String
)

internal fun manualDialAvailability(
    guest: EventGuest,
    canManageVoice: Boolean,
    failure: AppFailure?,
    dispatch: CallDispatchStatus?
): ManualDialAvailability {
    val alreadyReached = guest.callBlockReason == "already_reached" ||
        failure == AppFailure.AlreadyReached ||
        dispatch?.reason == "already_reached"
    val callbackScheduled = !alreadyReached &&
        guest.callBlockReason == "callback_scheduled" &&
        guest.callbackScheduledAt != null
    val awaitingResult = dispatch?.status == "accepted"
    val enabled = canManageVoice &&
        guest.dialable &&
        guest.hasActiveCampaign &&
        guest.canStartOutreachCall == true &&
        !alreadyReached &&
        !callbackScheduled &&
        !awaitingResult
    val description = when {
        alreadyReached -> "כבר נוצר קשר באירוע זה — לא ניתן לחייג"
        callbackScheduled -> "האורח ביקש שיחת חזרה — החיוג הידני מושבת"
        awaitingResult -> "הבקשה נקלטה — ממתינים לעדכון"
        enabled -> "חייג"
        else -> "החיוג אינו זמין"
    }
    return ManualDialAvailability(
        enabled = enabled,
        alreadyReached = alreadyReached,
        callbackScheduled = callbackScheduled,
        awaitingResult = awaitingResult,
        accessibilityDescription = description
    )
}

internal data class DispatchPresentation(
    val title: String,
    val body: String,
    val retryAllowed: Boolean
)

internal fun dispatchPresentation(dispatch: CallDispatchStatus): DispatchPresentation {
    val (title, body) = when (dispatch.status to dispatch.reason) {
        "accepted" to null -> "הבקשה נקלטה" to "ממתינים לעדכון מהמערכת."
        "dispatched" to null -> "השיחה יצאה" to "המשך המעקב זמין בפיד השיחות."
        "dispatched" to "already_dispatched" -> "השיחה כבר יצאה" to "בקשה מקבילה כבר טופלה."
        "dispatched" to "already_concluded" -> "השיחה כבר טופלה" to "מוצגת תוצאת השיחה הקיימת."
        "skipped" to "already_reached" -> "כבר נוצר קשר באירוע זה" to
            "עם אורח שכבר נוצר עמו קשר באירוע לא ניתן ליזום שיחה נוספת, כדי למנוע הטרדה וחיוב כפול."
        "skipped" to "no_call_consent" -> "אין הסכמה לחיוג" to "לא תבוצע שיחה לאורח זה."
        "skipped" to "dnc_listed" -> "האורח ביקש שלא להתקשר" to "לא תבוצע שיחה נוספת."
        "skipped" to "campaign_not_active" -> "הקמפיין אינו פעיל" to "הבקשה לא נשלחה לחיוג."
        "skipped" to "event_closed" -> "האירוע אינו מאפשר חיוג" to "הבקשה לא נשלחה לחיוג."
        "skipped" to "concurrent_owner" -> "בקשה אחרת כבר מטופלת" to "אין צורך לשלוח בקשה נוספת."
        "skipped" to "max_concurrency" -> "המערכת עמוסה כרגע" to "אפשר לנסות שוב מאוחר יותר."
        "skipped" to "campaign_hour_cap" -> "הקמפיין הגיע למגבלת הקצב" to "אפשר לנסות מאוחר יותר."
        "blocked" to "config_missing" -> "שירות החיוג אינו זמין" to "נדרשת בדיקת מערכת."
        "blocked" to "balance_below_reserve" -> "שירות החיוג חסום כרגע" to "לא ניתן לבצע את הבקשה."
        "blocked" to null, "blocked" to "outreach_disabled", "blocked" to "live_calls_disabled" ->
            "שירות החיוג אינו זמין כרגע" to "אפשר לבדוק שוב מאוחר יותר."
        "failed" to "failed_to_start" -> "השיחה לא התחילה" to "אפשר לנסות שוב."
        "failed" to "temporary_dispatch_failure" -> "לא ניתן היה להעביר את הבקשה" to "נסו שוב בעוד רגע."
        "unknown" to "start_unknown" -> "לא ניתן לאמת אם השיחה יצאה" to "אין לחייג שוב; בדקו בפיד השיחות."
        else -> "הבקשה עודכנה" to "המצב התקבל מהמערכת."
    }
    return DispatchPresentation(
        title = title,
        body = body,
        retryAllowed = dispatch.status == "failed" || dispatch.reason == "max_concurrency"
    )
}

internal fun formatCallbackScheduledAt(raw: String): String {
    val parsers = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX"
    )
    val parsed: Date? = parsers.firstNotNullOfOrNull { pattern ->
        runCatching { SimpleDateFormat(pattern, Locale.US).parse(raw) }.getOrNull()
    }
    return parsed?.let {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("he", "IL")).format(it)
    } ?: raw
}
