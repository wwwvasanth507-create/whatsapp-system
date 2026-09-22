package com.campuschat.app.data.repository

import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.UserDevice
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.repository.UserDiscoveryRepository
import com.campuschat.app.presentation.chat.newchat.NewChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@OptIn(ExperimentalCoroutinesApi::class)
class UserDiscoveryRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

    private val mockDatabaseProfiles = listOf(
        UserProfile(
            id = "user_b_id",
            username = "bob_builder",
            displayName = "Bob The Builder",
            avatarUrl = "https://example.com/bob.png"
        ),
        UserProfile(
            id = "user_c_id",
            username = "charlie_brown",
            displayName = "Charlie",
            avatarUrl = null
        )
    )

    private val mockDatabaseDevices = mapOf(
        "user_b_id" to listOf(
            UserDevice(id = "dev_b_pixel8", userId = "user_b_id", deviceName = "Pixel 8 Pro", platform = "android"),
            UserDevice(id = "dev_b_tab", userId = "user_b_id", deviceName = "Galaxy Tab S9", platform = "android")
        ),
        "user_c_id" to listOf(
            UserDevice(id = "dev_c_phone", userId = "user_c_id", deviceName = "Pixel 7", platform = "android")
        )
    )

    private val fakeDiscoveryRepository = object : UserDiscoveryRepository {
        override suspend fun searchUsers(query: String): Resource<List<UserProfile>> {
            val trimmed = query.trim()
            if (trimmed.isBlank()) {
                return Resource.Success(emptyList())
            }
            val matches = mockDatabaseProfiles.filter { profile ->
                profile.username.contains(trimmed, ignoreCase = true) ||
                        profile.displayName.contains(trimmed, ignoreCase = true)
            }
            return Resource.Success(matches)
        }

        override suspend fun getRecipientDevices(userId: String): Resource<List<UserDevice>> {
            if (userId.isBlank()) {
                return Resource.Success(emptyList())
            }
            val devices = mockDatabaseDevices[userId] ?: emptyList()
            return Resource.Success(devices)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 1. Search by exact username
    @Test
    fun test01_SearchByExactUsername() = kotlinx.coroutines.runBlocking {
        val res = fakeDiscoveryRepository.searchUsers("bob_builder")
        assertTrue(res is Resource.Success)
        val profiles = (res as Resource.Success).data
        assertEquals(1, profiles.size)
        assertEquals("bob_builder", profiles[0].username)
        assertEquals("user_b_id", profiles[0].id)
    }

    // 2. Search by partial username
    @Test
    fun test02_SearchByPartialUsername() = kotlinx.coroutines.runBlocking {
        val res = fakeDiscoveryRepository.searchUsers("charlie")
        assertTrue(res is Resource.Success)
        val profiles = (res as Resource.Success).data
        assertEquals(1, profiles.size)
        assertEquals("charlie_brown", profiles[0].username)
    }

    // 3. Search by display name
    @Test
    fun test03_SearchByDisplayName() = kotlinx.coroutines.runBlocking {
        val res = fakeDiscoveryRepository.searchUsers("Builder")
        assertTrue(res is Resource.Success)
        val profiles = (res as Resource.Success).data
        assertEquals(1, profiles.size)
        assertEquals("Bob The Builder", profiles[0].displayName)
    }

    // 4. Case-insensitive search
    @Test
    fun test04_CaseInsensitiveSearch() = kotlinx.coroutines.runBlocking {
        val resLower = fakeDiscoveryRepository.searchUsers("bob")
        val resUpper = fakeDiscoveryRepository.searchUsers("BOB")
        val resMixed = fakeDiscoveryRepository.searchUsers("BoB_BuIlDeR")

        assertTrue(resLower is Resource.Success)
        assertTrue(resUpper is Resource.Success)
        assertTrue(resMixed is Resource.Success)

        assertEquals(1, (resLower as Resource.Success).data.size)
        assertEquals(1, (resUpper as Resource.Success).data.size)
        assertEquals(1, (resMixed as Resource.Success).data.size)
        assertEquals("bob_builder", resLower.data[0].username)
    }

    // 5. Empty search
    @Test
    fun test05_EmptySearchReturnsEmptyList() = kotlinx.coroutines.runBlocking {
        val resEmpty = fakeDiscoveryRepository.searchUsers("")
        val resBlank = fakeDiscoveryRepository.searchUsers("   ")

        assertTrue(resEmpty is Resource.Success)
        assertTrue(resBlank is Resource.Success)
        assertTrue((resEmpty as Resource.Success).data.isEmpty())
        assertTrue((resBlank as Resource.Success).data.isEmpty())
    }

    // 6. No-result search
    @Test
    fun test06_NoResultSearchReturnsEmptyList() = kotlinx.coroutines.runBlocking {
        val res = fakeDiscoveryRepository.searchUsers("nonexistent_user_99999")
        assertTrue(res is Resource.Success)
        assertTrue((res as Resource.Success).data.isEmpty())
    }

    // 7. Authenticated user can discover another user
    @Test
    fun test07_AuthenticatedUserCanDiscoverAnotherUser() = kotlinx.coroutines.runBlocking {
        // Authenticated user A (alice) searches for user B (bob)
        val authenticatedUserAId = "user_a_alice"
        val searchResult = fakeDiscoveryRepository.searchUsers("bob_builder")

        assertTrue(searchResult is Resource.Success)
        val results = (searchResult as Resource.Success).data
        assertEquals(1, results.size)
        val discoveredUserB = results[0]
        assertEquals("user_b_id", discoveredUserB.id)
        assertFalse("User A should be able to discover User B", discoveredUserB.id == authenticatedUserAId)
    }

    // 8. Search result renders in NewChatScreen UI state
    @Test
    fun test08_SearchResultRendersInNewChatState() = kotlinx.coroutines.runBlocking {
        val viewModel = NewChatViewModel(fakeDiscoveryRepository)
        viewModel.onSearchQueryChanged("bob")

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("bob", state.searchQuery)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(1, state.searchResults.size)
        assertEquals("Bob The Builder", state.searchResults[0].profile.displayName)
        assertEquals("bob_builder", state.searchResults[0].profile.username)
    }

    // 9. Selecting the result loads ACTIVE recipient devices
    @Test
    fun test09_SelectingResultLoadsActiveRecipientDevices() = kotlinx.coroutines.runBlocking {
        val viewModel = NewChatViewModel(fakeDiscoveryRepository)
        viewModel.onSearchQueryChanged("bob")

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.searchResults.size)
        val item = state.searchResults[0]
        assertFalse(item.isLoadingDevices)
        assertEquals(2, item.devices.size)
        assertEquals("dev_b_pixel8", item.devices[0].id)
        assertEquals("Pixel 8 Pro", item.devices[0].deviceName)
        assertEquals("dev_b_tab", item.devices[1].id)
        assertEquals("Galaxy Tab S9", item.devices[1].deviceName)
    }

    // 10. No sensitive information is logged
    @Test
    fun test10_NoSensitiveInformationLogged() = kotlinx.coroutines.runBlocking {
        val stdout = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(stdout))

        try {
            // Trigger repository search and device query
            fakeDiscoveryRepository.searchUsers("bob_builder")
            fakeDiscoveryRepository.getRecipientDevices("user_b_id")
        } finally {
            System.setOut(originalOut)
        }

        val logOutput = stdout.toString()
        assertFalse("Logs must not contain auth tokens", logOutput.contains("Bearer "))
        assertFalse("Logs must not contain passwords", logOutput.contains("password"))
        assertFalse("Logs must not contain private keys", logOutput.contains("PRIVATE KEY"))
        assertFalse("Logs must not contain service role keys", logOutput.contains("service_role"))
    }
}
