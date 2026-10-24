/*
 * Authors:
 *   1. Aaditya Singh, A1946894
 */
package comp3011.models;

/**
 * Told about every frame a {@link VideoPlayerModel} puts on screen, with the timings behind it.
 *
 * <p>
 * Exists so the load test ({@code PlaybackLoadIT}) can compare the single and multi-threaded models on every
 * frame, not just the once-per-second console trace. Called on the JavaFX Application Thread, so it must be quick.
 * All times are {@link System#nanoTime()} based.
 * </p>
 */
@FunctionalInterface
public interface FrameTimingListener {
    /** The default: playback is not being measured. */
    FrameTimingListener NONE = (frameNumber, targetTimeNs, preparedAtNs, shownAtNs) -> { };

    /**
     * @param frameNumber  the frame's number in the media
     * @param targetTimeNs when the frame was due on screen
     * @param preparedAtNs when its effects and conversion finished (headroom = target - prepared)
     * @param shownAtNs    the JavaFX pulse time it was shown at (lateness = shown - target)
     */
    void frameShown(int frameNumber, long targetTimeNs, long preparedAtNs, long shownAtNs);
}
