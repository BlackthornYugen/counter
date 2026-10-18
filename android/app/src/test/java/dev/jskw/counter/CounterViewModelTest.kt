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
        val created = state.counters.find { it.title == "Trick-or-Treaters" }
        assertNotNull(created)
        assertEquals(5, created?.count)
        assertEquals(created?.id, state.selectedCounterId)
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
    fun testDecrementCounterRefusesAtZero() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.createCounter(title = "Zero Counter", initialCount = 0)
        advanceUntilIdle()

        val counterId = viewModel.uiState.value.selectedCounterId!!

        viewModel.decrementCounter(counterId)
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.selectedCounter?.count)
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
    fun testPinningSecondCounterUnpinsFirst() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.createCounter(title = "Counter A", initialCount = 0)
        advanceUntilIdle()
        val idA = viewModel.uiState.value.selectedCounterId!!

        viewModel.createCounter(title = "Counter B", initialCount = 0)
        advanceUntilIdle()
        val idB = viewModel.uiState.value.selectedCounterId!!

        viewModel.togglePin(idA)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.counters.find { it.id == idA }!!.isPinned)

        viewModel.togglePin(idB)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.counters.find { it.id == idA }!!.isPinned)
        assertTrue(viewModel.uiState.value.counters.find { it.id == idB }!!.isPinned)
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

        assertFalse(viewModel.uiState.value.counters.any { it.id == counterId })
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

    @Test
    fun testDeleteCounterKeepOnServer() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.createCounter(title = "Local Remove", initialCount = 1)
        advanceUntilIdle()

        val counterId = viewModel.uiState.value.selectedCounterId!!
        viewModel.togglePublishCounter(counterId)
        advanceUntilIdle()

        fakeApi.deletedUuid = null
        viewModel.deleteCounter(counterId, deleteOnServer = false)
        advanceUntilIdle()

        assertNull(fakeApi.deletedUuid)
        assertFalse(viewModel.uiState.value.counters.any { it.id == counterId })
        assertEquals("Counter removed from device", viewModel.uiState.value.userMessage)
    }

    @Test
    fun testForgetAdminKeyInViewModel() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        viewModel.createCounter(title = "Admin Counter", initialCount = 1)
        advanceUntilIdle()

        val counterId = viewModel.uiState.value.selectedCounterId!!
        viewModel.togglePublishCounter(counterId)
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.selectedCounter!!.deleteUuid)

        viewModel.forgetAdminKey(counterId)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedCounter!!.deleteUuid)
        assertEquals("Admin key forgotten", viewModel.uiState.value.userMessage)
    }

    @Test
    fun testUnpublishWithoutAdminKeyFailsInViewModel() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        val roUuid = "c768f56e-8ff4-4363-8822-79ee8ca6eef4"
        fakeApi.tokenRolesMap[roUuid] = "read_only"

        viewModel.importCounterFromLink("#RO Counter#read#$roUuid")
        advanceUntilIdle()

        val counterId = viewModel.uiState.value.selectedCounterId!!
        assertNull(viewModel.uiState.value.selectedCounter!!.deleteUuid)

        viewModel.togglePublishCounter(counterId)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.selectedCounter!!.isPublished)
        assertTrue(viewModel.uiState.value.userMessage!!.contains("Failed to unpublish counter"))
    }
}
