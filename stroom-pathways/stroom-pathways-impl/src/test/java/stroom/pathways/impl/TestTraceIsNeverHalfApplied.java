/*
 * Copyright 2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.pathways.impl;

import stroom.bytebuffer.ByteBufferUtils;
import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.trace.PathwaysDb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applying a trace writes several things: the changes it made to the model, the marker saying this
 * trace has been applied, and the day record saying what it gave that day. A commit used to be able
 * to fall between any two of them, so stopping Stroom at the wrong moment left some on disk and not
 * the rest.
 *
 * <p>What that cost: a widening on disk with no day record behind it is one the next narrowing takes
 * straight back out, because the model then holds something it has not been given. The day quietly
 * loses what happened and nothing on the screen says so.
 */
class TestTraceIsNeverHalfApplied {

    private static final ByteBuffers BYTE_BUFFERS = new ByteBuffers(new ByteBufferFactoryImpl());

    @Test
    void nothingCommitsWhileATraceIsBeingApplied() {
        final AtomicInteger commits = new AtomicInteger();
        final LmdbWriter writer = alwaysReadyToCommit(commits);

        writer.withoutCommitting(() -> {
            for (int row = 0; row < 50; row++) {
                writer.tryCommit();
            }
            assertThat(commits.get())
                    .as("fifty rows written and the threshold passed, and still nothing has landed")
                    .isZero();
        });

        writer.tryCommit();
        assertThat(commits.get())
                .as("and the first chance after the trace takes it")
                .isOne();
    }

    @Test
    void theOutermostCallIsTheOneThatLiftsIt() {
        final AtomicInteger commits = new AtomicInteger();
        final LmdbWriter writer = alwaysReadyToCommit(commits);

        writer.withoutCommitting(() -> {
            writer.withoutCommitting(writer::tryCommit);
            writer.tryCommit();
        });

        assertThat(commits.get())
                .as("leaving an inner call does not open the door")
                .isZero();
    }

    @Test
    void workThatFailsStillLeavesTheDoorAsItFoundIt() {
        final AtomicInteger commits = new AtomicInteger();
        final LmdbWriter writer = alwaysReadyToCommit(commits);

        try {
            writer.withoutCommitting(() -> {
                throw new IllegalStateException("the trace could not be applied");
            });
        } catch (final IllegalStateException e) {
            // The caller aborts; what matters is that the next writer is not left deferring.
        }

        writer.tryCommit();
        assertThat(commits.get()).isOne();
    }

    @Test
    void everyCommitTakesWhatWasLeftWithIt(@TempDir final Path tempDir) {
        // Against a real environment, because what is being tested is what commit() itself does -
        // a writer with commit() stubbed out cannot show it.
        try (final PathwaysDb db = PathwaysDb.create(tempDir, BYTE_BUFFERS, false)) {
            try (final LmdbWriter writer = db.createWriter()) {
                final List<String> order = new ArrayList<>();
                writer.beforeCommit(() -> {
                    order.add("left with it");
                    put(db, writer, "pending");
                });
                put(db, writer, "already written");
                order.add("commit");
                writer.commit();

                assertThat(order)
                        .as("what was left goes in before the commit that ends the turn, not after")
                        .containsExactly("commit", "left with it");
            }
            assertThat(rows(db))
                    .as("and both are on disk")
                    .containsExactlyInAnyOrder("already written", "pending");
        }
    }

    @Test
    void whatWasLeftGoesInWhateverEndsTheTurn(@TempDir final Path tempDir) {
        // The commit nobody asked for: a trace throws, the hold leaves through its try-with-resources
        // and the writer closes. That used to land the model changes with the day records still in
        // memory.
        try (final PathwaysDb db = PathwaysDb.create(tempDir, BYTE_BUFFERS, false)) {
            try (final LmdbWriter writer = db.createWriter()) {
                writer.beforeCommit(() -> put(db, writer, "pending"));
                put(db, writer, "already written");
            }
            assertThat(rows(db))
                    .as("closing the writer is a commit like any other")
                    .containsExactlyInAnyOrder("already written", "pending");
        }
    }

    @Test
    void theFlushCannotAskForACommitOfItsOwn(@TempDir final Path tempDir) {
        // What it writes is rows, and every row written offers the writer a commit. Left open, that
        // is the flush committing part way through itself, which is the thing it exists to stop.
        try (final PathwaysDb db = PathwaysDb.create(tempDir, BYTE_BUFFERS, false)) {
            try (final LmdbWriter writer = db.createWriter()) {
                writer.beforeCommit(() -> {
                    put(db, writer, "first");
                    writer.tryCommit();
                    put(db, writer, "second");
                });
                put(db, writer, "already written");
                writer.commit();
            }
            assertThat(rows(db))
                    .as("both halves of the flush land, so nothing committed between them")
                    .containsExactlyInAnyOrder("already written", "first", "second");
        }
    }

    private static void put(final PathwaysDb db, final LmdbWriter writer, final String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer key = ByteBuffer.allocateDirect(bytes.length);
        key.put(bytes).flip();
        final ByteBuffer value = ByteBuffer.allocateDirect(1);
        value.put((byte) 1).flip();
        db.getObservedValues().insert(writer, key, value);
    }

    private static List<String> rows(final PathwaysDb db) {
        final List<String> found = new ArrayList<>();
        db.getObservedValues().iterate((key, value) ->
                found.add(new String(ByteBufferUtils.toBytes(key), StandardCharsets.UTF_8)));
        return found;
    }

    // A writer that would commit on every row, so the test does not have to write ten thousand of
    // them to reach the threshold, and that counts commits instead of making them. Nothing here opens
    // an environment: what is being tested is when the decision to commit is taken, not the commit.
    private static LmdbWriter alwaysReadyToCommit(final AtomicInteger commits) {
        // Real locks because the constructor takes one; the environment and the commit listener are
        // never reached, because commit is overridden.
        return new LmdbWriter(null, new ReentrantLock(), null, new ReentrantLock()) {
            @Override
            public boolean shouldCommit() {
                return true;
            }

            @Override
            public void commit() {
                commits.incrementAndGet();
            }
        };
    }
}
