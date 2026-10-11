/*
 * THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
 * ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
 * DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
 * DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
 * OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS OF
 * THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY
 * OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT" ACCOMPANYING
 * THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT ACCOMPANY THIS
 * FILE, A COPY OF THE LICENSE MAY ALSO BE OBTAINED AT THE FOLLOWING WEB SITE:
 * https://github.com/guillermomolina/protos
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 */
package com.guillermomolina.protos.execution;

import com.guillermomolina.protos.runtime.ProtosFileFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions;
import com.guillermomolina.protos.runtime.ProtosFilesystemTreeObservationFlow;
import com.guillermomolina.protos.runtime.ProtosPathComponents;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * I024-C immutable captured-tree backend.
 *
 * <p>Directory/name/kind metadata remains immutable in memory while regular-file bytes spill into
 * private implementation-managed temporary backing. The backing path is never exposed through
 * Protos. Backend views and open Files retain internal leases; {@link Cleaner} reclamation removes
 * backing after the last such lease disappears. This is implementation custody, not a public
 * Filesystem close/release protocol.
 */
final class ProtosNioCapturedTreeFilesystemBackend
        implements ProtosStandardFilesystemProtocol.CapturedBackend {
    private static final Cleaner CLEANER = Cleaner.create();
    private static final int MAX_READ_CHUNK = 64 * 1024;

    static final class DirectoryNode {
        private final Map<String, EntryNode> children;

        DirectoryNode(Map<String, EntryNode> children) {
            Objects.requireNonNull(children, "children");
            this.children =
                    Collections.unmodifiableMap(new LinkedHashMap<>(children));
        }

        Map<String, EntryNode> children() {
            return children;
        }
    }

    static final class EntryNode {
        private final ProtosFilesystemTreeObservationFlow.EntryKind kind;
        private final DirectoryNode directory;
        private final String blobId;

        private EntryNode(
                ProtosFilesystemTreeObservationFlow.EntryKind kind,
                DirectoryNode directory,
                String blobId) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.directory = directory;
            this.blobId = blobId;
        }

        static EntryNode regular(String blobId) {
            return new EntryNode(
                    ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR,
                    null,
                    Objects.requireNonNull(blobId, "blobId"));
        }

        static EntryNode directory(DirectoryNode directory) {
            return new EntryNode(
                    ProtosFilesystemTreeObservationFlow.EntryKind.DIRECTORY,
                    Objects.requireNonNull(directory, "directory"),
                    null);
        }

        static EntryNode opaque(ProtosFilesystemTreeObservationFlow.EntryKind kind) {
            if (kind != ProtosFilesystemTreeObservationFlow.EntryKind.LINK
                    && kind != ProtosFilesystemTreeObservationFlow.EntryKind.OTHER) {
                throw new IllegalArgumentException("opaque capture kind must be link or other");
            }
            return new EntryNode(kind, null, null);
        }

        ProtosFilesystemTreeObservationFlow.EntryKind kind() {
            return kind;
        }

        DirectoryNode directory() {
            return directory;
        }

        String blobId() {
            return blobId;
        }
    }

    static final class Builder implements AutoCloseable {
        private final Path directory;
        private long nextBlob;
        private boolean transferred;

        Builder() throws IOException {
            directory = Files.createTempDirectory("protos-captured-tree-");
        }

        String captureBlob(SeekableByteChannel source) throws IOException {
            Objects.requireNonNull(source, "source");
            long size = source.size();
            if (size < 0) {
                throw new IOException("negative source size");
            }

            String blobId = String.format("blob-%016x", nextBlob++);
            Path targetPath = directory.resolve(blobId);
            boolean complete = false;
            try (SeekableByteChannel target =
                    Files.newByteChannel(
                            targetPath,
                            Set.of(
                                    StandardOpenOption.WRITE,
                                    StandardOpenOption.CREATE_NEW))) {
                source.position(0L);
                long remaining = size;
                ByteBuffer buffer = ByteBuffer.allocate(MAX_READ_CHUNK);
                while (remaining > 0) {
                    buffer.clear();
                    buffer.limit((int) Math.min(buffer.capacity(), remaining));
                    int read = source.read(buffer);
                    if (read <= 0) {
                        throw new IOException(
                                "source did not provide its captured finite byte extent");
                    }
                    remaining -= read;
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        int written = target.write(buffer);
                        if (written <= 0) {
                            throw new IOException("capture backing write made no progress");
                        }
                    }
                }
                complete = true;
            } finally {
                if (!complete) {
                    Files.deleteIfExists(targetPath);
                }
            }
            return blobId;
        }

        ProtosNioCapturedTreeFilesystemBackend complete(DirectoryNode root) {
            if (transferred) {
                throw new IllegalStateException("capture builder already transferred");
            }
            SharedBacking backing = new SharedBacking(directory);
            ProtosNioCapturedTreeFilesystemBackend backend =
                    new ProtosNioCapturedTreeFilesystemBackend(backing, root);
            transferred = true;
            return backend;
        }

        @Override
        public void close() {
            if (!transferred) {
                deleteTree(directory);
            }
        }
    }

    private static final class SharedBacking {
        private final Path directory;
        private int leases;
        private boolean deleted;

        SharedBacking(Path directory) {
            this.directory = Objects.requireNonNull(directory, "directory");
        }

        synchronized Lease retain() {
            if (deleted) {
                throw new IllegalStateException("captured backing already reclaimed");
            }
            leases++;
            return new Lease(this);
        }

        synchronized Path blob(String blobId) {
            if (deleted) {
                throw new IllegalStateException("captured backing already reclaimed");
            }
            return directory.resolve(blobId);
        }

        private void release() {
            Path toDelete = null;
            synchronized (this) {
                if (leases <= 0) {
                    return;
                }
                leases--;
                if (leases == 0 && !deleted) {
                    deleted = true;
                    toDelete = directory;
                }
            }
            if (toDelete != null) {
                deleteTree(toDelete);
            }
        }
    }

    private static final class Lease implements Runnable {
        private final SharedBacking backing;
        private final AtomicBoolean released = new AtomicBoolean();

        Lease(SharedBacking backing) {
            this.backing = Objects.requireNonNull(backing, "backing");
        }

        boolean active() {
            return !released.get();
        }

        @Override
        public void run() {
            if (released.compareAndSet(false, true)) {
                backing.release();
            }
        }
    }

    private static final class ResourceCleanup implements Runnable {
        private final SeekableByteChannel channel;
        private final Lease lease;
        private final AtomicBoolean cleaned = new AtomicBoolean();

        ResourceCleanup(SeekableByteChannel channel, Lease lease) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.lease = Objects.requireNonNull(lease, "lease");
        }

        IOException closeAndRelease() {
            if (!cleaned.compareAndSet(false, true)) {
                return null;
            }
            IOException failure = null;
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure = closeFailure;
            } finally {
                lease.run();
            }
            return failure;
        }

        @Override
        public void run() {
            closeAndRelease();
        }
    }

    private final SharedBacking backing;
    private final DirectoryNode root;
    private final Lease backendLease;
    private final Cleaner.Cleanable cleanable;

    private ProtosNioCapturedTreeFilesystemBackend(SharedBacking backing, DirectoryNode root) {
        this.backing = Objects.requireNonNull(backing, "backing");
        this.root = Objects.requireNonNull(root, "root");
        this.backendLease = backing.retain();
        this.cleanable = CLEANER.register(this, backendLease);
    }

    void releaseIfUntransferred() {
        cleanable.clean();
    }

    @Override
    public ProtosFilesystemOpenFlow.Cancellation open(
            ProtosPathComponents path,
            ProtosFilesystemOpenOptions options,
            ProtosStandardFilesystemProtocol.OpenCompletion completion) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(completion, "completion");

        EntryNode selected = resolveEntry(path);
        if (!backendLease.active()
                || selected == null
                || selected.kind() != ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR
                || !readOnlyExisting(options)) {
            completion.failed();
            return () -> {};
        }

        SeekableByteChannel channel = null;
        try {
            channel =
                    Files.newByteChannel(
                            backing.blob(selected.blobId()),
                            Set.of(StandardOpenOption.READ));
            ReadOnlyResource resource = new ReadOnlyResource(channel, backing);
            channel = null;
            completion.succeeded(
                    resource,
                    new ProtosFileFlow.Capabilities(
                            true, false, false, false, false, false),
                    resource::closeSilently);
        } catch (IOException | RuntimeException failure) {
            closeSilently(channel);
            completion.failed();
        }
        return () -> {};
    }

    @Override
    public ProtosFilesystemTreeObservationFlow.Cancellation entries(
            ProtosPathComponents path,
            ProtosFilesystemTreeObservationFlow.EntriesCompletion completion) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(completion, "completion");

        DirectoryNode directory = resolveDirectory(path);
        if (!backendLease.active() || directory == null) {
            completion.failed();
            return () -> {};
        }

        ArrayList<ProtosFilesystemTreeObservationFlow.Entry> result =
                new ArrayList<>(directory.children().size());
        for (Map.Entry<String, EntryNode> child : directory.children().entrySet()) {
            result.add(
                    new ProtosFilesystemTreeObservationFlow.Entry(
                            child.getKey(), child.getValue().kind()));
        }
        completion.succeeded(List.copyOf(result));
        return () -> {};
    }

    private DirectoryNode resolveDirectory(ProtosPathComponents path) {
        List<String> components = normalRelativeComponents(path, true);
        if (components == null) {
            return null;
        }
        DirectoryNode current = root;
        for (String name : components) {
            EntryNode entry = current.children().get(name);
            if (entry == null
                    || entry.kind()
                            != ProtosFilesystemTreeObservationFlow.EntryKind.DIRECTORY) {
                return null;
            }
            current = entry.directory();
        }
        return current;
    }

    /**
     * Reads the exact captured regular blob through a transient lease on the same shared backing.
     * The per-call channel keeps reads independent; no backend-wide lock or cursor is involved.
     */
    @Override
    public byte[] readRegularResource(ProtosPackageResourceName name) throws IOException {
        Objects.requireNonNull(name, "name");
        EntryNode selected = resolveEntry(name.components());
        if (selected == null
                || selected.kind() != ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR) {
            throw new IOException("package resource is not an exact captured regular resource");
        }

        Lease readLease;
        try {
            if (!backendLease.active()) {
                throw new IllegalStateException("captured backend released");
            }
            readLease = backing.retain();
        } catch (IllegalStateException released) {
            throw new IOException("captured package backing is released", released);
        }
        try {
            if (!backendLease.active()) {
                throw new IOException("captured package backing is released");
            }
            try (SeekableByteChannel channel =
                    Files.newByteChannel(
                            backing.blob(selected.blobId()), Set.of(StandardOpenOption.READ))) {
                return readExactly(channel);
            }
        } catch (IllegalStateException released) {
            throw new IOException("captured package backing is released", released);
        } finally {
            readLease.run();
        }
    }

    private static byte[] readExactly(SeekableByteChannel channel) throws IOException {
        long size = channel.size();
        if (size < 0 || size > Integer.MAX_VALUE - 8) {
            throw new IOException("captured package resource is too large for a host read");
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) size);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) <= 0) {
                throw new IOException("captured package resource ended before its extent");
            }
        }
        return buffer.array();
    }

    private EntryNode resolveEntry(ProtosPathComponents path) {
        List<String> components = normalRelativeComponents(path, false);
        if (components == null) {
            return null;
        }
        return resolveEntry(components);
    }

    private EntryNode resolveEntry(List<String> components) {
        DirectoryNode current = root;
        for (int index = 0; index < components.size(); index++) {
            EntryNode entry = current.children().get(components.get(index));
            if (entry == null) {
                return null;
            }
            if (index == components.size() - 1) {
                return entry;
            }
            if (entry.kind()
                    != ProtosFilesystemTreeObservationFlow.EntryKind.DIRECTORY) {
                return null;
            }
            current = entry.directory();
        }
        return null;
    }

    private static List<String> normalRelativeComponents(
            ProtosPathComponents path, boolean allowEmpty) {
        if (!allowEmpty && path.components().isEmpty()) {
            return null;
        }
        ArrayList<String> names = new ArrayList<>(path.components().size());
        for (String name : path.components()) {
            if (name.isEmpty() || name.equals(".") || name.equals("..")) {
                return null;
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    private static boolean readOnlyExisting(ProtosFilesystemOpenOptions options) {
        return options.readAccess()
                && !options.writeAccess()
                && options.creation() == ProtosFilesystemOpenOptions.Creation.EXISTING
                && !options.truncateInitialContent();
    }

    private static final class ReadOnlyResource implements ProtosFileFlow.ReadableResource {
        private final ResourceCleanup cleanup;
        private final Cleaner.Cleanable cleanable;
        private boolean closed;

        ReadOnlyResource(SeekableByteChannel channel, SharedBacking backing) {
            this.cleanup = new ResourceCleanup(channel, backing.retain());
            this.cleanable = CLEANER.register(this, cleanup);
        }

        @Override
        public synchronized ProtosFileFlow.Cancellation readAt(
                long position,
                int maxBytes,
                ProtosFileFlow.ReadCompletion completion) {
            Objects.requireNonNull(completion, "completion");
            if (closed
                    || position < 0
                    || maxBytes <= 0) {
                completion.failed();
                return () -> {};
            }

            int requested = Math.min(maxBytes, MAX_READ_CHUNK);
            ByteBuffer buffer = ByteBuffer.allocate(requested);
            try {
                cleanup.channel.position(position);
                int read = cleanup.channel.read(buffer);
                if (read < 0) {
                    completion.eof();
                } else if (read == 0) {
                    completion.failed();
                } else {
                    byte[] bytes = new byte[read];
                    buffer.flip();
                    buffer.get(bytes);
                    completion.data(bytes);
                }
            } catch (IOException | RuntimeException failure) {
                completion.failed();
            }
            return () -> {};
        }

        @Override
        public synchronized void close(ProtosFileFlow.CloseCompletion completion) {
            Objects.requireNonNull(completion, "completion");
            if (closed) {
                completion.succeeded();
                return;
            }
            closed = true;
            boolean released =
                    ProtosHostResourceClose.closePhysically(
                            () -> {
                                IOException failure = cleanup.closeAndRelease();
                                cleanable.clean();
                                if (failure != null) {
                                    throw failure;
                                }
                            });
            if (released) {
                completion.succeeded();
            } else {
                completion.failed();
            }
        }

        void closeSilently() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            cleanable.clean();
        }
    }

    private static void closeSilently(SeekableByteChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Failed captured-file acquisition retains no Protos-visible resource custody.
        }
    }

    private static void deleteTree(Path root) {
        try {
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(
                                Path file, BasicFileAttributes attributes)
                                throws IOException {
                            Files.deleteIfExists(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(
                                Path directory, IOException failure)
                                throws IOException {
                            if (failure != null) {
                                throw failure;
                            }
                            Files.deleteIfExists(directory);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | RuntimeException ignored) {
            // Managed backing reclamation is not a Protos-visible Filesystem operation.
        }
    }
}
