package io.emberline.shared.release;

/**
 * Pod-local application event: this pod's active release became {@code release} — the first read after start, or a
 * switch seen by the poll or by a ReleaseActivated (activation, rollback). Published after the pointer is set, on the
 * thread that refreshed it, so a listener must hand long work to its own thread.
 */
public record ActiveReleaseSwitched(ReleaseRef release) {
}
