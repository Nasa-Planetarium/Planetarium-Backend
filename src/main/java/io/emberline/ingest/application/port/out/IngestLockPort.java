package io.emberline.ingest.application.port.out;

import java.util.Optional;

/** Cluster-wide ingest lock (one stage at a time); held until closed. */
public interface IngestLockPort {

    Optional<Lock> tryAcquire();

    /** A held lock. */
    interface Lock extends AutoCloseable {
        @Override
        void close();
    }
}
