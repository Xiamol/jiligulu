package com.jiligulu.app.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiligulu.app.ui.theme.GuluBrandFont
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiligulu.app.ui.home.HomeScreen
import com.jiligulu.app.ui.home.HomeViewModel
import com.jiligulu.app.ui.persona.DrinkingOverlay
import com.jiligulu.app.ui.persona.GuluCompanionHeader
import com.jiligulu.app.ui.persona.PersonaViewModel
import com.jiligulu.app.ui.stats.StatsScreen
import kotlinx.coroutines.awaitCancellation

/** Main destinations own scroll state; the companion occupies a fixed header slot. */
@Composable
fun MainScreen(
    onAddBill: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    homeVm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
    personaVm: PersonaViewModel = viewModel(factory = PersonaViewModel.Factory)
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val message by personaVm.bubble.collectAsStateWithLifecycle()
    val drinkingId by personaVm.drinkingId.collectAsStateWithLifecycle()
    val showDrinking = drinkingId != null
    val pageState = rememberSaveableStateHolder()
    val lifecycleOwner = LocalLifecycleOwner.current
    BackHandler(enabled = showDrinking, onBack = personaVm::cancelDrinking)

    LaunchedEffect(lifecycleOwner, personaVm) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            personaVm.onAppOpen()
            personaVm.startIdleTicker()
            try {
                awaitCancellation()
            } finally {
                personaVm.stopIdleTicker()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("叽里咕噜", fontFamily = GuluBrandFont, fontSize = 28.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "设置",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    GuluCompanionHeader(
                        message = message,
                        onRefresh = personaVm::onMascotClick,
                        onWaterClick = personaVm::startDrinking
                    )
                    Spacer(Modifier.height(12.dp))
                }
            },
            bottomBar = {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        MainTab.entries.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                icon = {
                                    Icon(if (selectedTab == index) tab.selectedIcon else tab.icon,
                                        contentDescription = null)
                                },
                                label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                pageState.SaveableStateProvider(selectedTab) {
                    when (selectedTab) {
                        0 -> HomeScreen(onOpenChat = onOpenChat, onAddBill = onAddBill, vm = homeVm)
                        1 -> StatsScreen()
                    }
                }
            }
        }
        DrinkingOverlay(visible = showDrinking, onFinished = personaVm::completeDrinking,
            onCancel = personaVm::cancelDrinking)
    }
}

private enum class MainTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("账本", Icons.AutoMirrored.Outlined.ReceiptLong, Icons.AutoMirrored.Outlined.ReceiptLong),
    STATS("统计", Icons.Outlined.BarChart, Icons.Filled.BarChart)
}
