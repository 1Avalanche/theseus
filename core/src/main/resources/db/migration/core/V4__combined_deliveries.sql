-- Совпавшие плановое и отложенное срабатывания фиксируются одной доставкой.
alter table reminder_deliveries drop constraint reminder_deliveries_kind_check;
alter table reminder_deliveries add constraint reminder_deliveries_kind_check
    check (kind in ('SCHEDULED', 'SNOOZED', 'SCHEDULED_AND_SNOOZED'));
