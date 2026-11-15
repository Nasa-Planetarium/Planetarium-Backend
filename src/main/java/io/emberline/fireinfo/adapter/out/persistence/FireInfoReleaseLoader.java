package io.emberline.fireinfo.adapter.out.persistence;

import io.emberline.fireinfo.domain.LayerFile;
import io.emberline.fireinfo.domain.SggResolver;
import io.emberline.shared.release.load.ColumnKind;
import io.emberline.shared.release.load.ColumnSpec;
import io.emberline.shared.release.load.CopyInputStream;
import io.emberline.shared.release.load.CopyLine;
import io.emberline.shared.release.load.DuckDb;
import io.emberline.shared.release.load.FileChecks;
import io.emberline.shared.release.load.LoadFailures;
import io.emberline.shared.release.load.LoadResult;
import io.emberline.shared.release.load.ParquetColumns;
import io.emberline.shared.release.load.Partitions;
import io.emberline.shared.release.load.ReleaseLoadException;
import io.emberline.shared.release.load.ReleaseLoader;
import io.emberline.shared.release.load.ReleaseSource;
import io.emberline.shared.release.load.ValidationReport;
import io.emberline.shared.sql.SqlResource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * fireinfo partitions (contract MAPPING §8.2): fire_events, fwi_aoi_day, fwi_clim_aoi_month,
 * spread_nowcast_latest (head columns + cells) and aoi_month_fire_regime — each optional in a release, the served
 * columns copied as stored (a missing column refuses the release). From fire_events the loader also builds the
 * event → SGG index ({@code sgg_all} names resolved against the aoi_list SGGs, {@link SggResolver}); its size is
 * {@code rows["fireinfo.fire_event_sgg"]} and the names it could not resolve {@code rows["fireinfo.fire_event_sgg_dropped"]}.
 * A release without any of the files gets no fireinfo partition and no rows key (v1.1 stages as before).
 */
@Component
class FireInfoReleaseLoader implements ReleaseLoader {

    static final String AOI_LIST = "aoi_list.parquet";
    static final String T_LAYER = "fireinfo.layer";
    static final String T_EVENT = "fireinfo.fire_event";
    static final String T_EVENT_SGG = "fireinfo.fire_event_sgg";
    static final String SGG_DROPPED = "fireinfo.fire_event_sgg_dropped";
    static final String T_FWI = "fireinfo.fwi_day";
    static final String T_CLIM = "fireinfo.fwi_clim_month";
    static final String T_SPREAD_HEAD = "fireinfo.spread_head";
    static final String T_SPREAD = "fireinfo.spread_cell";
    static final String T_REGIME = "fireinfo.fire_regime_month";
    static final List<String> TABLES = List.of(T_LAYER, T_EVENT, T_EVENT_SGG, T_FWI, T_CLIM, T_SPREAD_HEAD, T_SPREAD, T_REGIME);

    static final List<ColumnSpec> EVENT_COLUMNS = List.of(
            ColumnSpec.required("event_id", ColumnKind.TEXT),
            ColumnSpec.required("region", ColumnKind.TEXT),
            ColumnSpec.required("start_date", ColumnKind.DATE),
            ColumnSpec.required("end_date", ColumnKind.DATE),
            ColumnSpec.required("duration_days", ColumnKind.INTEGER),
            ColumnSpec.required("n_det", ColumnKind.INTEGER),
            ColumnSpec.required("sum_weight", ColumnKind.NUMBER),
            ColumnSpec.required("frp_sum", ColumnKind.NUMBER),
            ColumnSpec.required("frp_max", ColumnKind.NUMBER),
            ColumnSpec.required("centroid_lat", ColumnKind.NUMBER),
            ColumnSpec.required("centroid_lon", ColumnKind.NUMBER),
            ColumnSpec.required("lat_min", ColumnKind.NUMBER),
            ColumnSpec.required("lat_max", ColumnKind.NUMBER),
            ColumnSpec.required("lon_min", ColumnKind.NUMBER),
            ColumnSpec.required("lon_max", ColumnKind.NUMBER),
            ColumnSpec.required("burned_area_km2", ColumnKind.NUMBER),
            ColumnSpec.required("burn_confirmed", ColumnKind.BOOLEAN),
            ColumnSpec.required("instrument", ColumnKind.TEXT),
            ColumnSpec.required("n_viirs", ColumnKind.INTEGER),
            ColumnSpec.required("n_modis", ColumnKind.INTEGER),
            ColumnSpec.required("country", ColumnKind.TEXT),
            ColumnSpec.required("adm1_code", ColumnKind.TEXT),
            ColumnSpec.required("sido_nm", ColumnKind.TEXT),
            ColumnSpec.required("sgg_cd", ColumnKind.TEXT),
            ColumnSpec.required("sgg_nm", ColumnKind.TEXT),
            ColumnSpec.required("sgg_all", ColumnKind.TEXT));
    static final List<ColumnSpec> FWI_COLUMNS = List.of(
            ColumnSpec.required("aoi_id", ColumnKind.TEXT),
            ColumnSpec.required("date", ColumnKind.DATE),
            ColumnSpec.required("name", ColumnKind.TEXT),
            ColumnSpec.required("fwi_mean", ColumnKind.NUMBER),
            ColumnSpec.required("fwi_max", ColumnKind.NUMBER),
            ColumnSpec.required("fwi_class", ColumnKind.TEXT),
            ColumnSpec.required("fwi_pct", ColumnKind.NUMBER),
            ColumnSpec.required("fwi_rel_class", ColumnKind.TEXT),
            ColumnSpec.required("isi_mean", ColumnKind.NUMBER),
            ColumnSpec.required("bui_mean", ColumnKind.NUMBER),
            ColumnSpec.required("ffmc_mean", ColumnKind.NUMBER),
            ColumnSpec.required("dc_mean", ColumnKind.NUMBER),
            ColumnSpec.required("n_cells", ColumnKind.INTEGER));
    static final List<ColumnSpec> CLIM_COLUMNS = List.of(
            ColumnSpec.required("aoi_id", ColumnKind.TEXT),
            ColumnSpec.required("month", ColumnKind.INTEGER),
            ColumnSpec.required("n_days", ColumnKind.INTEGER),
            ColumnSpec.required("q25", ColumnKind.NUMBER),
            ColumnSpec.required("q50", ColumnKind.NUMBER),
            ColumnSpec.required("q75", ColumnKind.NUMBER),
            ColumnSpec.required("q90", ColumnKind.NUMBER));
    static final List<ColumnSpec> SPREAD_COLUMNS = List.of(
            ColumnSpec.required("as_of_date", ColumnKind.DATE),
            ColumnSpec.required("target_date", ColumnKind.DATE),
            ColumnSpec.required("model", ColumnKind.TEXT),
            ColumnSpec.required("source_satellite", ColumnKind.TEXT),
            ColumnSpec.required("weather_available", ColumnKind.BOOLEAN),
            ColumnSpec.required("weather_lag_days", ColumnKind.INTEGER),
            ColumnSpec.required("region", ColumnKind.TEXT),
            ColumnSpec.required("cand_adm0", ColumnKind.TEXT),
            ColumnSpec.required("cell_lat", ColumnKind.NUMBER),
            ColumnSpec.required("cell_lon", ColumnKind.NUMBER),
            ColumnSpec.required("cell_size_deg", ColumnKind.NUMBER),
            ColumnSpec.required("p_spread", ColumnKind.NUMBER),
            ColumnSpec.required("active_today", ColumnKind.BOOLEAN),
            ColumnSpec.required("fire_event_days", ColumnKind.INTEGER),
            ColumnSpec.required("n_det_today_7x7", ColumnKind.INTEGER));
    static final List<ColumnSpec> REGIME_COLUMNS = List.of(
            ColumnSpec.required("aoi_id", ColumnKind.TEXT),
            ColumnSpec.required("name", ColumnKind.TEXT),
            ColumnSpec.required("year", ColumnKind.INTEGER),
            ColumnSpec.required("month", ColumnKind.INTEGER),
            ColumnSpec.required("fire_regime", ColumnKind.TEXT),
            ColumnSpec.required("W", ColumnKind.NUMBER),
            ColumnSpec.required("W_large", ColumnKind.NUMBER),
            ColumnSpec.required("share_large", ColumnKind.NUMBER),
            ColumnSpec.required("n_events", ColumnKind.INTEGER),
            ColumnSpec.required("n_large_events", ColumnKind.INTEGER),
            ColumnSpec.required("top_event_id", ColumnKind.TEXT),
            ColumnSpec.required("top_event_w", ColumnKind.NUMBER));
    /** aoi_list columns the SGG resolution reads (same names and aliases as the catalog loader). */
    static final List<ColumnSpec> AOI_COLUMNS = List.of(
            ColumnSpec.required("aoi_id", ColumnKind.TEXT),
            ColumnSpec.required("name_ko", ColumnKind.TEXT, "name"),
            ColumnSpec.required("parent_id", ColumnKind.TEXT, "parent_aoi"));

    /** Columns per layer file. */
    static final Map<LayerFile, List<ColumnSpec>> COLUMNS = Map.of(
            LayerFile.FIRE_EVENTS, EVENT_COLUMNS, LayerFile.FWI_AOI_DAY, FWI_COLUMNS, LayerFile.FWI_CLIM_AOI_MONTH, CLIM_COLUMNS,
            LayerFile.SPREAD_NOWCAST, SPREAD_COLUMNS, LayerFile.FIRE_REGIME, REGIME_COLUMNS);
    /** Duplicate-key checks per layer file (the spread file has no key column set to check). */
    static final Map<LayerFile, String> KEYS = Map.of(
            LayerFile.FIRE_EVENTS, "{event_id}", LayerFile.FWI_AOI_DAY, "{aoi_id}, {date}",
            LayerFile.FWI_CLIM_AOI_MONTH, "{aoi_id}, {month}", LayerFile.FIRE_REGIME, "{aoi_id}, {year}, {month}");
    /** Contract enums per layer file and column (values measured on the v1.2 files, MAPPING §8.4). */
    static final Map<LayerFile, Map<String, Set<String>>> CODES = Map.of(
            LayerFile.FIRE_EVENTS, Map.of("region", Set.of("KR", "ARG"), "instrument", Set.of("MODIS", "VIIRS")),
            LayerFile.FWI_AOI_DAY, Map.of("fwi_class", Set.of("low", "moderate", "high", "very_high", "extreme"),
                    "fwi_rel_class", Set.of("below_normal", "normal", "above_normal", "well_above_normal")),
            LayerFile.SPREAD_NOWCAST, Map.of("region", Set.of("KR", "ARG")),
            LayerFile.FIRE_REGIME, Map.of("fire_regime", Set.of("large_fire", "many_small", "mixed", "none")));

    private final DuckDb duckDb;
    private final Partitions partitions;

    FireInfoReleaseLoader(DuckDb duckDb, Partitions partitions) {
        this.duckDb = duckDb;
        this.partitions = partitions;
    }

    @Override
    public String context() {
        return "fireinfo";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public List<ReleaseFile> files() {
        List<ReleaseFile> files = new ArrayList<>();
        for (LayerFile f : LayerFile.values()) {
            files.add(new ReleaseFile(f.fileName(), false));
        }
        files.add(new ReleaseFile(AOI_LIST, false));
        return files;
    }

    @Override
    public void validate(ReleaseSource source) {
        ValidationReport report = source.report();
        for (LayerFile f : LayerFile.values()) {
            if (!source.has(f.fileName())) {
                continue;
            }
            String file = source.file(f.fileName()).toString();
            try (Connection duck = duckDb.open()) {
                ParquetColumns c = ParquetColumns.inspect(duck, file, f.fileName(), COLUMNS.get(f), report);
                if (!c.ok()) {
                    continue;
                }
                for (Map.Entry<String, Set<String>> code : CODES.getOrDefault(f, Map.of()).entrySet()) {
                    FileChecks.codeValues(duck, file, f.fileName(), c, code.getKey(), code.getValue(), Map.of(), report);
                }
                if (KEYS.containsKey(f)) {
                    FileChecks.uniqueKey(duck, file, f.fileName(), c, KEYS.get(f), report);
                }
                if (f == LayerFile.SPREAD_NOWCAST && heads(duck, file, c) > 1) {
                    report.reject("SPREAD_HEAD_NOT_UNIQUE", f.fileName(), "as_of_date..weather_lag_days differ between rows");
                }
            } catch (SQLException e) {
                report.reject("FILE_UNREADABLE", f.fileName(), null);
            }
        }
    }

    @Override
    public LoadResult load(ReleaseSource source) {
        long id = source.releaseId();
        Map<String, Long> rows = new LinkedHashMap<>();
        Map<LayerFile, Long> loaded = new LinkedHashMap<>();
        boolean any = false;
        for (LayerFile f : LayerFile.values()) {
            any |= source.has(f.fileName());
        }
        if (!any) {
            return new LoadResult(rows);
        }
        try (Connection duck = duckDb.open()) {
            ValidationReport scratch = new ValidationReport();
            if (source.has(LayerFile.FIRE_EVENTS.fileName())) {
                String file = source.file(LayerFile.FIRE_EVENTS.fileName()).toString();
                ParquetColumns c = ParquetColumns.inspect(duck, file, LayerFile.FIRE_EVENTS.fileName(), EVENT_COLUMNS, scratch);
                long n = copy(duck, file, c, "fireinfo/duck_fire_events.sql", T_EVENT, id, "fireinfo/copy_fire_event.sql",
                        (r, l) -> {
                            l.text(r.getString(1)).text(r.getString(2)).text(r.getString(3)).text(r.getString(4))
                                    .integer(r.getLong(5), r.wasNull()).integer(r.getLong(6), r.wasNull());
                            for (int i = 7; i <= 16; i++) {
                                number(l, r, i);
                            }
                            l.bool(r.getBoolean(17), r.wasNull()).text(r.getString(18)).integer(r.getLong(19), r.wasNull())
                                    .integer(r.getLong(20), r.wasNull());
                            for (int i = 21; i <= 26; i++) {
                                l.text(r.getString(i));
                            }
                        }, "fireinfo/index_fire_event.sql");
                rows.put(T_EVENT, n);
                loaded.put(LayerFile.FIRE_EVENTS, n);
                loadSggIndex(duck, source, file, c, rows);
            }
            if (source.has(LayerFile.FWI_AOI_DAY.fileName())) {
                String file = source.file(LayerFile.FWI_AOI_DAY.fileName()).toString();
                ParquetColumns c = ParquetColumns.inspect(duck, file, LayerFile.FWI_AOI_DAY.fileName(), FWI_COLUMNS, scratch);
                long n = copy(duck, file, c, "fireinfo/duck_fwi_day.sql", T_FWI, id, "fireinfo/copy_fwi_day.sql", (r, l) -> {
                    l.text(r.getString(1)).text(r.getString(2)).text(r.getString(3));
                    number(l, r, 4);
                    number(l, r, 5);
                    l.text(r.getString(6));
                    number(l, r, 7);
                    l.text(r.getString(8));
                    for (int i = 9; i <= 12; i++) {
                        number(l, r, i);
                    }
                    l.integer(r.getLong(13), r.wasNull());
                }, "fireinfo/index_fwi_day.sql");
                rows.put(T_FWI, n);
                loaded.put(LayerFile.FWI_AOI_DAY, n);
            }
            if (source.has(LayerFile.FWI_CLIM_AOI_MONTH.fileName())) {
                String file = source.file(LayerFile.FWI_CLIM_AOI_MONTH.fileName()).toString();
                ParquetColumns c = ParquetColumns.inspect(duck, file, LayerFile.FWI_CLIM_AOI_MONTH.fileName(), CLIM_COLUMNS, scratch);
                long n = copy(duck, file, c, "fireinfo/duck_fwi_clim_month.sql", T_CLIM, id, "fireinfo/copy_fwi_clim_month.sql",
                        (r, l) -> {
                            l.text(r.getString(1)).integer(r.getLong(2), r.wasNull()).integer(r.getLong(3), r.wasNull());
                            for (int i = 4; i <= 7; i++) {
                                number(l, r, i);
                            }
                        }, "fireinfo/index_fwi_clim_month.sql");
                rows.put(T_CLIM, n);
                loaded.put(LayerFile.FWI_CLIM_AOI_MONTH, n);
            }
            if (source.has(LayerFile.SPREAD_NOWCAST.fileName())) {
                String file = source.file(LayerFile.SPREAD_NOWCAST.fileName()).toString();
                ParquetColumns c = ParquetColumns.inspect(duck, file, LayerFile.SPREAD_NOWCAST.fileName(), SPREAD_COLUMNS, scratch);
                rows.put(T_SPREAD_HEAD, copy(duck, file, c, "fireinfo/duck_spread_head.sql", T_SPREAD_HEAD, id,
                        "fireinfo/copy_spread_head.sql", (r, l) -> l.text(r.getString(1)).text(r.getString(2)).text(r.getString(3))
                                .text(r.getString(4)).bool(r.getBoolean(5), r.wasNull()).integer(r.getLong(6), r.wasNull()), null));
                long n = copy(duck, file, c, "fireinfo/duck_spread_cells.sql", T_SPREAD, id, "fireinfo/copy_spread_cell.sql",
                        (r, l) -> {
                            l.text(r.getString(1)).text(r.getString(2));
                            for (int i = 3; i <= 6; i++) {
                                number(l, r, i);
                            }
                            l.bool(r.getBoolean(7), r.wasNull()).integer(r.getLong(8), r.wasNull()).integer(r.getLong(9), r.wasNull());
                        }, "fireinfo/index_spread_cell.sql");
                rows.put(T_SPREAD, n);
                loaded.put(LayerFile.SPREAD_NOWCAST, n);
            }
            if (source.has(LayerFile.FIRE_REGIME.fileName())) {
                String file = source.file(LayerFile.FIRE_REGIME.fileName()).toString();
                ParquetColumns c = ParquetColumns.inspect(duck, file, LayerFile.FIRE_REGIME.fileName(), REGIME_COLUMNS, scratch);
                long n = copy(duck, file, c, "fireinfo/duck_fire_regime.sql", T_REGIME, id, "fireinfo/copy_fire_regime_month.sql",
                        (r, l) -> {
                            l.text(r.getString(1)).text(r.getString(2)).integer(r.getLong(3), r.wasNull())
                                    .integer(r.getLong(4), r.wasNull()).text(r.getString(5));
                            number(l, r, 6);
                            number(l, r, 7);
                            number(l, r, 8);
                            l.integer(r.getLong(9), r.wasNull()).integer(r.getLong(10), r.wasNull()).text(r.getString(11));
                            number(l, r, 12);
                        }, "fireinfo/index_fire_regime_month.sql");
                rows.put(T_REGIME, n);
                loaded.put(LayerFile.FIRE_REGIME, n);
            }
            StringBuilder layer = new StringBuilder();
            loaded.forEach((f, n) -> {
                CopyLine.into(layer).text(f.fileName()).integer(n, false);
                layer.append('\n');
            });
            rows.put(T_LAYER, partitions.load(T_LAYER, id, SqlResource.text("fireinfo/copy_layer.sql"),
                    new ByteArrayInputStream(layer.toString().getBytes(StandardCharsets.UTF_8)),
                    SqlResource.statements("fireinfo/index_layer.sql")));
            return new LoadResult(rows);
        } catch (SQLException e) {
            throw new ReleaseLoadException(LoadFailures.reason(e, "fireinfo"), e);
        }
    }

    /**
     * The event → SGG index: every event naming SGGs ({@code sgg_all}) resolved against the aoi_list SGGs and their
     * parent SIDO names. rows: resolved (event, SGG) pairs and dropped names.
     */
    private void loadSggIndex(Connection duck, ReleaseSource source, String eventFile, ParquetColumns events,
                              Map<String, Long> rows) throws SQLException {
        String aoiFile = source.file(AOI_LIST).toString();
        ParquetColumns a = ParquetColumns.inspect(duck, aoiFile, AOI_LIST, AOI_COLUMNS, new ValidationReport());
        Map<String, String> sidoNames = new HashMap<>();
        List<String[]> sggRows = new ArrayList<>();
        try (PreparedStatement ps = duck.prepareStatement(a.render(SqlResource.text("fireinfo/duck_aoi_sgg_sido.sql")))) {
            ps.setString(1, aoiFile);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    String aoiId = r.getString(1);
                    if (aoiId.startsWith("SIDO_")) {
                        sidoNames.put(aoiId, r.getString(2));
                    } else {
                        sggRows.add(new String[] {aoiId, r.getString(2), r.getString(3)});
                    }
                }
            }
        }
        List<SggResolver.Sgg> sggs = new ArrayList<>();
        for (String[] s : sggRows) {
            if (s[1] != null) {
                sggs.add(new SggResolver.Sgg(s[0], s[1], s[2] == null ? null : sidoNames.get(s[2])));
            }
        }
        SggResolver resolver = new SggResolver(sggs);
        StringBuilder index = new StringBuilder();
        long dropped = 0;
        try (PreparedStatement ps = duck.prepareStatement(events.render(SqlResource.text("fireinfo/duck_fire_event_sgg_names.sql")))) {
            ps.setString(1, eventFile);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    String eventId = r.getString(1);
                    SggResolver.Result res = resolver.resolve(r.getString(2), r.getString(3));
                    dropped += res.dropped();
                    for (String sggId : res.sggIds()) {
                        CopyLine.into(index).text(eventId).text(sggId);
                        index.append('\n');
                    }
                }
            }
        }
        rows.put(T_EVENT_SGG, partitions.load(T_EVENT_SGG, source.releaseId(), SqlResource.text("fireinfo/copy_fire_event_sgg.sql"),
                new ByteArrayInputStream(index.toString().getBytes(StandardCharsets.UTF_8)),
                SqlResource.statements("fireinfo/index_fire_event_sgg.sql")));
        rows.put(SGG_DROPPED, dropped);
    }

    /** DuckDB select over the file → COPY into a fresh partition → indexes (none when {@code indexSql} is null) → attach. */
    private long copy(Connection duck, String file, ParquetColumns c, String duckSql, String table, long releaseId,
                      String copySql, CopyInputStream.RowEncoder encoder, String indexSql) throws SQLException {
        partitions.create(table, releaseId);
        long n;
        try (PreparedStatement ps = duck.prepareStatement(c.render(SqlResource.text(duckSql)))) {
            ps.setString(1, file);
            try (ResultSet rs = ps.executeQuery()) {
                n = partitions.copy(table, releaseId, SqlResource.text(copySql), new CopyInputStream(rs, encoder));
            }
        }
        partitions.finish(table, releaseId, indexSql == null ? List.of() : SqlResource.statements(indexSql));
        return n;
    }

    /** Distinct heads (as_of_date … weather_lag_days) of the spread file. */
    private static long heads(Connection duck, String file, ParquetColumns c) throws SQLException {
        long n = 0;
        try (PreparedStatement ps = duck.prepareStatement(c.render(SqlResource.text("fireinfo/duck_spread_head.sql")))) {
            ps.setString(1, file);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * A real-valued cell as stored: a parquet FLOAT is written with its shortest decimal ({@code Float.toString}), a
     * DOUBLE with {@code Double.toString}, so the double precision column holds the value the file shows.
     */
    static void number(CopyLine l, ResultSet r, int column) throws SQLException {
        Object v = r.getObject(column);
        if (v instanceof Float f) {
            l.real(f, false);
        } else if (v instanceof Number n) {
            l.num(n.doubleValue(), false);
        } else {
            l.text(null);
        }
    }

    @Override
    public void drop(long releaseId) {
        try {
            for (String table : TABLES) {
                partitions.drop(table, releaseId);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot drop fireinfo partitions", e);
        }
    }
}
