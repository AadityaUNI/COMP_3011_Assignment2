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
package comp3011.controllers;

import comp3011.frameprocessors.FrameProcessor;
import comp3011.models.MultiThreadedVideoPlayerModel;
import comp3011.models.SingleThreadedVideoPlayerModel;
import comp3011.models.VideoPlayerModel;
import comp3011.views.VideoPlayerView;

import java.io.File;
import java.util.ArrayList;
import java.util.function.BiConsumer;

import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * The controller part of the video player model-view-controller architecture.
 * 
 * <p>
 * Wires up events from the {@link VideoPlayerView} to the {@link VideoPlayerModel}.
 * This includes button actions, keyboard shortcuts and display resizing.
 * </p>
 */
public class VideoPlayerController {
    private final VideoPlayerModel model;
    private final VideoPlayerView view;
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;

    public VideoPlayerController(
            boolean audioEnabled,
            boolean singleThreaded,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            ArrayList<FrameProcessor> processors) {
        this.view = new VideoPlayerView();
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        // Multi-threaded is the default; the single-threaded original is kept behind --single as a baseline.
        if (singleThreaded) {
            model = new SingleThreadedVideoPlayerModel(
                    audioEnabled,
                    this::onVideoSizeChanged,
                    this::onFrameReady,
                    this::onStatusChanged,
                    this::onPlaybackStateChanged,
                    this::onAudioOutputStateChanged,
                    processors);
        } else {
            model = new MultiThreadedVideoPlayerModel(
                    audioEnabled,
                    this::onVideoSizeChanged,
                    this::onFrameReady,
                    this::onStatusChanged,
                    this::onPlaybackStateChanged,
                    this::onAudioOutputStateChanged,
                    processors);
        }

        view.getStartOverButton().setOnAction(_ -> startOver());
        view.getBackFiveButton().setOnAction(_ -> backFiveSeconds());
        view.getPausePlayButton().setOnAction(_ -> togglePause());
        view.getForwardFiveButton().setOnAction(_ -> forwardFiveSeconds());
        view.getStopButton().setOnAction(_ -> stopAndExit());
        view.getAudioButton().setOnAction(_ -> toggleAudioOutput());
        view.showAudioEnabled(model.isAudioOutputEnabled());
    }

    public void installKeyboardShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                stopAndExit();
                event.consume();
            } else if (event.getCode() == KeyCode.SPACE) {
                togglePause();
                event.consume();
            } else if (event.getCode() == KeyCode.LEFT) {
                backFiveSeconds();
                event.consume();
            } else if (event.getCode() == KeyCode.RIGHT) {
                forwardFiveSeconds();
                event.consume();
            } else if (event.getCode() == KeyCode.HOME) {
                startOver();
                event.consume();
            } else if (event.getCode() == KeyCode.A) {
                toggleAudioOutput();
                event.consume();
            }
        });
    }

    public VideoPlayerView getView() {
        return view;
    }

    public void play(File file) {
        view.setPlaybackControlsDisabled(false);
        model.play(file);
    }

    public void shutdown() {
        model.shutdown();
    }

    private void startOver() {
        model.startOver();
    }

    private void backFiveSeconds() {
        model.backFiveSeconds();
    }

    private void togglePause() {
        model.togglePause();
    }

    private void forwardFiveSeconds() {
        model.forwardFiveSeconds();
    }

    private void toggleAudioOutput() {
        model.toggleAudioOutput();
    }

    private void stopAndExit() {
        model.stopPlayback();
        javafx.application.Platform.exit();
    }

    private void onVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void onFrameReady(Image image) {
        view.setImage(image);
    }

    private void onStatusChanged(String status) {
        view.setStatus(status);
    }

    private void onPlaybackStateChanged(boolean playing, boolean paused) {
        if (playing && !paused) {
            view.showPauseAction();
        } else {
            view.showPlayAction();
        }
    }

    private void onAudioOutputStateChanged(boolean audioOutputEnabled) {
        view.showAudioEnabled(audioOutputEnabled);
    }
}
