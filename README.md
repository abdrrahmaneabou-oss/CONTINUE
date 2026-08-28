# PixelTrigger V5

Clean development baseline for PixelTrigger V5.

Baseline source: PixelTrigger V4 from `abdrrahmaneabou-oss/Chatgpt`, commit `daa9f20f145e26924ce2fea2d8f7f599129a8a95`.

This repository intentionally contains only the current Android source, build configuration, tests, and one CI workflow. Historical APKs, Gradle caches, generated build output, decompilation artifacts, and old patch workflows were not imported.

## V5 direction

V4 behavior is the protected baseline. V5 development will add the RedMagic shoulder-button engine as an independent path without altering the existing PixelProbe/virtualTouchEvent hot path unless explicitly intended.
