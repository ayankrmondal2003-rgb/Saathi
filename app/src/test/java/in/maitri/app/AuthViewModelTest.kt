package `in`.maitri.app

import `in`.maitri.app.data.*
import `in`.maitri.app.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeAuth()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun validateRegistration() {
        assertNotNull(AuthForm.validate("", "a@test.org", "abcdef", "abcdef"))
        assertNotNull(AuthForm.validate("A", "bad", "abcdef", "abcdef"))
        assertNotNull(AuthForm.validate("A", "a@test.org", "abc", "abc"))
        assertNotNull(AuthForm.validate("A", "a@test.org", "abcdef", "different"))
        assertNull(AuthForm.validate("A", "a@test.org", "abcdef", "abcdef"))
    }

    @Test fun loginReportsLoadingThenError() {
        val vm = AuthViewModel(repo)
        vm.submit(null, "a@test.org", "password")
        assertTrue(vm.state.value.busy)
        dispatcher.scheduler.runCurrent()
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.message)
        assertEquals(Gate.SIGNED_OUT, vm.state.value.gate)
    }

    @Test fun registrationVerificationAndLogout() {
        val vm = AuthViewModel(repo)
        dispatcher.scheduler.runCurrent()
        vm.submit("A", "a@test.org", "abcdef", "abcdef")
        dispatcher.scheduler.runCurrent()
        assertEquals(Gate.VERIFY, vm.state.value.gate)
        assertEquals(1, repo.sent)
        vm.resend()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, repo.sent)
        vm.refresh()
        dispatcher.scheduler.runCurrent()
        assertEquals(Gate.HOME, vm.state.value.gate)
        vm.logout()
        dispatcher.scheduler.runCurrent()
        assertNull(repo.current)
        assertEquals(Gate.SIGNED_OUT, vm.state.value.gate)
    }

    @Test fun restoredUnverifiedSessionRequiresVerification() {
        repo.sessions.value = Identity("uid", "A", "a@test.org", false, null)
        val vm = AuthViewModel(repo)
        dispatcher.scheduler.runCurrent()
        assertEquals(Gate.VERIFY, vm.state.value.gate)
    }

    @Test fun responderStartsOffDuty() {
        assertFalse(Responder().enabled)
        assertFalse(Responder().available)
    }

    private class FakeAuth : AuthRepository {
        override val sessions = MutableStateFlow<Identity?>(null)
        override val current get() = sessions.value
        var sent = 0
        override suspend fun login(email: String, password: String) { error("Rejected") }
        override suspend fun register(name: String, email: String, password: String) {
            sessions.value = Identity("uid", name, email, false, 1L)
        }
        override suspend fun sendVerification() { sent++ }
        override suspend fun refresh() { sessions.value = current?.copy(verified = true) }
        override suspend fun reset(email: String) = Unit
        override fun logout() { sessions.value = null }
    }
}
