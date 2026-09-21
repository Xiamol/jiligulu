package com.jiligulu.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jiligulu.app.data.prefs.UserPrefs
import com.jiligulu.app.data.reminder.WaterReminderWorker
import com.jiligulu.app.ui.add.AddBillScreen
import com.jiligulu.app.ui.chat.ChatScreen
import com.jiligulu.app.ui.main.MainScreen
import com.jiligulu.app.ui.home.HomeViewModel
import com.jiligulu.app.ui.onboarding.OnboardingScreen
import com.jiligulu.app.ui.settings.SettingsScreen
import com.jiligulu.app.ui.settings.UpdatePromptHost
import com.jiligulu.app.ui.startup.StartupScreen
import com.jiligulu.app.ui.startup.StartupViewModel
import com.jiligulu.app.ui.theme.GuluTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val MAIN = "main"
    const val ADD_BILL = "add_bill"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
    const val WELCOME_PREVIEW = "welcome_preview"
}

class MainActivity : ComponentActivity() {
    private val waterRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleWaterIntent(intent)
        enableEdgeToEdge()
        setContent {
            val app = LocalContext.current.applicationContext as JiliguluApp
            val themeMode by app.container.userPrefs.themeMode
                .collectAsStateWithLifecycle(initialValue = UserPrefs.THEME_SYSTEM)
            val request by waterRequests.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                UserPrefs.THEME_LIGHT -> false
                UserPrefs.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            GuluTheme(darkTheme = darkTheme) { JiliguluRoot(request) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWaterIntent(intent)
    }

    private fun handleWaterIntent(intent: Intent?) {
        if ((intent?.getLongExtra(WaterReminderWorker.EXTRA_WATER_REMINDER, 0L) ?: 0L) > 0L) {
            waterRequests.value += 1
            intent?.removeExtra(WaterReminderWorker.EXTRA_WATER_REMINDER)
        }
    }
}

@Composable
private fun JiliguluRoot(waterRequest: Int) {
    val app = LocalContext.current.applicationContext as JiliguluApp
    val startup: StartupViewModel = viewModel(factory = viewModelFactory {
        initializer { StartupViewModel(app.container) }
    })
    val state by startup.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val startupCompleted = app.container.startupCompleted
    var mainReady by remember { mutableStateOf(startupCompleted) }
    LaunchedEffect(lifecycleOwner, startup) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // 进程内已完成过一次完整启动就直接进主界面，不再播放入场动画
            // （切窗口/旋转/内存回收导致的 Activity 重建不应触发预载动画）。
            if (app.container.startupCompleted) {
                mainReady = true
                return@repeatOnLifecycle
            }
            val entry = startup.enter()
            try { awaitCancellation() } finally { startup.pause(entry) }
        }
    }
    val showSplash = !startupCompleted && (!state.ready || (state.nickname?.isNotBlank() == true && !mainReady))
    LaunchedEffect(state.ready) {
        if (state.ready && state.nickname?.isNotBlank() == true) app.container.updates.check(automatic = true)
    }
    Box(Modifier.fillMaxSize()) {
        state.nickname?.takeIf { state.prepared }?.let { nickname ->
            val homeVm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
            val homeState by homeVm.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(homeState.isLoaded) { if (homeState.isLoaded) mainReady = true }
            val navController = rememberNavController()
            val start = remember { if (nickname.isBlank()) Routes.ONBOARDING else Routes.MAIN }
            LaunchedEffect(waterRequest) {
                if (waterRequest > 0 && nickname.isNotBlank()) {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.MAIN) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            NavHost(
                navController = navController, startDestination = start,
                enterTransition = { fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 24 } },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(250)) },
                popExitTransition = { fadeOut(tween(200)) }
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(active = !showSplash, onDone = {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    })
                }
                composable(Routes.MAIN) {
                    MainScreen(
                        onAddBill = { navController.navigate(Routes.ADD_BILL) },
                        onOpenChat = { navController.navigate(Routes.CHAT) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        homeVm = homeVm
                    )
                }
                composable(Routes.ADD_BILL) { AddBillScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.CHAT) { ChatScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(onBack = { navController.popBackStack() },
                        onPreviewWelcome = { navController.navigate(Routes.WELCOME_PREVIEW) })
                }
                composable(Routes.WELCOME_PREVIEW) {
                    OnboardingScreen(onDone = { navController.popBackStack() }, preview = true, active = !showSplash)
                }
            }
        }
        AnimatedVisibility(visible = showSplash, enter = fadeIn(tween(100)), exit = fadeOut(tween(160))) {
            StartupScreen(state.error, startup::prepare)
        }
        UpdatePromptHost(enabled = !showSplash)
    }
}
