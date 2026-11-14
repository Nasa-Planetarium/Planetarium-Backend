package io.emberline.ingest.application.service;

import io.emberline.ingest.application.port.in.IngestOutcome;
import io.emberline.ingest.domain.Release;
import io.emberline.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;

/** Builds command outcomes; exit codes come from the ErrorCode catalogue (cli_exit). */
final class Outcomes {

    private Outcomes() {
    }

    static IngestOutcome of(Release r, Map<String, Long> rows, int exit) {
        return new IngestOutcome(r.key().value(), r.state().name(), r.reasonCodes(), r.aliasesUsed(), r.warnings(),
                r.methodVersion(), r.dataVersion(), rows, exit);
    }

    static IngestOutcome failed(String key, String state, ErrorCode code, List<String> reasons) {
        return new IngestOutcome(key, state, reasons, List.of(), List.of(), null, null, Map.of(), code.cliExit().orElse(3));
    }

    static int exit(ErrorCode code) {
        return code.cliExit().orElse(3);
    }
}
