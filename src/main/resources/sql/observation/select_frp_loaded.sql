-- whether the release loaded harmonized_cell_day_frp.parquet (only such a release has a cell_day_frp partition)
SELECT EXISTS (SELECT 1 FROM observation.cell_day_frp WHERE release_id = :releaseId)
