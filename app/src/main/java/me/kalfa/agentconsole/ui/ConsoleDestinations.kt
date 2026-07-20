package me.kalfa.agentconsole.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.History
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.reflect.KClass

/**
 * Top-level destinations for the adaptive navigation suite.
 * [route] is the type-safe route object; [routeClasses] are all route classes
 * for which this destination should render as selected (incl. detail screens).
 */
data class ConsoleDestination(
    val route: Any,
    val routeClasses: List<KClass<*>>,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

val consoleDestinations = listOf(
    ConsoleDestination(
        route = DashboardRoute,
        routeClasses = listOf(DashboardRoute::class),
        label = "דשבורד",
        selectedIcon = Icons.Filled.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard
    ),
    ConsoleDestination(
        route = LiveCallsRoute,
        routeClasses = listOf(LiveCallsRoute::class),
        label = "שיחות",
        selectedIcon = Icons.Filled.Hearing,
        unselectedIcon = Icons.Outlined.Hearing
    ),
    ConsoleDestination(
        route = EventsRoute,
        routeClasses = listOf(EventsRoute::class, EventDetailRoute::class),
        label = "אירועים",
        selectedIcon = Icons.Filled.Campaign,
        unselectedIcon = Icons.Outlined.Campaign
    ),
    ConsoleDestination(
        route = HistoryRoute,
        routeClasses = listOf(HistoryRoute::class, CallDetailRoute::class),
        label = "היסטוריה",
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History
    )
)
