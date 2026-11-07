package com.moyue.reader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.moyue.reader.ui.reader.ReaderScreen
import com.moyue.reader.ui.settings.SettingsScreen
import com.moyue.reader.ui.shelf.ShelfScreen

/**
 * 应用导航图。
 *
 * 隐藏书架不是独立路由，而是书架 ViewModel 的内存模式；
 * 这样进程重启/导航状态恢复时不会绕过密码直接回到隐藏书架。
 */
@Composable
fun MoYueNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.SHELF,
    ) {
        composable(Routes.SHELF) {
            ShelfScreen(
                onOpenBook = { bookId ->
                    navController.navigate(Routes.reader(bookId))
                },
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(
                navArgument(Routes.ARG_BOOK_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            val bookId = entry.arguments?.getString(Routes.ARG_BOOK_ID).orEmpty()
            ReaderScreen(
                bookId = bookId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

object Routes {
    const val SHELF = "shelf"
    const val SETTINGS = "settings"
    const val ARG_BOOK_ID = "bookId"
    const val READER = "reader/{$ARG_BOOK_ID}"

    fun reader(bookId: String): String = "reader/$bookId"
}
