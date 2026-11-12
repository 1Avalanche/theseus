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
import theseus.core.domain.user.Invite
import theseus.core.domain.user.Usage
import theseus.core.domain.user.User
import theseus.core.domain.user.UserRole
import theseus.core.domain.user.UserStatus
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class AccountsServiceTest {
    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val repository = mockk<AccountsRepository>(relaxed = true)
    private val service = AccountsService(repository, Clock.fixed(now, ZoneOffset.UTC), Duration.ofHours(72)) { "secret-code" }
    private val onboarding = User(7, 77, UserRole.USER, UserStatus.ONBOARDING, null, Gender.FEMALE, null, now, null)

    @Test
    fun `invite stores hash and configured expiry without plaintext code`() =
        runTest {
            val invite = Invite(1, AccountsService.hashCode("secret-code"), Gender.FEMALE, "маме", now, now.plus(Duration.ofHours(72)))
            coEvery { repository.createInvite(any(), any(), any(), any(), any()) } returns invite
            assertEquals(invite to "secret-code", service.createInvite(Gender.FEMALE, "маме"))
            coVerify {
                repository.createInvite(
                    AccountsService.hashCode("secret-code"),
                    Gender.FEMALE,
                    "маме",
                    now,
                    now.plus(Duration.ofHours(72)),
                )
            }
        }

    @Test
    fun `activation rejects expiry exactly at ttl and used or revoked invitations`() =
        runTest {
            val base = Invite(1, "hash", Gender.FEMALE, null, now.minusSeconds(1), now)
            for ((invite, code) in listOf(
                base to "invite_expired",
                base.copy(usedAt = now, usedByUserId = 2) to "invite_used",
                base.copy(revokedAt = now) to "invite_revoked",
            )) {
                coEvery { repository.lockInvite(any<String>()) } returns invite
                assertEquals(code, assertThrows<AccountException> { service.activate(77, "code") }.code)
            }
            coVerify(exactly = 0) { repository.createUser(any(), any(), any()) }
        }

    @Test
    fun `profile activates only after both name and timezone and keeps existing reminder timezone`() =
        runTest {
            coEvery { repository.lockUser(7) } returns onboarding
            val named = service.update(7, " Анна ", null, null)
            assertEquals(UserStatus.ONBOARDING, named.status)
            assertEquals("Анна", named.name)
            coEvery { repository.lockUser(7) } returns named
            val active = service.update(7, null, "Asia/Yekaterinburg", null)
            assertEquals(UserStatus.ACTIVE, active.status)
            assertEquals(now, active.onboardedAt)
            assertEquals(Gender.FEMALE, active.gender)
            coVerify(exactly = 0) { repository.skipDue(any(), any()) }
            assertEquals("invalid_timezone", assertThrows<AccountException> { service.update(7, null, "+03:00", null) }.code)
            assertThrows<AccountException> { service.update(7, " ", null, null) }
        }

    @Test
    fun `restore keeps incomplete onboarding and discards missed fires without deleting future reminders`() =
        runTest {
            coEvery { repository.lockUser(7) } returns onboarding.copy(status = UserStatus.REVOKED)
            assertEquals(UserStatus.ONBOARDING, service.access(7, restore = true).status)
            coVerify { repository.skipDue(7, now) }
            coEvery { repository.lockUser(7) } returns onboarding.copy(role = UserRole.ADMIN)
            assertEquals("invalid_state", assertThrows<AccountException> { service.access(7, restore = false) }.code)
        }

    @Test
    fun `usage rejects negative counts costs and cache larger than input`() =
        runTest {
            coEvery { repository.lockUser(7) } returns
                onboarding.copy(status = UserStatus.ACTIVE, name = "Аня", timezone = ZoneId.of("UTC"))
            val valid = Usage(7, "main", "model", "gen", 10, 1, 5, BigDecimal("0.00012345"), 500, now)
            for (usage in listOf(
                valid.copy(tokensIn = -1),
                valid.copy(tokensCached = 11),
                valid.copy(costUsd = BigDecimal("-0.01")),
                valid.copy(role = "other"),
                valid.copy(latencyMs = -1),
            )) {
                assertThrows<AccountException> { service.recordUsage(usage) }
            }
            service.recordUsage(valid)
            coVerify(exactly = 1) { repository.recordUsage(valid) }
        }
}
