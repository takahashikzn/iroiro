# iroiro

Image tools in pure Java, with no dependency beyond the JDK.

The name is Japanese: *iro* (色) is "color", and *iroiro* (色々) is "various" — a box of assorted
tools whose centerpiece is color reduction.

| Class | What it does |
|---|---|
| `IroQuant` | Palette PNG encoder with adaptive color reduction (a pure Java alternative to pngquant). Images that already fit the palette are encoded losslessly. Applies an embedded ICC profile (`iCCP`) that ImageIO ignores. |
| `IroResize` | High-quality downscaling (box shrink + Mitchell), reading the source a row at a time and subsampling the decode so a large image is never held at full size. |
| `IroHeader` | Pixel dimensions straight from an encoded image's header, including files ImageIO refuses to open. |
| `IroAlpha` | Transparency: detecting, flattening, opacity, color keying, compositing, soft and 1-bit masks. |
| `IroLayout` | Where an image goes on a canvas: joining, aligning in a box, placing at an offset, rotating. |
| `IroIO` | Writing through ImageIO with the retries it needs, TIFF, and text metadata (PNG `tEXt`). |
| `IroHalftone` | Down to one bit per pixel: threshold, ordered dithering (Bayer, clustered dot), Floyd-Steinberg. |
| `IroMeasure` | How far one image is from another: similarity, max/mean error, SSIM, Oklab color error. |
| `IroMisc` | What the rest stands on: the single bounded worker pool, pixel access, drawing that waits for the image, sharpening. |

## Requirements

Java 25. Nothing else at runtime.

## Build

Plain Ant. `ant jar` builds `target/iroiro.jar` without any dependency resolution; `ant test` also
fetches the test libraries with Ivy (expected in `~/.ant/lib`) and runs the tests.

## License

Apache License 2.0. Copyright 2026 takahashikzn
