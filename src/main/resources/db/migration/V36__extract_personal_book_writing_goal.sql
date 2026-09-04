-- Personal Book Writing Goal becomes User + Book scoped (#206, parent #145).
--
-- books.daily_target_word_count was shared Book data: one collaborator's daily target was every
-- collaborator's daily target, and anyone who could edit Book settings changed it for everybody.
-- The domain says the opposite. A Personal Book Writing Goal is owned by one User inside one Book,
-- only that User manages it, and its absence means "no target was chosen" -- never zero, failure or
-- a lesser contribution.
--
-- Planned writing days are already User + Book scoped and period-versioned in book_writing_schedules
-- (V22), so only the daily target still had to move. Per-day history is untouched: every
-- book_daily_writing_progress row already carries its own daily_target_word_count snapshot for the
-- User and date it belongs to, so past progress keeps the target that was actually in effect then.

create table book_personal_writing_goals (
    id uuid primary key,
    user_id uuid not null references users (id),
    book_id uuid not null references books (id) on delete cascade,
    daily_target_word_count integer,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uk_book_personal_writing_goals_user_book unique (user_id, book_id),
    constraint chk_book_personal_writing_goals_daily_target
        check (daily_target_word_count is null or daily_target_word_count > 0)
);

-- The unique key leads with user_id, which is the access path of every read: a goal is always looked
-- up as "this User's goal in this Book". Deleting a Book takes the other direction, and the cascade
-- above has to find its rows by book_id alone, so without this index every Book deletion would scan
-- every goal in the installation. book_collaborators and book_daily_writing_progress already carry a
-- book-leading index for the same reason.
create index idx_book_personal_writing_goals_book
    on book_personal_writing_goals (book_id);

-- The shared target was effective for the Book Owner and for every existing collaborator alike, so
-- each of them keeps it as their own goal and nobody's current daily target changes at the cutover.
-- The set is deliberately limited to the relationships that already existed: inventing a goal for an
-- unrelated User would fabricate a target nobody chose, and skipping current collaborators would
-- silently drop the target they were writing against.
--
-- V35 backfilled every pre-existing collaboration to LEGACY_COLLABORATOR, the compatibility role
-- that keeps the previous surface, so this preserves exactly that surface and elevates no access:
-- a Personal Book Writing Goal grants no Capability.
--
-- A non-positive legacy value is not a target at all, so it migrates as absence rather than as a
-- goal the check constraint would have to reject.
insert into book_personal_writing_goals (
    id,
    user_id,
    book_id,
    daily_target_word_count,
    created_at,
    updated_at
)
select gen_random_uuid(), holder.user_id, holder.book_id, holder.daily_target_word_count, now(), now()
from (
    select book.owner_user_id as user_id, book.id as book_id, book.daily_target_word_count
    from books book
    where book.daily_target_word_count > 0
    union
    select collaborator.user_id, book.id, book.daily_target_word_count
    from books book
    join book_collaborators collaborator on collaborator.book_id = book.id
    where book.daily_target_word_count > 0
) holder;

-- Contract step. The column has to go, not merely stop being read: while it exists it remains a
-- readable shared target that the new contract says does not exist, and an application version that
-- predates this migration would keep writing one collaborator's goal into every other's.
alter table books
    drop column daily_target_word_count;
