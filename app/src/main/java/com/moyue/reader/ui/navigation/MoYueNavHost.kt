package com.moyue.reader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.moyue.reader.MoYueApplication
import com.moyue.reader.ui.common.AndroidDocumentResolver
import com.moyue.reader.ui.external.ExternalOpenOverlays
import com.moyue.reader.ui.external.ExternalOpenViewModel
import com.moyue.reader.ui.reader.ReaderScreen
import com.moyue.reader.ui.settings.SettingsScreen
import com.moyue.reader.ui.shelf.ShelfScreen

/**
 * 应用导航图。
 *
 * 隐藏书架不是独立路由，而是书架 ViewModel 的内存模式；
 * 这样进程重启/导航状态恢复时不会绕过密码直接回到隐藏书架。
 *
 * 外部打开（ACTION_VIEW）的消费也挂在导航根：ViewModel 以 Activity 为宿主，
 * 生命周期长于任何目的地，导入成功后只需把 `openBookId` 交给这里的 effect 导航。
 */
@Composable
fun MoYueNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as MoYueApplication).container
    }
    // resolver 只做 Uri → 导入请求的适配，构造便宜；与书架页各自持有互不影响。
    val documentResolver = remember(context) { AndroidDocumentResolver(context) }
    val externalViewModel: ExternalOpenViewModel = viewModel(
        factory = ExternalOpenViewModel.factory(container, documentResolver),
    )
    val externalState by externalViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 导入成功后才导航。等到 RESUMED 再跳转：应用在后台收到 VIEW 时，
    // 若在 stopped 状态调用 navigate 会与保存/恢复导航状态竞争。
    LaunchedEffect(externalState.openBookId, lifecycleOwner) {
        val bookId = externalState.openBookId ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            navController.navigate(Routes.reader(bookId)) { launchSingleTop = true }
            externalViewModel.consumeOpen()
        }
    }

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

    ExternalOpenOverlays(
        state = externalState,
        onDismissError = externalViewModel::dismissError,
    )
}

object Routes {
    const val SHELF = "shelf"
    const val SETTINGS = "settings"
    const val ARG_BOOK_ID = "bookId"
    const val READER = "reader/{$ARG_BOOK_ID}"

    fun reader(bookId: String): String = "reader/$bookId"
}
