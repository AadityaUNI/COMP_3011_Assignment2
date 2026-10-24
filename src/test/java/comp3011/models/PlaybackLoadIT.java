/*
 * Load test comparing the multi-threaded video player model against the original single-threaded one.
 *
 * Authors:
 *   1. Aaditya Singh, A1946894
 */
package comp3011.models;

import comp3011.controllers.CommandLineController;
import comp3011.frameprocessors.FrameProcessor;
import comp3011.views.VideoPlayerView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Plays {@code loadtest.mp4} (the first minute of the assignment's movie.mp4, committed because the full video
 * is over GitHub's 100MB limit) on both players under 6 and 12 stacked effects, in a real window, and counts on
 * every frame shown:
 * <ul>
 * <li><b>late frames</b>: shown more than one frame duration (41.7ms at 24fps) after it was due. Up to one JavaFX
 * pulse (~16.7ms) of lateness is normal, since frames can only go on screen at a pulse.</li>
 * <li><b>negative headroom</b>: the frame's effects finished after it was due (the red value in the console trace).</li>
 * </ul>
 * Fails if the multi-threaded player does worse than the single-threaded one on either count.
 *
 * <p>
 * Run with {@code mvn verify}. Needs a display (a real window is opened) and {@code loadtest.mp4} in the project root,
 * or another video passed with {@code -Dloadtest.video=path}. Takes about 4-5 minutes (4 one-minute runs).
 * </p>
 */
class PlaybackLoadIT {
    // Each load level uses the first N letters, so each level adds effects on top of the previous one.
    // 11 distinct effects exist, so the 12th repeats pepper.
    private static final String EFFECT_CHAIN = "sfwyvdjmbpnp";
    private static final long CLIP_SECONDS = 60;
    // A player that can't keep up plays slower than real time, so cap how long one run may take.
    private static final long EXTRA_SECONDS_ALLOWED = 20;
    // Ignore the first second: JIT warm-up, and the single-threaded clock starts before its first frame is processed.
    private static final double WARM_UP_SECONDS = 1.0;
    // Percentage points of noise allowed before multi counts as worse (e.g. 0.1% vs 0.3% at a light load).
    private static final double TOLERANCE_PERCENT = 1.0;

    private static File clip;
    private static long clipDurationUs;
    private static long frameDurationNs;
    private static int warmUpFrames;
    private static boolean startedJavaFx;

    // Skips (rather than fails) when there is no video or no display, e.g. on a headless build server where
    // *.mp4 is not checked in, so "mvn verify" still passes there. Developers run it locally with both.
    @BeforeAll
    static void setUp() throws Exception {
        File source = new File(System.getProperty("loadtest.video", "loadtest.mp4"));
        assumeTrue(source.isFile(), "Skipping load test: no video. Put loadtest.mp4 in the project root or pass "
                + "-Dloadtest.video=<path>. Looked for " + source.getAbsolutePath());
        assumeTrue(hasDisplay(), "Skipping load test: no display to open the player window on");
        clip = clipFirstMinute(source);

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(clip)) {
            grabber.start();
            double frameRate = grabber.getFrameRate();
            frameDurationNs = Math.round(1_000_000_000.0 / frameRate);
            warmUpFrames = (int) Math.round(frameRate * WARM_UP_SECONDS);
            clipDurationUs = grabber.getLengthInTime();
            grabber.stop();
        }

        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // Fine: another test in this JVM started JavaFX.
        } catch (RuntimeException noDisplay) {
            assumeTrue(false, "Skipping load test: JavaFX could not start (" + noDisplay.getMessage() + ")");
        }
        startedJavaFx = true;
        Platform.setImplicitExit(false); // keep JavaFX alive between runs, when no window is open
    }

    @AfterAll
    static void tearDown() {
        if (startedJavaFx) {
            Platform.exit();
        }
    }

    // On Linux a window needs an X11 or Wayland display; Windows and macOS always have one when run locally.
    private static boolean hasDisplay() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return true;
        }
        return System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
    }

    @ParameterizedTest(name = "{0} effects")
    @ValueSource(ints = {6, 12})
    void multiThreadedKeepsUpAtLeastAsWellAsSingleThreaded(int effectCount) throws Exception {
        String effects = "-" + EFFECT_CHAIN.substring(0, effectCount);

        Result single = play(true, effects);
        Result multi = play(false, effects);

        System.out.printf("%n=== Load test: %d effects (%s) on %s, frame budget %.1fms ===%n",
                effectCount, effects, clip.getName(), frameDurationNs / 1e6);
        System.out.printf("%-7s %7s %20s %20s %12s %15s%n",
                "model", "frames", "late frames", "negative headroom", "worst late", "worst headroom");
        System.out.println(single.row());
        System.out.println(multi.row());
        System.out.println();

        assertTrue(single.frames() > 0, "single-threaded player showed no frames");
        assertTrue(multi.frames() > 0, "multi-threaded player showed no frames");
        assertTrue(multi.latePercent() <= single.latePercent() + TOLERANCE_PERCENT,
                String.format("%d effects: multi-threaded had more late frames (%.1f%%) than single-threaded (%.1f%%)",
                        effectCount, multi.latePercent(), single.latePercent()));
        assertTrue(multi.negativeHeadroomPercent() <= single.negativeHeadroomPercent() + TOLERANCE_PERCENT,
                String.format("%d effects: multi-threaded had more negative headroom frames (%.1f%%) than "
                        + "single-threaded (%.1f%%)",
                        effectCount, multi.negativeHeadroomPercent(), single.negativeHeadroomPercent()));
    }

    // Plays the clip once in its own window and returns the timings of every frame shown after warm-up.
    private Result play(boolean singleThreaded, String effects) throws Exception {
        String name = singleThreaded ? "single" : "multi";
        // Parse the effects exactly as the app does, so the test runs the same processors a user would get.
        ArrayList<FrameProcessor> processors =
                new CommandLineController(new String[]{effects, clip.getPath()}).getProcessors();
        FrameTimings timings = new FrameTimings();
        CompletableFuture<String> finished = new CompletableFuture<>();

        Run run = onFxThread(() -> {
            VideoPlayerView view = new VideoPlayerView();
            Stage stage = new Stage();
            stage.setTitle("Load test: " + name + "-threaded, " + effects);
            stage.setScene(new Scene(view.getRoot()));
            Rectangle2D screen = Screen.getPrimary().getVisualBounds();
            stage.setX(screen.getMinX());
            stage.setY(screen.getMinY());

            // Same sizing as VideoPlayerApp: one video pixel per screen pixel.
            BiConsumer<Integer, Integer> videoSizeChanged = (width, height) -> {
                view.setVideoDisplaySize(width / scale(stage.getOutputScaleX()), height / scale(stage.getOutputScaleY()));
                stage.sizeToScene();
            };
            Consumer<String> statusChanged = status -> {
                view.setStatus(status);
                if (status.equals("Playback finished") || status.startsWith("Error")) {
                    finished.complete(status);
                }
            };

            VideoPlayerModel model = singleThreaded
                    ? new SingleThreadedVideoPlayerModel(false, videoSizeChanged, view::setImage, statusChanged,
                            (playing, paused) -> { }, audioOn -> { }, processors)
                    : new MultiThreadedVideoPlayerModel(false, videoSizeChanged, view::setImage, statusChanged,
                            (playing, paused) -> { }, audioOn -> { }, processors);
            model.setFrameTimingListener(timings);
            stage.show();
            model.play(clip);
            return new Run(stage, model);
        });

        long timeLimitSeconds = clipDurationUs / 1_000_000L + EXTRA_SECONDS_ALLOWED;
        boolean cutShort = false;
        try {
            String status = finished.get(timeLimitSeconds, TimeUnit.SECONDS);
            assertEquals("Playback finished", status, name + "-threaded player failed");
        } catch (TimeoutException e) {
            cutShort = true; // couldn't play the clip in real time; judge it on the frames it did show
        }

        boolean stoppedEarly = cutShort;
        return onFxThread(() -> {
            run.model().setFrameTimingListener(FrameTimingListener.NONE);
            run.model().shutdown();
            run.stage().close();
            return timings.result(name, stoppedEarly);
        });
    }

    // Stream-copies the first minute (video and audio only, no re-encode) into target/, once.
    private static File clipFirstMinute(File source) throws Exception {
        File out = new File("target/load-test/first-" + CLIP_SECONDS + "s-" + source.getName());
        if (out.isFile() && out.lastModified() >= source.lastModified()) {
            return out;
        }
        out.getParentFile().mkdirs();

        String ffmpeg = Loader.load(org.bytedeco.ffmpeg.ffmpeg.class); // the ffmpeg program bundled with JavaCV
        Process process = new ProcessBuilder(ffmpeg, "-y", "-v", "error",
                "-i", source.getPath(),
                "-t", Long.toString(CLIP_SECONDS),
                "-map", "0:v:0", "-map", "0:a:0?",
                "-c", "copy",
                out.getPath())
                .inheritIO()
                .start();
        if (process.waitFor() != 0) {
            fail("ffmpeg could not clip " + source + " (exit code " + process.exitValue() + ")");
        }
        return out;
    }

    private static double scale(double outputScale) {
        return outputScale > 0 ? outputScale : 1;
    }

    private static <T> T onFxThread(Callable<T> task) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }

    private record Run(Stage stage, VideoPlayerModel model) {
    }

    // Counts late and negative-headroom frames. Only touched on the FX thread.
    private static final class FrameTimings implements FrameTimingListener {
        private int seen;
        private int frames;
        private int late;
        private int negativeHeadroom;
        private long worstLatenessNs = Long.MIN_VALUE;
        private long worstHeadroomNs = Long.MAX_VALUE;

        @Override
        public void frameShown(int frameNumber, long targetTimeNs, long preparedAtNs, long shownAtNs) {
            if (seen++ < warmUpFrames) {
                return;
            }
            frames++;

            long latenessNs = shownAtNs - targetTimeNs;
            long headroomNs = targetTimeNs - preparedAtNs;
            if (latenessNs > frameDurationNs) {
                late++;
            }
            if (headroomNs < 0) {
                negativeHeadroom++;
            }
            worstLatenessNs = Math.max(worstLatenessNs, latenessNs);
            worstHeadroomNs = Math.min(worstHeadroomNs, headroomNs);
        }

        Result result(String model, boolean cutShort) {
            return new Result(model, frames, late, negativeHeadroom, worstLatenessNs, worstHeadroomNs, cutShort);
        }
    }

    private record Result(String model, int frames, int late, int negativeHeadroom,
                          long worstLatenessNs, long worstHeadroomNs, boolean cutShort) {
        double latePercent() {
            return frames == 0 ? 0 : 100.0 * late / frames;
        }

        double negativeHeadroomPercent() {
            return frames == 0 ? 0 : 100.0 * negativeHeadroom / frames;
        }

        String row() {
            return String.format("%-7s %7d %11d (%5.1f%%) %11d (%5.1f%%) %10.1fms %13.1fms%s",
                    model, frames,
                    late, latePercent(),
                    negativeHeadroom, negativeHeadroomPercent(),
                    frames == 0 ? 0 : worstLatenessNs / 1e6,
                    frames == 0 ? 0 : worstHeadroomNs / 1e6,
                    cutShort ? "   (too slow: stopped at the time limit)" : "");
        }
    }
}
