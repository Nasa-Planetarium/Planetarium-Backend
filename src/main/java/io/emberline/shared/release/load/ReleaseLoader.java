package io.emberline.shared.release.load;

import java.util.List;

/**
 * SPI each read context implements for its own schema ("only the schema owner writes its tables").
 * ingest validates the folder, then calls {@link #validate} on every loader, then {@link #load} in {@link #order()}.
 * A loader creates one child partition per table (LIST by release_id), streams rows with COPY, builds indexes,
 * adds CHECK (release_id = id), ANALYZEs and ATTACHes. {@link #drop} removes those partitions again.
 */
public interface ReleaseLoader {

    /** Context (= schema) name, e.g. {@code catalog}. */
    String context();

    /** Load order (smaller first). */
    int order();

    /** Release files this context consumes; required ones must exist and be listed in SHA256SUMS. */
    List<ReleaseFile> files();

    /** File-level checks (columns, code values, duplicate keys, data cases). Adds reasons/warnings/aliases to the report. */
    void validate(ReleaseSource source);

    /** Creates and attaches this context's partitions for {@code source.releaseId()}. Returns rows per table. */
    LoadResult load(ReleaseSource source);

    /** Detaches and drops this context's partitions of a release (rejected or failed load). Idempotent. */
    void drop(long releaseId);

    /** A file name inside the release folder and whether the release must contain it. */
    record ReleaseFile(String name, boolean required) {
    }
}
