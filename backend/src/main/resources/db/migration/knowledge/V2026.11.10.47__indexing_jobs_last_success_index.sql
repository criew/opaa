SET LOCAL lock_timeout = '5s';

-- Backs the latest completed run per library that the indexing gauges read every minute.
CREATE INDEX idx_indexing_jobs_library_completed ON indexing_jobs (library_id, completed_at)
    WHERE status = 'COMPLETED';
