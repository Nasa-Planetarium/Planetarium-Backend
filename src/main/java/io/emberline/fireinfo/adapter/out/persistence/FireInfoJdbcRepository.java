package io.emberline.fireinfo.adapter.out.persistence;

import io.emberline.fireinfo.application.port.out.FireInfoReadPort;
import io.emberline.fireinfo.domain.Bbox;
import io.emberline.fireinfo.domain.EventArea;
import io.emberline.fireinfo.domain.EventPosition;
import io.emberline.fireinfo.domain.EventSelection;
import io.emberline.fireinfo.domain.EventSort;
import io.emberline.fireinfo.domain.FireEvent;
import io.emberline.fireinfo.domain.FwiDay;
import io.emberline.fireinfo.domain.FwiHead;
import io.emberline.fireinfo.domain.LayerFile;
import io.emberline.fireinfo.domain.RegimeHead;
import io.emberline.fireinfo.domain.RegimeMonth;
import io.emberline.fireinfo.domain.SpreadCell;
import io.emberline.fireinfo.domain.SpreadHead;
import io.emberline.shared.error.DataUnavailableException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.sql.SqlResource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads of the fireinfo partitions of one release. The event list SQL is one template with the area rule, the sort and
 * the keyset condition filled from fixed fragment files (registries keyed by rule and sort, no switch); values are
 * always bind parameters.
 */
@Repository
class FireInfoJdbcRepository implements FireInfoReadPort {

    static final Map<EventArea.Kind, String> AREA_SQL = Map.of(
            EventArea.Kind.COUNTRY, "fireinfo/area_country.sql",
            EventArea.Kind.ADM1, "fireinfo/area_adm1.sql",
            EventArea.Kind.SIDO, "fireinfo/area_sido.sql",
            EventArea.Kind.SGG, "fireinfo/area_sgg.sql",
            EventArea.Kind.BBOX, "fireinfo/area_bbox.sql");
    static final Map<EventArea.Kind, BiConsumer<EventArea, Map<String, Object>>> AREA_PARAMS = Map.of(
            EventArea.Kind.COUNTRY, (a, p) -> p.put("country", a.value()),
            EventArea.Kind.ADM1, (a, p) -> p.put("adm1Code", a.value()),
            EventArea.Kind.SIDO, (a, p) -> p.put("sidoNm", a.value()),
            EventArea.Kind.SGG, (a, p) -> p.put("sggId", a.value()),
            EventArea.Kind.BBOX, (a, p) -> {
                p.put("west", a.box().west());
                p.put("south", a.box().south());
                p.put("east", a.box().east());
                p.put("north", a.box().north());
            });
    static final Map<EventSort, String> ORDER_SQL = Map.of(
            EventSort.SUM_WEIGHT, "fireinfo/order_sum_weight.sql",
            EventSort.START_DATE, "fireinfo/order_start_date.sql",
            EventSort.BURNED_AREA_KM2, "fireinfo/order_burned_area_km2.sql");
    static final Map<EventSort, String> AFTER_SQL = Map.of(
            EventSort.SUM_WEIGHT, "fireinfo/after_sum_weight.sql",
            EventSort.START_DATE, "fireinfo/after_start_date.sql",
            EventSort.BURNED_AREA_KM2, "fireinfo/after_burned_area_km2.sql");
    static final String AFTER_NULL_BURNED_SQL = "fireinfo/after_burned_area_km2_null.sql";

    private final JdbcClient jdbc;

    FireInfoJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<LayerFile> layers(long releaseId) {
        return translate(() -> {
            Set<LayerFile> out = EnumSet.noneOf(LayerFile.class);
            jdbc.sql(SqlResource.text("fireinfo/select_layer_files.sql")).param("releaseId", releaseId)
                    .query(String.class).list().forEach(f -> LayerFile.ofFileName(f).ifPresent(out::add));
            return out;
        });
    }

    @Override
    public List<FireEvent> eventPage(long releaseId, EventSelection selection, Optional<EventPosition> after, int limit) {
        Map<String, Object> params = selectionParams(releaseId, selection);
        String afterSql = after.map(a -> {
            params.put("afterId", a.eventId());
            params.put("afterValue", a.value());
            params.put("afterDate", a.date());
            boolean nullBurned = a.sort() == EventSort.BURNED_AREA_KM2 && a.value() == null;
            return SqlResource.text(nullBurned ? AFTER_NULL_BURNED_SQL : AFTER_SQL.get(a.sort()));
        }).orElse("TRUE");
        params.put("limit", limit);
        String sql = SqlResource.text("fireinfo/select_fire_events_page.sql")
                .replace("{area}", SqlResource.text(AREA_SQL.get(selection.area().kind())))
                .replace("{after}", afterSql)
                .replace("{order}", SqlResource.text(ORDER_SQL.get(selection.sort())));
        return translate(() -> jdbc.sql(sql).params(params).query((rs, n) -> event(rs)).list());
    }

    @Override
    public long eventCount(long releaseId, EventSelection selection) {
        String sql = SqlResource.text("fireinfo/select_fire_events_count.sql")
                .replace("{area}", SqlResource.text(AREA_SQL.get(selection.area().kind())));
        return translate(() -> jdbc.sql(sql).params(selectionParams(releaseId, selection)).query(Long.class).single());
    }

    @Override
    public Optional<FireEvent> event(long releaseId, String eventId) {
        return translate(() -> jdbc.sql(SqlResource.text("fireinfo/select_fire_event.sql")).param("releaseId", releaseId)
                .param("eventId", eventId).query((rs, n) -> event(rs)).optional());
    }

    @Override
    public FwiHead fwiHead(long releaseId, String aoiId) {
        return translate(() -> jdbc.sql(SqlResource.text("fireinfo/select_fwi_head.sql")).param("releaseId", releaseId)
                .param("aoiId", aoiId).query((rs, n) -> new FwiHead(rs.getObject("as_of_date", LocalDate.class),
                        rs.getString("name"), rs.getLong("days"))).single());
    }

    @Override
    public Optional<LocalDate> fwiLayerAsOf(long releaseId) {
        // max() answers one row, null when the release has no FWI rows
        List<LocalDate> max = translate(() -> jdbc.sql(SqlResource.text("fireinfo/select_fwi_layer_as_of.sql"))
                .param("releaseId", releaseId).query((rs, n) -> rs.getObject(1, LocalDate.class)).list());
        return max.isEmpty() ? Optional.empty() : Optional.ofNullable(max.get(0));
    }

    @Override
    public List<FwiDay> fwiDays(long releaseId, String aoiId, LocalDate start, LocalDate end) {
        return translate(() -> jdbc.sql(SqlResource.text("fireinfo/select_fwi_days.sql")).param("releaseId", releaseId)
                .param("aoiId", aoiId).param("start", start).param("end", end)
                .query((rs, n) -> new FwiDay(rs.getObject("date", LocalDate.class), dbl(rs, "fwi_mean"), dbl(rs, "fwi_max"),
                        rs.getString("fwi_class"), dbl(rs, "fwi_pct"), rs.getString("fwi_rel_class"), dbl(rs, "isi_mean"),
                        dbl(rs, "bui_mean"), dbl(rs, "ffmc_mean"), dbl(rs, "dc_mean"), integer(rs, "n_cells"))).list());
    }

    @Override
    public Optional<SpreadHead> spreadHead(long releaseId) {
        return translate(() -> jdbc.sql(SqlResource.text("fireinfo/select_spread_head.sql")).param("releaseId", releaseId)
                .query((rs, n) -> new SpreadHead(rs.getObject("as_of_date", LocalDate.class),
                        rs.getObject("target_date", LocalDate.class), rs.getString("model"), rs.getString("source_satellite"),
                        rs.getBoolean("weather_available"), integer(rs, "weather_lag_days"))).optional());
    }

    @Override
    public List<SpreadCell> spreadCells(long releaseId, Bbox box) {
        return translate(() -> jdbc.sql(SqlResource.text("fireinfo/select_spread_cells.sql")).param("releaseId", releaseId)
                .param("west", box.west()).param("south", box.south()).param("east", box.east()).param("north", box.north())
                .query((rs, n) -> new SpreadCell(rs.getString("region"), rs.getString("cand_adm0"), rs.getDouble("cell_lat"),
                        rs.getDouble("cell_lon"), rs.getDouble("cell_size_deg"), rs.getDouble("p_spread"),
                        rs.getBoolean("active_today"), integer(rs, "fire_event_days"), integer(rs, "n_det_today_7x7"))).list());
    }

    @Override
    public RegimeHead regimeHead(long releaseId, String aoiId) {
        return translate(() -> jdbc.sql(SqlResource.text("fireinfo/select_fire_regime_head.sql")).param("releaseId", releaseId)
                .param("aoiId", aoiId).query((rs, n) -> new RegimeHead(rs.getLong("months"), rs.getString("name"))).single());
    }

    @Override
    public List<RegimeMonth> regimeMonths(long releaseId, String aoiId, int from, int to) {
        return translate(() -> jdbc.sql(SqlResource.text("fireinfo/select_fire_regime_months.sql")).param("releaseId", releaseId)
                .param("aoiId", aoiId).param("from", from).param("to", to)
                .query((rs, n) -> new RegimeMonth(rs.getInt("year"), rs.getInt("month"), rs.getString("fire_regime"),
                        dbl(rs, "W"), dbl(rs, "W_large"), dbl(rs, "share_large"), integer(rs, "n_events"),
                        integer(rs, "n_large_events"), rs.getString("top_event_id"), dbl(rs, "top_event_w"))).list());
    }

    private static Map<String, Object> selectionParams(long releaseId, EventSelection selection) {
        Map<String, Object> params = new HashMap<>();
        params.put("releaseId", releaseId);
        params.put("start", selection.start());
        params.put("end", selection.end());
        params.put("minDet", selection.minDet());
        AREA_PARAMS.get(selection.area().kind()).accept(selection.area(), params);
        return params;
    }

    private static FireEvent event(ResultSet rs) throws SQLException {
        return new FireEvent(rs.getString("event_id"), rs.getString("region"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), rs.getInt("duration_days"), rs.getInt("n_det"),
                rs.getDouble("sum_weight"), dbl(rs, "frp_sum"), dbl(rs, "frp_max"), rs.getDouble("centroid_lat"),
                rs.getDouble("centroid_lon"), rs.getDouble("lat_min"), rs.getDouble("lat_max"), rs.getDouble("lon_min"),
                rs.getDouble("lon_max"), dbl(rs, "burned_area_km2"), bool(rs, "burn_confirmed"), rs.getString("instrument"),
                integer(rs, "n_viirs"), integer(rs, "n_modis"), rs.getString("country"), rs.getString("adm1_code"),
                rs.getString("sido_nm"), rs.getString("sgg_cd"), rs.getString("sgg_nm"), rs.getString("sgg_all"));
    }

    private static Double dbl(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Boolean bool(ResultSet rs, String column) throws SQLException {
        boolean v = rs.getBoolean(column);
        return rs.wasNull() ? null : v;
    }

    private static <T> T translate(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException e) {
            throw new DataUnavailableException(ErrorCode.DATA_NOT_LOADED, null, "release fire-info layers are not available", e);
        }
    }
}
