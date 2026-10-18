package dev.jskw.counter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.jskw.counter.data.model.Counter
import dev.jskw.counter.data.repository.CounterRepository
import dev.jskw.counter.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CounterUiState(
    val counters: List<Counter> = emptyList(),
    val selectedCounterId: String? = null,
    val syncingCounterIds: Set<String> = emptySet(),
    val publishingCounterIds: Set<String> = emptySet(),
    val userMessage: String? = null,
    val showAddDialog: Boolean = false
) {
    val selectedCounter: Counter?
        get() = counters.find { it.id == selectedCounterId }
}

class CounterViewModel(
    private val repository: CounterRepository
) : ViewModel() {

    private val _selectedCounterId = MutableStateFlow<String?>(null)
    private val _syncingCounterIds = MutableStateFlow<Set<String>>(emptySet())
    private val _publishingCounterIds = MutableStateFlow<Set<String>>(emptySet())
    private val _userMessage = MutableStateFlow<String?>(null)
    private val _showAddDialog = MutableStateFlow(false)

    val uiState: StateFlow<CounterUiState> = combine(
        repository.getAllCounters(),
        combine(
            _selectedCounterId,
            _syncingCounterIds,
            _publishingCounterIds,
            _userMessage,
            _showAddDialog
        ) { selectedId, syncingIds, publishingIds, message, showDialog ->
            UiStateParams(selectedId, syncingIds, publishingIds, message, showDialog)
        }
    ) { counters, params ->
        val validSelectedId = if (counters.any { it.id == params.selectedId }) params.selectedId else null
        CounterUiState(
            counters = counters,
            selectedCounterId = validSelectedId,
            syncingCounterIds = params.syncingIds,
            publishingCounterIds = params.publishingIds,
            userMessage = params.message,
            showAddDialog = params.showDialog
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CounterUiState()
    )

    private data class UiStateParams(
        val selectedId: String?,
        val syncingIds: Set<String>,
        val publishingIds: Set<String>,
        val message: String?,
        val showDialog: Boolean
    )

    init {
        syncPublishedCounters()
    }

    fun selectCounter(id: String?) {
        _selectedCounterId.value = id
    }

    fun showAddDialog(show: Boolean) {
        _showAddDialog.value = show
    }

    fun createCounter(title: String, initialCount: Int = 0) {
        viewModelScope.launch {
            val trimmedTitle = title.trim()
            if (trimmedTitle.isEmpty()) {
                _userMessage.value = "Counter title cannot be empty"
                return@launch
            }
            val newCounter = repository.createCounter(trimmedTitle, initialCount)
            _selectedCounterId.value = newCounter.id
            _showAddDialog.value = false
        }
    }

    fun incrementCounter(id: String) {
        viewModelScope.launch {
            repository.incrementCounter(id)
        }
    }

    fun decrementCounter(id: String) {
        viewModelScope.launch {
            repository.decrementCounter(id)
        }
    }

    fun togglePin(id: String) {
        viewModelScope.launch {
            repository.togglePin(id)
        }
    }

    fun updatePinnedStatus(id: String, isPinned: Boolean) {
        viewModelScope.launch {
            repository.updatePinnedStatus(id, isPinned)
        }
    }

    fun deleteCounter(id: String) {
        viewModelScope.launch {
            if (_selectedCounterId.value == id) {
                _selectedCounterId.value = null
            }
            repository.deleteCounter(id)
            _userMessage.value = "Counter deleted"
        }
    }

    fun togglePublishCounter(id: String) {
        viewModelScope.launch {
            val currentCounter = uiState.value.counters.find { it.id == id } ?: return@launch
            AppLog.d("CounterVM", "togglePublishCounter: id=$id, isCurrentlyPublished=${currentCounter.isPublished}")
            _publishingCounterIds.update { it + id }
            try {
                if (currentCounter.isPublished) {
                    val result = repository.unpublishCounter(id)
                    result.onSuccess {
                        AppLog.i("CounterVM", "Counter $id successfully unpublished")
                        _userMessage.value = "Counter unpublished"
                    }.onFailure { e ->
                        AppLog.e("CounterVM", "Failed to unpublish counter $id: ${e.message}", e)
                        _userMessage.value = "Failed to unpublish counter: ${e.message}"
                    }
                } else {
                    val result = repository.publishCounter(id)
                    result.onSuccess {
                        AppLog.i("CounterVM", "Counter $id successfully published! ViewUuid=${it.viewUuid}")
                        _userMessage.value = "Counter published! Share link ready."
                    }.onFailure { e ->
                        AppLog.e("CounterVM", "Failed to publish counter $id: ${e.message}", e)
                        _userMessage.value = "Failed to publish counter: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                AppLog.e("CounterVM", "togglePublishCounter exception for $id: ${e.message}", e)
                _userMessage.value = "Operation failed: ${e.message}"
            } finally {
                _publishingCounterIds.update { it - id }
            }
        }
    }

    fun syncRemoteCount(id: String) {
        viewModelScope.launch {
            AppLog.d("CounterVM", "syncRemoteCount requested for id=$id")
            _syncingCounterIds.update { it + id }
            try {
                val result = repository.syncRemoteCount(id)
                result.onSuccess {
                    AppLog.d("CounterVM", "syncRemoteCount success for id=$id, count=${it.count}")
                    _userMessage.value = "Synced live count: ${it.count}"
                }.onFailure { e ->
                    AppLog.e("CounterVM", "syncRemoteCount failed for id=$id: ${e.message}", e)
                    _userMessage.value = "Sync failed: ${e.message}"
                }
            } finally {
                _syncingCounterIds.update { it - id }
            }
        }
    }

    fun syncPublishedCounters() {
        viewModelScope.launch {
            val counters = repository.getAllCounters().firstOrNull() ?: emptyList()
            val published = counters.filter { it.isPublished }
            AppLog.d("CounterVM", "syncPublishedCounters: syncing ${published.size} published counters")
            published.forEach { counter ->
                _syncingCounterIds.update { it + counter.id }
                try {
                    repository.syncRemoteCount(counter.id)
                } catch (e: Exception) {
                    AppLog.w("CounterVM", "syncPublishedCounters failed for ${counter.id}: ${e.message}")
                } finally {
                    _syncingCounterIds.update { it - counter.id }
                }
            }
        }
    }

    private var observeJob: kotlinx.coroutines.Job? = null
    private val observePinnedJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    fun observeRemoteCount(id: String) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            AppLog.d("CounterVM", "observeRemoteCount: starting observation for id=$id")
            try {
                repository.observeRemoteCount(id).collect { result ->
                    result.onSuccess {
                        // The repository updates the DB which updates the flows, so we just
                        // observe for errors.
                    }.onFailure { e ->
                        if (e is CancellationException) return@onFailure
                        AppLog.e("CounterVM", "observeRemoteCount error for $id: ${e.message}", e)
                        _userMessage.value = "Live sync interrupted: ${e.message}"
                    }
                }
            } catch(e: Exception) {
               if (e is CancellationException) throw e
               AppLog.e("CounterVM", "observeRemoteCount exception for $id: ${e.message}", e)
               _userMessage.value = "Live sync interrupted: ${e.message}"
            }
        }
    }

    fun stopObservingRemoteCount() {
        observeJob?.cancel()
        observeJob = null
    }

    fun observePinnedPublishedCounters(pinnedCounters: List<Counter>) {
        val publishedPinnedIds = pinnedCounters.filter { it.isPublished }.map { it.id }.toSet()

        val iterator = observePinnedJobs.iterator()
        while (iterator.hasNext()) {
            val (id, job) = iterator.next()
            if (id !in publishedPinnedIds) {
                job.cancel()
                iterator.remove()
            }
        }

        publishedPinnedIds.forEach { id ->
            if (!observePinnedJobs.containsKey(id)) {
                val job = viewModelScope.launch {
                    try {
                        repository.observeRemoteCount(id).collect {
                            // Repository updates DB which updates flows
                        }
                    } catch (_: Exception) {
                    }
                }
                observePinnedJobs[id] = job
            }
        }
    }

    fun stopAllRemoteObservations() {
        stopObservingRemoteCount()
        observePinnedJobs.values.forEach { it.cancel() }
        observePinnedJobs.clear()
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    class Factory(private val repository: CounterRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CounterViewModel(repository) as T
        }
    }
}
