SET LOCAL lock_timeout = '5s';

-- The stop mark of a streamed agent run (ADR-0070, ADR-0053): the hash of the run's cancel token
-- and when the person asked to stop. Any instance sets it, the running instance reads it before
-- each tool call. Gone with the reservation.
ALTER TABLE agent_run_reservations ADD COLUMN cancel_token_hash text;
ALTER TABLE agent_run_reservations ADD COLUMN cancel_requested_at timestamptz;
