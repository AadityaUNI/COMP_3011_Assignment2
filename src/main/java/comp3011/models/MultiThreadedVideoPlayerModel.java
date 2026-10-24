/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Aaditya Singh, A1946894
 *
 * Diff from starter code (VideoPlayerModel.java, kept unchanged as SingleThreadedVideoPlayerModel):
 *   Removed:
 *     - prepareNextFrame, prepareNextFrameQueued and the single preparedFrame field: decoding, effects and
 *       conversion no longer run on the FX thread.
 *     - Deadline and logical time in PreparedFrame; they are now worked out on the FX thread at display time.
 *   Added:
 *     - Decode thread (startDecodeThread, stopDecodeThread, runDecodeLoop, waitForSeekAfterEndOfStream): the only
 *       thread that grabs.
 *     - Effect worker pool (processingPool, processNextDecodedFrame): runs effects and conversion in parallel.
 *     - grabberLock, seekGeneration, endOfStreamGeneration, nextDecodeIndex/nextDisplayIndex, framesInFlight,
 *       decodedFrames and processedFrames, with the DecodedFrame and SequencedFrame records.
 *     - invalidateQueuedFrames, discardProcessedFrames, waitForPipelineToDrain, startPlaybackClock and showOnScreen
 *       (one reused on-screen image).
 *     - FrameTimingListener hook for the load test; effects passed in from the command line.
 *   Changed:
 *     - pumpPlayback: drops stale frames and shows frames in sequence order instead of preparing them.
 *     - readNextVideoFrame: runs under grabberLock, clones and stamps the frame; no longer processes it.
 *     - seekTo / closePlaybackResources: lock-protected seek, and an ordered shutdown of the threads.
 *     - pendingAudio is now a ConcurrentLinkedQueue; audio is queued while muted and dropped when due.
 *     - playbackOpen, audioOutputEnabled volatile; shutdown() stops the pool.
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011.models;

import comp3011.frameprocessors.FrameProcessor;
import comp3011.frameprocessors.InfoFrame;
import comp3011.frameprocessors.InfoVideo;

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
import javafx.scene.image.WritableImage;

/**
 * The multi-threaded implementation of VideoPlayerModel. This is the default model; --single selects the
 * original single-threaded one instead.
 *
 * Architecture: a three-stage pipeline keeps decoding and the expensive effects off the JavaFX Application
 * Thread (the "FX thread"), which only displays frames and stays responsive:
 *
 *   decode thread  --decodedFrames-->  effect workers  --processedFrames-->  FX thread
 *   (grab + stamp)    (bounded queue)  (processingPool:   (priority queue)   (pumpPlayback:
 *                                       effects+convert)                     audio + display)
 *
 *   - FX thread: owns control (play, pause, seek, stop), the clock, audio output and when each frame is shown.
 *     pumpPlayback runs on the AnimationTimer and never blocks.
 *   - Decode thread ("video-decoder"): the only thread that grabs during playback; queues audio, hands video
 *     frames to the pool.
 *   - Effect workers (processingPool): run the FrameProcessor stack on a private frame copy and convert it to an
 *     image. Frames finish out of order.
 *
 * Ownership and critical regions: every piece of shared state has one owner or one guard.
 *   - grabberLock guards the grabber, held from grab() until the frame is cloned and stamped, and around a seek.
 *     Nothing blocks while holding it, so the FX thread can always take it.
 *   - The playback clock, nextDisplayIndex and the pause state belong to the FX thread alone.
 *   - pendingAudio is a concurrent queue: the decode thread adds (under the lock), the FX thread drains.
 *   - Workers own a cloned frame each, so pixels are never shared. The FrameProcessors are shared, so process()
 *     keeps per-frame state in locals.
 *
 * Permit invariant: framesInFlight bounds frames anywhere in the pipeline. Each permit is taken by the decoder and
 * returned exactly once when the frame is shown or dropped; all permits back = pipeline empty.
 *
 * Seek generations: a seek bumps seekGeneration instead of chasing frames through workers. Frames carry their
 * generation and are dropped wherever found stale; processedFrames sorts by generation, then index.
 *
 * Errors and shutdown: background errors go to the FX thread via runLater and are re-checked so an old session
 * can't close a new one. closePlaybackResources() stops the decoder, invalidates, waits for the pipeline to drain,
 * then closes resources. See README.md for the full correctness argument.
 **/
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
    // Playback clock (FX thread only). Starts when the first frame after a start/seek is ready to show,
    // so pipeline latency never eats into frame deadlines.
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;
    // FX thread only. The one on-screen image, reused for every frame (see showOnScreen). Null when off screen.
    private WritableImage screenImage;
    private FrameTimingListener frameTimingListener = FrameTimingListener.NONE; // FX thread only; measurement hook
    private int framesBuffer = 35; // ~1.5 seconds ahead at 24fps; smaller refill burst after each seek
    // CPU budget: FFmpeg needs only 2 threads (it defaults to cores + 1). Effects are the heavy part, so workers
    // get most cores; their lower priority leaves room for the FX/render threads. Never 0: that pool would throw.
    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static final int PROCESSING_THREADS = Math.max(1, CORES - 2);
    private static final int DECODER_THREADS = 2;
    // Workers run below normal priority so that when every core is busy (e.g. refill after a seek) the FX and render
    // threads still go first. The decode thread stays at normal priority: workers wait on it.
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

    // Caps frames anywhere in the pipeline. One permit per frame, returned exactly once when it is shown or dropped,
    // so the decoder can't race ahead and blocks while paused.
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
        // Keep pendingAudio: it can't be decoded again, and writeDueAudio drops it as it falls due while muted,
        // so unmuting resumes sound at once. Flushing only empties what the sound card already had.
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
        screenImage = null; // the view is cleared, so the next frame must hand it a fresh image
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
        processingPool.shutdownNow(); // workers are daemon threads, but stop them cleanly rather than leave them to the JVM
    }

    public void setFrameTimingListener(FrameTimingListener listener) {
        frameTimingListener = listener;
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

    // Interrupt and join, so afterwards nothing else is inside the grabber. Quick: put/acquire/park all respond to
    // interrupt, and a native grab() just finishes.
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

    // Makes every frame in the pipeline stale and drops the queued ones. Caller holds grabberLock, so no
    // new-generation frame exists yet and draining can only remove stale frames.
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

        // Audio is only queued under grabberLock, so everything queued now belongs to the old position.
        pendingAudio.clear();
    }

    private void discardProcessedFrames() {
        framesInFlight.release(processedFrames.drainTo(new ArrayList<>()));
    }

    // Close only: waits until no worker is inside a FrameProcessor before they are re-initialised.
    // Quick, since workers check the generation between effects.
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

    // FX thread only. Doesn't clear audio: that is done under grabberLock, as new-position audio may be queued by now.
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

    // Runs until interrupted. Each pass: take a permit, grab + stamp under the lock, hand off outside it.
    // Blocking only happens outside grabberLock, so the FX thread can always take it to seek.
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

    // At end of stream, idle until a seek (seekTo unparks) or stop. The FX thread decides when playback finishes.
    private void waitForSeekAfterEndOfStream() {
        int endGeneration = endOfStreamGeneration;
        while (seekGeneration.get() == endGeneration && !Thread.currentThread().isInterrupted()) {
            LockSupport.park(this);
        }
    }

    // One critical region, so the frame data, timestamp, frame number and generation all describe the same grab.
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

    // Pool worker, one task per decoded frame. poll() not take(): a seek may have drained the queue.
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
                    // Re-check: a seek/close may have happened since. The first handler's close also stops the rest.
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
    // handling user interaction with the GUI and rendering. Heavy work is on other threads, so the jobs are light:
    // (1) feed audio, (2) drop stale frames and show the next one if due, (3) finish once the last frame is shown.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        // Task (1)
        writeDueAudio(now);

        // Task (2): stale frames sort first. remove(head), not poll(), in case a worker inserted one meanwhile.
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

            // While paused, only the first frame after a start/seek is shown, so the picture jumps to the new position.
            boolean mayShow = !pauseRequested || head.index() == 0;
            long relativeTimestampUs = Math.max(0, frame.mediaTimestampUs() - firstTimestampUs);
            long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

            if (mayShow && targetTimeNs <= now && processedFrames.remove(head)) {
                nextDisplayIndex.incrementAndGet();
                framesInFlight.release();
                displayPreparedFrame(frame, targetTimeNs, logicalPlaybackBaseUs + relativeTimestampUs, now);
                return;
            }
        }

        // Task (3): the decoder hit the end and every frame has left the pipeline, so the last one has been shown.
        if (endOfStreamGeneration == seekGeneration.get()
                && framesInFlight.availablePermits() == framesBuffer) {
            finishPlayback();
        }
    }

    // The first frame of a start/seek is due now; later frames are due at their media-time offset from it.
    // If paused, the pause also starts now, so resume shifts the clock correctly.
    private void startPlaybackClock(long firstFrameTimestampUs, long now) {
        firstTimestampUs = firstFrameTimestampUs;
        playbackStartNs = now;
        if (pauseRequested) {
            pauseStartedNs = now;
        }
    }

    private void displayPreparedFrame(PreparedFrame frame, long targetTimeNs, long logicalTimestampUs, long now) {
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

        frameTimingListener.frameShown(frame.frameNumber(), targetTimeNs, frame.preparedAtNs(), now);
        showOnScreen(frame.image());
    }

    // FX thread only. Copies the frame into the one on-screen image instead of showing a new image per frame.
    // Each shown image gets its own GPU texture (~6 MB), freed only after GC, so new images fill the texture pool
    // and JavaFX forces a full System.gc() that stalls the screen. One reused image keeps one texture.
    private void showOnScreen(Image frameImage) {
        int width = (int) frameImage.getWidth();
        int height = (int) frameImage.getHeight();
        if (screenImage == null
                || (int) screenImage.getWidth() != width
                || (int) screenImage.getHeight() != height) {
            screenImage = new WritableImage(width, height);
            notifyFrameReady(screenImage); // only when the image changes; after that its pixels are updated in place
        }
        // Measured at 1920x800: ~0.6-1.5ms per frame on average, under 5ms at worst, out of a 41.7ms frame.
        screenImage.getPixelWriter().setPixels(0, 0, width, height, frameImage.getPixelReader(), 0, 0);
    }

    // Decode thread, under grabberLock (so a seek can clear stale audio safely). Queued even while muted: the
    // decoder runs ~1.5s ahead, so skipping it would leave a silent gap after unmuting.
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
