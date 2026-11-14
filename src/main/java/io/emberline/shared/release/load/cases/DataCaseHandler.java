package io.emberline.shared.release.load.cases;

import io.emberline.shared.release.load.ValidationReport;

/** What ingest does when {@code rows} rows of {@code file} fall into one data case. */
public interface DataCaseHandler {

    DataCase handles();

    void handle(String file, long rows, ValidationReport report);
}
