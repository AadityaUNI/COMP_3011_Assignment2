/*
 * Evidence for the FrameMottler refactor, measured against FrameMottlerLegacy (the original starter version):
 * the effect looks identical, one instance can be shared by the effect workers without races, and sharing it
 * without a lock is faster than the only safe way to share the legacy version (behind a lock).
 *
 * Authors:
 *   1. Aaditya Singh, A1946894
 */
package comp3011.frameprocessors;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Effects are deterministic: the same frame number of the same media always gets the same result. So the output of a
 * single-threaded, in-order run is an exact reference, and any race on shared state (per-block fields, the damage mask
 * cache) shows up as a frame whose bytes differ from it.
 */
public class FrameMottlerThreadSafetyTests {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;
    private static final int CHANNELS = 3;
    private static final int STRIDE = WIDTH * CHANNELS;
    // Spans several 72-frame damage mask segments, so the mask cache is filled and evicted many times.
    private static final int FRAMES = 300;
    private static final int THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());
    private static final int ROUNDS = 5;

    /** One unit of work: mottle the given frame number and return its pixels. */
    @FunctionalInterface
    private interface FrameJob {
        byte[] run(int frameNumber) throws Exception;
    }

    private static InfoVideo videoInfo(String mediaName) {
        return new InfoVideo(mediaName, FRAMES, WIDTH, HEIGHT, Frame.DEPTH_UBYTE, CHANNELS, STRIDE, 24.0, 24, 41667, 0);
    }

    private static <T extends FrameProcessor> T initialised(T processor, String mediaName) throws Exception {
        processor.initialise(videoInfo(mediaName));
        return processor;
    }

    // Each class seeds its instances from its own static instance counter, so a FrameMottler and a FrameMottlerLegacy
    // normally get different seeds and different-looking output. Giving both the same seed (after initialise, which
    // sets it) makes their output directly comparable.
    private static void setMediaSeed(FrameProcessor processor, int seed) throws ReflectiveOperationException {
        Field mediaSeed = processor.getClass().getDeclaredField("mediaSeed");
        mediaSeed.setAccessible(true);
        mediaSeed.setInt(processor, seed);
    }

    // Random but reproducible input pixels for a frame number.
    private static byte[] sourcePixels(int frameNumber) {
        byte[] pixels = new byte[STRIDE * HEIGHT];
        new Random(frameNumber).nextBytes(pixels);
        return pixels;
    }

    // A heap-backed Frame is enough for the mottler, so no native FFmpeg code or video file is needed.
    private static byte[] mottle(FrameProcessor mottler, int frameNumber) throws Exception {
        byte[] pixels = sourcePixels(frameNumber);
        Frame frame = new Frame();
        frame.image = new Buffer[] { ByteBuffer.wrap(pixels) };
        mottler.process(frame, new InfoFrame(frameNumber, frameNumber * 41667L));
        return pixels;
    }

    private static byte[][] sequential(FrameProcessor mottler) throws Exception {
        byte[][] output = new byte[FRAMES][];
        for (int frameNumber = 0; frameNumber < FRAMES; frameNumber++) {
            output[frameNumber] = mottle(mottler, frameNumber);
        }
        return output;
    }

    // Runs every frame in the given order on THREADS workers, all released at once so they overlap as much as
    // possible, like the model's effect workers. Returns the output indexed by frame number.
    private static byte[][] onWorkers(FrameJob job, List<Integer> order) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<byte[]>> results = new ArrayList<>();
            for (int frameNumber : order) {
                results.add(pool.submit(() -> {
                    start.await();
                    return job.run(frameNumber);
                }));
            }
            start.countDown();

            byte[][] output = new byte[FRAMES][];
            for (int i = 0; i < order.size(); i++) {
                output[order.get(i)] = results.get(i).get();
            }
            return output;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Integer> framesThatDiffer(byte[][] expected, byte[][] actual) {
        List<Integer> mismatches = new ArrayList<>();
        for (int frameNumber = 0; frameNumber < FRAMES; frameNumber++) {
            if (!Arrays.equals(expected[frameNumber], actual[frameNumber])) {
                mismatches.add(frameNumber);
            }
        }
        return mismatches;
    }

    private static List<Integer> playbackOrder() {
        List<Integer> order = new ArrayList<>();
        for (int frameNumber = 0; frameNumber < FRAMES; frameNumber++) {
            order.add(frameNumber);
        }
        return order;
    }

    // Mottles frame 10 of movie.mp4 fresh, then again after the mottler has processed other.mp4.
    // Returns true if both results are the same, i.e. nothing from other.mp4 leaked into movie.mp4.
    private static boolean frameUnchangedAfterSwitchingVideos(FrameProcessor mottler) throws Exception {
        mottler.initialise(videoInfo("movie.mp4"));
        byte[] movieFresh = mottle(mottler, 10);

        // Leave other.mp4's masks for frame 10's segment in the cache. Frame 100 moves the cache off that segment
        // first, so even a cache that only remembers the segment number has to rebuild it with other.mp4's masks.
        mottler.initialise(videoInfo("other.mp4"));
        mottle(mottler, 100);
        mottle(mottler, 10);

        mottler.initialise(videoInfo("movie.mp4"));
        return Arrays.equals(movieFresh, mottle(mottler, 10));
    }

    private static long bestOfThreeNs(FrameJob job, List<Integer> order) throws Exception {
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            long start = System.nanoTime();
            onWorkers(job, order);
            best = Math.min(best, System.nanoTime() - start);
        }
        return best;
    }

    @Test
    void refactoredMottlerLooksIdenticalToLegacy() throws Exception {
        // The refactor only moved state from fields into locals and a cache; the effect itself must not change.
        FrameMottlerLegacy legacy = initialised(new FrameMottlerLegacy(), "movie.mp4");
        FrameMottler mottler = initialised(new FrameMottler(), "movie.mp4");
        setMediaSeed(legacy, 3011);
        setMediaSeed(mottler, 3011);

        assertEquals(List.of(), framesThatDiffer(sequential(legacy), sequential(mottler)),
                "frames where the refactored mottler differs from the legacy one");
    }

    @Test
    void sharedLegacyMottlerCorruptsFrames() throws Exception {
        // The original problem: the legacy mottler keeps per-block scale indices and the current damage masks in
        // fields, so workers sharing it overwrite each other's values mid-frame. This also shows the shared-instance
        // tests below can catch a race, so their passing means something.
        FrameMottlerLegacy legacy = initialised(new FrameMottlerLegacy(), "movie.mp4");
        byte[][] reference = sequential(legacy);

        List<Integer> mismatches = List.of();
        for (int round = 0; round < ROUNDS && mismatches.isEmpty(); round++) {
            mismatches = framesThatDiffer(reference, onWorkers(n -> mottle(legacy, n), playbackOrder()));
        }
        assertFalse(mismatches.isEmpty(), "expected a shared legacy mottler to corrupt frames, but none differed");
        System.out.printf("Shared FrameMottlerLegacy: %d of %d frames corrupted%n", mismatches.size(), FRAMES);
    }

    @Test
    void sharedMottlerMatchesSequentialDuringPlayback() throws Exception {
        // Frames handed to workers in order, as during normal playback: neighbouring frames run at the same time.
        FrameMottler mottler = initialised(new FrameMottler(), "movie.mp4");
        byte[][] reference = sequential(mottler);

        for (int round = 0; round < ROUNDS; round++) {
            mottler.initialise(videoInfo("movie.mp4")); // empty mask cache, so workers race to fill it
            assertEquals(List.of(), framesThatDiffer(reference, onWorkers(n -> mottle(mottler, n), playbackOrder())),
                    "frames that differ from the single-threaded result in round " + round);
        }
    }

    @Test
    void sharedMottlerMatchesSequentialWhenFramesArriveOutOfOrder() throws Exception {
        // Shuffled order, like seeking back and forth: frames from far-apart mask segments race for the same cache
        // slot, so masks are constantly evicted and rebuilt while other workers are reading them.
        FrameMottler mottler = initialised(new FrameMottler(), "movie.mp4");
        byte[][] reference = sequential(mottler);

        Random shuffle = new Random(3011);
        for (int round = 0; round < ROUNDS; round++) {
            List<Integer> order = playbackOrder();
            Collections.shuffle(order, shuffle);
            mottler.initialise(videoInfo("movie.mp4"));
            assertEquals(List.of(), framesThatDiffer(reference, onWorkers(n -> mottle(mottler, n), order)),
                    "frames that differ from the single-threaded result in round " + round);
        }
    }

    @Test
    void sharedMottlerOutpacesLockedLegacy() throws Exception {
        // Making the legacy mottler safe to share needs a lock around process(), which lets only one worker mottle
        // at a time. (One legacy instance per worker is not an option: each instance gets its own seed, so a frame
        // would look different depending on which worker ran it.) The refactored mottler needs no lock.
        assumeTrue(Runtime.getRuntime().availableProcessors() >= 4, "needs at least 4 cores to measure a speedup");

        FrameMottlerLegacy legacy = initialised(new FrameMottlerLegacy(), "movie.mp4");
        FrameMottler mottler = initialised(new FrameMottler(), "movie.mp4");
        byte[][] legacyReference = sequential(legacy); // also warms up the JIT
        byte[][] reference = sequential(mottler);

        Object legacyLock = new Object();
        FrameJob lockedLegacy = n -> {
            synchronized (legacyLock) {
                return mottle(legacy, n);
            }
        };
        FrameJob shared = n -> mottle(mottler, n);

        // Both must be correct for the timing comparison to be fair.
        assertEquals(List.of(), framesThatDiffer(legacyReference, onWorkers(lockedLegacy, playbackOrder())));
        assertEquals(List.of(), framesThatDiffer(reference, onWorkers(shared, playbackOrder())));

        long lockedNs = bestOfThreeNs(lockedLegacy, playbackOrder());
        long sharedNs = bestOfThreeNs(shared, playbackOrder());
        double speedup = lockedNs / (double) sharedNs;
        System.out.printf("%d frames on %d workers: locked FrameMottlerLegacy %.1f ms, shared FrameMottler %.1f ms,"
                + " speedup %.2fx%n", FRAMES, THREADS, lockedNs / 1e6, sharedNs / 1e6, speedup);

        // Deliberately loose so a busy machine doesn't fail it; with no real speedup it would sit near 1.0x.
        assertTrue(speedup > 1.5, "expected the shared mottler to beat the locked legacy one by over 1.5x, got "
                + String.format("%.2f", speedup));
    }
}
