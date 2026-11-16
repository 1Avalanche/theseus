package theseus.core.application

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import theseus.core.domain.reminder.After
import theseus.core.domain.reminder.NewReminder
import theseus.core.domain.reminder.ReminderStatus
import theseus.core.domain.reminder.When
import theseus.core.domain.reminder.WhenResolver
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

class SelectedDeletionTest {
    @Test fun `search reports incomplete candidate set before group selection`() =
        runTest {
            repeat(31) { create("Вода $it") }
            val result = service.search(1, "вода")
            assertEquals(30, result.reminders.size)
            assertTrue(result.truncated)
        }

    private val clock = MutableClock(Instant.parse("2026-11-02T07:15:00Z"))
    private val repository = InMemoryReminderRepository()
    private val service =
        ReminderService(
            repository,
            UserService(InMemoryUserRepository(user(), user(id = 2, chatId = 200))),
            WhenResolver(LocalTime.of(9, 0)),
            clock,
        )

    private suspend fun create(
        text: String,
        userId: Long = 1,
    ) = service.createOneTime(userId, text, When(after = After(days = 1)))

    @Test fun `selected deletion includes descendants once and preserves unrelated reminders`() =
        runTest {
            val water = create("Вода")
            val child =
                repository.create(
                    NewReminder(
                        1,
                        "Перенос воды",
                        water.timezone,
                        LocalDateTime.parse("2026-11-03T11:00"),
                        clock.instant().plusSeconds(86400),
                        parentId = water.id,
                    ),
                    clock.instant(),
                )
            val second = create("Купить воду")
            val other = create("Позвонить маме")
            val ids = listOf(water.id, child.id, second.id)
            val preview = service.deletionSelection(1, ids)
            assertEquals(3, preview.reminders.size)
            assertEquals(ErrorCode.CONFIRMATION_REQUIRED, assertThrows<DomainException> { service.deleteSelected(1, ids, null) }.error)
            assertEquals(4, service.list(1).reminders.size)
            val count = service.deleteSelected(1, ids, Confirmation(requireNotNull(preview.fingerprint), clock.instant()))
            assertEquals(3, count)
            assertEquals(listOf(other.id), service.list(1).reminders.map { it.id })
        }

    @Test fun `edited or newly added descendant invalidates whole selection before any deletion`() =
        runTest {
            val first = create("Вода")
            val second = create("Купить воду")
            val ids = listOf(first.id, second.id)
            val preview = service.deletionSelection(1, ids)
            repository.save(second.copy(text = "Изменено"), clock.instant())
            assertEquals(
                ErrorCode.CONFIRMATION_STALE,
                assertThrows<DomainException> {
                    service.deleteSelected(1, ids, Confirmation(requireNotNull(preview.fingerprint), clock.instant()))
                }.error,
            )
            assertTrue(repository.reminders.values.none { it.status == ReminderStatus.DELETED })
            val next = service.deletionSelection(1, ids)
            repository.create(
                NewReminder(
                    1,
                    "Новый перенос",
                    first.timezone,
                    first.startLocal,
                    requireNotNull(first.nextFireAt),
                    parentId = first.id,
                ),
                clock.instant(),
            )
            assertEquals(
                ErrorCode.CONFIRMATION_STALE,
                assertThrows<DomainException> {
                    service.deleteSelected(1, ids, Confirmation(requireNotNull(next.fingerprint), clock.instant()))
                }.error,
            )
            assertTrue(repository.reminders.values.none { it.status == ReminderStatus.DELETED })
        }

    @Test fun `foreign ids duplicates expired confirmation and unsupported descendants cannot delete anything`() =
        runTest {
            val own = create("Вода")
            val foreign = create("Чужое", 2)
            assertEquals(
                ErrorCode.NOT_FOUND,
                assertThrows<DomainException> { service.deletionSelection(1, listOf(own.id, foreign.id)) }.error,
            )
            assertEquals(
                ErrorCode.INVALID_ARGUMENTS,
                assertThrows<DomainException> { service.deletionSelection(1, listOf(own.id, own.id)) }.error,
            )
            val preview = service.deletionSelection(1, listOf(own.id))
            val confirmation = Confirmation(requireNotNull(preview.fingerprint), clock.instant())
            clock.advance(Duration.ofHours(1))
            assertEquals(
                ErrorCode.CONFIRMATION_STALE,
                assertThrows<DomainException> { service.deleteSelected(1, listOf(own.id), confirmation) }.error,
            )
            val child =
                repository.create(
                    NewReminder(
                        1,
                        "Неизвестный перенос",
                        own.timezone,
                        own.startLocal,
                        requireNotNull(own.nextFireAt),
                        parentId = own.id,
                    ),
                    clock.instant(),
                )
            repository.reminders[child.id] = child.copy(status = ReminderStatus.UNSUPPORTED, rawStatus = "future")
            assertEquals(ErrorCode.UNSUPPORTED_STATE, assertThrows<DomainException> { service.deletionSelection(1, listOf(own.id)) }.error)
            assertTrue(repository.reminders.values.none { it.status == ReminderStatus.DELETED })
        }
}
