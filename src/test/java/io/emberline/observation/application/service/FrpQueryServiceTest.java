package io.emberline.observation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.emberline.catalog.api.AoiSummary;
import io.emberline.catalog.api.CatalogApi;
import io.emberline.observation.application.port.in.CellFrpList;
import io.emberline.observation.application.port.in.CellFrpQuery;
import io.emberline.observation.application.port.out.FrpReadPort;
import io.emberline.observation.domain.CellIdRange;
import io.emberline.observation.domain.FrpRow;
import io.emberline.shared.cache.LayerCache;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.config.EmberlineProperties;
import io.emberline.shared.error.EmberlineException;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.release.ReleaseRef;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * L3-4 at the service: the /cells/frp limits are the contract values from {@code emberline.limits} (Limits.CONTRACT),
 * checked at their edges, each with its error number; the row limit reads at most frp_rows + 1 rows. L3-5: -0.0 is
 * written as 0 while the row stays. R5: the cache key is the normalized query.
 */
class FrpQueryServiceTest {

    static final ReleaseRef RELEASE = new ReleaseRef(7, "v1.2-2026-11-15", "v1-m2v-2026-11-14", "v1.2-2026-11-15#0123456789ab", Instant.EPOCH);
    static final String KR = "124.5,33,132,38.7";
    static final EmberlineProperties.Limits LIMITS = EmberlineProperties.Limits.CONTRACT;

    /** Returns {@code rows} rows of the asked kind and remembers the limit it was given. */
    static final class FakePort implements FrpReadPort {
        int rows;
        List<Integer> limits = new ArrayList<>();
        List<FrpRow> data = null;

        @Override
        public boolean loaded(long releaseId) {
            return true;
        }

        @Override
        public List<FrpRow> days(long releaseId, LocalDate start, LocalDate end, CellIdRange range, long[] cells, int limit) {
            limits.add(limit);
            return rows(start);
        }

        @Override
        public List<FrpRow> months(long releaseId, LocalDate start, LocalDate end, CellIdRange range, long[] cells, int limit) {
            limits.add(limit);
            return rows(start.withDayOfMonth(1));
        }

        private List<FrpRow> rows(LocalDate date) {
            if (data != null) {
                return data;
            }
            List<FrpRow> out = new ArrayList<>();
            for (int i = 0; i < rows; i++) {
                out.add(new FrpRow(4549889L + i, date, 1.0, 1.5, 2.5f, 1));
            }
            return out;
        }
    }

    static final class RecordingCache implements LayerCache {
        final List<String> keys = new ArrayList<>();

        @Override
        public <T> T get(LayerRoute route, ReleaseRef release, String normalizedQuery, Class<T> type, Supplier<T> compute) {
            keys.add(key(route, release, normalizedQuery));
            return compute.get();
        }

        @Override
        public String json(LayerRoute route, ReleaseRef release, String normalizedQuery, Supplier<String> serialize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String key(LayerRoute route, ReleaseRef release, String normalizedQuery) {
            return route.code() + " " + release.dataVersion() + " " + normalizedQuery;
        }
    }

    static final CatalogApi NO_CATALOG = new CatalogApi() {
        @Override
        public AoiSummary requireAoi(ReleaseRef release, String aoiId, String param) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AoiSummary> findAoi(ReleaseRef release, String aoiId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> boundaryGeometry(ReleaseRef release, String aoiId) {
            throw new UnsupportedOperationException();
        }
    };

    final FakePort port = new FakePort();
    final RecordingCache cache = new RecordingCache();
    final FrpQueryService service = new FrpQueryService(port, NO_CATALOG, (release, aoiId) -> Optional.empty(), cache,
            new EmberlineProperties(null, null, null, null, null));

    CellFrpList frp(String bbox, String start, String end, String freq) {
        return service.frp(RELEASE, new CellFrpQuery(null, bbox, LocalDate.parse(start), LocalDate.parse(end), freq));
    }

    static void assertError(Runnable call, ErrorCode code, String param) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(EmberlineException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(code);
            assertThat(e.param()).isEqualTo(param);
        });
    }

    @Test
    void theLimitsAreTheContractValues() {
        assertThat(LIMITS.frpRows()).isEqualTo(50_000);
        assertThat(LIMITS.frpDayDays()).isEqualTo(31);
        assertThat(LIMITS.frpMonthMonths()).isEqualTo(24);
        assertThat(LIMITS.frpBboxAreaDeg2()).isEqualTo(100.0);
    }

    @Test
    void dayPeriodIsAtMost31Days() {
        assertThat(frp(KR, "2025-03-01", "2025-03-31", "day").freq()).isEqualTo("day");
        assertError(() -> frp(KR, "2025-03-01", "2025-04-01", "day"), ErrorCode.FRP_RANGE_TOO_LARGE, "end");
    }

    @Test
    void monthPeriodIsAtMost24CalendarMonths() {
        assertThat(frp(KR, "2024-12-31", "2026-11-01", "month").freq()).isEqualTo("month");
        assertError(() -> frp(KR, "2024-11-30", "2026-11-01", "month"), ErrorCode.FRP_RANGE_TOO_LARGE, "end");
    }

    @Test
    void bboxAreaIsAtMost100SquareDegrees() {
        assertThat(frp("120,30,130,40", "2025-03-01", "2025-03-01", "day").count()).isZero();
        assertError(() -> frp("120,30,130.1,40", "2025-03-01", "2025-03-01", "day"), ErrorCode.BBOX_AREA_TOO_LARGE, "bbox");
    }

    @Test
    void answerRowsAreAtMost50000AndOnlyThatManyPlusOneAreRead() {
        port.rows = LIMITS.frpRows();
        assertThat(frp(KR, "2025-03-01", "2025-03-31", "day").count()).isEqualTo(50_000);
        assertThat(port.limits).containsExactly(50_001);
        port.rows = LIMITS.frpRows() + 1;
        assertError(() -> frp(KR, "2025-03-01", "2025-03-31", "day"), ErrorCode.FRP_RANGE_TOO_LARGE, null);
        assertError(() -> frp(KR, "2025-01-01", "2025-03-31", "month"), ErrorCode.FRP_RANGE_TOO_LARGE, null);
    }

    @Test
    void exactlyOneOfAoiAndBbox() {
        assertError(() -> service.frp(RELEASE, new CellFrpQuery("KR", KR, LocalDate.parse("2025-03-01"), LocalDate.parse("2025-03-02"), "day")),
                ErrorCode.AOI_AND_BBOX, "bbox");
        assertError(() -> service.frp(RELEASE, new CellFrpQuery(null, null, LocalDate.parse("2025-03-01"), LocalDate.parse("2025-03-02"), "day")),
                ErrorCode.INVALID_PARAM, "bbox");
        assertError(() -> frp(KR, "2025-03-02", "2025-03-01", "day"), ErrorCode.INVALID_PARAM, "start");
    }

    @Test
    void minusZeroIsWrittenAsZeroAndTheRowStays() {
        port.data = List.of(new FrpRow(4549889L, LocalDate.parse("2025-03-20"), -0.0, -0.0, -0.0f, 1),
                new FrpRow(4549890L, LocalDate.parse("2025-03-20"), 0.0, 0.0, 0.0f, 1));
        CellFrpList list = frp(KR, "2025-03-20", "2025-03-20", "day");
        assertThat(list.count()).isEqualTo(2);
        for (CellFrpList.Cell c : list.cells()) {
            assertThat(Double.doubleToRawLongBits(c.frpSum())).isZero();
            assertThat(Double.doubleToRawLongBits(c.frpSnppEquivSum())).isZero();
            assertThat(Float.floatToRawIntBits(c.frpSnppEquivMax())).isZero();
        }
        assertThat(list.cells().get(0).cellLat()).isEqualTo(36.35);
        assertThat(list.cells().get(0).cellLon()).isEqualTo(128.95);
        assertThat(list.dataRelease()).isEqualTo("data-v1.2-2026-11-15");
        assertThat(list.coverage().available()).isTrue();
    }

    @Test
    void theCacheKeyIsTheNormalizedQuery() {
        frp(" 124.5, 33.0 ,132,38.70", "2025-03-20", "2025-03-21", "day");
        frp(KR, "2025-03-01", "2025-04-30", "month");
        assertThat(cache.keys).containsExactly(
                "frp v1.2-2026-11-15#0123456789ab bbox=124.5,33.0,132.0,38.7&end=2025-03-21&freq=day&start=2025-03-20",
                "frp v1.2-2026-11-15#0123456789ab bbox=124.5,33.0,132.0,38.7&end=2025-04-30&freq=month&start=2025-03-01");
    }

    @Test
    void anAoiWithoutCellsIsCoverageNoCells() {
        CatalogApi catalog = new CatalogApi() {
            @Override
            public AoiSummary requireAoi(ReleaseRef release, String aoiId, String param) {
                return new AoiSummary(aoiId, null, null, "country", null, 0, false);
            }

            @Override
            public Optional<AoiSummary> findAoi(ReleaseRef release, String aoiId) {
                return Optional.empty();
            }

            @Override
            public Optional<String> boundaryGeometry(ReleaseRef release, String aoiId) {
                return Optional.empty();
            }
        };
        FrpQueryService s = new FrpQueryService(port, catalog, (release, aoiId) -> Optional.of(new long[0]), cache,
                new EmberlineProperties(null, null, null, null, null));
        CellFrpList list = s.frp(RELEASE, new CellFrpQuery("ADM0_XXX", null, LocalDate.parse("2025-03-01"), LocalDate.parse("2025-03-02"), "day"));
        assertThat(list.cells()).isEmpty();
        assertThat(list.aoiId()).isEqualTo("ADM0_XXX");
        assertThat(list.coverage().available()).isFalse();
        assertThat(list.coverage().reason().code()).isEqualTo("no_cells");
        assertThat(port.limits).isEmpty();
    }
}
