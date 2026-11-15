package io.emberline.outlook.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks of a list of municipality rows (contract listAoiOccurrence): only rows with {@code suspected_industrial=false}
 * are ranked, by p_any descending then aoi_id ascending, 1-based, no shared ranks; suspected rows have no rank. The
 * ranked rows come first in rank order, then the suspected rows in the same order.
 */
public final class AoiRanking {

    static final Comparator<AoiRow> ORDER = Comparator.comparingDouble(AoiRow::pAny).reversed()
            .thenComparing(AoiRow::aoiId);

    /** A row and its rank (null for a suspected row). */
    public record Ranked(AoiRow row, Integer rank) {
    }

    private AoiRanking() {
    }

    public static List<Ranked> rank(List<AoiRow> rows) {
        List<AoiRow> ranked = rows.stream().filter(r -> !r.suspectedIndustrial()).sorted(ORDER).toList();
        List<AoiRow> suspected = rows.stream().filter(AoiRow::suspectedIndustrial).sorted(ORDER).toList();
        List<Ranked> out = new ArrayList<>(rows.size());
        for (int i = 0; i < ranked.size(); i++) {
            out.add(new Ranked(ranked.get(i), i + 1));
        }
        suspected.forEach(r -> out.add(new Ranked(r, null)));
        return out;
    }
}
