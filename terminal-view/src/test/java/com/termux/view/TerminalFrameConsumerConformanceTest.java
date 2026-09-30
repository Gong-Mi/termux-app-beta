package com.termux.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #57 P2 conformance matrix: the SAME scenario library
 * ({@link TerminalFrameConsumerConformance}) must pass on every backend
 * implementation of {@link TerminalFrameConsumer}, regardless of threading
 * model. This test pins the two reference implementations available at JVM
 * level:
 *
 * <ul>
 *   <li><b>sync-mirror</b>: a minimal synchronous consumer with the same
 *       generation/detach/attach gate logic as {@link CanvasFrameConsumer}
 *       (whose View/Canvas deps keep it out of plain JVM tests — its pixel
 *       conformance is owned by the emulator render smoke, the layer-3
 *       boundary);</li>
 *   <li><b>fake-async</b>: the push-style {@link FakeAsyncFrameConsumer}
 *       (Surface/GLES threading shape) from the #64 async contract.</li>
 * </ul>
 *
 * <p>A future backend (retained layer, row bitmap, Surface backbuffer, GLES)
 * adds itself to {@link #factories()} and inherits the whole matrix — this is
 * the "all backends reuse one conformance contract" requirement of #57 P2.</p>
 */
@RunWith(Parameterized.class)
public class TerminalFrameConsumerConformanceTest {

    // ------------------------------------------------------------------
    // Synchronous mirror of CanvasFrameConsumer's gate logic (no View/Canvas).
    // ------------------------------------------------------------------
    private static final class SyncMirrorConsumer implements TerminalFrameConsumer {
        long mGeneration = -1;
        boolean mAttached;
        long mDelivered;
        RenderGeometry mGeometry;

        @Override public void attach(long renderGeneration, RenderGeometry geometry) {
            mGeneration = renderGeneration;
            mGeometry = geometry;
            mAttached = true;
        }

        @Override public void submit(TerminalRenderFrame frame, RenderDamage damage,
                                     TerminalFrameIdentity identity, long renderGeneration) {
            if (!mAttached) {
                throw new IllegalStateException("not attached");
            }
            if (renderGeneration != mGeneration) return;
            mDelivered++;
        }

        @Override public void detach(long renderGeneration) {
            if (renderGeneration != mGeneration) return;
            mAttached = false;
        }

        @Override public RenderStats snapshot() {
            return new RenderStats(0, 0, 0, mDelivered, mDelivered, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private static final class SyncMirrorFactory implements TerminalFrameConsumerConformance.BackendFactory {
        private SyncMirrorConsumer mConsumer;

        @Override public TerminalFrameConsumer create() {
            mConsumer = new SyncMirrorConsumer();
            return mConsumer;
        }

        @Override public boolean isSynchronous() { return true; }
        @Override public void drain() { }
        @Override public long deliveredFrames() { return mConsumer == null ? 0 : mConsumer.mDelivered; }
        @Override public void close() { mConsumer = null; }
    }

    // ------------------------------------------------------------------
    // Async fake factory: FakeAsyncFrameConsumer with a delivery counter.
    // ------------------------------------------------------------------
    private static final class FakeAsyncFactory implements TerminalFrameConsumerConformance.BackendFactory {
        private final List<Long> mRendered = new CopyOnWriteArrayList<>();
        private ExecutorService mExecutor;
        private FakeAsyncFrameConsumer mConsumer;

        @Override public TerminalFrameConsumer create() {
            mRendered.clear();
            mExecutor = FakeAsyncFrameConsumer.newSerialBackendExecutor();
            mConsumer = new FakeAsyncFrameConsumer(mExecutor, mRendered::add);
            return mConsumer;
        }

        @Override public boolean isSynchronous() { return false; }

        @Override public void drain() {
            if (mExecutor == null || mExecutor.isShutdown()) return;
            long before;
            do {
                before = mRendered.size();
                // Run one backend cycle from the harness thread: submit a no-op
                // task and wait for it, guaranteeing all earlier frames drained.
                final AtomicLong done = new AtomicLong();
                try {
                    mExecutor.execute(() -> done.incrementAndGet());
                } catch (RuntimeException rejectedOrShutdown) {
                    return;
                }
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (done.get() == 0 && System.nanoTime() < deadline) {
                    Thread.yield();
                }
            } while (mRendered.size() != before);
        }

        @Override public long deliveredFrames() { return mRendered.size(); }

        @Override public void close() {
            if (mExecutor != null) {
                mExecutor.shutdownNow();
                mExecutor = null;
            }
            mConsumer = null;
        }
    }

    @Parameters(name = "{0}")
    public static Collection<Object[]> factories() {
        return Arrays.asList(new Object[][] {
            {"sync-mirror", new SyncMirrorFactory()},
            {"fake-async", new FakeAsyncFactory()},
        });
    }

    private final String mName;
    private final TerminalFrameConsumerConformance.BackendFactory mFactory;

    public TerminalFrameConsumerConformanceTest(String name,
                                                 TerminalFrameConsumerConformance.BackendFactory factory) {
        mName = name;
        mFactory = factory;
    }

    private TerminalFrameConsumerConformance conformance() {
        return new TerminalFrameConsumerConformance(mFactory, (condition, message) -> {
            if (!condition) {
                fail("[" + mName + "] " + message);
            }
        });
    }

    // ---------------- Layer 1: data protocol ----------------

    @Test
    public void dataProtocol_firstFrameFullRedraw() {
        conformance().dataProtocol_firstFrameFullRedraw();
    }

    @Test
    public void dataProtocol_damageAfterDroppedIntermediate() {
        conformance().dataProtocol_damageAfterDroppedIntermediate();
    }

    // ---------------- Layer 2: consumer lifecycle ----------------

    @Test
    public void lifecycle_foreignGenerationIgnored() {
        conformance().lifecycle_foreignGenerationIgnored();
    }

    @Test
    public void lifecycle_submitAfterDetachRefused() {
        conformance().lifecycle_submitAfterDetachRefused();
    }

    @Test
    public void lifecycle_detachThenReattachNewGeneration() {
        conformance().lifecycle_detachThenReattachNewGeneration();
    }

    @Test
    public void lifecycle_postJoinSilence() {
        conformance().lifecycle_postJoinSilence();
    }
}
