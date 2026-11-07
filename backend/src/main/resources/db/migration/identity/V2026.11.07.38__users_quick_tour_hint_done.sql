SET LOCAL lock_timeout = '5s';

-- Whether the person has dealt with the "Neu hier?" hint of the quick tour (started the tour or
-- declined it). A plain yes/no value on purpose: no point in time, no counter, no progress. Every
-- existing account starts at false and sees the hint once; the default stays, so the previous
-- version keeps inserting accounts without naming the column.
ALTER TABLE users ADD COLUMN quick_tour_hint_done boolean NOT NULL DEFAULT false;
