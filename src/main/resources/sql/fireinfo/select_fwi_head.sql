-- as_of_date = the AOI's last date in fwi_aoi_day, name as the file has it (one name per AOI); nulls when absent
SELECT max(date) AS as_of_date, min(name) AS name, count(*) AS days
FROM fireinfo.fwi_day
WHERE release_id = :releaseId AND aoi_id = :aoiId
