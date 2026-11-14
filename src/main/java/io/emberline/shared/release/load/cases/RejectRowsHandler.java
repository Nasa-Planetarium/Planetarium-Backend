package io.emberline.shared.release.load.cases;

import io.emberline.shared.release.load.ValidationReport;

/** Cases that make the file unusable: the release is rejected with the case as reason code. */
public final class RejectRowsHandler implements DataCaseHandler {

    private final DataCase dataCase;

    public RejectRowsHandler(DataCase dataCase) {
        this.dataCase = dataCase;
    }

    @Override
    public DataCase handles() {
        return dataCase;
    }

    @Override
    public void handle(String file, long rows, ValidationReport report) {
        report.reject(dataCase.name(), file, rows + " rows");
    }
}
