package com.termux.terminal;

/** Drains a bounded amount of PTY input and reports whether another batch is required. */
final class TerminalInputQueueDrain {

    interface Consumer {
        void accept(byte[] buffer, int length);
    }

    static final class Result {
        private final int mBytesRead;
        private final boolean mHasMore;

        Result(int bytesRead, boolean hasMore) {
            mBytesRead = bytesRead;
            mHasMore = hasMore;
        }

        int getBytesRead() {
            return mBytesRead;
        }

        boolean hasMore() {
            return mHasMore;
        }
    }

    private TerminalInputQueueDrain() {
    }

    static Result drain(ByteQueue queue, byte[] buffer, int byteBudget, Consumer consumer) {
        if (byteBudget <= 0) throw new IllegalArgumentException("byteBudget <= 0");
        if (buffer.length == 0) throw new IllegalArgumentException("empty buffer");

        int totalBytesRead = 0;
        while (totalBytesRead < byteBudget) {
            int bytesRead = queue.read(buffer, 0,
                Math.min(buffer.length, byteBudget - totalBytesRead), false);
            if (bytesRead <= 0) break;
            consumer.accept(buffer, bytesRead);
            totalBytesRead += bytesRead;
        }

        return new Result(totalBytesRead, queue.hasData());
    }
}
