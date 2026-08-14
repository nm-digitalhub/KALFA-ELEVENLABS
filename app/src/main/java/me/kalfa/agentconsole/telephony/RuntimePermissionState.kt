package me.kalfa.agentconsole.telephony

/**
 * The four states a runtime permission can be in, and the only correct way to tell
 * them apart.
 *
 * Android's own state table (developer.android.com/training/permissions/requesting)
 * is the source here, and it contains a trap worth stating plainly:
 *
 * | state                    | checkSelfPermission | shouldShowRationale |
 * |--------------------------|---------------------|---------------------|
 * | never requested          | DENIED              | false               |
 * | denied once              | DENIED              | true                |
 * | denied twice / don't ask | DENIED              | false               |
 * | granted                  | GRANTED             | n/a                 |
 *
 * Rows 1 and 3 are IDENTICAL to a caller reading the system. "Never asked" and
 * "permanently denied" cannot be distinguished from a single reading — the app has
 * to remember whether it ever asked. That memory must outlive the process: the
 * distinction is only interesting after a cold start, since within one session you
 * already know.
 *
 * This matters because a permanently-denied permission makes the request itself a
 * silent no-op: per the docs, "calling requestPermissions() or
 * requestPermissionLauncher.launch() will NOT show a dialog". An app that just calls
 * launch() and waits therefore sits forever in a state it cannot see, which is
 * exactly what this app did — its only guard was a `rememberSaveable` scoped to a
 * screen's lifetime, so after a cold start it could not tell the two rows apart and
 * re-launched a request that could never appear.
 */
enum class RuntimePermissionState {
    /** Held. Nothing to do. */
    Granted,

    /** Never asked on this install. A request will show the system dialog. */
    NeverRequested,

    /**
     * Denied once. A request still shows the dialog, and the docs ask for
     * educational UI first, with a way to decline.
     */
    DeniedOnce,

    /**
     * Denied twice, or "don't ask again". The system dialog will never appear
     * again on this install; only the user, in system settings, can change it.
     *
     * Android's guidance for this state is explicit and worth honouring rather
     * than working around: "Do NOT direct users to system settings or
     * continuously prompt them - respect their choice." So the app's job here is
     * to say precisely which capability is lost, once, and then get out of the
     * way — not to nag and not to bounce the user into Settings.
     */
    DeniedPermanently,
    ;

    /** True when asking again can actually produce a dialog. */
    val isRequestable: Boolean get() = this == NeverRequested || this == DeniedOnce
}

/**
 * Classifies a permission from the two system reads plus the app's own durable
 * memory of whether it ever asked.
 *
 * [everRequested] must come from storage that survives process death — a
 * `rememberSaveable`, a ViewModel field or anything scoped to a screen is not
 * enough, because the ambiguity this resolves only appears after a cold start.
 */
fun classifyPermission(
    granted: Boolean,
    shouldShowRationale: Boolean,
    everRequested: Boolean,
): RuntimePermissionState = when {
    granted -> RuntimePermissionState.Granted
    // Only ever true after a denial the user can still reverse, so it settles the
    // ambiguous pair on its own and is checked before everRequested is consulted.
    shouldShowRationale -> RuntimePermissionState.DeniedOnce
    everRequested -> RuntimePermissionState.DeniedPermanently
    else -> RuntimePermissionState.NeverRequested
}
