-- Contributor progress is filtered by the historical writing date, not by a User's current
-- timezone. Prefer the persisted daily rollup when it identifies exactly one writing date. Earlier
-- schemas permitted isolated events without such evidence; those use the immutable UTC event date,
-- never a User's mutable current timezone, as a stable compatibility fallback.
alter table book_word_count_events
    add column progress_date date,
    add column original_chapter_id uuid,
    add column chapter_title_snapshot varchar(255);

update book_word_count_events event
set progress_date = coalesce(
    (
        select min(progress.progress_date)
        from book_daily_writing_progress progress
        where progress.book_id = event.book_id
          and progress.user_id = event.actor_user_id
          and event.created_at between progress.created_at and progress.updated_at
        having count(*) = 1
    ),
    (event.created_at at time zone 'UTC')::date
);

-- A legacy event did not persist its Chapter. Even when its Scene still exists, that Scene may have
-- moved since the event, so V37 deliberately leaves the old Chapter origin unknown. New writes
-- snapshot the exact Chapter from this version onward.

alter table book_word_count_events
    alter column progress_date set not null;

create index idx_book_word_count_events_book_progress_actor
    on book_word_count_events (book_id, progress_date, actor_user_id);

comment on column book_word_count_events.progress_date is
    'Historical writing date resolved when the authenticated event was recorded; never reinterpreted after timezone changes.';

comment on column book_word_count_events.original_chapter_id is
    'Chapter containing the Scene when the event was recorded; null when legacy evidence is unavailable and intentionally not an FK so deletion preserves origin.';

comment on column book_word_count_events.chapter_title_snapshot is
    'Chapter title when the event was recorded, retained for Book-scoped contributor history.';
