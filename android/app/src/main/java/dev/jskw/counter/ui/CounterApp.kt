package dev.jskw.counter.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jskw.counter.notification.PinnedCounterNotifier
import dev.jskw.counter.ui.components.AddCounterDialog
import dev.jskw.counter.ui.components.CounterDetailPane
import dev.jskw.counter.ui.components.CounterListPane
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CounterApp(
    viewModel: CounterViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val navigator = rememberListDetailPaneScaffoldNavigator<String>()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    fun handleTogglePin(counterId: String) {
        val targetCounter = uiState.counters.find { it.id == counterId }
        val willBePinned = targetCounter?.isPinned == false

        if (willBePinned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        viewModel.togglePin(counterId)
    }

    val pinnedCounters = remember(uiState.counters) {
        uiState.counters.filter { it.isPinned }
    }

    LaunchedEffect(pinnedCounters) {
        PinnedCounterNotifier.updateNotification(context, pinnedCounters)
    }

    // Check status and sync published counters when user opens or resumes the app
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.syncPublishedCounters()
    }

    // Stream live count events via WebSockets only while the app is actively in the foreground
    LifecycleResumeEffect(pinnedCounters, uiState.selectedCounterId, uiState.selectedCounter?.isPublished) {
        viewModel.observePinnedPublishedCounters(pinnedCounters)
        val selected = uiState.selectedCounter
        if (selected != null && selected.isPublished) {
            viewModel.observeRemoteCount(selected.id)
        } else {
            viewModel.stopObservingRemoteCount()
        }
        onPauseOrDispose {
            viewModel.stopAllRemoteObservations()
        }
    }

    // Show user messages via Snackbar
    uiState.userMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearUserMessage()
        }
    }

    // Handle back button on mobile when detail is focused
    BackHandler(enabled = navigator.canNavigateBack()) {
        scope.launch {
            navigator.navigateBack()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        ListDetailPaneScaffold(
            directive = navigator.scaffoldDirective,
            value = navigator.scaffoldValue,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            listPane = {
                CounterListPane(
                    counters = uiState.counters,
                    selectedCounterId = uiState.selectedCounterId,
                    onSelectCounter = { counterId ->
                        viewModel.selectCounter(counterId)
                        scope.launch {
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, counterId)
                        }
                    },
                    onTogglePin = { counterId ->
                        handleTogglePin(counterId)
                    },
                    onAddCounterClick = {
                        viewModel.showAddDialog(true)
                    }
                )
            },
            detailPane = {
                val selectedCounter = uiState.selectedCounter
                val isPublishing = selectedCounter?.let { uiState.publishingCounterIds.contains(it.id) } ?: false

                CounterDetailPane(
                    counter = selectedCounter,
                    isPublishing = isPublishing,
                    onIncrement = { viewModel.incrementCounter(it) },
                    onDecrement = { viewModel.decrementCounter(it) },
                    onTogglePin = { counterId ->
                        handleTogglePin(counterId)
                    },
                    onDelete = { counterId ->
                        viewModel.deleteCounter(counterId)
                        scope.launch {
                            if (navigator.canNavigateBack()) {
                                navigator.navigateBack()
                            }
                        }
                    },
                    onTogglePublish = { viewModel.togglePublishCounter(it) },
                    onShowMessage = { msg ->
                        scope.launch { snackbarHostState.showSnackbar(msg) }
                    },
                    onBackClick = if (navigator.canNavigateBack()) {
                        {
                            scope.launch { navigator.navigateBack() }
                        }
                    } else null
                )
            }
        )
    }

    if (uiState.showAddDialog) {
        AddCounterDialog(
            onDismiss = { viewModel.showAddDialog(false) },
            onCreateCounter = { title, initialCount ->
                viewModel.createCounter(title, initialCount)
                // New counter is selected automatically in viewModel.createCounter
                scope.launch {
                    val newCounterId = viewModel.uiState.value.selectedCounterId
                    if (newCounterId != null) {
                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, newCounterId)
                    }
                }
            }
        )
    }
}
