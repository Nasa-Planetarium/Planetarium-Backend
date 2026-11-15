-- the last date of the whole fwi_aoi_day file (as_of_date of an AOI the file does not carry)
SELECT max(date)
FROM fireinfo.fwi_day
WHERE release_id = :releaseId
