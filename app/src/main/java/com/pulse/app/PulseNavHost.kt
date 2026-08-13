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
import androidx.navigation.NavType
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pulse.feature.food.CreateFoodScreen
import com.pulse.feature.food.FoodSearchScreen
import com.pulse.feature.scanner.ScannerScreen

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

object Routes {
    const val SCANNER = "scanner"
    const val CREATE_FOOD = "create_food?barcode={barcode}&name={name}"

    fun createFood(barcode: String?, name: String?): String =
        "create_food?barcode=${barcode.orEmpty()}&name=${name.orEmpty()}"
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
                    onScanClicked = { navController.navigate(Routes.SCANNER) },
                )
            }
            composable(TopLevelDestination.WORKOUT.route) { Placeholder("Workout") }
            composable(TopLevelDestination.PROGRESS.route) { Placeholder("Progress") }
            composable(TopLevelDestination.PROFILE.route) { Placeholder("Profile") }

            // Full-screen, not a tab: the scanner is an immersive flow, and so
            // is manual food creation reached from a failed scan
            // (PHASE2_ARCHITECTURE.md §7.1).
            composable(Routes.SCANNER) {
                ScannerScreen(
                    onLogFood = { foodId ->
                        navController.navigate(Routes.createFood(null, null)) {
                            popUpTo(Routes.SCANNER) { inclusive = true }
                        }
                    },
                    onCreateFood = { barcode, suggestedName ->
                        navController.navigate(Routes.createFood(barcode, suggestedName)) {
                            popUpTo(Routes.SCANNER) { inclusive = true }
                        }
                    },
                    onClose = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.CREATE_FOOD,
                arguments = listOf(
                    navArgument("barcode") { nullable = true; defaultValue = null; type = NavType.StringType },
                    navArgument("name") { nullable = true; defaultValue = null; type = NavType.StringType },
                ),
            ) { entry ->
                CreateFoodScreen(
                    barcode = entry.arguments?.getString("barcode"),
                    suggestedName = entry.arguments?.getString("name"),
                    onSaved = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() },
                )
            }
        }
    }
}

/** Stands in for screens arriving in later phases. */
@Composable
private fun Placeholder(name: String) {
    Text("$name — coming in a later phase")
}
