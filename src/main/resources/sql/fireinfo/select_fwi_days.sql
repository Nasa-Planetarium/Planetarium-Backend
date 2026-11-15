SELECT date, fwi_mean, fwi_max, fwi_class, fwi_pct, fwi_rel_class, isi_mean, bui_mean, ffmc_mean, dc_mean, n_cells
FROM fireinfo.fwi_day
WHERE release_id = :releaseId AND aoi_id = :aoiId AND date BETWEEN :start AND :end
ORDER BY date
