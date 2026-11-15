-- table, index and total bytes of one release partition (load report)
SELECT pg_table_size(to_regclass(?)), pg_indexes_size(to_regclass(?)), pg_total_relation_size(to_regclass(?))
