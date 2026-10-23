/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Aaditya Singh, A1946894
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * The multi-threaded implementation of {@link VideoPlayerModel}. This is the
 * default model; {@code --single} selects the original single-threaded one instead.
 *
 * <p>
 * <b>Architecture.</b> Playback is a three-stage pipeline, so decoding and the (expensive) frame effects run off
 * the JavaFX Application Thread (the "FX thread"), which is left to display frames and stay responsive:
 * </p>
 * <pre>
 *   decode thread  --decodedFrames-->  effect workers  --processedFrames-->  FX thread
 *   (grab + stamp)    (bounded queue)  (processingPool:   (priority queue)   (pumpPlayback:
 *                                       effects+convert)                     audio + display)
 * </pre>
 * <ul>
 * <li><b>FX thread</b>: owns all control (play, pause, seek, stop), the playback clock, audio output and the choice
 * of when each frame is shown. {@code pumpPlayback} runs on its {@link AnimationTimer} and never blocks.</li>
 * <li><b>Decode thread</b> ("video-decoder"): the only thread that grabs from FFmpeg while playback is running.
 * It reads one frame at a time, queues that frame's audio and hands the video frame to the pool.</li>
 * <li><b>Effect workers</b> ({@code processingPool}): run the {@link FrameProcessor} stack on a private copy of
 * a frame and convert it to a JavaFX image. Several frames are in different workers at once, so they finish out
 * of order.</li>
 * </ul>
 *
 * <p>
 * <b>Ownership and critical regions.</b> Every piece of shared state has one owner or one guard:
 * </p>
 * <ul>
 * <li>The {@code grabber} is guarded by {@code grabberLock}. The decode thread holds it from {@code grab()} until
 * the frame has been cloned and stamped with its generation and index, so the frame data, timestamp and generation
 * all describe the same grab. A seek takes the same lock around {@code setTimestamp()} and
 * {@code invalidateQueuedFrames()}, so no frame can be stamped half-way through a seek. Nothing ever blocks (queue
 * {@code put}, semaphore {@code acquire}) while holding the lock, so the FX thread can always get it to seek.</li>
 * <li>The playback clock, {@code nextDisplayIndex} and the pause state belong to the FX thread alone.</li>
 * <li>{@code pendingAudio} is a concurrent queue: the decode thread adds (under the lock), the FX thread drains.</li>
 * <li>Each worker copies its frame before touching it ({@code Frame.clone()}), so workers never share pixel data
 * and need no lock for it. The {@link FrameProcessor} objects <em>are</em> shared by all workers, so their
 * {@code process()} methods keep per-frame state in local variables only.</li>
 * </ul>
 *
 * <p>
 * <b>Permit invariant.</b> {@code framesInFlight} holds {@code framesBuffer} permits and bounds the number of frames
 * anywhere in the pipeline: queued, inside a worker, or waiting to be displayed. The decode thread takes one
 * permit per frame, and that permit is given back <em>exactly once</em>, by whichever stage displays or discards the
 * frame. That is what stops the decoder racing ahead and what blocks it while paused. It also gives a simple
 * "pipeline empty" test: all permits are available again.
 * </p>
 *
 * <p>
 * <b>Seek generations.</b> Frames cannot be pulled out of a worker that is already running, so a seek instead
 * increments {@code seekGeneration}. Every frame is stamped with the generation it was grabbed in; workers
 * re-check it between effects and the FX thread re-checks it before display, so a frame from before the seek is
 * dropped (and its permit returned) wherever it is found. {@code processedFrames} sorts by generation then index,
 * so stale frames surface at the head to be dropped and current frames come out in display order, and only the
 * next index in sequence is ever shown.
 * </p>
 *
 * <p>
 * <b>Errors and shutdown.</b> A failure on a background thread is re-posted to the FX thread with
 * {@code Platform.runLater}, and re-checked there against the current generation or decode thread, so a late
 * handler from an old session cannot close a newer one. {@code closePlaybackResources()} tears down in a fixed
 * order: stop the timer, interrupt and join the decode thread (so nothing else is inside the grabber), invalidate
 * the queued frames, wait for the pipeline to drain (so no worker is still inside a {@code FrameProcessor}), and only
 * then close the audio, grabber and converter. {@code shutdown()} finally stops the pool.
 * </p>
 */
public class MultiThreadedVideoPlayerModel implements VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    private final List<FrameProcessor> frameProcessors;
    // Written by the decode thread, drained by the FX thread, so it must be a concurrent queue.
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private volatile boolean playbackOpen; // True when playback is happening. Volatile: the decode thread polls it.
    private boolean pauseRequested;
    private volatile boolean audioOutputEnabled; // Toggled and read on the FX thread; the decode thread queues audio regardless.
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    // Playback clock: maps media time to wall-clock time. Owned by the FX thread alone, and started when the
    // first frame after a start/seek is ready to show (not when it was decoded), so pipeline latency never
    // eats into frame deadlines. Other threads never read or write these.
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;
    private int framesBuffer = 35; // ~1.5 seconds ahead at 24fps; smaller refill burst after each seek
    // CPU budget: decoding one video is cheap, so FFmpeg gets 2 threads (left alone it starts cores + 1).
    // The effect workers get the rest minus 3 cores, kept free for our decode thread, the FX thread and the
    // JavaFX render thread. Never 0: a 0-thread pool throws.
    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static final int PROCESSING_THREADS = Math.max(1, CORES - 3);
    private static final int DECODER_THREADS = 2;
    // Workers run one step below normal priority (Windows "below normal"). It only matters when every core is busy,
    // e.g. the refill burst after a seek: the JavaFX FX and render threads then get the CPU first, so frames still
    // reach the screen. With spare cores the workers run at full speed. Our decode thread stays at normal priority,
    // since the workers can't do anything until it hands them frames.
    private final AtomicInteger workerCount = new AtomicInteger();
    private final ExecutorService processingPool = Executors.newFixedThreadPool(PROCESSING_THREADS, task -> {
        Thread worker = new Thread(task, "effect-worker-" + workerCount.incrementAndGet());
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.setDaemon(true); // set explicitly: a new thread otherwise copies this from whichever thread creates it
        return worker;
    });

    // ---- Pipeline state: decode thread -> effect workers -> FX thread ----

    // The only thread allowed to grab while playback is running. Started and stopped by the FX thread.
    private Thread decodeThread;

    // Guards the grabber. Held from grab() until the frame is stamped with its generation, and around
    // setTimestamp() + invalidateQueuedFrames() on seek. Never block (queue put, semaphore) while holding it.
    private final ReentrantLock grabberLock = new ReentrantLock();

    // Changes on every seek / close. Frames stamped with an older value are stale and must be dropped.
    private final AtomicInteger seekGeneration = new AtomicInteger(0);

    // The generation in which the decoder ran off the end of the media, or -1. Written by the decode thread
    // (under grabberLock), read by the FX thread to decide when playback has finished.
    private volatile int endOfStreamGeneration = -1;

    // Sequence number the decode thread gives the next frame it grabs. Guarded by grabberLock.
    private int nextDecodeIndex = 0;

    // Sequence number the FX thread is waiting to display next. Only touched on the FX thread.
    private final AtomicInteger nextDisplayIndex = new AtomicInteger(0);

    // Caps the frames anywhere in the pipeline: queued, inside a worker, or waiting for display. The decode
    // thread takes one permit per frame, and that permit is given back exactly once, by whoever displays or
    // drops the frame. This is what stops the decoder racing ahead (and what blocks it while paused).
    private final Semaphore framesInFlight = new Semaphore(framesBuffer);

    // Decode thread -> workers.
    private final BlockingQueue<DecodedFrame> decodedFrames = new ArrayBlockingQueue<>(framesBuffer);

    // Workers -> FX thread. Ordered by generation then index: stale frames surface at the head so the FX
    // thread can drop them, and current frames come out in display order. Size is capped by framesInFlight.
    private final PriorityBlockingQueue<SequencedFrame> processedFrames = new PriorityBlockingQueue<>(
            framesBuffer,
            Comparator.comparingInt(SequencedFrame::generation).thenComparingInt(SequencedFrame::index));

    public MultiThreadedVideoPlayerModel(
            boolean audioEnabled,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler,
            ArrayList<FrameProcessor> processors) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessors = processors;
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        // Keep pendingAudio: the decoder runs up to framesBuffer frames ahead, so the audio for frames already in the
        // pipeline can't be decoded again. writeDueAudio drops it as it falls due while muted, so unmuting resumes
        // sound straight away with nothing stale. Flushing only empties what the sound card already had.
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
        processingPool.shutdownNow(); // workers are daemon threads, but stop them cleanly rather than leave them to the JVM
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            grabberLock.lock();
            try {
                grabber.setTimestamp(grabTimestampUs);
                invalidateQueuedFrames();
            } finally {
                grabberLock.unlock();
            }
            LockSupport.unpark(decodeThread); // wake it if it was idling at end of stream (no-op otherwise)
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            startDecodeThread();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.setVideoOption("threads", Integer.toString(DECODER_THREADS)); // must be set before start()
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    // ---- Pipeline control (FX thread only) ----

    private void startDecodeThread() {
        decodeThread = new Thread(this::runDecodeLoop, "video-decoder");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    // Interrupts the decode thread and waits for it to exit, so afterwards nothing else is inside the grabber.
    // The wait is short: put(), acquire() and park() all respond to interrupt, and a native grab() just finishes.
    private void stopDecodeThread() {
        if (decodeThread == null) {
            return;
        }
        decodeThread.interrupt();
        try {
            decodeThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        decodeThread = null;
    }

    // Makes every frame currently in the pipeline stale and throws away the queued ones.
    // Caller must hold grabberLock: then no frame of the new generation can exist yet, so draining
    // the queues can only ever remove stale frames, never the first frame after a seek.
    private void invalidateQueuedFrames() {
        seekGeneration.incrementAndGet();
        nextDecodeIndex = 0;
        nextDisplayIndex.set(0);

        List<DecodedFrame> discarded = new ArrayList<>();
        decodedFrames.drainTo(discarded);
        for (DecodedFrame decoded : discarded) {
            decoded.frame().close(); // free the clone's native memory
        }
        framesInFlight.release(discarded.size());
        discardProcessedFrames();

        // Audio is only ever queued by the decode thread while it holds grabberLock, so everything queued
        // right now belongs to the old position, and nothing for the new position can be queued until we unlock.
        pendingAudio.clear();
    }

    private void discardProcessedFrames() {
        framesInFlight.release(processedFrames.drainTo(new ArrayList<>()));
    }

    // Close only: waits until every frame has left the pipeline, so no worker is still inside a FrameProcessor
    // when the next playback re-initialises them. Workers check the generation between effects, so this is quick.
    private void waitForPipelineToDrain() {
        try {
            while (!framesInFlight.tryAcquire(framesBuffer, 10, TimeUnit.MILLISECONDS)) {
                discardProcessedFrames(); // a worker may have queued a frame after we last drained
            }
            framesInFlight.release(framesBuffer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // FX thread only. Stale audio is cleared in invalidateQueuedFrames(), under grabberLock, not here:
    // by now the decode thread may already have queued audio for the new position.
    private void resetPlaybackClock(long logicalTimestampUs) {
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }

    // ---- Decode thread ----

    // Runs until stopDecodeThread() interrupts it. Each pass: reserve a pipeline slot, grab + stamp under the
    // lock, then hand the frame to the pool outside the lock. Blocking only ever happens outside grabberLock,
    // so the FX thread can always take the lock to seek.
    private void runDecodeLoop() {
        try {
            while (playbackOpen && !Thread.currentThread().isInterrupted()) {
                framesInFlight.acquire(); // blocks while the pipeline is full, e.g. when paused

                DecodedFrame decoded;
                try {
                    decoded = grabAndStampNextVideoFrame();
                } catch (Exception e) {
                    framesInFlight.release();
                    throw e;
                }

                if (decoded == null) {
                    framesInFlight.release();
                    waitForSeekAfterEndOfStream();
                    continue;
                }

                try {
                    decodedFrames.put(decoded);
                } catch (InterruptedException e) {
                    decoded.frame().close();
                    framesInFlight.release();
                    throw e;
                }
                processingPool.submit(this::processNextDecodedFrame);
            }
        } catch (InterruptedException e) {
            // Asked to stop by stopDecodeThread().
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                Thread failingThread = Thread.currentThread();
                Platform.runLater(() -> { // GUI/resource work belongs on the FX thread
                    // Re-check: stopDecodeThread() clears decodeThread, so a replaced or stopped decoder is ignored.
                    if (decodeThread == failingThread) {
                        handlePlaybackError(e);
                    }
                });
            }
        }
    }

    // After end of stream the decoder idles until a seek moves the grabber somewhere else (seekTo unparks it),
    // or until it is stopped. The FX thread decides when playback has finished, once the last frame is shown.
    private void waitForSeekAfterEndOfStream() {
        int endGeneration = endOfStreamGeneration;
        while (seekGeneration.get() == endGeneration && !Thread.currentThread().isInterrupted()) {
            LockSupport.park(this);
        }
    }

    // Everything that reads the grabber's shared state happens inside one critical region, so the
    // frame data, its timestamp, its frame number and its generation stamp all describe the same grab.
    private DecodedFrame grabAndStampNextVideoFrame() throws Exception {
        grabberLock.lock();
        try {
            return readNextVideoFrame();
        } finally {
            grabberLock.unlock();
        }
    }

    // Caller must hold grabberLock. Returns null at end of stream or if playback was closed.
    private DecodedFrame readNextVideoFrame() throws Exception {
        while (playbackOpen) {
            Frame frame = grabFrame();
            if (frame == null) {
                // Read under grabberLock, so this names the generation whose position just ran off the end.
                endOfStreamGeneration = seekGeneration.get();
                return null;
            }

            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                // copySamples() copies out of the grabber's buffer, so this must stay inside the lock.
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            if (!frameProcessorsInitialised) {
                initialiseFrameProcessors(new InfoVideo(
                        mediaName(videoFile),
                        totalVideoFrames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        frameRate,
                        intFrameRate,
                        videoFrameDurationUs,
                        grabber.getPixelFormat()));
                frameProcessorsInitialised = true;
            }

            // Only the media timestamp travels with the frame; the FX thread turns it into a deadline.
            return new DecodedFrame(
                    seekGeneration.get(),
                    nextDecodeIndex++,
                    frame.clone(), // deep copy: the next grab() overwrites the grabber's own buffer
                    grabber.getFrameNumber(),
                    timestampUs);
        }

        return null;
    }

    // Pool worker. One task is submitted per decoded frame, so normally there is a frame waiting.
    // poll() (not take()) because a seek may have drained the queue, and a blocked worker would leak.
    private void processNextDecodedFrame() {
        DecodedFrame decoded = decodedFrames.poll();
        if (decoded == null) {
            return; // its frame was discarded by a seek/close, which already gave back its permit
        }

        boolean queuedForDisplay = false;
        try (Frame frame = decoded.frame()) { // close() frees the clone's native memory once we're done
            if (decoded.generation() != seekGeneration.get()) {
                return; // stale: decoded before the latest seek
            }

            processFrame(frame, new InfoFrame(decoded.frameNumber(), decoded.timestampUs()), decoded.generation());

            JavaFXFrameConverter frameConverter = converter; // read once; it's nulled on close
            if (frameConverter == null || decoded.generation() != seekGeneration.get()) {
                return;
            }
            Image image = frameConverter.convert(frame); // copies pixels, so closing the frame after is safe

            processedFrames.put(new SequencedFrame(
                    decoded.generation(),
                    decoded.index(),
                    new PreparedFrame(
                            image,
                            decoded.frameNumber(),
                            decoded.timestampUs(),
                            System.nanoTime())));
            queuedForDisplay = true; // from here the FX thread owns the permit
        } catch (Exception e) {
            if (decoded.generation() == seekGeneration.get()) {
                Platform.runLater(() -> {
                    // Re-check: a seek or close may have started a new session before this ran. The first
                    // handler's close bumps the generation, so the other failing workers' handlers drop out too.
                    if (decoded.generation() == seekGeneration.get()) {
                        handlePlaybackError(e);
                    }
                });
            }
        } finally {
            if (!queuedForDisplay) {
                framesInFlight.release(); // dropped here, so give its pipeline slot back
            }
        }
    }

    // This is called on a heart beat by the GUI thread. We want to be co-operative here by (a) not blocking, and (b)
    // getting our required work out of the way quickly so that we can return control flow to the JavaFX event loop for
    // handling user interaction with the GUI and rendering. The heavy lifting (decoding, effects, conversion) now
    // happens on other threads, so this only has three light jobs: (1) keep audio flowing if sound is on,
    // (2) drop frames left over from before a seek, then show the next frame in sequence if its deadline has come, and
    // (3) detect that the end of the stream has been reached and the last frame has been shown, and finish playback.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        // Task (1) done in the fx thread
        writeDueAudio(now);

        // Task (2): drop frames from before the latest seek. They sort first (lower generation), so they
        // surface at the head. remove(head) rather than poll() in case a worker inserted something meanwhile.
        SequencedFrame head;
        while ((head = processedFrames.peek()) != null && head.generation() != seekGeneration.get()) {
            if (processedFrames.remove(head)) {
                framesInFlight.release();
            }
        }

        // Only the next frame in sequence may be shown; a later frame that finished early waits its turn.
        if (head != null && head.index() == nextDisplayIndex.get()) {
            PreparedFrame frame = head.prepared();
            if (firstTimestampUs == NO_SEEK_REQUEST) {
                startPlaybackClock(frame.mediaTimestampUs(), now);
            }

            // While paused, only the first frame after a start/seek is shown, so the picture still jumps
            // to the new position. Every later frame waits for resume.
            boolean mayShow = !pauseRequested || head.index() == 0;
            long relativeTimestampUs = Math.max(0, frame.mediaTimestampUs() - firstTimestampUs);
            long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

            if (mayShow && targetTimeNs <= now && processedFrames.remove(head)) {
                nextDisplayIndex.incrementAndGet();
                framesInFlight.release();
                displayPreparedFrame(frame, targetTimeNs, logicalPlaybackBaseUs + relativeTimestampUs);
                return;
            }
        }

        // Task (3): the decoder hit the end and every frame has left the pipeline, so the last one has been shown.
        if (endOfStreamGeneration == seekGeneration.get()
                && framesInFlight.availablePermits() == framesBuffer) {
            finishPlayback();
        }
    }

    // Anchors the clock on the first frame of a start/seek: that frame is due now, and every later frame is
    // due at its media-time offset from it. If paused, the pause is treated as starting now too, so resume
    // shifts the clock correctly.
    private void startPlaybackClock(long firstFrameTimestampUs, long now) {
        firstTimestampUs = firstFrameTimestampUs;
        playbackStartNs = now;
        if (pauseRequested) {
            pauseStartedNs = now;
        }
    }

    private void displayPreparedFrame(PreparedFrame frame, long targetTimeNs, long logicalTimestampUs) {
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        // Headroom = how long before its deadline the frame finished processing.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (targetTimeNs - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    // Decode thread, holding grabberLock. Being under the lock is what lets a seek clear stale audio safely.
    // Audio is queued even while muted: the decoder is up to framesBuffer frames ahead of the display, so skipping
    // it here would leave a ~1.5s silent gap after unmuting. The FX thread drops muted audio once it falls due.
    private void queueAudio(long timestampUs, Frame frame) {
        if (audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;

        if (!audioOutputEnabled) {
            // Muted: discard only audio that is due, so unmuting never plays stale sound but future audio is kept.
            PendingAudio audio;
            while ((audio = pendingAudio.peek()) != null && audio.timestampUs() <= dueTimestampUs) {
                pendingAudio.poll();
            }
            return;
        }

        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;

        // stop the only other grabber user, make everything in flight stale, then wait for
        // workers to leave the FrameProcessors. Only after that is it safe to close the grabber and converter.
        stopDecodeThread();
        grabberLock.lock();
        try {
            invalidateQueuedFrames();
        } finally {
            grabberLock.unlock();
        }
        waitForPipelineToDrain();

        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void processFrame(Frame frame, InfoFrame info, int generation) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            // Stop early if a seek or close made this frame stale
            if (generation != seekGeneration.get()) {
                return;
            }
            processor.process(frame, info);
        }
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long preparedAtNs) { // deadline and logical time are worked out on the FX thread at display time
    }

    // A frame straight off the decode thread
    private record DecodedFrame(
            int generation,
            int index,
            Frame frame,
            int frameNumber,
            long timestampUs) {
    }

    // A fully prepared frame plus the tags the FX thread needs to order it and reject stale ones.
    private record SequencedFrame(
            int generation,
            int index,
            PreparedFrame prepared) {
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
