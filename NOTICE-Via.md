# Bundled third-party software

Release builds of this mod **fat-jar** the following components so cross-version support is
self-contained. They are **not** part of this repository's source and remain under their own
licenses:

| Component | License | Source |
|---|---|---|
| ViaVersion | GPL-3.0 | https://github.com/ViaVersion/ViaVersion |
| ViaBackwards | GPL-3.0 | https://github.com/ViaVersion/ViaBackwards |
| ViaRewind | GPL-3.0 | https://github.com/ViaVersion/ViaRewind |

This mod's own source code (everything under `src/`) is licensed **GPL-3.0** (see `LICENSE`), which is
compatible with the bundled Via libraries, so the distributed jar is a clean GPL-3.0 combined work.
Corresponding source is available in this repository and at the links above.
