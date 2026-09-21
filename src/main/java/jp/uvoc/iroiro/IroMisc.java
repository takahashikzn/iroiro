// Copyright 2026 takahashikzn
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package jp.uvoc.iroiro;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.ImageObserver;
import java.awt.image.Kernel;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static java.awt.RenderingHints.*;


/**
 * What the other tools stand on: the one worker pool they share and the scan that splits a pass
 * over it, pixel access, drawing that waits for the image, and a few odds and ends.
 * <p>
 * Per-pixel passes elsewhere should go through {@link #scan} or {@link #bands} too, rather than
 * starting threads of their own: then the whole process has a single CPU budget for image work,
 * however many images arrive at once.
 *
 * @author takahashikzn
 */
public final class IroMisc {

    private IroMisc() { }

    /** Below 256K pixels, run one full-range scan on the calling thread. */
    public static final int PARALLEL_MIN =
        Optional.ofNullable(System.getProperty(IroMisc.class.getName() + ".parallelMin")).stream().map(Integer::parseInt).findAny().orElse(1 << 18);

    public static final int PARALLELISM =
        Optional.ofNullable(System.getProperty(IroMisc.class.getName() + ".parallelism")).stream().map(Integer::parseInt).findAny()
            .orElseGet(() -> Math.max(1, Runtime.getRuntime().availableProcessors()));

    /** Lazy: small images and serial callers do not need to initialize the pool. */
    private static final class Workers {

        private static final AtomicInteger seq = new AtomicInteger();

        // One CPU budget for all images and all tools. Joining nested scans can help execute their
        // tasks; the pool must not grow spare workers when many images arrive simultaneously.
        // Deliberately not the common pool: callers are typically a bounded pool of platform threads
        // rather than common pool workers, so a parallel stream adds each caller on top of the pool
        // for every image in flight. Here the caller only waits, and the pool is the whole budget.
        static final ForkJoinPool POOL = new ForkJoinPool(PARALLELISM, pool -> {
            final var worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            worker.setName("iro-worker-" + seq.incrementAndGet());
            return worker;
        }, null, false, PARALLELISM, PARALLELISM, 1, pool -> true, 60, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    public interface Scan {

        /** Reads any needed input, writes only [from, to), with mutable scratch allocated inside. */
        void run(int from, int to);
    }

    /**
     * Execute the same scan either immediately over [0, size), or in disjoint ranges on the shared
     * pool. Pixel count controls both the threshold and the task count (at most one per CPU).
     * Scratch buffers belong inside the scan, so queued images do not allocate one set per task.
     * Returns only after every range finishes, including when one of them throws.
     * <p>
     * The ranges split at arbitrary indexes. A pass that packs several items into one output byte
     * must scan in units that start on a byte boundary (rows, for instance), not in single items.
     */
    public static void scan(final boolean parallel, final int size, final long pixels, final Scan body) {

        Objects.requireNonNull(body, "body");
        if (size < 0 || pixels < 0) throw new IllegalArgumentException("negative scan size");
        if (size == 0) return;
        if (!parallel || size < 2 || pixels < PARALLEL_MIN || PARALLELISM < 2) {
            body.run(0, size);
            return;
        }

        final int parts = Math.min(size, Math.clamp(1 + (pixels - 1) / PARALLEL_MIN, 2, PARALLELISM));
        final var batch = new RecursiveAction() {

            @Override
            protected void compute() {
                final var tasks = new ForkJoinTask<?>[parts - 1];
                int started = 0;
                Throwable failure = null;
                try {
                    for (int i = 0; i < parts - 1; i++) {
                        final int from = (int) ((long) size * i / parts);
                        final int to = (int) ((long) size * (i + 1) / parts);
                        tasks[i] = ForkJoinTask.adapt(() -> body.run(from, to));
                        tasks[i].fork();
                        started++;
                    }
                    body.run((int) ((long) size * (parts - 1) / parts), size);
                } //
                catch (final RuntimeException | Error e) { failure = e; } //
                finally {
                    // Cancellation is not enough: a cancelled task may still be writing pixels.
                    for (int i = 0; i < started; i++) {
                        try { tasks[i].join(); } //
                        catch (final RuntimeException | Error e) {
                            if (failure == null) failure = e;
                            else if (failure != e) failure.addSuppressed(e);
                        }
                    }
                }
                if (failure instanceof RuntimeException e) throw e;
                if (failure instanceof Error e) throw e;
            }
        };
        if (ForkJoinTask.getPool() == Workers.POOL) batch.invoke();
        else Workers.POOL.invoke(batch);
    }

    /** {@link #scan} over the rows of an image: {@code from} and {@code to} are row numbers. */
    public static void bands(final boolean parallel, final int width, final int height, final Scan body) {
        scan(parallel, height, (long) width * height, body);
    }

    // ------------------------------------------------------------------------ pixels

    /** ARGB of every pixel, row by row. Always a copy, never the image's own backing store. */
    public static int[] pixels(final BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    public static boolean isBinary(final BufferedImage img) {
        return img.getType() == BufferedImage.TYPE_BYTE_BINARY && img.getColorModel().getPixelSize() == 1;
    }

    // ----------------------------------------------------------------------- drawing

    /** Rendering hints for output that is kept: bicubic, antialiased, quality over speed. */
    static final Map<RenderingHints.Key, Object> QUALITY = Map.of( //
        KEY_RENDERING, VALUE_RENDER_QUALITY, //
        KEY_ANTIALIASING, VALUE_ANTIALIAS_ON, //
        KEY_INTERPOLATION, VALUE_INTERPOLATION_BICUBIC);

    /**
     * Draw and wait until the image has actually been drawn. A {@link java.awt.Toolkit} image is
     * produced asynchronously, and drawing one that has not arrived yet draws nothing.
     */
    public static void drawImage(final Graphics2D g2d, final Image img, final int x, final int y) {
        final var done = new AtomicBoolean();
        if (!g2d.drawImage(img, x, y, observer(done))) await(done);
    }

    /** {@link #drawImage(Graphics2D, Image, int, int)}, scaled to {@code w} x {@code h}. */
    public static void drawImage(final Graphics2D g2d, final Image img, final int x, final int y, final int w, final int h) {
        final var done = new AtomicBoolean();
        if (!g2d.drawImage(img, x, y, w, h, observer(done))) await(done);
    }

    /** Only the observer sets {@code done}; the drawImage result must not overwrite what it reported. */
    private static ImageObserver observer(final AtomicBoolean done) {
        return (img, flags, x, y, w, h) -> {
            // FRAMEBITS: an animation never reports ALLBITS, and one full frame is all that gets drawn
            if ((flags & (ImageObserver.ALLBITS | ImageObserver.FRAMEBITS | ImageObserver.ERROR | ImageObserver.ABORT)) == 0) return true;
            done.set(true);
            return false;
        };
    }

    private static void await(final AtomicBoolean done) {
        boolean interrupted = false;
        while (!done.get()) try { Thread.sleep(25); } //
        catch (final InterruptedException e) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** {@code img} itself when it already is a {@link BufferedImage}, otherwise drawn onto ARGB. */
    public static BufferedImage toBufferedImage(final Image img) {
        if (img instanceof BufferedImage bi) return bi;
        return draw(new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB), g2d -> drawImage(g2d, img, 0, 0));
    }

    /** Run {@code f} on a graphics of {@code canvas}, dispose it, and return the canvas. */
    static BufferedImage draw(final BufferedImage canvas, final Consumer<Graphics2D> f) {
        final var g2d = canvas.createGraphics();
        try { f.accept(g2d); } finally { g2d.dispose(); }
        return canvas;
    }

    // ------------------------------------------------------------------------ filters

    /** A 3x3 sharpening kernel, applied {@code level} times. */
    public static BufferedImage sharpen(final BufferedImage img, final int level) {

        final var op = new ConvolveOp(new Kernel(3, 3, new float[] {
            -1, -1, -1, //
            -1, +9, -1, //
            -1, -1, -1 }));

        var ret = img;
        for (int i = level; 0 < i; i--)
            ret = op.filter(ret, null);

        return ret;
    }
}
