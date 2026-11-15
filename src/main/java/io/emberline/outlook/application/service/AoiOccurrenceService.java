package io.emberline.outlook.application.service;

import io.emberline.catalog.api.AoiSummary;
import io.emberline.catalog.api.CatalogApi;
import io.emberline.outlook.application.port.in.AoiOccurrenceItem;
import io.emberline.outlook.application.port.in.AoiOccurrenceListResponse;
import io.emberline.outlook.application.port.in.AoiOccurrenceResponse;
import io.emberline.outlook.application.port.in.GetAoiOccurrenceUseCase;
import io.emberline.outlook.application.port.in.GridSummary;
import io.emberline.outlook.application.port.in.LayerBody;
import io.emberline.outlook.application.port.in.ListAoiOccurrenceUseCase;
import io.emberline.outlook.application.port.in.OccurrenceCsv;
import io.emberline.outlook.application.port.out.LayerBodyCachePort;
import io.emberline.outlook.domain.AoiLayer;
import io.emberline.outlook.domain.AoiRanking;
import io.emberline.outlook.domain.AoiRow;
import io.emberline.outlook.domain.DisplayRound;
import io.emberline.outlook.domain.GridLayer;
import io.emberline.outlook.domain.GridSummaryStats;
import io.emberline.outlook.domain.VsNormal;
import io.emberline.shared.cache.LayerRoute;
import io.emberline.shared.json.Coverage;
import io.emberline.shared.release.ReleaseRef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Municipality probabilities (aoi_occurrence file rows, {@code unit=aoi}) and, for every other AOI, the grid summary of
 * its 0.5° cells ({@code unit=grid_summary}, {@code p_any} null — no regional probability is made). The list
 * answers {@code parent_id=KR} (all rows) and {@code SIDO_*} (that province's rows); any other existing AOI is out of
 * the layer's coverage (200, no items). Ranks are over the listed rows. Values are rounded to 5 decimals for display.
 */
@Service
class AoiOccurrenceService implements ListAoiOccurrenceUseCase, GetAoiOccurrenceUseCase {

    static final String KR = "KR";
    static final String SIDO_LEVEL = "kr_sido";
    static final String UNIT_AOI = "aoi";
    static final String UNIT_GRID_SUMMARY = "grid_summary";
    /** Contract listAoiOccurrence text/csv {@code x-csv-columns}. */
    static final List<String> CSV_COLUMNS = List.of("aoi_id", "name_ko", "target_month", "p_any", "p_none",
            "baseline_p", "vs_normal", "suspected_industrial", "rank", "model", "as_of_date");

    private final CatalogApi catalog;
    private final OccurrenceLayers layers;
    private final GridSummaries summaries;
    private final LayerBodyCachePort bodies;

    AoiOccurrenceService(CatalogApi catalog, OccurrenceLayers layers, GridSummaries summaries, LayerBodyCachePort bodies) {
        this.catalog = catalog;
        this.layers = layers;
        this.summaries = summaries;
        this.bodies = bodies;
    }

    @Override
    public LayerBody list(ReleaseRef release, String parentId) {
        AoiSummary parent = catalog.requireAoi(release, parentId, "parent_id");
        AoiLayer layer = layers.aois(release);
        boolean wholeCountry = KR.equals(parent.aoiId());
        boolean covered = wholeCountry || SIDO_LEVEL.equals(parent.level());
        List<AoiRow> rows = !covered ? List.of() : wholeCountry ? layer.rows()
                : layer.rows().stream().filter(r -> parent.aoiId().equals(parentOf(release, r.aoiId()))).toList();
        List<AoiRanking.Ranked> ranked = AoiRanking.rank(rows);
        Coverage coverage = covered ? Coverage.availableFor(Coverage.Scope.KR_SGG)
                : Coverage.unavailable(Coverage.Scope.KR_SGG, Coverage.Reason.OUT_OF_COVERAGE);
        String normalized = "parent_id=" + parent.aoiId();
        return new LayerBody(ranked.size(), () -> bodies.json(release, LayerRoute.OCCURRENCE_AOIS, normalized, wholeCountry,
                () -> listResponse(release, parent, layer, ranked, coverage)),
                () -> csv(release, parent, layer, ranked));
    }

    @Override
    public LayerBody aoi(ReleaseRef release, String aoiId) {
        AoiSummary aoi = catalog.requireAoi(release, aoiId, "aoi_id");
        AoiLayer aois = layers.aois(release);
        String normalized = "aoi_id=" + aoi.aoiId();
        Optional<AoiRow> row = aois.find(aoi.aoiId());
        if (row.isPresent()) {
            AoiOccurrenceResponse body = rowResponse(release, aoi, row.get());
            return new LayerBody(0, () -> bodies.json(release, LayerRoute.AOI_OCCURRENCE, normalized, false, () -> body), null);
        }
        GridLayer grid = layers.grid(release);
        Optional<GridSummaryStats> stats = summaries.of(release, aoi.aoiId(), grid);
        AoiOccurrenceResponse body = summaryResponse(release, aoi, grid, stats);
        return new LayerBody(0, () -> bodies.json(release, LayerRoute.AOI_OCCURRENCE, normalized, false, () -> body), null);
    }

    private String parentOf(ReleaseRef release, String aoiId) {
        return catalog.findAoi(release, aoiId).map(AoiSummary::parentId).orElse(null);
    }

    private String nameOf(ReleaseRef release, String aoiId) {
        return catalog.findAoi(release, aoiId).map(AoiSummary::nameKo).orElse(null);
    }

    private AoiOccurrenceListResponse listResponse(ReleaseRef release, AoiSummary parent, AoiLayer layer,
                                                   List<AoiRanking.Ranked> ranked, Coverage coverage) {
        String dataRelease = release.dataRelease();
        Coverage rowCoverage = Coverage.availableFor(Coverage.Scope.KR_SGG);
        List<AoiOccurrenceItem> items = new ArrayList<>(ranked.size());
        for (AoiRanking.Ranked r : ranked) {
            AoiRow row = r.row();
            items.add(new AoiOccurrenceItem(row.aoiId(), nameOf(release, row.aoiId()), UNIT_AOI, row.targetMonth(),
                    row.asOfDate(), row.model(), round(row.pAny()), round(row.pNone()), round(row.baselineP()),
                    code(row.vsNormal()), row.suspectedIndustrial(), r.rank(), round(row.rate5y()), row.nYears(),
                    row.lag1Projected(), null, rowCoverage, OccurrenceTexts.AOI_NOTICE, dataRelease));
        }
        return new AoiOccurrenceListResponse(parent.aoiId(), layer.targetMonth(), layer.asOfDate(), items.size(), items,
                coverage, OccurrenceTexts.AOI_NOTICE, dataRelease);
    }

    private AoiOccurrenceResponse rowResponse(ReleaseRef release, AoiSummary aoi, AoiRow row) {
        return new AoiOccurrenceResponse(row.aoiId(), aoi.nameKo(), UNIT_AOI, row.targetMonth(), row.asOfDate(),
                row.model(), round(row.pAny()), round(row.pNone()), round(row.baselineP()), code(row.vsNormal()),
                row.suspectedIndustrial(), round(row.rate5y()), row.nYears(), row.lag1Projected(), null,
                Coverage.availableFor(Coverage.Scope.KR_SGG), OccurrenceTexts.AOI_NOTICE, release.dataRelease());
    }

    private static AoiOccurrenceResponse summaryResponse(ReleaseRef release, AoiSummary aoi, GridLayer grid,
                                                         Optional<GridSummaryStats> stats) {
        GridSummary summary = stats.map(s -> new GridSummary(s.nCells(), s.nPersistent(),
                new GridSummary.Bands(s.lt10(), s.p10to40(), s.p40to70(), s.ge70()),
                DisplayRound.round(s.pAnyMax(), DisplayRound.FULL), DisplayRound.round(s.pAnyMedian(), DisplayRound.FULL)))
                .orElse(null);
        Coverage coverage = stats.isPresent() ? Coverage.availableFor(Coverage.Scope.WORLD_LAND_DETECTED)
                : Coverage.unavailable(Coverage.Scope.WORLD_LAND_DETECTED, Coverage.Reason.NO_CELLS);
        return new AoiOccurrenceResponse(aoi.aoiId(), aoi.nameKo(), UNIT_GRID_SUMMARY, grid.targetMonth(),
                grid.asOfDate(), grid.model(), null, null, null, null, null, null, null, null, summary, coverage,
                OccurrenceTexts.GRID_NOTICE, release.dataRelease());
    }

    /** CSV columns as {@code x-csv-columns}, values as stored (unrounded). */
    private OccurrenceCsv csv(ReleaseRef release, AoiSummary parent, AoiLayer layer, List<AoiRanking.Ranked> ranked) {
        List<List<Object>> rows = new ArrayList<>(ranked.size());
        for (AoiRanking.Ranked r : ranked) {
            AoiRow row = r.row();
            rows.add(Arrays.asList(row.aoiId(), nameOf(release, row.aoiId()), row.targetMonth(), row.pAny(), row.pNone(),
                    row.baselineP(), code(row.vsNormal()), row.suspectedIndustrial(), r.rank(), row.model(), row.asOfDate()));
        }
        return new OccurrenceCsv("emberline_occurrence_aois_" + parent.aoiId() + "_" + layer.targetMonth() + ".csv",
                CSV_COLUMNS, rows);
    }

    private static double round(double value) {
        return DisplayRound.round(value, DisplayRound.FULL);
    }

    private static Double round(Double value) {
        return DisplayRound.round(value, DisplayRound.FULL);
    }

    private static String code(VsNormal v) {
        return v == null ? null : v.code();
    }
}
