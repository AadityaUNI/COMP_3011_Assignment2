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
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private boolean singleThreadingRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    private ArrayList<FrameProcessor> processorsRequested = new ArrayList<>();
    private final HashMap<Character, Supplier<FrameProcessor>> validEffects = new HashMap<>();

    // All valid effects, keyed by the single letter used on the command line (e.g. "-jm" applies jitter then mottle).
    // To add a new effect:
    //   1. Write a class extending FrameProcessor. Override initialise() for one-off setup per video and process()
    //      for the per-frame work. The multi-threaded model shares one instance between concurrent workers, so
    //      process() must keep per-frame state in local variables, not fields.
    //   2. Register it here with an unused letter, e.g. validEffects.put('x', FrameExample::new);
    //   3. List the letter in the help text printed by --help, and add it to the tests.
    // A Supplier (not an instance) is stored so each run gets a fresh processor, in the order the user typed them.
    {
        validEffects.put('n', FrameNumberer::new);
        validEffects.put('s', FrameScratcher::new);
        validEffects.put('f', FrameFlickerer::new);
        validEffects.put('w', FrameBlackAndWhiter::new);
        validEffects.put('y', FrameYellower::new);
        validEffects.put('v', FrameVignetter::new);
        validEffects.put('d', FrameDuster::new);
        validEffects.put('j', FrameJitterer::new);
        validEffects.put('m', FrameMottler::new);
        validEffects.put('b', FrameBleeder::new);
        validEffects.put('p', FramePepperer::new);
    }

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean isSingleThreadingRequested() {
        return singleThreadingRequested;
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                helpRequested = true;
            } else if ("-a".equals(arg) || "--audio".equals(arg)) {
                audioRequested = true;
            } else if ("-x".equals(arg) || "--maximise".equals(arg)) {
                maximiseRequested = true;
            } else if ("--single".equals(arg)) {
                // No short form: -s is already the scratch effect.
                singleThreadingRequested = true;
            } else if ("-1".equals(arg) || "--monitor-1".equals(arg)) {
                setDisplayId(1);
            } else if ("-2".equals(arg) || "--monitor-2".equals(arg)) {
                setDisplayId(2);
            } else if ("--number-frames".equals(arg)) {
                processorsRequested.add(new FrameNumberer());
            } else if ("--scratch-frames".equals(arg)) {
                processorsRequested.add(new FrameScratcher());
            } else if ("--flicker-frames".equals(arg)) {
                processorsRequested.add(new FrameFlickerer());
            } else if ("--black-and-white".equals(arg)) {
                processorsRequested.add(new FrameBlackAndWhiter());
            } else if ("--yellow-frames".equals(arg)) {
                processorsRequested.add(new FrameYellower());
            } else if ("--vignette-frames".equals(arg)) {
                processorsRequested.add(new FrameVignetter());
            } else if ("--dust-frames".equals(arg)) {
                processorsRequested.add(new FrameDuster());
            } else if ("--jitter-frames".equals(arg)) {
                processorsRequested.add(new FrameJitterer());
            } else if ("--mottle-frames".equals(arg)) {
                processorsRequested.add(new FrameMottler());
            } else if ("--bleed-frames".equals(arg)) {
                processorsRequested.add(new FrameBleeder());
            } else if ("--pepper-frames".equals(arg)) {
                processorsRequested.add(new FramePepperer());
            } else if (tryProcessorParsing(arg)) {
                continue;
            } else if (arg.startsWith("-")) {
                errorMessage = "Unknown option: " + arg;
            } else {
                videoFiles.add(arg);
            }
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            // Help on its own needs no file, but effects with nothing to apply them to are still an error.
            if (!helpRequested || !processorsRequested.isEmpty()) {
                errorMessage = "No video file specified.";
            }
        }
    }

    private boolean tryProcessorParsing(String arg) {
        if (!arg.startsWith("-")) {
            return false;
        }
        if (arg.length() < 2) {
            return false;
        }
        for (int i = 1; i < arg.length(); i++) {
            if (!validEffects.containsKey(arg.charAt(i))) {
                return false;
            }
        }

        // valid effect chain
        for (int i = 1; i < arg.length(); i++) {
            processorsRequested.add(validEffects.get(arg.charAt(i)).get());
        }
        return true;
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        System.out.println("  -n, --number-frames    Render the frame number onto each frame");
        System.out.println("  -s, --scratch-frames   Render vertical film scratches");
        System.out.println("  -f, --flicker-frames   Randomly dim frames");
        System.out.println("  -w, --black-and-white  Convert frames to black and white");
        System.out.println("  -y, --yellow-frames    Apply a warmer colour temperature");
        System.out.println("  -v, --vignette-frames  Darken the frame edges");
        System.out.println("  -d, --dust-frames      Render dust and hair marks");
        System.out.println("  -j, --jitter-frames    Randomly displace frames by a few pixels");
        System.out.println("  -m, --mottle-frames    Add cloudy emulsion mottling");
        System.out.println("  -b, --bleed-frames     Bleed light into frames");
        System.out.println("  -p, --pepper-frames    Pepper frames with dark spots/blotches");
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }

    public ArrayList<FrameProcessor> getProcessors() {
        return processorsRequested;
    }
}
