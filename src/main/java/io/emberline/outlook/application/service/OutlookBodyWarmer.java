package io.emberline.outlook.application.service;

import io.emberline.outlook.application.port.in.GetGridOccurrenceUseCase;
import io.emberline.outlook.application.port.in.GridOccurrenceQuery;
import io.emberline.outlook.application.port.in.ListAoiOccurrenceUseCase;
import io.emberline.shared.error.EmberlineException;
import io.emberline.shared.release.ActiveReleaseSwitched;
import io.emberline.shared.release.ReleaseRef;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Serializes the whole-world grid ({@code fields} map and full) and the KR list as soon as this API pod has the release
 * (contract CacheControlLayer: 「/occurrence/grid 전 세계·/occurrence/aois?parent_id=KR 는 적재 때 미리 직렬화」): on every
 * {@link ActiveReleaseSwitched} — the pod's first read of the active release (its first request or first poll after
 * start) and every switch after it (activation or rollback seen by the poll or by ReleaseActivated). It never reads the
 * active release itself, so the pod's lazy first read stays where it was. It goes through the same use cases a request
 * does, so the bodies land in the layer cache and in this pod's memory. One background thread; a release without the
 * outlook files is skipped. Not in the ingest process (no web application — it serves nothing). docs/DEVIATIONS.md
 *
 */
@Component
@ConditionalOnWebApplication
class OutlookBodyWarmer {

    private static final Logger log = LoggerFactory.getLogger(OutlookBodyWarmer.class);

    private final GetGridOccurrenceUseCase grid;
    private final ListAoiOccurrenceUseCase aois;
    private final AtomicReference<String> warmed = new AtomicReference<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "outlook-body-warmer");
        t.setDaemon(true);
        return t;
    });

    OutlookBodyWarmer(GetGridOccurrenceUseCase grid, ListAoiOccurrenceUseCase aois) {
        this.grid = grid;
        this.aois = aois;
    }

    @EventListener
    void on(ActiveReleaseSwitched event) {
        worker.execute(() -> warm(event.release()));
    }

    /** The three bodies of {@code release}, once per data_version (a request may have serialized them already). */
    void warm(ReleaseRef release) {
        if (release.dataVersion().equals(warmed.get())) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            grid.grid(release, new GridOccurrenceQuery(null, null, GridOccurrenceService.MAP)).json().get();
            grid.grid(release, new GridOccurrenceQuery(null, null, GridOccurrenceService.FULL)).json().get();
            aois.list(release, AoiOccurrenceService.KR).json().get();
            warmed.set(release.dataVersion());
            log.info("outlook bodies of {} serialized in {} ms (whole grid map/full, KR list)", release.dataVersion(),
                    (System.nanoTime() - t0) / 1_000_000);
        } catch (EmberlineException e) {
            log.info("outlook bodies of {} not serialized ahead: {}", release.dataVersion(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("outlook bodies of {} not serialized ahead", release.dataVersion(), e);
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
