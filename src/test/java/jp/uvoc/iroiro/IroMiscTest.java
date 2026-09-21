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

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static jp.uvoc.iroiro.IroMeasure.*;
import static jp.uvoc.iroiro.IroMisc.*;

import org.junit.Test;

import static org.assertj.core.api.Assertions.*;
import static org.junit.Assume.assumeTrue;


/** Execution policy, concurrent image pipelines, and contracts for the quality measures in {@link IroMeasure}. */
public class IroMiscTest {

    @Test
    public void smallAndDisabledScansRunTheFullRangeImmediately() {
        final var caller = Thread.currentThread();
        final var calls = new AtomicInteger();
        final Scan body = (from, to) -> {
            assertThat(Thread.currentThread()).isSameAs(caller);
            assertThat(from).isZero();
            assertThat(to).isEqualTo(17);
            calls.incrementAndGet();
        };
        scan(true, 17, PARALLEL_MIN - 1, body);
        scan(false, 17, (long) PARALLEL_MIN * 100, body);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    public void emptyAndSingleRangeScansAreWellDefined() {
        scan(true, 0, Long.MAX_VALUE, (from, to) -> { throw new AssertionError("empty scan"); });
        final var caller = Thread.currentThread();
        scan(true, 1, Long.MAX_VALUE, (from, to) -> {
            assertThat(Thread.currentThread()).isSameAs(caller);
            assertThat(from).isZero();
            assertThat(to).isEqualTo(1);
        });
        assertThatIllegalArgumentException().isThrownBy(() -> scan(true, -1, 1, (a, b) -> { }));
        assertThatIllegalArgumentException().isThrownBy(() -> scan(true, 1, -1, (a, b) -> { }));
    }

    @Test
    public void largeRangesCoverEveryItemExactlyOnceOnTheSharedPool() {
        final var caller = Thread.currentThread();
        final var visits = new AtomicIntegerArray(1003);
        final var pools = ConcurrentHashMap.<ForkJoinPool> newKeySet();
        final var ranges = new AtomicInteger();
        scan(true, visits.length(), Long.MAX_VALUE, (from, to) -> {
            assertThat(from).isLessThan(to);
            if (PARALLELISM > 1) {
                assertThat(Thread.currentThread()).isNotSameAs(caller);
                pools.add(ForkJoinTask.getPool());
            } else assertThat(Thread.currentThread()).isSameAs(caller);
            ranges.incrementAndGet();
            for (int i = from; i < to; i++) visits.incrementAndGet(i);
        });
        for (int i = 0; i < visits.length(); i++) assertThat(visits.get(i)).isEqualTo(1);
        assertThat(ranges.get()).isEqualTo(Math.min(PARALLELISM, visits.length()));
        if (PARALLELISM > 1) {
            assertThat(pools).hasSize(1);
            final var pool = pools.iterator().next();
            assertThat(pool).isNotSameAs(ForkJoinPool.commonPool());
            assertThat(pool.getParallelism()).isEqualTo(PARALLELISM);
            assertThat(pool.getPoolSize()).isLessThanOrEqualTo(PARALLELISM);
        }
    }

    @Test(timeout = 10000)
    public void thresholdSizedScansCanUseTwoWorkersAtOnce() {
        assumeTrue(PARALLELISM > 1);
        final var both = new CountDownLatch(2);
        final var threads = ConcurrentHashMap.<Thread> newKeySet();
        scan(true, 2, PARALLEL_MIN, (from, to) -> {
            threads.add(Thread.currentThread());
            both.countDown();
            await(both);
        });
        assertThat(threads).hasSize(2);
    }

    @Test(timeout = 15000)
    public void nestedScansCompleteWithoutExpandingThePool() {
        final var sum = new AtomicInteger();
        final var pools = ConcurrentHashMap.<ForkJoinPool> newKeySet();
        scan(true, 8, PARALLEL_MIN * 8L, (from, to) -> {
            for (int i = from; i < to; i++)
                scan(true, 101, PARALLEL_MIN * 4L, (lo, hi) -> {
                    if (PARALLELISM > 1) pools.add(ForkJoinTask.getPool());
                    sum.addAndGet(hi - lo);
                });
        });
        assertThat(sum.get()).isEqualTo(808);
        if (PARALLELISM > 1) {
            assertThat(pools).hasSize(1);
            assertThat(pools.iterator().next().getPoolSize()).isLessThanOrEqualTo(PARALLELISM);
        }
    }

    /** Returning on the first exception would leave another task writing into the caller's buffer. */
    @Test(timeout = 10000)
    public void aFailureStillWaitsForTheOtherRanges() throws Exception {
        assumeTrue(PARALLELISM > 1);
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var finished = new AtomicBoolean();
        final var callers = Executors.newSingleThreadExecutor();
        try {
            final var future = callers.submit(() -> scan(true, 2, PARALLEL_MIN, (from, to) -> {
                if (from == 0) {
                    entered.countDown();
                    await(release);
                    finished.set(true);
                } else throw new IllegalStateException("range failure");
            }));
            await(entered);
            assertThatExceptionOfType(TimeoutException.class).isThrownBy(() -> future.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertThatExceptionOfType(ExecutionException.class).isThrownBy(() -> future.get(5, TimeUnit.SECONDS)).withStackTraceContaining("range failure");
            assertThat(finished.get()).isTrue();
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    /** Several callers share the pool across both tools; output must equal the complete serial pipeline. */
    @Test(timeout = 30000)
    public void concurrentResizeAndQuantizePipelinesMatchSerial() throws Exception {
        final var sources = new BufferedImage[] { pattern(1025, 769, false), pattern(1025, 769, true) };
        final var resized = new BufferedImage[2];
        final var expected = new byte[4][];
        for (int i = 0; i < sources.length; i++) {
            resized[i] = IroResize.resize(sources[i], 513, 517, false);
            for (int format = 0; format < 2; format++)
                expected[i * 2 + format] = IroQuant.encode(resized[i], IroQuant.Opts.DEFAULT.maxColors(32).palettizeLossy(format == 0).parallel(false));
        }
        final var callers = Executors.newFixedThreadPool(4);
        try {
            final var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) {
                final int variant = i % 4;
                jobs.add(callers.submit(() -> {
                    final var out = IroResize.resize(sources[variant / 2], 513, 517);
                    assertThat(pixels(out)).isEqualTo(pixels(resized[variant / 2]));
                    assertThat(IroQuant.encode(out, IroQuant.Opts.DEFAULT.maxColors(32).palettizeLossy(variant % 2 == 0))).isEqualTo(expected[variant]);
                }));
            }
            for (final var job: jobs) job.get(20, TimeUnit.SECONDS);
        } finally { callers.shutdownNow(); }
    }

    @Test
    public void ssimMeasuresTinyImagesAndTheLastPixel() {
        for (final int[] size: new int[][] { { 1, 1 }, { 4, 7 }, { 8, 8 }, { 11, 13 }, { 513, 517 } }) {
            final var a = pattern(size[0], size[1], false);
            final var b = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
            b.setRGB(0, 0, size[0], size[1], pixels(a), 0, size[0]);
            assertThat(ssim(a, b)).isEqualTo(1);
            final int x = size[0] - 1;
            final int y = size[1] - 1;
            b.setRGB(x, y, (a.getRGB(x, y) & 0xFFFFFF) < 0x808080 ? 0xFFFFFFFF : 0xFF000000);
            assertThat(ssim(a, b)).isLessThan(1);
            assertThat(ssim(a, b)).isCloseTo(ssim(b, a), within(1e-12));
        }
        final var black = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        final var white = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        white.setRGB(0, 0, 0xFFFFFFFF);
        assertThat(ssim(black, white)).isCloseTo(6.5025 / (65025 + 6.5025), within(1e-12));
    }

    @Test
    public void qualityMeasuresRejectMismatchedDimensions() {
        final var a = pattern(5, 4, false);
        final var b = pattern(4, 5, false);
        assertThatIllegalArgumentException().isThrownBy(() -> maxError(a, b));
        assertThatIllegalArgumentException().isThrownBy(() -> avgError(a, b));
        assertThatIllegalArgumentException().isThrownBy(() -> premultipliedError(a, b));
        assertThatIllegalArgumentException().isThrownBy(() -> ssim(a, b));
        assertThatIllegalArgumentException().isThrownBy(() -> colorError(a, b, Background.BLACK));
        assertThatNullPointerException().isThrownBy(() -> colorError(a, a, null));
    }

    @Test
    public void hiddenRgbDoesNotAffectColorErrorMeasures() {
        final var a = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        final var b = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        a.setRGB(0, 0, 0x00FF0000);
        b.setRGB(0, 0, 0x0000FFFF);
        assertThat(maxError(a, b)).isZero();
        assertThat(avgError(a, b)).isZero();
        assertThat(premultipliedError(a, b)).isZero();
        for (final var background: Background.values()) assertThat(colorError(a, b, background).max()).isZero();
    }

    @Test(timeout = 15000)
    public void qualityMeasuresAreDeterministicInsideNestedScans() {
        final var a = pattern(513, 517, true);
        final var b = pattern(513, 517, false);
        final var expected = colorError(a, b, Background.CHECKERBOARD);
        final double expectedSsim = ssim(a, b);
        scan(true, 4, PARALLEL_MIN * 4L, (from, to) -> {
            for (int i = from; i < to; i++) {
                assertThat(colorError(a, b, Background.CHECKERBOARD)).isEqualTo(expected);
                assertThat(ssim(a, b)).isEqualTo(expectedSsim);
            }
        });
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker did not arrive");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static BufferedImage pattern(final int w, final int h, final boolean alpha) {
        final var src = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                src.setRGB(x, y,
                    (alpha ? (x + y) & 255 : 255) << 24 | (x * 255 / Math.max(1, w - 1)) << 16 | (y * 255 / Math.max(1, h - 1)) << 8 | (x * 7 + y * 3) & 255);
        return src;
    }

    /** Manual timing only; run the test class main on the same JVM to compare serial/parallel work. */
    static void main(final String[] args) {
        final var src = pattern(3001, 2003, true);
        System.out.printf("workers=%d, threshold=%d pixels%n", PARALLELISM, PARALLEL_MIN);
        final var reduced = IroResize.resize(src, 1001, 701, false);
        for (final boolean parallel: new boolean[] { false, true }) {
            benchmark("resize parallel=" + parallel, () -> IroResize.resize(src, 1001, 701, parallel));
            benchmark("quantize parallel=" + parallel, () -> IroQuant.encode(reduced, IroQuant.Opts.DEFAULT.parallel(parallel)));
            benchmark("truecolor parallel=" + parallel, () -> IroQuant.encode(reduced, IroQuant.Opts.DEFAULT.parallel(parallel).palettizeLossy(false)));
        }
    }

    private static void benchmark(final String label, final Runnable body) {
        for (int i = 0; i < 5; i++) body.run();
        final var times = new long[7];
        for (int i = 0; i < times.length; i++) {
            final long start = System.nanoTime();
            body.run();
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        System.out.printf(java.util.Locale.ROOT, "%s: %.3f ms%n", label, times[times.length / 2] / 1e6);
    }
}
