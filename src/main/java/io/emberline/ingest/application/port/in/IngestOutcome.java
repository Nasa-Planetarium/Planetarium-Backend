package io.emberline.ingest.application.port.in;

import java.util.List;
import java.util.Map;

/** Result of one ingest command; the CLI prints it as its last stdout line and exits with {@code exitCode}. */
public record IngestOutcome(String releaseKey, String state, List<String> reasonCodes, List<String> aliasesUsed,
                            List<String> warnings, String methodVersion, String dataVersion, Map<String, Long> rows,
                            int exitCode) {

    public IngestOutcome {
        reasonCodes = List.copyOf(reasonCodes);
        aliasesUsed = List.copyOf(aliasesUsed);
        warnings = List.copyOf(warnings);
        rows = Map.copyOf(rows);
    }

    /** Same outcome with the reason codes of this command (the release row may carry older ones). */
    public IngestOutcome withReasons(List<String> reasons) {
        return new IngestOutcome(releaseKey, state, reasons, aliasesUsed, warnings, methodVersion, dataVersion, rows, exitCode);
    }
}
