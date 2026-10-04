# puppy performance and architecture notes

## Rendering and execution

PTY management, PRoot, Alpine processes, terminal parsing, shell hooks and filesystem work execute on the CPU. TerminalView draws to Android Canvas without forcing a hardware layer. Android may accelerate Canvas drawing with the device GPU and can use its software path when hardware acceleration is disabled or unavailable. puppy does not profile GPU models, select Vulkan, or claim that GPU features are active.

PTY reads use the native poll loop. Output text is batched for up to 16 ms before delivery to the Android UI thread. TerminalBuffer continues parsing on the UI thread, so very large continuous output can still delay input or drawing. The view requests redraws only after output and avoids invalidation while hidden; there are no idle animation loops.

## Terminal memory

Scrollback is bounded to 10,000 lines by default. The current screen and scrollback use Kotlin cell objects, not a compact glyph atlas or disk-backed terminal buffer. This is bounded but can use noticeable memory at large column counts. No runtime memory-pressure callback or user-configurable scrollback limit is implemented.

## Shell integration

A marked, idempotent startup block is appended to existing shell startup files without replacing their contents. Scripts live at `~/.puppy/integration/` and can be disabled with the app's **Interactive shell enhancements** setting. The setting is passed into the next shell's environment; an already-running shell is not reconfigured.

The Bash/Zsh/Fish prompt hooks provide the current directory, a lightweight read of a conventional `.git/HEAD` branch, project marker names (Node, Python, Rust, Go or Java), previous exit status, and Bash/Zsh command duration when it reaches two seconds. The default BusyBox ash prompt displays the current directory and emits shell-state markers only. Git status, ahead/behind counts and runtime version commands are not queried. Fish's own highlighting, command validation, autosuggestions and history remain active when Fish is installed.

OSC 133 markers communicate prompt/command state. TerminalBuffer also recognizes alternate-screen mode (`CSI ?1049`) and switches to `FULLSCREEN_TUI`; app input remains forwarded directly to the PTY. puppy does not currently perform command-line overlays for Bash/Zsh, fuzzy corrections, or a puppy-managed ranked history index. This avoids intercepting keypresses needed by editors, REPLs, SSH, tmux and other raw-input programs.

## Graphics effects and adaptive resources

There is no custom blur backend, wallpaper background mode, imported-font manager, GPU capability classifier, performance overlay, thermal controller, refresh-rate policy or Battery Saver integration. No unsupported blur percentage is claimed. Terminal correctness does not depend on GPU rendering.

## Verification and measurements

No device performance benchmark was run in this environment. The changes were validated with JVM unit tests, Bash/Zsh script syntax checks, Android lint/build tasks, package ID inspection and APK signing verification. Latency, throughput, frame rate, RAM, battery use and thermal behavior are not measured results.
