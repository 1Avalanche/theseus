package theseus.core.application

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import theseus.core.domain.reminder.After
import theseus.core.domain.reminder.NewReminder
import theseus.core.domain.reminder.Recurrence
import theseus.core.domain.reminder.Reminder
import theseus.core.domain.reminder.ReminderStatus
import theseus.core.domain.reminder.When
import theseus.core.domain.reminder.WhenResolver
import theseus.core.texts.Texts
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ReminderDomainTest {
    @Test
    fun `unknown reminder blocks delete all without hiding it behind empty preview`() =
        runTest {
            val root = series()
            reminders.save(root.copy(status = ReminderStatus.UNSUPPORTED, rawStatus = "future_status"), clock.instant())
            val preview = service.list(1)
            assertTrue(preview.reminders.isEmpty())
            assertEquals(1, preview.unsupportedCount)
            val snapshot = Confirmation(requireNotNull(preview.fingerprint), clock.instant())
            assertEquals(ErrorCode.UNSUPPORTED_STATE, assertThrows<DomainException> { service.deleteAll(1, snapshot) }.error)
            assertEquals("future_status", reminders.reminders.getValue(root.id).rawStatus)
        }

    @Test
    fun `delete all requires snapshot and covers paused series transfers and own one time reminders`() =
        runTest {
            val root = series()
            moveFirst(root.id)
            service.pause(1, root.id, null, null)
            service.create(1, "Проверить почту", When(time = LocalTime.of(18, 0)), null)
            service.create(2, "Чужое", When(time = LocalTime.of(18, 0)), null)
            assertEquals(ErrorCode.CONFIRMATION_REQUIRED, assertThrows<DomainException> { service.deleteAll(1, null) }.error)
            assertEquals(3, service.list(1).reminders.size)
            val snapshot = Confirmation(requireNotNull(service.list(1).fingerprint), clock.instant())
            assertEquals(3, service.deleteAll(1, snapshot))
            assertTrue(service.list(1).reminders.isEmpty())
            assertEquals(1, service.list(2).reminders.size)
            reminders.reminders.values.filter { it.userId == 1L }.forEach {
                assertEquals(ReminderStatus.DELETED, it.status)
                assertNull(it.nextFireAt)
                assertNull(it.snoozedUntil)
                assertNull(it.pausedUntil)
            }
        }

    @Test
    fun `new reminder or edited target invalidates delete all without partial deletion`() =
        runTest {
            val root = series()
            val snapshot = Confirmation(requireNotNull(service.list(1).fingerprint), clock.instant())
            service.create(1, "Проверить почту", When(time = LocalTime.of(18, 0)), null)
            assertEquals(ErrorCode.CONFIRMATION_STALE, assertThrows<DomainException> { service.deleteAll(1, snapshot) }.error)
            assertEquals(2, service.list(1).reminders.size)
            val current = Confirmation(requireNotNull(service.list(1).fingerprint), clock.instant())
            service.update(1, root.id, "series", null, "Новое содержание", null, null)
            assertEquals(ErrorCode.CONFIRMATION_STALE, assertThrows<DomainException> { service.deleteAll(1, current) }.error)
            assertEquals(2, service.list(1).reminders.size)
        }

    @Test
    fun `expired delete all leaves every target unchanged`() =
        runTest {
            series()
            val snapshot = Confirmation(requireNotNull(service.list(1).fingerprint), clock.instant())
            clock.now = clock.instant().plus(Duration.ofHours(1))
            assertEquals(ErrorCode.CONFIRMATION_STALE, assertThrows<DomainException> { service.deleteAll(1, snapshot) }.error)
            assertEquals(1, service.list(1).reminders.size)
        }

    private val clock = MutableClock(Instant.parse("2026-11-02T07:15:00Z"))
    private val reminders = InMemoryReminderRepository()
    private val profiles = InMemoryUserRepository(user(), user(id = 2, chatId = 200))
    private val service = ReminderService(reminders, UserService(profiles), WhenResolver(LocalTime.of(9, 0)), clock)

    private suspend fun series(time: LocalTime = LocalTime.of(11, 0)): Reminder =
        requireNotNull(service.create(1, "Принять таблетки", null, Recurrence("daily", time = time)).reminder)

    private suspend fun confirmation(id: Long): Confirmation = Confirmation(service.snapshot(1, id), clock.instant())

    private suspend fun moveFirst(id: Long): ReminderChange =
        service.update(
            1,
            id,
            "occurrence",
            LocalDateTime.parse("2026-11-02T11:00"),
            null,
            When(date = LocalDate.parse("2026-11-03"), time = LocalTime.of(13, 0)),
            null,
        )

    @Test
    fun `creates recurring reminder with local schedule and upcoming occurrences`() =
        runTest {
            val reminder = series()

            assertEquals("FREQ=DAILY;INTERVAL=1", reminder.recurrenceRule)
            assertEquals(Instant.parse("2026-11-02T08:00:00Z"), reminder.nextFireAt)
            assertEquals(ZoneId.of("Europe/Moscow"), reminder.timezone)
            assertEquals(
                listOf("2026-11-02T11:00", "2026-11-03T11:00", "2026-11-04T11:00", "2026-11-05T11:00", "2026-11-06T11:00"),
                service.upcoming(1, reminder.id).map(LocalDateTime::toString),
            )
        }

    @Test
    fun `possible duplicate requires both normalized text and overlapping time`() =
        runTest {
            val first = requireNotNull(service.create(1, "Принять таблетки", When(time = LocalTime.of(11, 0)), null).reminder)
            val duplicate = service.create(1, "принять, ТАБЛЕТКИ!", When(time = LocalTime.of(11, 30)), null)

            assertNull(duplicate.reminder)
            assertEquals(listOf(first.id), duplicate.candidates.map { it.id })
            assertEquals(1, reminders.reminders.size)
            assertTrue(service.create(1, "Принять таблетки", When(time = LocalTime.of(13, 0)), null).reminder != null)
            assertTrue(service.create(1, "Позвонить маме", When(time = LocalTime.of(11, 0)), null).reminder != null)
            assertEquals(3, reminders.reminders.size)
        }

    @Test
    fun `force requires confirmation and creates duplicate after valid snapshot`() =
        runTest {
            service.create(1, "Принять таблетки", When(time = LocalTime.of(11, 0)), null)
            val duplicate = service.create(1, "Принять таблетки", When(time = LocalTime.of(11, 30)), null)

            assertEquals(
                ErrorCode.CONFIRMATION_REQUIRED,
                assertThrows<DomainException> {
                    service.create(1, "Принять таблетки", When(time = LocalTime.of(11, 30)), null, force = true)
                }.error,
            )
            val forced =
                service.create(
                    1,
                    "Принять таблетки",
                    When(time = LocalTime.of(11, 30)),
                    null,
                    force = true,
                    confirmation = Confirmation(duplicate.fingerprint, clock.instant()),
                )

            assertTrue(forced.reminder != null)
            assertEquals(2, reminders.reminders.size)
        }

    @Test
    fun `changed duplicate invalidates creation confirmation`() =
        runTest {
            val first = requireNotNull(service.create(1, "Принять таблетки", When(time = LocalTime.of(11, 0)), null).reminder)
            val duplicate = service.create(1, "Принять таблетки", When(time = LocalTime.of(11, 30)), null)
            service.update(1, first.id, null, null, "Принять новые таблетки", null, null)

            val error =
                assertThrows<DomainException> {
                    service.create(
                        1,
                        "Принять таблетки",
                        When(time = LocalTime.of(11, 30)),
                        null,
                        force = true,
                        confirmation = Confirmation(duplicate.fingerprint, clock.instant()),
                    )
                }

            assertEquals(ErrorCode.CONFIRMATION_STALE, error.error)
            assertEquals(1, reminders.reminders.size)
        }

    @Test
    fun `moves one occurrence into child and preserves remaining series`() =
        runTest {
            val original = series()
            val moved = moveFirst(original.id)
            val remaining = requireNotNull(moved.series)

            assertEquals(original.id, moved.reminder.parentId)
            assertNull(moved.reminder.recurrenceRule)
            assertEquals(LocalDateTime.parse("2026-11-03T13:00"), moved.reminder.startLocal)
            assertEquals(setOf(Instant.parse("2026-11-02T08:00:00Z")), remaining.excludedOccurrences)
            assertEquals(Instant.parse("2026-11-03T08:00:00Z"), remaining.nextFireAt)
            assertEquals(2, reminders.reminders.size)
        }

    @Test
    fun `skips one concrete occurrence without creating child`() =
        runTest {
            val original = series()
            val skipped =
                service.delete(
                    1,
                    original.id,
                    "occurrence",
                    LocalDateTime.parse("2026-11-02T11:00"),
                    confirmation(original.id),
                )

            assertEquals(setOf(Instant.parse("2026-11-02T08:00:00Z")), skipped.excludedOccurrences)
            assertEquals(Instant.parse("2026-11-03T08:00:00Z"), skipped.nextFireAt)
            assertEquals(1, reminders.reminders.size)
            assertEquals(
                ErrorCode.OCCURRENCE_NOT_FOUND,
                assertThrows<DomainException> {
                    service.delete(
                        1,
                        original.id,
                        "occurrence",
                        LocalDateTime.parse("2026-11-02T11:00"),
                        confirmation(original.id),
                    )
                }.error,
            )
        }

    @Test
    fun `schedule reset requires confirmation and clears exclusions and pending children`() =
        runTest {
            val original = series()
            val moved = moveFirst(original.id)
            val changedSchedule = Recurrence("daily", time = LocalTime.of(12, 0))

            assertEquals(
                ErrorCode.CONFIRMATION_REQUIRED,
                assertThrows<DomainException> {
                    service.update(1, original.id, "series", null, null, null, changedSchedule)
                }.error,
            )
            assertEquals(1 to 1, service.consequences(1, original.id))
            val updated =
                service
                    .update(
                        1,
                        original.id,
                        "series",
                        null,
                        null,
                        null,
                        changedSchedule,
                        confirmation(original.id),
                    ).reminder

            assertTrue(updated.excludedOccurrences.isEmpty())
            assertEquals(Instant.parse("2026-11-02T09:00:00Z"), updated.nextFireAt)
            assertEquals(ReminderStatus.DELETED, reminders.reminders.getValue(moved.reminder.id).status)
            assertNull(reminders.reminders.getValue(moved.reminder.id).nextFireAt)
        }

    @Test
    fun `text update preserves exclusions and moved child`() =
        runTest {
            val original = series()
            val moved = moveFirst(original.id)

            val updated = service.update(1, original.id, "series", null, "Принять витамины", null, null).reminder

            assertEquals("Принять витамины", updated.text)
            assertEquals(moved.series!!.excludedOccurrences, updated.excludedOccurrences)
            assertEquals(ReminderStatus.ACTIVE, reminders.reminders.getValue(moved.reminder.id).status)
        }

    @Test
    fun `schedule reset retains completed child history`() =
        runTest {
            val original = series()
            val moved = moveFirst(original.id)
            reminders.save(moved.reminder.copy(status = ReminderStatus.DONE, nextFireAt = null), clock.instant())

            service.update(
                1,
                original.id,
                "series",
                null,
                null,
                null,
                Recurrence("daily", time = LocalTime.of(12, 0)),
                confirmation(original.id),
            )

            assertEquals(ReminderStatus.DONE, reminders.reminders.getValue(moved.reminder.id).status)
            assertTrue(
                reminders.reminders
                    .getValue(original.id)
                    .excludedOccurrences
                    .isEmpty(),
            )
        }

    @Test
    fun `confirmation expires after one hour without extending lifetime`() =
        runTest {
            val original = series(LocalTime.of(17, 0))
            val approved = confirmation(original.id)
            clock.advance(Duration.ofHours(1))

            assertEquals(
                ErrorCode.CONFIRMATION_STALE,
                assertThrows<DomainException> {
                    service.delete(1, original.id, "series", null, approved)
                }.error,
            )
            assertEquals(ReminderStatus.ACTIVE, reminders.reminders.getValue(original.id).status)
        }

    @Test
    fun `passed target occurrence invalidates card before scheduler runs`() =
        runTest {
            val original = series()
            val approved = confirmation(original.id)
            clock.advance(Duration.ofMinutes(46))

            assertEquals(
                ErrorCode.CONFIRMATION_STALE,
                assertThrows<DomainException> {
                    service.delete(1, original.id, "occurrence", LocalDateTime.parse("2026-11-02T11:00"), approved)
                }.error,
            )
            assertTrue(
                reminders.reminders
                    .getValue(original.id)
                    .excludedOccurrences
                    .isEmpty(),
            )
        }

    @Test
    fun `foreign reminder is indistinguishable from missing reminder`() =
        runTest {
            val original = series()

            assertEquals(ErrorCode.NOT_FOUND, assertThrows<DomainException> { service.get(2, original.id) }.error)
            assertEquals(
                ErrorCode.NOT_FOUND,
                assertThrows<DomainException> {
                    service.update(2, original.id, "series", null, "Чужое изменение", null, null)
                }.error,
            )
            assertEquals(original.text, reminders.reminders.getValue(original.id).text)
        }

    @Test
    fun `pause cancels snooze and manual resume skips paused occurrences`() =
        runTest {
            val original = series()
            service.snooze(1, original.id, When(after = After(minutes = 30)))
            val paused = service.pause(1, original.id, null, null)

            assertEquals(ReminderStatus.PAUSED, paused.status)
            assertNull(paused.nextFireAt)
            assertNull(paused.snoozedUntil)
            assertEquals(
                ErrorCode.INVALID_STATE,
                assertThrows<DomainException> { service.snooze(1, original.id, When(after = After(minutes = 30))) }.error,
            )
            clock.advance(Duration.ofDays(2))
            val resumed = service.resume(1, original.id)

            assertEquals(ReminderStatus.ACTIVE, resumed.status)
            assertEquals(Instant.parse("2026-11-04T08:00:00Z"), resumed.nextFireAt)
            assertNull(resumed.snoozedUntil)
            assertTrue(reminders.deliveries.isEmpty())
        }

    @Test
    fun `inclusive pause resumes on next calendar day`() =
        runTest {
            val original = series()
            val paused = service.pause(1, original.id, null, LocalDate.parse("2026-11-10"))

            assertEquals(Instant.parse("2026-11-10T21:00:00Z"), paused.pausedUntil)
            assertEquals(LocalDate.parse("2026-11-11"), paused.pausedUntil!!.atZone(original.timezone).toLocalDate())
        }

    @Test
    fun `existing reminder keeps timezone after profile change`() =
        runTest {
            val original = series()
            profiles.users[1] = user(timezone = "Asia/Yekaterinburg")

            val snoozed = service.snooze(1, original.id, When(time = LocalTime.of(12, 0)))

            assertEquals(ZoneId.of("Europe/Moscow"), snoozed.timezone)
            assertEquals(Instant.parse("2026-11-02T09:00:00Z"), snoozed.snoozedUntil)
            assertFalse(snoozed.timezone == profiles.users.getValue(1).timezone)
        }

    @Test
    fun `snoozing overdue series preserves latest scheduled debt`() =
        runTest {
            val original = series()
            clock.advance(Duration.ofDays(2).plusHours(2))
            val snoozed = service.snooze(1, original.id, When(after = After(hours = 1)))
            val snoozeAt = requireNotNull(snoozed.snoozedUntil)
            val notifier = FakeNotifier()
            val scheduler =
                ReminderScheduler(
                    reminders,
                    profiles,
                    notifier,
                    Texts.load("texts/core.properties"),
                    clock,
                    Duration.ofMinutes(5),
                )

            assertTrue(requireNotNull(snoozed.nextFireAt) <= clock.instant())
            assertEquals(1, scheduler.tick())

            assertEquals(
                Instant.parse("2026-11-04T08:00:00Z"),
                reminders.deliveries
                    .single()
                    .second.occurrenceAt,
            )
            assertEquals(snoozeAt, reminders.reminders.getValue(original.id).nextFireAt)
            assertEquals(snoozeAt, reminders.reminders.getValue(original.id).snoozedUntil)
            assertEquals(1, notifier.sent.size)
        }

    @Test
    fun `moving and skipping future occurrences preserve overdue scheduled debt`() =
        runTest {
            val original = series()
            clock.advance(Duration.ofDays(2).plusHours(2))
            val moved =
                service.update(
                    1,
                    original.id,
                    "occurrence",
                    LocalDateTime.parse("2026-11-05T11:00"),
                    null,
                    When(date = LocalDate.parse("2026-11-05"), time = LocalTime.of(13, 0)),
                    null,
                )
            assertTrue(requireNotNull(moved.series!!.nextFireAt) <= clock.instant())
            val skipped =
                service.delete(
                    1,
                    original.id,
                    "occurrence",
                    LocalDateTime.parse("2026-11-06T11:00"),
                    confirmation(original.id),
                )
            assertTrue(requireNotNull(skipped.nextFireAt) <= clock.instant())
            val scheduler =
                ReminderScheduler(
                    reminders,
                    profiles,
                    FakeNotifier(),
                    Texts.load("texts/core.properties"),
                    clock,
                    Duration.ofMinutes(5),
                )

            assertEquals(1, scheduler.tick())

            assertEquals(
                Instant.parse("2026-11-04T08:00:00Z"),
                reminders.deliveries
                    .single()
                    .second.occurrenceAt,
            )
            assertEquals(
                setOf(Instant.parse("2026-11-05T08:00:00Z"), Instant.parse("2026-11-06T08:00:00Z")),
                reminders.reminders.getValue(original.id).excludedOccurrences,
            )
        }

    @Test
    fun `identical recurrence update preserves exclusions and child without confirmation`() =
        runTest {
            val original = series()
            val moved = moveFirst(original.id)
            val originalSeries = requireNotNull(moved.series)

            val unchanged =
                service
                    .update(
                        1,
                        original.id,
                        "series",
                        null,
                        null,
                        null,
                        Recurrence("daily", time = LocalTime.of(11, 0)),
                    ).reminder

            assertEquals(originalSeries.excludedOccurrences, unchanged.excludedOccurrences)
            assertEquals(originalSeries.nextFireAt, unchanged.nextFireAt)
            assertEquals(ReminderStatus.ACTIVE, reminders.reminders.getValue(moved.reminder.id).status)
        }

    @Test
    fun `profile timezone change invalidates duplicate force card`() =
        runTest {
            service.create(1, "Принять таблетки", When(time = LocalTime.of(17, 0)), null)
            val duplicate = service.create(1, "Принять таблетки", When(time = LocalTime.of(17, 30)), null)
            val approved = Confirmation(duplicate.fingerprint, clock.instant())
            profiles.users[1] = user(timezone = "Asia/Yekaterinburg")

            val error =
                assertThrows<DomainException> {
                    service.create(
                        1,
                        "Принять таблетки",
                        When(time = LocalTime.of(17, 30)),
                        null,
                        force = true,
                        confirmation = approved,
                    )
                }

            assertEquals(ErrorCode.CONFIRMATION_STALE, error.error)
            assertEquals(1, reminders.reminders.size)
        }

    @Test
    fun `structural recurring duplicate is detected beyond five year horizon`() =
        runTest {
            val old =
                reminders.create(
                    NewReminder(
                        1,
                        "Поздравить маму",
                        ZoneId.of("Europe/Moscow"),
                        LocalDateTime.parse("2025-11-01T11:00"),
                        Instant.parse("2035-11-01T08:00:00Z"),
                        recurrenceRule = "FREQ=YEARLY;INTERVAL=10;BYMONTHDAY=1;BYMONTH=11",
                    ),
                    clock.instant(),
                )

            val duplicate =
                service.create(
                    1,
                    "Поздравить маму",
                    null,
                    Recurrence("yearly", byMonthDay = listOf(1), byMonth = listOf(11), time = LocalTime.of(11, 0)),
                )

            assertNull(duplicate.reminder)
            assertEquals(listOf(old.id), duplicate.candidates.map { it.id })
            assertEquals(1, reminders.reminders.size)
        }

    @Test
    fun `deleting root removes active nested descendants and preserves completed history`() =
        runTest {
            val tree = descendantTree()

            service.delete(1, tree.root, "series", null, confirmation(tree.root))

            assertDeletedDescendants(tree)
            assertEquals(ReminderStatus.DELETED, reminders.reminders.getValue(tree.root).status)
        }

    @Test
    fun `resetting root removes paused descendants and nested transfers`() =
        runTest {
            val tree = descendantTree()

            val changed =
                service
                    .update(
                        1,
                        tree.root,
                        "series",
                        null,
                        null,
                        null,
                        Recurrence("daily", time = LocalTime.of(12, 0)),
                        confirmation(tree.root),
                    ).reminder

            assertDeletedDescendants(tree)
            assertTrue(changed.excludedOccurrences.isEmpty())
            assertEquals(ReminderStatus.ACTIVE, changed.status)
        }

    private suspend fun descendantTree(): DescendantTree {
        val root = series()
        val child =
            service
                .update(
                    1,
                    root.id,
                    "occurrence",
                    LocalDateTime.parse("2026-11-03T11:00"),
                    null,
                    When(date = LocalDate.parse("2026-11-03"), time = LocalTime.of(13, 0)),
                    null,
                ).reminder
        service.update(
            1,
            child.id,
            null,
            null,
            null,
            null,
            Recurrence("daily", time = LocalTime.of(13, 0), startDate = LocalDate.parse("2026-11-03")),
        )
        service.pause(1, child.id, LocalDate.parse("2026-11-10"), null)
        val grandchild =
            service
                .update(
                    1,
                    child.id,
                    "occurrence",
                    LocalDateTime.parse("2026-11-11T13:00"),
                    "Вложенный перенос",
                    When(date = LocalDate.parse("2026-11-11"), time = LocalTime.of(14, 0)),
                    null,
                ).reminder
        val completed =
            service
                .update(
                    1,
                    root.id,
                    "occurrence",
                    LocalDateTime.parse("2026-11-04T11:00"),
                    null,
                    When(date = LocalDate.parse("2026-11-04"), time = LocalTime.of(15, 0)),
                    null,
                ).reminder
        reminders.save(completed.copy(status = ReminderStatus.DONE, nextFireAt = null), clock.instant())
        return DescendantTree(root.id, child.id, grandchild.id, completed.id)
    }

    private fun assertDeletedDescendants(tree: DescendantTree) {
        for (id in listOf(tree.child, tree.grandchild)) {
            val deleted = reminders.reminders.getValue(id)
            assertEquals(ReminderStatus.DELETED, deleted.status)
            assertNull(deleted.nextFireAt)
            assertNull(deleted.pausedUntil)
        }
        assertEquals(ReminderStatus.DONE, reminders.reminders.getValue(tree.completed).status)
    }

    private data class DescendantTree(
        val root: Long,
        val child: Long,
        val grandchild: Long,
        val completed: Long,
    )
}
