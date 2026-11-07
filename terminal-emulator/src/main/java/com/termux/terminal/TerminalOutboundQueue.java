package com.termux.terminal;

import java.util.ArrayDeque;

/**
 * Bounded parser-output queue: non-blocking whole-chunk admission on the parser
 * side, blocking FIFO reads on the PTY writer side.
 *
 * <p>This replaces the old fixed 4 KiB {@link ByteQueue} on
 * {@code TerminalSession.mTerminalToProcessIOQueue}. The old queue made the
 * parser thread wait for the PTY writer, which could park the parser inside the
 * emulator monitor and stall every model getter. Here an offer never waits: it
 * either accepts the whole chunk (copying it, so the caller may immediately
 * reuse its buffer), rejects it because a bound would be exceeded
 * ({@link OfferResult#FULL}), or rejects it because the queue was closed
 * ({@link OfferResult#CLOSED}). A rejected offer leaves the queue contents
 * unchanged, so callers can turn overload into an explicit transport failure
 * instead of silently truncating input.</p>
 *
 * <p>Chunks are kept whole and copied out in FIFO order. A single read may span
 * several chunks, which keeps the writer's 4 KiB copy pattern working exactly
 * as before. {@link #close()} clears buffered output and wakes a blocked
 * reader, which then observes {@code -1}.</p>
 */
final class TerminalOutboundQueue {

    enum OfferResult {
        /** The whole chunk was copied into the queue. */
        ACCEPTED,
        /** The queue is closed; nothing was accepted. */
        CLOSED,
        /** Accepting the chunk would exceed a payload or chunk-count bound; nothing was accepted. */
        FULL
    }

    /** 1 MiB of buffered parser output, enough for a large paste while still finite. */
    static final int DEFAULT_MAX_PAYLOAD_BYTES = 1024 * 1024;
    /** At most 4096 separately admitted chunks, so tiny writes cannot exhaust heap. */
    static final int DEFAULT_MAX_CHUNKS = 4096;

    private final ArrayDeque<byte[]> mChunks = new ArrayDeque<>();
    private final int mMaxPayloadBytes;
    private final int mMaxChunks;

    /** Offset into the first chunk; only the head chunk may be partially consumed. */
    private int mHeadOffset;
    /** Total bytes still buffered, including the partially consumed head chunk. */
    private int mPayloadBytes;
    private boolean mOpen = true;

    TerminalOutboundQueue() {
        this(DEFAULT_MAX_PAYLOAD_BYTES, DEFAULT_MAX_CHUNKS);
    }

    TerminalOutboundQueue(int maxPayloadBytes, int maxChunks) {
        if (maxPayloadBytes <= 0) throw new IllegalArgumentException("maxPayloadBytes <= 0");
        if (maxChunks <= 0) throw new IllegalArgumentException("maxChunks <= 0");
        mMaxPayloadBytes = maxPayloadBytes;
        mMaxChunks = maxChunks;
    }

    /**
     * Attempt to accept a whole chunk without waiting.
     *
     * <p>A zero-length offer is an explicit no-op: it copies nothing, consumes
     * no queue entry and reports success unless the queue is already closed.</p>
     */
    OfferResult offer(byte[] data, int offset, int count) {
        if (offset < 0 || count < 0 || count > data.length - offset) {
            throw new IllegalArgumentException("offset/count outside buffer");
        }
        synchronized (this) {
            if (!mOpen) return OfferResult.CLOSED;
            if (count == 0) return OfferResult.ACCEPTED;
            if (count > mMaxPayloadBytes - mPayloadBytes || mChunks.size() >= mMaxChunks) {
                return OfferResult.FULL;
            }
            byte[] copy = new byte[count];
            System.arraycopy(data, offset, copy, 0, count);
            mChunks.addLast(copy);
            mPayloadBytes += count;
            notifyAll();
            return OfferResult.ACCEPTED;
        }
    }

    /**
     * Legacy boolean view of {@link #offer}: true only when the whole chunk was
     * accepted, false when it was closed or rejected. Production code uses
     * {@link #offer} so overload is distinguishable from shutdown.
     */
    boolean write(byte[] data, int offset, int count) {
        return offer(data, offset, count) == OfferResult.ACCEPTED;
    }

    int read(byte[] buffer, boolean block) {
        return read(buffer, 0, buffer.length, block);
    }

    /**
     * Copy up to {@code length} bytes in FIFO order. With {@code block} set,
     * waits while the queue is open and empty; returns -1 once closed. A
     * zero-length request returns 0 without waiting.
     */
    int read(byte[] buffer, int offset, int length, boolean block) {
        if (offset < 0 || length < 0 || length > buffer.length - offset) {
            throw new IllegalArgumentException("offset/length outside buffer");
        }
        if (length == 0) return 0;
        synchronized (this) {
            while (mPayloadBytes == 0 && mOpen) {
                if (!block) return 0;
                try {
                    wait();
                } catch (InterruptedException e) {
                    // The session owns shutdown; interruption is treated as a spurious wake-up.
                }
            }
            if (!mOpen) return -1;

            int totalRead = 0;
            while (length > 0 && !mChunks.isEmpty()) {
                byte[] head = mChunks.peekFirst();
                int available = head.length - mHeadOffset;
                int bytesToCopy = Math.min(length, available);
                System.arraycopy(head, mHeadOffset, buffer, offset, bytesToCopy);
                mHeadOffset += bytesToCopy;
                offset += bytesToCopy;
                length -= bytesToCopy;
                totalRead += bytesToCopy;
                mPayloadBytes -= bytesToCopy;
                if (mHeadOffset == head.length) {
                    mChunks.removeFirst();
                    mHeadOffset = 0;
                }
            }
            return totalRead;
        }
    }

    /** Close and clear the queue, waking any blocked reader. */
    void close() {
        synchronized (this) {
            mOpen = false;
            mChunks.clear();
            mHeadOffset = 0;
            mPayloadBytes = 0;
            notifyAll();
        }
    }

    synchronized boolean hasData() {
        return mPayloadBytes > 0;
    }

    synchronized int availableBytes() {
        return mPayloadBytes;
    }

    synchronized int chunkCount() {
        return mChunks.size();
    }

    synchronized boolean isOpen() {
        return mOpen;
    }
}
