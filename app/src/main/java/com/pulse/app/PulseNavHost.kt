package com.pulse.app

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pulse.feature.food.FoodSearchScreen

/**
 * Five bottom-bar destinations (PHASE2_ARCHITECTURE.md §7.1).
 *
 * Logging actions are deliberately NOT destinations — they open as modal bottom
 * sheets over whatever screen you're on, so you never lose your place to log
 * something. Full-screen destinations are reserved for genuinely immersive
 * flows: the scanner, an active workout, and onboarding.
 */
enum class TopLevelDestination(val route: String, val label: String) {
    HOME("home", "Home"),
    FOOD("food", "Food"),
    WORKOUT("workout", "Workout"),
    PROGRESS("progress", "Progress"),
    PROFILE("profile", "Profile"),
}

@Composable
fun PulseNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Avoid piling up a back stack of tab switches.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(destination.label.first().toString()) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopLevelDestination.HOME.route) { Placeholder("Home") }
            composable(TopLevelDestination.FOOD.route) {
                FoodSearchScreen(
                    onScanClicked = { /* scanner — next */ },
                )
            }
            composable(TopLevelDestination.WORKOUT.route) { Placeholder("Workout") }
            composable(TopLevelDestination.PROGRESS.route) { Placeholder("Progress") }
            composable(TopLevelDestination.PROFILE.route) { Placeholder("Profile") }
        }
    }
}

/** Stands in for screens arriving in later phases. */
@Composable
private fun Placeholder(name: String) {
    Text("$name — coming in a later phase")
}
