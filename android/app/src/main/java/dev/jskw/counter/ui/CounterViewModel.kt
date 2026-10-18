package dev.jskw.counter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.jskw.counter.data.model.Counter
import dev.jskw.counter.data.repository.CounterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CounterUiState(
    val counters: List<Counter> = emptyList(),
    val selectedCounterId: String? = null,
    val syncingCounterIds: Set<String> = emptySet(),
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
    private val _userMessage = MutableStateFlow<String?>(null)
    private val _showAddDialog = MutableStateFlow(false)

    val uiState: StateFlow<CounterUiState> = combine(
        repository.getAllCounters(),
        _selectedCounterId,
        _syncingCounterIds,
        _userMessage,
        _showAddDialog
    ) { counters, selectedId, syncingIds, message, showDialog ->
        val validSelectedId = if (counters.any { it.id == selectedId }) selectedId else null
        CounterUiState(
            counters = counters,
            selectedCounterId = validSelectedId,
            syncingCounterIds = syncingIds,
            userMessage = message,
            showAddDialog = showDialog
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CounterUiState()
    )

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
            _syncingCounterIds.update { it + id }
            try {
                if (currentCounter.isPublished) {
                    repository.unpublishCounter(id)
                    _userMessage.value = "Counter unpublished"
                } else {
                    val published = repository.publishCounter(id)
                    if (published != null) {
                        _userMessage.value = "Counter published! Share link ready."
                    } else {
                        _userMessage.value = "Failed to publish counter"
                    }
                }
            } catch (e: Exception) {
                _userMessage.value = "Operation failed: ${e.message}"
            } finally {
                _syncingCounterIds.update { it - id }
            }
        }
    }

    fun syncRemoteCount(id: String) {
        viewModelScope.launch {
            _syncingCounterIds.update { it + id }
            try {
                val result = repository.syncRemoteCount(id)
                result.onSuccess {
                    _userMessage.value = "Synced live count: ${it.count}"
                }.onFailure { e ->
                    _userMessage.value = "Sync failed: ${e.message}"
                }
            } finally {
                _syncingCounterIds.update { it - id }
            }
        }
    }

    private var observeJob: kotlinx.coroutines.Job? = null

    fun observeRemoteCount(id: String) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            _syncingCounterIds.update { it + id }
            try {
                repository.observeRemoteCount(id).collect { result ->
                    result.onSuccess {
                        // The repository updates the DB which updates the flows, so we just
                        // observe for errors.
                    }.onFailure { e ->
                        _userMessage.value = "Live sync interrupted: ${e.message}"
                        _syncingCounterIds.update { it - id }
                    }
                }
            } catch(e: Exception) {
               _userMessage.value = "Live sync interrupted: ${e.message}"
               _syncingCounterIds.update { it - id }
            }
        }
    }

    fun stopObservingRemoteCount() {
        observeJob?.cancel()
        observeJob = null
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
