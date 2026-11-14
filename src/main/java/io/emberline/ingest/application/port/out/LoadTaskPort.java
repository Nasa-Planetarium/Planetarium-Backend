package io.emberline.ingest.application.port.out;

import java.util.Map;

/** ingest.load_task: one row per (release, context). */
public interface LoadTaskPort {

    void started(long releaseId, String context);

    void loaded(long releaseId, String context, Map<String, Long> rows);

    void failed(long releaseId, String context, String reason);
}
