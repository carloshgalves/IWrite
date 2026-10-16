-- Timestamps are not evidence of which events were included in a daily rollup.
-- New nonzero events are marked TRUE atomically with the rollup upsert by the service.
-- Only events for a Book/User with NO daily rollup on ANY date are provably unapplied.
-- V37 may assign an event a fallback UTC date different from the legacy writing date;
-- the absence of a rollup ON THAT DATE cannot prove an event was never aggregated.
-- FALSE remains valid if a daily rollup is created later; other legacy events stay
-- NULL (unknown), never guessed from timestamps or mismatched historical dates.
alter table book_word_count_events
    add column daily_rollup_applied boolean;

update book_word_count_events event
set daily_rollup_applied = false
where not exists (
    select 1
    from book_daily_writing_progress daily
    where daily.book_id = event.book_id
      and daily.user_id = event.actor_user_id
);

comment on column book_word_count_events.daily_rollup_applied is
    'TRUE: included in daily rollup atomically; FALSE: provably excluded; NULL: ambiguous legacy rollup coverage.';
