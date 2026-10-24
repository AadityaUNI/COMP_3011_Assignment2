# COMP3011 Assignment 2 - Multi-threaded Video Player

For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505

Modified by Aaditya Singh, A1946894.

## Architecture

```
 decode thread  --decodedFrames-->  effect workers  --processedFrames-->  FX thread
 (grab + stamp)    (bounded queue)  (effects + convert)  (priority queue)  (audio + display)
```

| Thread | Job |
|---|---|
| **FX thread** (JavaFX) | Owns all control (play, pause, seek, stop), the playback clock, audio output, and deciding when each frame is shown. `pumpPlayback` never blocks. |
| **`video-decoder`** | The only thread that reads from FFmpeg during playback. Grabs a frame, queues its audio, hands the video to the pool. |
| **`effect-worker-N`** pool | Runs the effect stack on a private copy of a frame and converts it to an image. Several frames are processed at once. |

Class layout:
- `controllers/`
  - `CommandLineController` parses options and builds the effect list.
  - `VideoPlayerController` picks the model and wires it to the view.
- `models/`
  - `VideoPlayerModel` is an interface with two implementations.
  - `MultiThreadedVideoPlayerModel` is the new design.
  - `SingleThreadedVideoPlayerModel` is the starter model, kept unchanged and used only with `--single`.
- `views/`: the unchanged JavaFX view.
- `frameprocessors/`: the effects.
  - `FrameMottlerLegacy` is the original mottler, kept only as a test baseline (in `src/test/java`).
- **Adding a new effect:** see the comment above `validEffects` in `CommandLineController.java`.

## Design correctness

### Who owns shared state

| State | Protection |
|---|---|
| `grabber`, `nextDecodeIndex` | `grabberLock` |
| Playback clock, `nextDisplayIndex`, pause state, `screenImage` | FX thread only |
| `pendingAudio` | `ConcurrentLinkedQueue` (decode thread adds, FX thread drains) |
| `seekGeneration` | `AtomicInteger` |
| `playbackOpen`, `endOfStreamGeneration`, `audioOutputEnabled` | `volatile` |
| Frame pixels | Each frame is `clone()`d, so no two threads share pixel data |
| Frame counts in the pipeline | `framesInFlight` semaphore |

### Race conditions handled (model)

| # | Race | Fix |
|---|---|---|
| 1 | FFmpeg's grabber is not thread safe | Only the decode thread grabs, and only while holding `grabberLock`. |
| 2 | The grabber reuses its frame buffer on the next grab | The frame is `clone()`d inside the lock. |
| 3 | Workers finish frames out of order | Each frame gets a sequence index. `processedFrames` is sorted, and only `nextDisplayIndex` may be shown. |
| 4 | Frames from before a seek still in the pipeline | Each frame is stamped with `seekGeneration`. Workers check it between effects and the FX thread checks it before display. Stale frames are dropped. |
| 5 | A frame stamped half-way through a seek | The seek runs `setTimestamp()` + `invalidateQueuedFrames()` under the same lock as grab + stamp. |
| 6 | Old audio playing after a seek | Audio is queued under `grabberLock` and cleared under it, so everything cleared is old. |
| 7 | Decoder running ahead, memory growth, decoding while paused | `framesInFlight` permits. The decoder takes one per frame. It is returned exactly once, when the frame is shown or dropped (`queuedForDisplay` marks the hand-off). |
| 8 | A worker blocking forever after a seek emptied the queue | Workers use `poll()`, not `take()`. |
| 9 | Peek-then-poll while workers insert | The FX thread uses `remove(head)`, so it only removes the frame it checked. |
| 10 | Grabber closed mid-grab, or effects re-initialised while workers still use them | Fixed close order: stop timer → interrupt + join decoder → invalidate → wait for all permits → close audio/grabber/converter. |
| 11 | Converter set to null by close while a worker uses it | Workers read it once into a local and skip the frame if it is null. |
| 12 | A late error handler from an old session closing a new one | Errors go to the FX thread via `Platform.runLater` and are re-checked against the current generation / decode thread. |
| 13 | Last frames cut off at end of stream | Playback only finishes when `endOfStreamGeneration` is current and every permit is back. The decoder `park`s until a seek `unpark`s it. An early `unpark` is not lost. |
| 14 | Effect fields written in `initialise()` not visible to workers | `initialise()` runs on the decode thread before the frame is queued. The queue `put`/`poll` and the executor `submit` guarantee workers see those writes (happens-before). |

### No deadlocks
- There is only one lock (`grabberLock`), so lock ordering can't go wrong.
- No thread blocks while holding it: the queue `put` and semaphore `acquire` both happen outside the lock. The FX thread can always take it to seek.
- The FX thread never blocks in `pumpPlayback`.
- The decoder's blocking calls (`acquire`, `put`, `park`) respond to interrupt, so `stopDecodeThread()`'s `join()` returns quickly.

## Effects and thread sharing

There is one instance per effect position, shared by every worker. One instance per worker would not work: each instance's random seed comes from its creation index, so a frame would look different depending on which worker ran it. Shared instances mean `process()` must not keep per-frame state in fields.

| Effect | Problem in starter | Fix |
|---|---|---|
| `FrameBleeder` | Bright/blur mask arrays were fields | Allocated per call and passed as arguments |
| `FrameJitterer` | `sourcePixels` / `blackRow` were fields | Per-call locals |
| `FrameMottler` | Per-block scale indices were fields | Returned as an `int[]` |
| `FrameMottler` | Damage-mask cache fields were overwritten by other frames | 3-slot `AtomicReferenceArray` of immutable `DamageMask` records, keyed by index. A race only causes an identical rebuild. Cleared in `initialise()`. |
| Other 8 effects | none | Only write fields in `initialise()`, so already safe |

## Performance decisions
- **Workers = cores − 2**, at below-normal priority and as daemon threads. When every core is busy, the FX and render threads still get the CPU first.
- **FFmpeg gets 2 threads.** Decoding one video is cheap, and FFmpeg's default of cores + 1 would compete with the effects.
- **`framesBuffer = 35`** (~1.5 s at 24 fps). Enough to absorb slow frames, and a small refill after each seek.
- **One reused on-screen `WritableImage`.** Showing a new image per frame filled JavaFX's GPU texture pool, which forced full `System.gc()` stalls.
- **The clock starts at the first frame ready to show,** not when it was decoded, so pipeline delay never makes frames late.
- **Sharing the mottler without a lock** is ~8× faster than sharing the legacy mottler behind a lock on 16 cores (see tests).

## Tests

| Test | Asserts |
|---|---|
| **`CommandLineControllerTests`** (`mvn test`) | |
| `generalStackedParse` | Chained letters keep their order and mix with long forms and flags (`-a`, `-x`, `-2`). |
| `repeatedEffectsParse` | A repeated effect (`-sns`) is applied each time it is given, and each use gets its own processor. |
| `threadingModeParse` | Multi-threaded by default; `--single` selects the original and isn't read as an effect chain. |
| `helpWithoutFile` | `--help` alone needs no file; effects without a file are an error. |
| `invalidParse` | Unknown letters or flags mixed into a chain are rejected with no effects created. |
| **`FrameMottlerThreadSafetyTests`** (`mvn test`) | |
| `refactoredMottlerLooksIdenticalToLegacy` | The refactor produces byte-identical frames to the original. |
| `sharedLegacyMottlerCorruptsFrames` | A shared legacy mottler corrupts frames, which shows the tests can detect a race. |
| `sharedMottlerMatchesSequentialDuringPlayback` | A shared mottler on N threads matches a single-threaded run (5 rounds). |
| `sharedMottlerMatchesSequentialWhenFramesArriveOutOfOrder` | Same, with shuffled frames (like seeking), which stresses the mask cache. |
| `sharedMottlerOutpacesLockedLegacy` | The lock-free shared mottler is > 1.5× faster than a locked legacy one. |
| **`SharedEffectThreadSafetyTests`** (`mvn test`) | |
| `bleederChangesFramesDeterministically` / `jittererChangesFramesDeterministically` | The effect changes frames and repeats exactly, so the thread test below can't pass trivially. |
| `sharedBleederMatchesSequential` / `sharedJittererMatchesSequential` | A shared instance on N threads matches a single-threaded run (5 rounds). The same test against the starter versions corrupted 32 (Bleeder) and 21 (Jitterer) of 200 frames. |
| **`PlaybackLoadIT`** (`mvn verify`) | |
| `multiThreadedKeepsUpAtLeastAsWellAsSingleThreaded` | Plays the first minute with 6 and 12 effects on both players. The multi-threaded player must have no more late frames and no more negative-headroom frames than the single-threaded one (1 % tolerance). Prints a comparison table. Opens a real window, so it needs a display. Takes ~4-5 min. |

Thread-safety tests use in-memory frames with random but repeatable pixels. Effects are deterministic, so a single-threaded run is an exact reference: any race shows up as a byte difference.

Only the Mottler, Bleeder and Jitterer have thread-safety tests. The other 8 effects were already thread safe and their design was not changed, so no tests were written for them.

### Load test results

Output of `PlaybackLoadIT` (`mvn verify`) on a 16-core machine, playing the first 60 s of `movie.mp4` (1920x800, 24 fps, 41.7 ms frame budget).

**6 effects (`-sfwyvd`)**

| Model | Frames | Late frames | Negative headroom | Worst late | Worst headroom |
|---|---|---|---|---|---|
| single | 1415 | 0 (0.0%) | 5 (0.4%) | 24.5 ms | -12.0 ms |
| multi | 1415 | 0 (0.0%) | 0 (0.0%) | 16.6 ms | 931.2 ms |

**12 effects (`-sfwyvdjmbpnp`)**

| Model | Frames | Late frames | Negative headroom | Worst late | Worst headroom |
|---|---|---|---|---|---|
| single | 1415 | 1 (0.1%) | 596 (42.1%) | 41.7 ms | -35.8 ms |
| multi | 1415 | 0 (0.0%) | 0 (0.0%) | 16.5 ms | 926.5 ms |
