-- 1 when the child is currently a partition of the parent
SELECT count(*) FROM pg_inherits WHERE inhrelid = to_regclass(?)::oid AND inhparent = to_regclass(?)::oid
