package com.talhanation.recruits.khaoskorps;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Moves the path log's rows off the game's threads: rows go into a bounded queue that never makes
 * the caller wait (a row that doesn't fit is dropped and counted), and one daemon thread writes
 * them to a sink, flushing about once a second. A sink error, or anything else the sink throws, is
 * reported once through {@code onError}; the rows still queued then, and every row after, are
 * dropped and counted. Closing needn't wait: the writer finishes the queued rows, closes the sink
 * and then runs {@code onClosed}. Plain Java, covered by JUnit.
 */
public final class PathLogWriter {

    /** Where rows end up; only the writer thread calls it. */
    public interface Sink {
        void write(int file, String row) throws IOException;

        void flush() throws IOException;

        void close() throws IOException;
    }

    public static final int CAPACITY = 8192;

    private record Row(int file, String text) {
    }

    private static final Object STOP = new Object();

    private final BlockingQueue<Object> queue;
    private final Sink sink;
    private final Consumer<IOException> onError;
    private final Runnable onClosed;
    private final long flushNanos;
    private final AtomicLong dropped = new AtomicLong();
    private volatile long written;
    private volatile boolean failed;
    private volatile boolean stopping;
    private final Thread thread;

    public PathLogWriter(Sink sink, int capacity, long flushMillis, Consumer<IOException> onError) {
        this(sink, capacity, flushMillis, onError, () -> { });
    }

    public PathLogWriter(Sink sink, int capacity, long flushMillis, Consumer<IOException> onError, Runnable onClosed) {
        this.queue = new ArrayBlockingQueue<>(capacity + 1); // the one extra slot is kept for STOP
        this.sink = sink;
        this.onError = onError;
        this.onClosed = onClosed;
        this.flushNanos = TimeUnit.MILLISECONDS.toNanos(flushMillis);
        this.thread = new Thread(this::run, "recruits-path-log");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Queues a row without waiting; false, and counted as dropped, if the queue is full, the sink failed or closing began. */
    public boolean offer(int file, String row) {
        if (this.failed || this.stopping || this.queue.remainingCapacity() <= 1 || !this.queue.offer(new Row(file, row))) {
            this.dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    public long dropped() {
        return this.dropped.get();
    }

    public long written() {
        return this.written;
    }

    public boolean hasFailed() {
        return this.failed;
    }

    /**
     * Stops taking rows; the writer writes those already queued, closes the sink and runs onClosed.
     * Waits up to {@code waitMillis} for that (0: doesn't wait). Never interrupts the writer, so a
     * slow disk can't turn a close into a write error.
     */
    public void close(long waitMillis) {
        this.stopping = true;
        this.queue.offer(STOP); // wakes the writer at once; without it, it notices within a flush interval
        if (waitMillis <= 0) return;
        try {
            this.thread.join(waitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        long lastFlush = System.nanoTime();
        try {
            while (true) {
                Object next = this.queue.poll(this.flushNanos, TimeUnit.NANOSECONDS);
                if (next == STOP || (next == null && this.stopping)) {
                    Object rest;
                    while ((rest = this.queue.poll()) != null) {
                        if (rest instanceof Row row) this.write(row);
                    }
                    break;
                }
                if (next instanceof Row row) this.write(row);
                if (System.nanoTime() - lastFlush >= this.flushNanos) {
                    this.sink.flush();
                    lastFlush = System.nanoTime();
                }
            }
            this.sink.flush();
        } catch (IOException e) {
            this.fail(e);
        } catch (RuntimeException e) {
            this.fail(new IOException("the path log's sink failed", e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                this.sink.close();
            } catch (IOException e) {
                this.fail(e);
            } catch (RuntimeException e) {
                this.fail(new IOException("the path log's sink failed to close", e));
            }
            // A row offered just as closing began can land after the drain; it is counted, not lost silently.
            Object left;
            while ((left = this.queue.poll()) != null) {
                if (left instanceof Row) this.dropped.incrementAndGet();
            }
            this.onClosed.run();
        }
    }

    private void write(Row row) throws IOException {
        try {
            this.sink.write(row.file(), row.text());
        } catch (IOException | RuntimeException e) {
            this.dropped.incrementAndGet();
            throw e;
        }
        this.written++;
    }

    private void fail(IOException e) {
        if (this.failed) return;
        this.failed = true;
        Object left;
        while ((left = this.queue.poll()) != null) {
            if (left instanceof Row) this.dropped.incrementAndGet();
        }
        this.onError.accept(e);
    }

    /** Files named {@code <prefix>-<name>.csv} in a directory, each opened with its header row. */
    public static final class FileSink implements Sink {

        private final Writer[] writers;

        public FileSink(Path directory, String prefix, String[] names, String[] headers) throws IOException {
            Files.createDirectories(directory);
            this.writers = new Writer[names.length];
            try {
                for (int i = 0; i < names.length; i++) {
                    this.writers[i] = new BufferedWriter(Files.newBufferedWriter(directory.resolve(prefix + "-" + names[i] + ".csv"),
                            StandardCharsets.UTF_8), 1 << 16);
                    this.writers[i].write(headers[i]);
                    this.writers[i].write('\n');
                }
            } catch (IOException e) {
                this.close();
                throw e;
            }
        }

        @Override
        public void write(int file, String row) throws IOException {
            this.writers[file].write(row);
            this.writers[file].write('\n');
        }

        @Override
        public void flush() throws IOException {
            for (Writer writer : this.writers) writer.flush();
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (Writer writer : this.writers) {
                if (writer == null) continue;
                try {
                    writer.close();
                } catch (IOException e) {
                    if (first == null) first = e;
                }
            }
            if (first != null) throw first;
        }
    }
}
