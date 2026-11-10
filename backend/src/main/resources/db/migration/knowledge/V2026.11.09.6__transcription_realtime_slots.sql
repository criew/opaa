SET LOCAL lock_timeout = '5s';

-- The live transcription sessions open right now across all instances (ADR-0072), one row per
-- session without any reference to the person: the cap of the installation counts them. A row is
-- deleted when its session ends; one left behind by a stopped instance no longer counts once it is
-- older than the hard limit of a session plus a margin, and is removed by the next opening.
CREATE TABLE transcription_realtime_slots (
    id uuid NOT NULL,
    opened_at timestamp with time zone NOT NULL,
    CONSTRAINT transcription_realtime_slots_pkey PRIMARY KEY (id)
);
