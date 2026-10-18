package dev.jskw.counter

import dev.jskw.counter.data.repository.CounterRepositoryImpl
import dev.jskw.counter.ui.CounterViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CounterViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeDao: FakeCounterDao
    private lateinit var fakeApi: FakeCounterApiService
    private lateinit var repository: CounterRepositoryImpl
    private lateinit var viewModel: CounterViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeDao = FakeCounterDao()
        fakeApi = FakeCounterApiService()
        repository = CounterRepositoryImpl(fakeDao, fakeApi)
        viewModel = CounterViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testCreateCounterUpdatesState() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.createCounter(title = "Trick-or-Treaters", initialCount = 5)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.counters.size)
        assertEquals("Trick-or-Treaters", state.counters[0].title)
        assertEquals(5, state.counters[0].count)
        assertEquals(state.counters[0].id, state.selectedCounterId)
        assertNotNull(state.selectedCounter)
    }

    @Test
    fun testIncrementAndDecrementCounter() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.createCounter(title = "Bottles of Water", initialCount = 10)
        advanceUntilIdle()

        val counterId = viewModel.uiState.value.selectedCounterId!!

        viewModel.incrementCounter(counterId)
        advanceUntilIdle()
        assertEquals(11, viewModel.uiState.value.selectedCounter?.count)

        viewModel.decrementCounter(counterId)
        advanceUntilIdle()
        assertEquals(10, viewModel.uiState.value.selectedCounter?.count)
    }

    @Test
    fun testTogglePin() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.createCounter(title = "Pinned Counter", initialCount = 15)
        advanceUntilIdle()

        val counterId = viewModel.uiState.value.selectedCounterId!!
        assertFalse(viewModel.uiState.value.selectedCounter!!.isPinned)

        viewModel.togglePin(counterId)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.selectedCounter!!.isPinned)

        viewModel.togglePin(counterId)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.selectedCounter!!.isPinned)
    }

    @Test
    fun testDeleteCounterClearsSelection() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.createCounter(title = "Temp Counter", initialCount = 1)
        advanceUntilIdle()

        val counterId = viewModel.uiState.value.selectedCounterId!!
        assertNotNull(counterId)

        viewModel.deleteCounter(counterId)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.counters.isEmpty())
        assertNull(viewModel.uiState.value.selectedCounterId)
        assertNull(viewModel.uiState.value.selectedCounter)
    }

    @Test
    fun testTogglePublishCounter() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.createCounter(title = "Online Counter", initialCount = 3)
        advanceUntilIdle()

        val counterId = viewModel.uiState.value.selectedCounterId!!
        assertFalse(viewModel.uiState.value.selectedCounter!!.isPublished)

        viewModel.togglePublishCounter(counterId)
        advanceUntilIdle()

        val published = viewModel.uiState.value.selectedCounter!!
        assertTrue(published.isPublished)
        assertNotNull(published.viewUuid)
        assertNotNull(published.updateUuid)

        // Toggle again to unpublish
        viewModel.togglePublishCounter(counterId)
        advanceUntilIdle()

        val unpublished = viewModel.uiState.value.selectedCounter!!
        assertFalse(unpublished.isPublished)
        assertEquals(published.viewUuid, unpublished.viewUuid)
        assertEquals(published.updateUuid, unpublished.updateUuid)

        // Toggle third time to republish
        viewModel.togglePublishCounter(counterId)
        advanceUntilIdle()

        val republished = viewModel.uiState.value.selectedCounter!!
        assertTrue(republished.isPublished)
        assertEquals(published.viewUuid, republished.viewUuid)
        assertEquals(published.updateUuid, republished.updateUuid)
    }

    @Test
    fun testShowAddDialogToggle() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        assertFalse(viewModel.uiState.value.showAddDialog)

        viewModel.showAddDialog(true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showAddDialog)

        viewModel.showAddDialog(false)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showAddDialog)
    }

    @Test
    fun testSyncPublishedCountersUpdatesCount() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.createCounter(title = "Sync Counter", initialCount = 10)
        advanceUntilIdle()
        val counterId = viewModel.uiState.value.selectedCounterId!!

        viewModel.togglePublishCounter(counterId)
        advanceUntilIdle()
        assertEquals(10, viewModel.uiState.value.selectedCounter?.count)

        // syncPublishedCounters will fetch live count (42 from fakeApi)
        viewModel.syncPublishedCounters()
        advanceUntilIdle()

        assertEquals(42, viewModel.uiState.value.selectedCounter?.count)
    }

    @Test
    fun testObserveRemoteCountDoesNotSetPublishingIds() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.createCounter(title = "Live Counter", initialCount = 10)
        advanceUntilIdle()
        val counterId = viewModel.uiState.value.selectedCounterId!!

        viewModel.togglePublishCounter(counterId)
        advanceUntilIdle()

        viewModel.observeRemoteCount(counterId)
        advanceUntilIdle()

        // Verify observeRemoteCount does not put counter in publishingCounterIds
        assertFalse(viewModel.uiState.value.publishingCounterIds.contains(counterId))

        // Ensure user can toggle unpublish even while observing
        viewModel.togglePublishCounter(counterId)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.selectedCounter!!.isPublished)
    }
}
