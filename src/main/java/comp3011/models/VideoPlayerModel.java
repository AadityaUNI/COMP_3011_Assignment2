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
package comp3011.models;

import java.io.File;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Defines the playback operations the {@link comp3011.controllers.VideoPlayerController} drives, so the
 * controller is independent of the concurrency model in use. Implementations are
 * {@link MultiThreadedVideoPlayerModel} (the default) and
 * {@link SingleThreadedVideoPlayerModel} (the original reference design, selected with {@code --single}).
 * </p>
 */
public interface VideoPlayerModel {
    void play(File file);

    void startOver();

    void backFiveSeconds();

    void forwardFiveSeconds();

    void togglePause();

    void toggleAudioOutput();

    boolean isAudioOutputEnabled();

    void stopPlayback();

    void shutdown();

    // Measurement only: lets the load test time every frame shown. Pass FrameTimingListener.NONE to stop.
    void setFrameTimingListener(FrameTimingListener listener);
}
