package io.emberline.shared.release.load;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collected by ingest and every loader. reason codes reject the release ({@code CHECK:file[:detail]});
 * warnings and aliases are kept and published (/methods/current ingest block, CLI output).
 */
public final class ValidationReport {

    private final List<String> reasons = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Set<String> aliases = new LinkedHashSet<>();

    public synchronized void reject(String check, String file, String detail) {
        reasons.add(detail == null ? check + ":" + file : check + ":" + file + ":" + detail);
    }

    public synchronized void warn(String check, String file, String detail) {
        warnings.add(detail == null ? check + ":" + file : check + ":" + file + ":" + detail);
    }

    /** {@code old->new}, e.g. {@code modis_harmonized->modis_m2v} or {@code parent_aoi->parent_id}. */
    public synchronized void alias(String from, String to) {
        aliases.add(from + "->" + to);
    }

    public synchronized boolean ok() {
        return reasons.isEmpty();
    }

    public synchronized List<String> reasons() {
        return List.copyOf(reasons);
    }

    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    public synchronized List<String> aliases() {
        return List.copyOf(aliases);
    }
}
