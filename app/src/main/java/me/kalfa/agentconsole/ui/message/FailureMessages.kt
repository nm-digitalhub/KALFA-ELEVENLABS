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
