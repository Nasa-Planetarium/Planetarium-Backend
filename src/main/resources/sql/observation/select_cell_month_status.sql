-- the month's status in the cell table (one value per month in the release); no row → the month is out of range
SELECT status::text AS status
FROM observation.cell_month
WHERE release_id = :releaseId AND year = :year AND month = :month
LIMIT 1
