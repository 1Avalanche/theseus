package theseus.core.application

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import theseus.core.domain.user.AccountException
import theseus.core.domain.user.Gender
import theseus.core.domain.user.User
import theseus.core.domain.user.UserRole
import theseus.core.domain.user.UserStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class HardDeleteServiceTest {
    private val now = Instant.parse("2026-11-14T10:00:00Z")
    private val repository = mockk<HardDeleteRepository>(relaxed = true)
    private val service = HardDeleteService(repository, Clock.fixed(now, ZoneOffset.UTC), 1)
    private val target = User(7, 77, UserRole.USER, UserStatus.ONBOARDING, null, Gender.FEMALE, null, now, null)

    @Test fun `prepare blocks ordinary user and complete requires block before irreversible deletion`() =
        runTest {
            coEvery { repository.lockUser(7) } returns target
            assertThrows<AccountException> { service.complete(7) }
            assertEquals(UserStatus.REVOKED, service.prepare(7).first.status)
            coVerify(exactly = 0) { repository.eraseUser(any()) }
            coEvery { repository.lockUser(7) } returns target.copy(status = UserStatus.REVOKED)
            service.complete(7)
            coVerify {
                repository.lockChat(77)
                repository.eraseUser(7)
            }
        }

    @Test fun `owner and every admin are protected and unknown user is rejected`() =
        runTest {
            for (protected in listOf(target.copy(role = UserRole.ADMIN), target.copy(chatId = 1))) {
                coEvery { repository.lockUser(7) } returns protected
                assertThrows<AccountException> { service.prepare(7) }
                assertThrows<AccountException> { service.complete(7) }
            }
            coEvery { repository.lockUser(7) } returns null
            assertEquals("not_found", assertThrows<AccountException> { service.prepare(7) }.code)
            coVerify(exactly = 0) {
                repository.eraseUser(any())
                repository.saveUser(any(), any())
            }
        }

    @Test fun `owner access is rechecked independently of target`() =
        runTest {
            coEvery { repository.findByChatId(1) } returns
                target.copy(id = 1, chatId = 1, role = UserRole.ADMIN, status = UserStatus.ACTIVE)
            service.requireOwner()
            coEvery { repository.findByChatId(1) } returns target.copy(role = UserRole.ADMIN, status = UserStatus.REVOKED)
            assertThrows<AccountException> { service.requireOwner() }
        }
}
