package io.emberline.ingest.application.service;

import io.emberline.ingest.application.port.in.IngestOutcome;
import io.emberline.ingest.application.port.in.StageReleaseUseCase;
import io.emberline.ingest.application.port.out.IngestLockPort;
import io.emberline.ingest.application.port.out.LoadTaskPort;
import io.emberline.ingest.application.port.out.ReleaseFolderPort;
import io.emberline.ingest.application.port.out.ReleaseRepository;
import io.emberline.ingest.domain.Manifest;
import io.emberline.ingest.domain.Release;
import io.emberline.ingest.domain.ReleaseKey;
import io.emberline.shared.error.ErrorCode;
import io.emberline.shared.release.load.LoadResult;
import io.emberline.shared.release.load.ReleaseLoadException;
import io.emberline.shared.release.load.ReleaseLoader;
import io.emberline.shared.release.load.ReleaseManifestCheck;
import io.emberline.shared.release.load.ReleaseSource;
import io.emberline.shared.release.load.ValidationReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * stage: key → folder (inside the root) → lock → manifest + per-file sha256 (SHA256SUMS, and release_manifest.csv when
 * the release ships it) → loaders' checks → VALIDATED → LOADING → each context loads its own partitions → LOADED. Any
 * failure: REJECTED with reason codes, partitions dropped, the active release untouched.
 */
@Service
class ReleaseStagingService implements StageReleaseUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReleaseStagingService.class);

    private final ReleaseRepository releases;
    private final ReleaseFolderPort folders;
    private final IngestLockPort locks;
    private final LoadTaskPort tasks;
    private final List<ReleaseLoader> loaders;
    private final ReleaseManifestCheck releaseManifest;
    private final Clock clock;

    ReleaseStagingService(ReleaseRepository releases, ReleaseFolderPort folders, IngestLockPort locks, LoadTaskPort tasks,
                          List<ReleaseLoader> loaders, ReleaseManifestCheck releaseManifest, Optional<Clock> clock) {
        this.releases = releases;
        this.folders = folders;
        this.locks = locks;
        this.tasks = tasks;
        this.loaders = loaders.stream().sorted(Comparator.comparingInt(ReleaseLoader::order)).toList();
        this.releaseManifest = releaseManifest;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    @Override
    public IngestOutcome stage(String rawKey) {
        ReleaseKey key = new ReleaseKey(rawKey);
        Optional<Path> dir = folders.resolve(key);
        if (dir.isEmpty()) {
            return Outcomes.failed(key.value(), "ABSENT", ErrorCode.RELEASE_INVALID, List.of("RELEASE_DIR_MISSING:" + key));
        }
        Optional<IngestLockPort.Lock> lock = locks.tryAcquire();
        if (lock.isEmpty()) {
            return Outcomes.failed(key.value(), stateOf(key), ErrorCode.RELEASE_STATE_CONFLICT,
                    List.of("RELEASE_STATE_CONFLICT:another release is LOADING"));
        }
        try (IngestLockPort.Lock held = lock.get()) {
            abortStaleLoads();
            return stageLocked(key, dir.get());
        }
    }

    private String stateOf(ReleaseKey key) {
        return releases.findByKey(key).map(r -> r.state().name()).orElse("ABSENT");
    }

    /** A LOADING row while nobody holds the lock is a crashed load: reject it and drop what it created. */
    private void abortStaleLoads() {
        for (Release stale : releases.findLoading()) {
            loaders.forEach(l -> l.drop(stale.id()));
            stale.reject(List.of("LOAD_ABORTED"));
            releases.update(stale);
            log.warn("release {} was LOADING without a holder; rejected", stale.key());
        }
    }

    private IngestOutcome stageLocked(ReleaseKey key, Path dir) {
        Optional<Manifest> manifest = folders.manifest(dir);
        Optional<Release> existing = releases.findByKey(key);
        if (existing.isPresent() && existing.get().state().isLoaded()) {
            Release r = existing.get();
            boolean same = manifest.isPresent() && manifest.get().sha256().equalsIgnoreCase(r.manifestSha256());
            return same ? Outcomes.of(r, Map.of(), 0)
                    : Outcomes.failed(key.value(), r.state().name(), ErrorCode.RELEASE_STATE_CONFLICT,
                    List.of("RELEASE_STATE_CONFLICT:release key already loaded with other content"));
        }
        Release release;
        if (existing.isPresent()) {
            release = existing.get();
            release.restage();
            releases.update(release);
            release = releases.findByKey(key).orElseThrow();
        } else {
            release = releases.insert(Release.stage(key, clock.instant()));
        }
        ValidationReport report = new ValidationReport();
        String methodVersion = folders.methodVersion(dir).orElse(null);
        Map<Path, String> hashes = new HashMap<>();
        Function<Path, String> sha256 = file -> hashes.computeIfAbsent(file, folders::sha256);
        manifest.ifPresentOrElse(m -> checkFiles(dir, m, sha256, report), () -> report.reject("MANIFEST_MISSING", "SHA256SUMS", null));
        releaseManifest.check(dir, needed().keySet(), sha256, report);
        ReleaseSource source = new ReleaseSource(release.id(), key.value(), dir, methodVersion, report);
        if (report.ok()) {
            for (ReleaseLoader loader : loaders) {
                loader.validate(source);
            }
        }
        if (!report.ok() || methodVersion == null) {
            List<String> reasons = new ArrayList<>(report.reasons());
            if (reasons.isEmpty()) {
                reasons.add("META_INVALID:m2v_meta.json");
            }
            release.reject(reasons);
            releases.update(release);
            log.warn("release {} rejected: {}", key, reasons);
            return Outcomes.of(releases.findByKey(key).orElseThrow(), Map.of(), Outcomes.exit(ErrorCode.RELEASE_INVALID));
        }
        release.validated(methodVersion, manifest.get().sha256(), report.aliases(), report.warnings());
        releases.update(release);
        release = releases.findByKey(key).orElseThrow();
        release.startLoading();
        releases.update(release);
        release = releases.findByKey(key).orElseThrow();
        Map<String, Long> rows = new LinkedHashMap<>();
        String failure = load(source, rows);
        if (failure != null) {
            loaders.forEach(l -> l.drop(source.releaseId()));
            release.reject(List.of(failure));
            releases.update(release);
            return Outcomes.of(releases.findByKey(key).orElseThrow(), rows, Outcomes.exit(ErrorCode.RELEASE_INVALID));
        }
        release.loaded(clock.instant());
        releases.update(release);
        return Outcomes.of(releases.findByKey(key).orElseThrow(), rows, 0);
    }

    private String load(ReleaseSource source, Map<String, Long> rows) {
        for (ReleaseLoader loader : loaders) {
            tasks.started(source.releaseId(), loader.context());
            try {
                LoadResult result = loader.load(source);
                rows.putAll(result.rows());
                tasks.loaded(source.releaseId(), loader.context(), result.rows());
            } catch (ReleaseLoadException e) {
                log.error("load of {} failed: {}", loader.context(), e.reason(), e);
                tasks.failed(source.releaseId(), loader.context(), e.reason());
                return e.reason();
            } catch (RuntimeException e) {
                log.error("load of {} failed", loader.context(), e);
                tasks.failed(source.releaseId(), loader.context(), "LOAD_FAILED:" + loader.context());
                return "LOAD_FAILED:" + loader.context();
            }
        }
        return null;
    }

    /** File name → required, over every loader's {@link ReleaseLoader#files()}. */
    private Map<String, Boolean> needed() {
        Map<String, Boolean> needed = new LinkedHashMap<>();
        for (ReleaseLoader loader : loaders) {
            for (ReleaseLoader.ReleaseFile f : loader.files()) {
                needed.merge(f.name(), f.required(), Boolean::logicalOr);
            }
        }
        return needed;
    }

    /**
     * Every file a loader needs is present (required) and listed in SHA256SUMS with a matching sha256. The sha256 of a
     * file is computed once and shared with the release_manifest.csv check ({@link ReleaseManifestCheck}).
     */
    private void checkFiles(Path dir, Manifest manifest, Function<Path, String> sha256, ValidationReport report) {
        needed().forEach((name, required) -> {
            Path file = dir.resolve(name);
            boolean present = Files.isRegularFile(file);
            if (!present) {
                if (required) {
                    report.reject("FILE_MISSING", name, null);
                }
                return;
            }
            String listed = manifest.files().get(name);
            if (listed == null) {
                report.reject("MANIFEST_INCOMPLETE", name, null);
            } else if (!listed.equalsIgnoreCase(sha256.apply(file))) {
                report.reject("FILE_HASH_MISMATCH", name, null);
            }
        });
    }
}
