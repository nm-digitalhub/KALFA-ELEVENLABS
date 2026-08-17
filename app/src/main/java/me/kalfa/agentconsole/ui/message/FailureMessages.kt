package me.kalfa.agentconsole.ui.message

import me.kalfa.agentconsole.domain.error.AppFailure

fun AppFailure.toHebrewMessage(context: FailureContext): String =
    when (this) {
        AppFailure.NetworkUnavailable -> when (context) {
            FailureContext.PRESENCE -> "אין חיבור לרשת. הסטטוס לא התעדכן בשרת — ייתכן ששיחות לא יגיעו."
            FailureContext.PUSH_REGISTRATION -> "המכשיר לא נרשם לקבלת שיחות כשהאפליקציה סגורה. בדוק את החיבור ונסה שוב."
            else -> "לא ניתן להתחבר כרגע. בדוק את החיבור ונסה שוב."
        }
        AppFailure.Unauthorized -> when (context) {
            FailureContext.PRESENCE -> "ההתחברות פגה. הסטטוס לא התעדכן ושיחות לא יגיעו עד התחברות מחדש."
            FailureContext.PUSH_REGISTRATION -> "ההתחברות פגה. המכשיר לא ירשם לקבלת שיחות עד התחברות מחדש."
            else -> "פג תוקף ההתחברות. יש להתחבר מחדש."
        }
        AppFailure.NotSignedIn -> when (context) {
            FailureContext.PRESENCE -> "לא מחובר. שיחות לא יגיעו עד ההתחברות."
            FailureContext.PUSH_REGISTRATION -> "לא מחובר. המכשיר לא ירשם לקבלת שיחות עד ההתחברות."
            else -> "לא מחובר. יש להתחבר כדי להמשיך."
        }
        AppFailure.Forbidden -> "אין לך הרשאה לבצע את הפעולה."
        // Phrased as a FACT, not a refusal, because the caller is expected to offer a
        // confirmation rather than show this on its own — it is what the agent reads
        // if something surfaces it directly.
        AppFailure.OutsideCallHours -> "השעה מחוץ לשעות החיוג הרגילות (08:00–19:00)."
        // Each reason says what actually happened. They were ALL rendered as "אין לך
        // הרשאה" until 2026-08-17, when a missed-call return failing with
        // `not_found` sent the owner looking for a permissions problem that did not
        // exist. The reason codes are dial-intent's own and are stable.
        // ONE MESSAGE PER REASON, and the platform's code named where it is the
        // diagnosis. Every 403 used to render as "אין לך הרשאה", so a return that
        // failed with `not_found` reported a permissions problem; then an interim
        // version collapsed six upstream faults into "נסה שוב", which is advice
        // that is simply false for a rejected service account.
        //
        // The line each one draws is whether RETRYING HELPS, and that is decided
        // by what the code actually means rather than by grouping.
        is AppFailure.DialRefused -> {
            val code = voxCode?.let { " (קוד $it)" } ?: ""
            when (reason) {
                // ── consent and eligibility: final, and about the person ──────
                "dnc" -> "המספר נמצא ברשימת החסומים ולא ניתן לחייג אליו."
                "opted_out" -> "האדם ביקש להסיר את מספרו ולא ניתן לחייג אליו."
                "quiet_hours" -> "לא ניתן לחייג כעת — שבת או חג."
                "attempt_cap" -> "בוצעו כבר מספר ניסיונות חיוג לשיחה הזו."
                "not_open" -> "הבקשה כבר טופלה."
                "not_linked", "event_not_active", "past_event_day" ->
                    "לא ניתן לחייג לפי כללי האירוע."

                // ── about the CALL being returned: final, and each one distinct ─
                "not_found" -> "לא נמצאה שיחה עם המזהה הזה$code."
                "invalid_session_id" -> "מזהה השיחה אינו תקין."
                "not_inbound" -> "זו שיחה יוצאת שביצענו — אין למי לחזור."
                "out_of_window", "stale" -> "השיחה ישנה מכדי לחזור אליה מכאן."
                "withheld_number", "invalid_phone" ->
                    "השיחה הגיעה ממספר חסוי — אין לאן לחזור."

                // ── WAITING HELPS. Only these three say so. ───────────────────
                "rate_limited" ->
                    "יותר מדי בקשות לספק הטלפוניה$code. המתן דקה ונסה שוב."
                "duplicate_request" ->
                    "אותה בקשה נשלחה זה עתה$code. המתן מספר שניות ונסה שוב."
                "lookup_failed" -> "לא ניתן לאמת את פרטי השיחה כרגע. נסה שוב."
                "network_error" -> "אין תקשורת עם ספק הטלפוניה. בדוק את החיבור ונסה שוב."

                // ── NOT ours to retry. Saying "נסה שוב" here would be false. ──
                "platform_fault" ->
                    "תקלה אצל ספק הטלפוניה$code. לא בצד שלנו — נסה שוב בעוד מספר דקות, ואם חוזר יש לדווח."
                "token_expired" ->
                    "אימות מול ספק הטלפוניה פג$code. נדרש טיפול — יש לדווח."
                "config_fault" ->
                    "תקלת הגדרות מול ספק הטלפוניה$code. אין טעם לנסות שוב — יש לדווח."
                "bad_request" ->
                    "שגיאה בבקשה שנשלחה לספק$code. באג במערכת — יש לדווח."

                // ── the DEVICE, not the server. These two never reach the network:
                // the app cannot place a leg because its own telephony is not up.
                // Before them, a dead SDK crashed the process instead of saying so.
                "no_device_identity" ->
                    "המכשיר עדיין לא נרשם לטלפוניה. עבור למצב \"זמין\" ונסה שוב."
                "telephony_unavailable" ->
                    "מערכת הטלפוניה במכשיר אינה זמינה כרגע. עבור למצב \"זמין\" ונסה שוב."

                // Normally intercepted upstream and turned into OutsideCallHours,
                // which asks the agent to confirm instead of announcing a refusal.
                // Here as a safety net: if it ever reaches this branch it must read
                // as Hebrew, not as a raw reason code in parentheses.
                "outside_hours" -> "השעה מחוץ לשעות החיוג הרגילות (08:00–19:00)."

                // An unknown code is reported as a refusal WITHOUT inventing a
                // cause, and carries both names so a bug report can quote them.
                else -> "לא ניתן לחייג כעת ($reason$code)."
            }
        }
        AppFailure.NotFound -> when (context) {
            FailureContext.GUEST_CALL -> "האורח לא נמצא."
            FailureContext.CAMPAIGN -> "הקמפיין לא נמצא."
            else -> "הפריט המבוקש לא נמצא."
        }
        AppFailure.Conflict -> when (context) {
            FailureContext.LIVE_CALL -> "השיחה כבר אינה פעילה."
            FailureContext.GUEST_CALL -> "לא ניתן לחייג לאורח לפי כללי הקמפיין."
            FailureContext.CAMPAIGN -> "לא ניתן לשנות את מצב הקמפיין כעת."
            else -> "המצב השתנה ולא ניתן להשלים את הפעולה."
        }
        AppFailure.Validation -> when (context) {
            FailureContext.GUEST_CALL -> "לא ניתן לחייג: חסר מספר תקין או נתון נדרש."
            else -> "אחד הנתונים אינו תקין."
        }
        AppFailure.RealtimeDisconnected -> "העדכון בזמן אמת נותק. מוצגים הנתונים האחרונים שנקלטו."
        AppFailure.CallNoLongerActive -> "השיחה כבר אינה פעילה."
        AppFailure.CampaignHoldRequired -> "להפעלת הקמפיין נדרשת מסגרת חיוב מאושרת."
        AppFailure.NoActiveCampaign -> "אין לאירוע קמפיין פעיל."
        AppFailure.GuestMissingPhone -> "לאורח אין מספר טלפון תקין לחיוג."
        AppFailure.AlreadyReached -> "כבר נוצר קשר באירוע זה."
        AppFailure.Unknown -> when (context) {
            FailureContext.PRESENCE -> "הסטטוס לא התעדכן בשרת. ייתכן ששיחות לא יגיעו."
            FailureContext.PUSH_REGISTRATION -> "המכשיר לא נרשם לקבלת שיחות כשהאפליקציה סגורה."
            else -> "הפעולה לא הושלמה. נסה שוב."
        }
    }

enum class FailureContext {
    GENERAL,
    ANALYSIS,
    LIVE_CALL,
    GUEST_CALL,
    CAMPAIGN,

    /** AgentPresence.setStatus/setShiftActive — see PresenceSyncState's kdoc. */
    PRESENCE,

    /** VoxClientManager.registerCurrentPushToken (and the ensureLoggedIn it needs). */
    PUSH_REGISTRATION
}
