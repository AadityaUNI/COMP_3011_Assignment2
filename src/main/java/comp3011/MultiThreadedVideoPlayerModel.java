/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
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
 * The multi-threaded implementation of {@link VideoPlayerModel}, selected with
 * the {@code --multi} command line flag.
 *
 * <p>
 * TODO: currently a copy of {@link SingleThreadedVideoPlayerModel} as starter
 * code. To be re-engineered so that decoding and frame processing run off the
 * JavaFX Application Thread.
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
    private boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;
    private int framesBuffer = 48; // 2 seconds ahead
    int cores = Runtime.getRuntime().availableProcessors();
    private final ExecutorService processingPool = Executors.newFixedThreadPool(cores);

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
        pendingAudio.clear();
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
        processingPool.shutdownNow(); // pool threads are non-daemon and would otherwise keep the JVM alive
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

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
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
                Platform.runLater(() -> handlePlaybackError(e)); // GUI/resource work belongs on the FX thread
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

            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
                playbackStartNs = System.nanoTime();
                if (pauseRequested) {
                    pauseStartedNs = playbackStartNs;
                }
            }

            long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
            long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
            long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

            return new DecodedFrame(
                    seekGeneration.get(),
                    nextDecodeIndex++,
                    frame.clone(), // deep copy: the next grab() overwrites the grabber's own buffer
                    grabber.getFrameNumber(),
                    timestampUs,
                    logicalTimestampUs,
                    targetTimeNs);
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
                            decoded.logicalTimestampUs(),
                            decoded.targetTimeNs(),
                            System.nanoTime())));
            queuedForDisplay = true; // from here the FX thread owns the permit
        } catch (Exception e) {
            if (decoded.generation() == seekGeneration.get()) {
                Platform.runLater(() -> handlePlaybackError(e));
            }
        } finally {
            if (!queuedForDisplay) {
                framesInFlight.release(); // dropped here, so give its pipeline slot back
            }
        }
    }

    // This is called on a heart beat by the GUI thread. We want to be co-operative here by (a) not blocking, and (b)
    // getting our required work out of the way quickly so that we can return control flow to the JavaFX event loop for
    // handling user interaction with the GUI and rendering. We have three jobs: (1) keep audio flowing if sound is on,
    // (2) display a frame if it is due and (3) prepare the next frame if we're in the window after the previous frame
    // has gone to the display. We don't buffer frames here, just handling them one at a time. Not a great architecture,
    // living on the edge a bit, but can't do much better on a single thread.
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
        if (head != null
                && head.index() == nextDisplayIndex.get()
                && head.prepared().targetTimeNs() <= now
                && processedFrames.remove(head)) {
            nextDisplayIndex.incrementAndGet();
            framesInFlight.release();
            displayPreparedFrame(head.prepared());
            return;
        }

        // Task (3): the decoder hit the end and every frame has left the pipeline, so the last one has been shown.
        if (endOfStreamGeneration == seekGeneration.get()
                && framesInFlight.availablePermits() == framesBuffer) {
            finishPlayback();
        }
    }

    private void displayPreparedFrame(PreparedFrame frame) {
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
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

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
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

        // Order matters: stop the only other grabber user, make everything in flight stale, then wait for
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
            // Stop early if a seek or close made this frame stale; its output would be dropped anyway.
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
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }

    // A frame straight off the decode thread, already clone()d so it no longer shares the grabber's buffers.
    private record DecodedFrame(
            int generation,
            int index,
            Frame frame,
            int frameNumber,
            long timestampUs,
            long logicalTimestampUs,
            long targetTimeNs) {
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
