/*
 * Evidence that FrameBleeder and FrameJitterer can be shared by the multi-threaded model's effect workers.
 * Both used to keep per-frame scratch buffers in fields (bright/blur masks, source pixel copy), which concurrent
 * workers would overwrite. Those buffers are now locals inside process(), so a shared instance must give exactly
 * the same output as a single-threaded run.
 *
 * Authors:
 *   1. Aaditya Singh, A1946894
 */
package comp3011.frameprocessors;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SharedEffectThreadSafetyTests {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;
    private static final int CHANNELS = 3;
    private static final int STRIDE = WIDTH * CHANNELS;
    private static final int FRAMES = 200;
    private static final int THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());
    private static final int ROUNDS = 5;

    private static <T extends FrameProcessor> T initialised(T processor) throws Exception {
        processor.initialise(new InfoVideo("movie.mp4", FRAMES, WIDTH, HEIGHT, Frame.DEPTH_UBYTE, CHANNELS, STRIDE,
                24.0, 24, 41667, 0));
        return processor;
    }

    // Random but reproducible input pixels for a frame number.
    private static byte[] sourcePixels(int frameNumber) {
        byte[] pixels = new byte[STRIDE * HEIGHT];
        new Random(frameNumber).nextBytes(pixels);
        return pixels;
    }

    // A heap-backed Frame is enough for these effects, so no video file or native FFmpeg code is needed.
    private static byte[] apply(FrameProcessor processor, int frameNumber) throws Exception {
        byte[] pixels = sourcePixels(frameNumber);
        Frame frame = new Frame();
        frame.image = new Buffer[] { ByteBuffer.wrap(pixels) };
        processor.process(frame, new InfoFrame(frameNumber, frameNumber * 41667L));
        return pixels;
    }

    private static byte[][] sequential(FrameProcessor processor) throws Exception {
        byte[][] output = new byte[FRAMES][];
        for (int frameNumber = 0; frameNumber < FRAMES; frameNumber++) {
            output[frameNumber] = apply(processor, frameNumber);
        }
        return output;
    }

    // Runs every frame on THREADS workers sharing the one instance, all released at once so they overlap.
    private static byte[][] onSharedWorkers(FrameProcessor processor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<byte[]>> results = new ArrayList<>();
            for (int frameNumber = 0; frameNumber < FRAMES; frameNumber++) {
                int n = frameNumber;
                results.add(pool.submit(() -> {
                    start.await();
                    return apply(processor, n);
                }));
            }
            start.countDown();

            byte[][] output = new byte[FRAMES][];
            for (int frameNumber = 0; frameNumber < FRAMES; frameNumber++) {
                output[frameNumber] = results.get(frameNumber).get();
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

    private static int framesChangedByEffect(byte[][] output) {
        int changed = 0;
        for (int frameNumber = 0; frameNumber < FRAMES; frameNumber++) {
            if (!Arrays.equals(sourcePixels(frameNumber), output[frameNumber])) {
                changed++;
            }
        }
        return changed;
    }

    private static void assertSharedMatchesSequential(FrameProcessor processor) throws Exception {
        byte[][] reference = sequential(processor);
        for (int round = 0; round < ROUNDS; round++) {
            assertEquals(List.of(), framesThatDiffer(reference, onSharedWorkers(processor)),
                    "frames that differ from the single-threaded result in round " + round);
        }
    }

    // --- FrameBleeder ---

    @Test
    void bleederChangesFramesDeterministically() throws Exception {
        // Guards the thread test below: if the effect did nothing, sharing it would trivially "match".
        FrameBleeder bleeder = initialised(new FrameBleeder());
        byte[][] output = sequential(bleeder);
        assertEquals(FRAMES, framesChangedByEffect(output), "every frame should get some bleed");
        assertArrayEquals(output[42], apply(bleeder, 42), "same frame twice should give the same result");
    }

    @Test
    void sharedBleederMatchesSequential() throws Exception {
        // The masks are now allocated per call, so workers can't overwrite each other's masks mid-frame.
        assertSharedMatchesSequential(initialised(new FrameBleeder()));
    }

    // --- FrameJitterer ---

    @Test
    void jittererChangesFramesDeterministically() throws Exception {
        // Only about 1 in 5 frames is shifted, so check that a fair number are, and that the result is repeatable.
        FrameJitterer jitterer = initialised(new FrameJitterer());
        byte[][] output = sequential(jitterer);
        int changed = framesChangedByEffect(output);
        assertTrue(changed > FRAMES / 10, "expected a fair number of jittered frames, got " + changed);
        assertEquals(List.of(), framesThatDiffer(output, sequential(jitterer)),
                "running the same frames again should give the same result");
    }

    @Test
    void sharedJittererMatchesSequential() throws Exception {
        // The source pixel copy is now a local, so one worker can't copy another frame's pixels into its own.
        assertSharedMatchesSequential(initialised(new FrameJitterer()));
    }
}