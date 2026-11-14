package io.emberline.harmonized.domain;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every month of one AOI in one release (aoi_month joined with aoi_month_compare): the unit that is read with one SQL
 * statement and cached as one Redis entry. Year ranges are cut from it in memory.
 */
public final class AoiMonths {

    private final String aoiId;
    private final List<MonthRecord> months;
    private final Map<Integer, MonthRecord> byMonth;

    public AoiMonths(String aoiId, List<MonthRecord> months) {
        this.aoiId = aoiId;
        this.months = months.stream()
                .sorted(Comparator.comparingInt(MonthRecord::year).thenComparingInt(MonthRecord::month)).toList();
        this.byMonth = new HashMap<>(months.size() * 2);
        for (MonthRecord m : this.months) {
            byMonth.put(key(m.year(), m.month()), m);
        }
    }

    public String aoiId() {
        return aoiId;
    }

    public List<MonthRecord> months() {
        return months;
    }

    public Optional<MonthRecord> at(int year, int month) {
        return Optional.ofNullable(byMonth.get(key(year, month)));
    }

    /** First year with a row, or empty when the AOI has no rows at all. */
    public Optional<Integer> firstYear() {
        return months.isEmpty() ? Optional.empty() : Optional.of(months.get(0).year());
    }

    public Optional<Integer> lastYear() {
        return months.isEmpty() ? Optional.empty() : Optional.of(months.get(months.size() - 1).year());
    }

    private static int key(int year, int month) {
        return year * 100 + month;
    }
}
