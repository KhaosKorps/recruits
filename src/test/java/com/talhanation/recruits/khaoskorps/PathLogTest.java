package com.talhanation.recruits.khaoskorps;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The path log's rows and its writer (Nena's M2 to M4), without a server. */
class PathLogTest {

    private static PathLogRecord record() {
        return new PathLogRecord(7, 2, 1200, "a1b2c3d4", PathLogRecord.NAV_RECRUIT, PathLogRecord.FOLLOW,
                12.34, -2, 1536, 3, 0L);
    }

    @Test
    void aPathRowCarriesEveryFieldInHeaderOrder() {
        PathLogRecord record = record();
        record.searched(PathLogRecord.WORKER, 412, 850);
        record.result(false, 9, 4.06, -3);
        String row = record.finish(4, PathLogRecord.APPLIED);
        assertEquals("7,2,1200,a1b2c3d4,R,F,123,-2,1536,3,412,0,9,41,-3,W,850,4,A", row);
        assertEquals(PathLogRecord.HEADER.split(",").length, row.split(",", -1).length);
    }

    @Test
    void aRowIsWrittenOnceAndAFailedSearchIsAnError() {
        PathLogRecord record = record();
        record.failed();
        String row = record.finish(-1, PathLogRecord.NO_PATH);
        assertTrue(row.endsWith(",-1,E"), row);
        assertNull(record.finish(5, PathLogRecord.APPLIED), "a second finish must not give a second row");
    }

    @Test
    void aSearchThatNeverRanSaysSo() {
        String row = record().finish(-1, PathLogRecord.NO_PATH);
        assertEquals("7,2,1200,a1b2c3d4,R,F,123,-2,1536,3,-1,0,0,-1,0,-,-1,-1,N", row);
    }

    @Test
    void aSummaryRowCountsItsWindowAndStartsAgain() {
        PathLogSummary summary = new PathLogSummary();
        summary.goalStarted();
        summary.tick(true, 2.0, false);
        summary.stalled();
        summary.goalStopped();
        summary.tick(true, 6.0, true);
        summary.tick(true, -1, true);
        summary.tick(false, 9.0, true);
        summary.gaveUp();
        summary.reused();
        String row = summary.row(5000, "a1b2c3d4", 300, 0);
        assertEquals("5000,a1b2c3d4,4,3,2,1,1,1,1,40,60,300,0", row);
        assertEquals(PathLogSummary.HEADER.split(",").length, row.split(",", -1).length);
        assertEquals("5100,a1b2c3d4,0,0,0,0,0,0,0,-1,-1,300,7", summary.row(5100, "a1b2c3d4", 300, 7));
    }

    @Test
    void theSettingsRowHasEveryFollowSettingAndKeepsPointsInEveryLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            String row = PathLogSettingsRow.row(1, 40, FollowSettings.DEFAULTS, true, 1);
            String[] header = PathLogSettingsRow.HEADER.split(",");
            assertEquals(FollowSettings.class.getRecordComponents().length + 4, header.length);
            assertEquals(header.length, row.split(",", -1).length, row);
            assertTrue(PathLogSettingsRow.HEADER.contains(",max_fall_distance,"), PathLogSettingsRow.HEADER);
            assertTrue(PathLogSettingsRow.HEADER.endsWith(",path_log,use_async_pathfinding,async_pathfinding_threads"), PathLogSettingsRow.HEADER);
            assertTrue(row.contains(",1.05,"), "follow speed with a point: " + row);
            assertTrue(row.contains(",ORIGINAL,"), row);
            assertTrue(row.endsWith(",0,1,1"), "path log off, async on, one thread: " + row);
        } finally {
            Locale.setDefault(before);
        }
    }

    /** A sink that keeps rows in memory, optionally holding the writer at its first row until released. */
    private static final class MemorySink implements PathLogWriter.Sink {
        final List<String> rows = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch firstRow = new CountDownLatch(1);
        final CountDownLatch release;
        final int failAt;
        volatile boolean closed;

        MemorySink(boolean hold, int failAt) {
            this.release = new CountDownLatch(hold ? 1 : 0);
            this.failAt = failAt;
        }

        @Override
        public void write(int file, String row) throws IOException {
            if (this.rows.size() + 1 == this.failAt) throw new IOException("disk full");
            this.rows.add(file + ":" + row);
            this.firstRow.countDown();
            try {
                this.release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }

    @Test
    void rowsReachTheirFilesInOrderAndCloseWritesEverythingQueued() {
        MemorySink sink = new MemorySink(false, -1);
        PathLogWriter writer = new PathLogWriter(sink, 64, 50, e -> { });
        for (int i = 0; i < 20; i++) assertTrue(writer.offer(i % 3, "row" + i));
        writer.close(2000);
        assertEquals(20, sink.rows.size());
        assertEquals("0:row0", sink.rows.get(0));
        assertEquals("1:row19", sink.rows.get(19));
        assertTrue(sink.closed);
        assertEquals(0, writer.dropped());
    }

    @Test
    void aFullQueueDropsAndCountsRowsWithoutEverWaiting() throws InterruptedException {
        MemorySink sink = new MemorySink(true, -1);
        PathLogWriter writer = new PathLogWriter(sink, 10, 50, e -> { });
        writer.offer(0, "first");
        assertTrue(sink.firstRow.await(5, TimeUnit.SECONDS));
        long started = System.nanoTime();
        int accepted = 0;
        for (int i = 0; i < 25; i++) if (writer.offer(0, "row" + i)) accepted++;
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(10, accepted, "the queue holds 10 while the writer is held at its first row");
        assertEquals(15, writer.dropped());
        assertTrue(tookMillis < 1000, "offering took " + tookMillis + " ms");
        sink.release.countDown();
        writer.close(2000);
        assertEquals(11, sink.rows.size());
    }

    @Test
    void aSinkErrorIsReportedOnceAndLaterRowsAreDropped() throws InterruptedException {
        MemorySink sink = new MemorySink(false, 3);
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch reported = new CountDownLatch(1);
        PathLogWriter writer = new PathLogWriter(sink, 64, 50, e -> {
            errors.incrementAndGet();
            reported.countDown();
        });
        for (int i = 0; i < 5; i++) writer.offer(0, "row" + i);
        assertTrue(reported.await(5, TimeUnit.SECONDS));
        assertTrue(writer.hasFailed());
        assertFalse(writer.offer(0, "after"));
        writer.close(2000);
        assertEquals(1, errors.get());
        assertEquals(2, sink.rows.size());
        assertTrue(sink.closed);
    }

    /** Rows still queued when the sink fails are thrown away, and counted as dropped. */
    @Test
    void aSinkErrorCountsTheRowsItThrowsAway() throws InterruptedException {
        MemorySink sink = new MemorySink(true, 3);
        CountDownLatch reported = new CountDownLatch(1);
        PathLogWriter writer = new PathLogWriter(sink, 64, 50, e -> reported.countDown());
        writer.offer(0, "first");
        assertTrue(sink.firstRow.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 5; i++) assertTrue(writer.offer(0, "row" + i));
        sink.release.countDown();
        assertTrue(reported.await(5, TimeUnit.SECONDS));
        writer.close(2000);
        assertEquals(2, sink.rows.size(), "rows written before the failure");
        assertEquals(4, writer.dropped(), "the failing row and the 3 still queued are thrown away and counted");
    }

    /** Closing without waiting returns at once, even with the writer busy, and every queued row is still written. */
    @Test
    void closingWithoutWaitingReturnsAtOnceAndStillWritesEverything() throws InterruptedException {
        MemorySink sink = new MemorySink(true, -1);
        PathLogWriter writer = new PathLogWriter(sink, 64, 50, e -> { });
        writer.offer(0, "first");
        assertTrue(sink.firstRow.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 5; i++) writer.offer(0, "row" + i);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> writer.close(0));
        sink.release.countDown();
        for (int wait = 0; wait < 100 && !sink.closed; wait++) Thread.sleep(50);
        assertTrue(sink.closed, "the sink was never closed");
        assertEquals(6, sink.rows.size());
    }

    /**
     * Dada's volume model: 20 following recruits re-planning every 10 ticks make 40 path rows a
     * second, plus 4 summary rows. A whole minute of that fits in the queue with the writer stalled.
     */
    @Test
    void aMinuteOfTwentyFollowersFitsInTheQueueWithTheWriterStalled() throws InterruptedException {
        MemorySink sink = new MemorySink(true, -1);
        PathLogWriter writer = new PathLogWriter(sink, PathLogWriter.CAPACITY, 50, e -> { });
        writer.offer(0, "first");
        assertTrue(sink.firstRow.await(5, TimeUnit.SECONDS));
        int rows = 60 * (40 + 4);
        for (int i = 0; i < rows; i++) writer.offer(i % 2, "row" + i);
        assertEquals(0, writer.dropped(), rows + " rows in a minute");
        sink.release.countDown();
        writer.close(5000);
        assertEquals(rows + 1, sink.rows.size());
    }

    @Test
    void theFileSinkWritesEachFileWithItsHeader(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        PathLogWriter.FileSink sink = new PathLogWriter.FileSink(dir.resolve("recruits-paths"), "20261114T120000Z",
                new String[]{"paths", "recruits"}, new String[]{"a,b", "c"});
        sink.write(0, "1,2");
        sink.write(1, "3");
        sink.close();
        assertEquals(List.of("a,b", "1,2"), Files.readAllLines(dir.resolve("recruits-paths/20261114T120000Z-paths.csv"), StandardCharsets.UTF_8));
        assertEquals(List.of("c", "3"), Files.readAllLines(dir.resolve("recruits-paths/20261114T120000Z-recruits.csv"), StandardCharsets.UTF_8));
    }
}
