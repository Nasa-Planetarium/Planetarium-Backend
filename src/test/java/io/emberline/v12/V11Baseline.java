package io.emberline.v12;

/**
 * Stage output of a plain core-v11 copy at 62779c6 (V12ReleaseTest's v11 staging run on a 62779c6 checkout):
 * warnings in order, rows per table sorted by table name. Two texts differ from 62779c6 on purpose: the
 * VALUE_OUTSIDE_INTERVAL warning, reworded after (43f5bb2 serves value_lo/value_hi as stored),
 * and the FILE_ABSENT detail text. Three warnings were added on purpose: core-v11's
 * aoi_month_compare has no baseline_period / continuity_ratio / continuity_source, optional since data-v1.2 r3.
 */
final class V11Baseline {

    static final String[] WARNINGS = {
        "COLUMN_ABSENT:aoi_list.parquet:name_en",
        "AOI_ID_OUTSIDE_CONTRACT:aoi_list.parquet:30 rows kept",
        "MISSING_MONTH_NULL_VALUE:aoi_month.parquet:273 rows kept NULL (status missing)",
        // the v1.2 r3 compare columns are optional, so a v1.1 file stages with these three warnings
        "COLUMN_ABSENT:aoi_month_compare.parquet:baseline_period",
        "COLUMN_ABSENT:aoi_month_compare.parquet:continuity_ratio",
        "COLUMN_ABSENT:aoi_month_compare.parquet:continuity_source",
        "COLUMN_ABSENT:harmonized_cell_month.parquet:as_of_date",
        "MISSING_MONTH_NULL_VALUE:harmonized_cell_month.parquet:14 rows kept NULL (status missing)",
        "VALUE_OUTSIDE_INTERVAL:harmonized_cell_month.parquet:36 rows kept as stored; interval served as stored, interval_out_of_range on read",
        // changed only this detail text: without the file /satellite-status freq=day is 503
        "FILE_ABSENT:satellite_day_status.parquet:satellite-status freq=day answers 503 layer not loaded"};
    static final String ROWS = "{catalog.aoi=5051, catalog.aoi_boundary=271, harmonized.aoi_month=85449, harmonized.aoi_month_compare=85449, "
            + "harmonized.cell_center=1863, observation.cell_month=60978, provenance.method_meta=1, provenance.month_status=313, "
            + "provenance.satellite_day=0}";
    static final String[] ALIASES = {"name->name_ko", "parent_aoi->parent_id", "kr->country", "sgg->kr_sgg", "sido->kr_sido"};

    private V11Baseline() {
    }
}
