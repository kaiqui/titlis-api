-- Backfill is_dlq = true for queues whose display_name contains "dlq" (case-insensitive).
-- Needed for queues first observed before the IsDLQ fix in the operator collector.
UPDATE titlis_oltp.queues
SET is_dlq = true
WHERE is_dlq = false
  AND LOWER(display_name) LIKE '%dlq%';
