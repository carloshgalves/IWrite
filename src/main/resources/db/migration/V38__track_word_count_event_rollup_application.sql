-- Timestamps are not evidence of which events were included in a daily rollup.
-- New nonzero events are marked TRUE atomically with the rollup upsert by the service.
-- Events without any daily rollup at cutover are provably unapplied; this FALSE marker
-- remains valid if a new rollup is subsequently created for the same contributor/day.
-- A historical event with an existing rollup remains NULL (unknown), never guessed.
alter table book_word_count_events
    add column daily_rollup_applied boolean;

update book_word_count_events event
set daily_rollup_applied = false
where not exists (
    select 1
    from book_daily_writing_progress daily
    where daily.book_id = event.book_id
      and daily.user_id = event.actor_user_id
      and daily.progress_date = event.progress_date
);

comment on column book_word_count_events.daily_rollup_applied is
    'TRUE: included in daily rollup atomically; FALSE: provably excluded; NULL: ambiguous legacy rollup coverage.';
