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
import com.guillermomolina.protos.runtime.ProtosFilesystemNamespaceMutationFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenFlow;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions;
import com.guillermomolina.protos.runtime.ProtosFilesystemTreeObservationFlow;
import com.guillermomolina.protos.runtime.ProtosPathComponents;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * PLAT054-3E3: Context and Process custody of the default Filesystem of one embedded Process
 * ({@code PROCESS_IO.md}, Embedding filesystem grant and Embedding filesystem base).
 *
 * <p>This object is the backend of the bootstrap-local {@code filesystem} capability; it exists
 * only when the Context effectively allows file I/O and its working-directory base was safely
 * established ({@link #provisionOrNull}). Every operation goes through {@link TruffleFile}s
 * derived from that base, so the provider configured for the Context mediates each resolution,
 * acquisition, and namespace transition, and its restrictions bind the capability for its whole
 * lifetime. No path is ever converted to a host {@code java.nio.file.Path}, so a virtual,
 * restricted, or custom provider cannot be bypassed.
 *
 * <p>Confinement: the authority of the capability is the namespace the provider authorizes, and
 * its base is the working directory captured once at provisioning (there is no mutable
 * Process-global working directory). Each semantic Path component is resolved as exactly one
 * child name: a component the provider would reinterpret as several names, a root, or a prefix
 * change is rejected. Links, aliases, mounts, and concurrent namespace changes are resolved by the
 * provider inside a single provider operation, so they cannot reach anything the provider does not
 * authorize, and no operation verifies one resolution and then acts on another.
 *
 * <p>Atomicity: {@code existing} and {@code createNew} are single provider acquisitions; {@code
 * create} is an existing-open with an exclusive-create fallback that re-selects the existing
 * target after a concurrent creator; truncate-on-open is the provider's open-time truncation;
 * {@code replace} is one {@link StandardCopyOption#ATOMIC_MOVE} transition and fails rather than
 * degrading to copy/delete; {@code remove} deletes the final entry itself and never recursively.
 * A provider that cannot meet one of these guarantees reports a failure that the standard flows
 * surface as an ordinary {@code IOError}.
 *
 * <p>Custody: a provisioned but unused capability holds only the base. The set of open Files is
 * created on the first successful open. Closing is idempotent, is reached by Process termination
 * and by Context finalization or disposal, refuses every later operation, and closes every File
 * still open, so no resource regains usability after the terminal boundary. Operations run
 * synchronously on the invoking Actor thread: no worker, poller, or queue is ever created.
 */
final class ProtosEmbeddedFilesystemCustody implements ProtosStandardFilesystemProtocol.Backend {
    private static final int MAX_READ_CHUNK = 64 * 1024;
    private static final int ZERO_FILL_CHUNK = 64 * 1024;
    /** Bound on open-or-create re-selection when concurrent creators and removers keep racing. */
    private static final int MAX_CREATE_SELECTIONS = 16;

    private static final ProtosFilesystemOpenFlow.Cancellation NO_OPEN_CANCELLATION = () -> {};
    private static final ProtosFilesystemNamespaceMutationFlow.Cancellation
            NO_MUTATION_CANCELLATION = () -> {};
    private static final ProtosFilesystemTreeObservationFlow.Cancellation
            NO_ENTRIES_CANCELLATION = () -> {};
    private static final ProtosFileFlow.Cancellation NO_FILE_CANCELLATION = () -> {};

    private final TruffleFile base;
    private final String separator;
    /* Guarded by this; the open-File set is created by the first successful open. */
    private Set<ChannelResource> openResources;
    private boolean closed;
    private int acquisitions;

    private ProtosEmbeddedFilesystemCustody(TruffleFile base, String separator) {
        this.base = base;
        this.separator = separator;
    }

    /**
     * The custody of the default Filesystem, or {@code null} when the Context does not effectively
     * allow file I/O. When it does, the base is the Context's effective working directory within
     * the configured provider; a base that cannot be safely represented aborts the bootstrap with
     * a {@link ProtosEmbeddingException} before any guest source executes.
     */
    static ProtosEmbeddedFilesystemCustody provisionOrNull(TruffleLanguage.Env env) {
        Objects.requireNonNull(env, "env");
        if (!env.isFileIOAllowed()) {
            return null;
        }
        TruffleFile base;
        String separator;
        try {
            base = env.getCurrentWorkingDirectory();
            separator = env.getFileNameSeparator();
            if (base == null || !base.isAbsolute() || separator == null || separator.isEmpty()) {
                throw unsafeBase("the working directory is not an absolute provider path");
            }
        } catch (ProtosEmbeddingException unsafe) {
            throw unsafe;
        } catch (RuntimeException failure) {
            throw unsafeBase(String.valueOf(failure.getMessage()));
        }
        return new ProtosEmbeddedFilesystemCustody(base, separator);
    }

    private static ProtosEmbeddingException unsafeBase(String reason) {
        return new ProtosEmbeddingException(
                "Protos Process bootstrap failed: the default Filesystem base cannot be safely"
                        + " provisioned within the Context filesystem provider ("
                        + reason
                        + ")");
    }

    @Override
    public ProtosFilesystemOpenFlow.Cancellation open(
            ProtosPathComponents path,
            ProtosFilesystemOpenOptions options,
            ProtosStandardFilesystemProtocol.OpenCompletion completion) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(completion, "completion");
        TruffleFile target = resolveOrNull(path);
        if (target == null || isClosed()) {
            completion.failed();
            return NO_OPEN_CANCELLATION;
        }
        SeekableByteChannel channel;
        try {
            channel = acquire(target, options, completion);
        } catch (IOException | RuntimeException failure) {
            completion.failed();
            return NO_OPEN_CANCELLATION;
        }
        if (channel == null) {
            // Cancellation won before any portable effect; nothing was acquired.
            return NO_OPEN_CANCELLATION;
        }
        ChannelResource resource = new ChannelResource(this, channel);
        if (!register(resource)) {
            resource.release();
            completion.failed();
            return NO_OPEN_CANCELLATION;
        }
        completion.succeeded(
                resource,
                new ProtosFileFlow.Capabilities(
                        options.readAccess(),
                        options.writeAccess(),
                        true,
                        true,
                        options.writeAccess(),
                        channel instanceof FileChannel),
                resource::release);
        return NO_OPEN_CANCELLATION;
    }

    /**
     * Selects and acquires the target as FILESYSTEM.md §18.1/§18.2 require. Returns {@code null}
     * when cancellation won the commitment handshake before any effect was published.
     */
    private static SeekableByteChannel acquire(
            TruffleFile target,
            ProtosFilesystemOpenOptions options,
            ProtosStandardFilesystemProtocol.OpenCompletion completion)
            throws IOException {
        ProtosFilesystemOpenOptions.Creation creation = options.creation();
        Set<OpenOption> existingOptions = accessOptions(options);
        if (options.truncateInitialContent()) {
            existingOptions.add(StandardOpenOption.TRUNCATE_EXISTING);
        }
        // Creation needs write access at the provider even when the File exposes only reading.
        Set<OpenOption> createOptions = accessOptions(options);
        createOptions.add(StandardOpenOption.WRITE);
        createOptions.add(StandardOpenOption.CREATE_NEW);

        boolean committed = false;
        for (int selection = 0; selection < MAX_CREATE_SELECTIONS; selection++) {
            if (creation != ProtosFilesystemOpenOptions.Creation.CREATE_NEW) {
                if (options.truncateInitialContent() && !committed) {
                    if (!completion.commitPortableEffect()) {
                        return null;
                    }
                    committed = true;
                }
                try {
                    return target.newByteChannel(existingOptions);
                } catch (NoSuchFileException absent) {
                    if (creation == ProtosFilesystemOpenOptions.Creation.EXISTING) {
                        throw absent;
                    }
                }
            }
            if (!committed) {
                if (!completion.commitPortableEffect()) {
                    return null;
                }
                committed = true;
            }
            try {
                // An exclusive creation is one race-free check-and-create; the new file is empty.
                return target.newByteChannel(createOptions);
            } catch (FileAlreadyExistsException exists) {
                if (creation == ProtosFilesystemOpenOptions.Creation.CREATE_NEW) {
                    throw exists;
                }
                // A concurrent creator won; open-or-create re-selects the then-existing target.
            }
        }
        throw new IOException("concurrent namespace changes prevented an open-or-create selection");
    }

    private static Set<OpenOption> accessOptions(ProtosFilesystemOpenOptions options) {
        Set<OpenOption> result = new HashSet<>();
        if (options.readAccess()) {
            result.add(StandardOpenOption.READ);
        }
        if (options.writeAccess()) {
            result.add(StandardOpenOption.WRITE);
        }
        return result;
    }

    @Override
    public ProtosFilesystemNamespaceMutationFlow.Cancellation replace(
            ProtosPathComponents sourcePath,
            ProtosPathComponents targetPath,
            ProtosFilesystemNamespaceMutationFlow.MutationCompletion completion) {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(completion, "completion");
        TruffleFile source = resolveEntryOrNull(sourcePath);
        TruffleFile target = resolveEntryOrNull(targetPath);
        if (source == null || target == null || isClosed()) {
            completion.failed();
            return NO_MUTATION_CANCELLATION;
        }
        // One atomic provider transition; a provider without it fails instead of copying.
        completion.commitPortableEffect(() -> source.move(target, StandardCopyOption.ATOMIC_MOVE));
        return NO_MUTATION_CANCELLATION;
    }

    @Override
    public ProtosFilesystemNamespaceMutationFlow.Cancellation remove(
            ProtosPathComponents path,
            ProtosFilesystemNamespaceMutationFlow.MutationCompletion completion) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(completion, "completion");
        TruffleFile entry = resolveEntryOrNull(path);
        if (entry == null || isClosed()) {
            completion.failed();
            return NO_MUTATION_CANCELLATION;
        }
        // Deletes the final entry itself (a link, not its referent) and never recursively.
        completion.commitPortableEffect(entry::delete);
        return NO_MUTATION_CANCELLATION;
    }

    @Override
    public ProtosFilesystemTreeObservationFlow.Cancellation entries(
            ProtosPathComponents path,
            ProtosFilesystemTreeObservationFlow.EntriesCompletion completion) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(completion, "completion");
        TruffleFile directory = resolveOrNull(path);
        if (directory == null || isClosed()) {
            completion.failed();
            return NO_ENTRIES_CANCELLATION;
        }
        List<ProtosFilesystemTreeObservationFlow.Entry> observed;
        try {
            observed = observeEntries(directory);
        } catch (IOException | RuntimeException failure) {
            completion.failed();
            return NO_ENTRIES_CANCELLATION;
        }
        completion.succeeded(observed);
        return NO_ENTRIES_CANCELLATION;
    }

    private static List<ProtosFilesystemTreeObservationFlow.Entry> observeEntries(
            TruffleFile directory) throws IOException {
        ArrayList<ProtosFilesystemTreeObservationFlow.Entry> observed = new ArrayList<>();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try (DirectoryStream<TruffleFile> children = directory.newDirectoryStream()) {
            for (TruffleFile child : children) {
                String name = child.getName();
                ProtosFilesystemTreeObservationFlow.EntryKind kind;
                try {
                    kind = classify(child);
                } catch (NoSuchFileException removedWhileObserving) {
                    // An entry removed while the observation is in flight may be omitted.
                    continue;
                }
                if (name == null || !names.add(name)) {
                    throw new IOException("directory observation is not one finite exact-name set");
                }
                // Rejects names that are not one semantic Path component instead of rewriting them.
                observed.add(new ProtosFilesystemTreeObservationFlow.Entry(name, kind));
            }
        }
        return List.copyOf(observed);
    }

    /** One no-follow attribute observation of exactly the named child entry. */
    private static ProtosFilesystemTreeObservationFlow.EntryKind classify(TruffleFile child)
            throws IOException {
        TruffleFile.Attributes attributes =
                child.getAttributes(
                        List.of(
                                TruffleFile.IS_SYMBOLIC_LINK,
                                TruffleFile.IS_DIRECTORY,
                                TruffleFile.IS_REGULAR_FILE),
                        LinkOption.NOFOLLOW_LINKS);
        if (attributes.get(TruffleFile.IS_SYMBOLIC_LINK)) {
            return ProtosFilesystemTreeObservationFlow.EntryKind.LINK;
        }
        if (attributes.get(TruffleFile.IS_DIRECTORY)) {
            return ProtosFilesystemTreeObservationFlow.EntryKind.DIRECTORY;
        }
        if (attributes.get(TruffleFile.IS_REGULAR_FILE)) {
            return ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR;
        }
        return ProtosFilesystemTreeObservationFlow.EntryKind.OTHER;
    }

    /**
     * Resolves a namespace-mutation Path. The empty Path denotes the base itself, which has no
     * final entry component to replace or remove, so it is rejected.
     */
    private TruffleFile resolveEntryOrNull(ProtosPathComponents path) {
        return path.components().isEmpty() ? null : resolveOrNull(path);
    }

    /**
     * Maps each semantic component to exactly one provider child name below the base, or returns
     * {@code null} when the provider cannot represent a component as one child name. Resolution
     * is purely lexical: it performs no I/O, so nothing is checked here and later used elsewhere.
     */
    private TruffleFile resolveOrNull(ProtosPathComponents path) {
        TruffleFile current = base;
        try {
            for (String component : path.components()) {
                if (component.indexOf('/') >= 0
                        || component.indexOf('\0') >= 0
                        || component.contains(separator)) {
                    return null;
                }
                TruffleFile child = current.resolve(component);
                if (!component.equals(child.getName())
                        || !Objects.equals(current.getPath(), child.getParent().getPath())) {
                    return null;
                }
                current = child;
            }
        } catch (RuntimeException unrepresentable) {
            return null;
        }
        return current;
    }

    private synchronized boolean isClosed() {
        return closed;
    }

    private synchronized boolean register(ChannelResource resource) {
        if (closed) {
            return false;
        }
        if (openResources == null) {
            openResources = new HashSet<>();
        }
        openResources.add(resource);
        acquisitions++;
        return true;
    }

    private synchronized void unregister(ChannelResource resource) {
        if (openResources != null) {
            openResources.remove(resource);
        }
    }

    /**
     * Revokes the capability and closes every File still open. Idempotent; never waits on guest
     * work, and a concurrent File operation observes its closed channel as an ordinary failure.
     */
    void close() {
        List<ChannelResource> retired;
        synchronized (this) {
            closed = true;
            retired = openResources == null ? List.of() : List.copyOf(openResources);
            openResources = null;
        }
        for (ChannelResource resource : retired) {
            resource.release();
        }
    }

    synchronized boolean isClosedForTesting() {
        return closed;
    }

    /** Whether the open-File custody set was ever materialized (it is not by provisioning). */
    synchronized boolean operationalStateMaterializedForTesting() {
        return openResources != null;
    }

    synchronized int openResourceCountForTesting() {
        return openResources == null ? 0 : openResources.size();
    }

    synchronized int acquisitionCountForTesting() {
        return acquisitions;
    }

    TruffleFile baseForTesting() {
        return base;
    }

    /**
     * One selected provider resource. The channel is bound to the resource chosen by the open, so
     * later namespace changes never retarget it; positions are explicit per operation.
     */
    private static final class ChannelResource
            implements ProtosFileFlow.ReadableResource,
                    ProtosFileFlow.WritableResource,
                    ProtosFileFlow.SeekableResource,
                    ProtosFileFlow.SizedResource,
                    ProtosFileFlow.TruncatableResource,
                    ProtosFileFlow.SyncableResource {
        private final ProtosEmbeddedFilesystemCustody custody;
        private final SeekableByteChannel channel;
        /* Guarded by this. */
        private boolean closed;

        ChannelResource(ProtosEmbeddedFilesystemCustody custody, SeekableByteChannel channel) {
            this.custody = custody;
            this.channel = channel;
        }

        @Override
        public synchronized ProtosFileFlow.Cancellation readAt(
                long position, int maxBytes, ProtosFileFlow.ReadCompletion completion) {
            Objects.requireNonNull(completion, "completion");
            if (closed || !representable(position) || maxBytes <= 0) {
                completion.failed();
                return NO_FILE_CANCELLATION;
            }
            ByteBuffer buffer = ByteBuffer.allocate(Math.min(maxBytes, MAX_READ_CHUNK));
            try {
                channel.position(position);
                int read = channel.read(buffer);
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
            return NO_FILE_CANCELLATION;
        }

        /**
         * Positioned write (FILESYSTEM.md §18.2.1). A start beyond EOF first fills the logical gap
         * with explicit zeros, because a generic provider channel leaves gap content unspecified;
         * if no byte of the write is then contributed, that tentative growth is truncated away.
         */
        @Override
        public synchronized ProtosFileFlow.Cancellation writeAt(
                long position, byte[] bytes, ProtosFileFlow.WriteCompletion completion) {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(completion, "completion");
            if (closed || !representable(position)) {
                completion.failed(0);
                return NO_FILE_CANCELLATION;
            }
            if (bytes.length == 0) {
                completion.succeeded();
                return NO_FILE_CANCELLATION;
            }
            long start = position;
            long previousSize;
            try {
                previousSize = start > 0 ? channel.size() : 0;
            } catch (IOException | RuntimeException failure) {
                completion.failed(0);
                return NO_FILE_CANCELLATION;
            }
            if (!completion.commitFirstContribution()) {
                return NO_FILE_CANCELLATION;
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            try {
                if (start > previousSize) {
                    fillZeros(previousSize, start);
                }
                channel.position(start);
                while (buffer.hasRemaining()) {
                    if (channel.write(buffer) <= 0) {
                        break;
                    }
                }
            } catch (IOException | RuntimeException failure) {
                // Falls through: the contributed prefix decides the outcome below.
            }
            if (buffer.hasRemaining()) {
                if (buffer.position() == 0 && start > previousSize) {
                    discardTentativeGrowth(previousSize);
                }
                completion.failed(buffer.position());
            } else {
                completion.succeeded();
            }
            return NO_FILE_CANCELLATION;
        }

        private void fillZeros(long from, long until) throws IOException {
            ByteBuffer zeros = ByteBuffer.allocate((int) Math.min(ZERO_FILL_CHUNK, until - from));
            channel.position(from);
            long remaining = until - from;
            while (remaining > 0) {
                zeros.clear();
                zeros.limit((int) Math.min(zeros.capacity(), remaining));
                int written = channel.write(zeros);
                if (written <= 0) {
                    throw new IOException("zero-gap fill made no progress");
                }
                remaining -= written;
            }
        }

        private void discardTentativeGrowth(long previousSize) {
            try {
                channel.truncate(previousSize);
            } catch (IOException | RuntimeException ignored) {
                // The write still reports no contribution; the channel failure is its outcome.
            }
        }

        @Override
        public synchronized ProtosFileFlow.Cancellation endPosition(
                ProtosFileFlow.IntegerCompletion completion) {
            return size(completion);
        }

        @Override
        public synchronized ProtosFileFlow.Cancellation size(
                ProtosFileFlow.IntegerCompletion completion) {
            Objects.requireNonNull(completion, "completion");
            if (closed) {
                completion.failed();
                return NO_FILE_CANCELLATION;
            }
            long size;
            try {
                size = channel.size();
            } catch (IOException | RuntimeException failure) {
                completion.failed();
                return NO_FILE_CANCELLATION;
            }
            completion.succeeded(size);
            return NO_FILE_CANCELLATION;
        }

        /** One provider truncation: never extends, and a no-op commits nothing. */
        @Override
        public synchronized ProtosFileFlow.Cancellation truncate(
                long size, ProtosFileFlow.ChangeCompletion completion) {
            Objects.requireNonNull(completion, "completion");
            if (closed || size < 0) {
                completion.failed();
                return NO_FILE_CANCELLATION;
            }
            try {
                long current = channel.size();
                if (size >= current) {
                    completion.succeeded();
                    return NO_FILE_CANCELLATION;
                }
                if (!completion.commitChange()) {
                    return NO_FILE_CANCELLATION;
                }
                channel.truncate(size);
            } catch (IOException | RuntimeException failure) {
                completion.failed();
                return NO_FILE_CANCELLATION;
            }
            completion.succeeded();
            return NO_FILE_CANCELLATION;
        }

        /** Advertised only for a provider {@link FileChannel}, whose force is a durability barrier. */
        @Override
        public synchronized ProtosFileFlow.Cancellation sync(
                ProtosFileFlow.SyncCompletion completion) {
            Objects.requireNonNull(completion, "completion");
            if (closed || !(channel instanceof FileChannel file)) {
                completion.failed();
                return NO_FILE_CANCELLATION;
            }
            if (!completion.commitDurability()) {
                return NO_FILE_CANCELLATION;
            }
            try {
                file.force(true);
            } catch (IOException | RuntimeException failure) {
                completion.failed();
                return NO_FILE_CANCELLATION;
            }
            completion.succeeded();
            return NO_FILE_CANCELLATION;
        }

        @Override
        public void close(ProtosFileFlow.CloseCompletion completion) {
            Objects.requireNonNull(completion, "completion");
            synchronized (this) {
                if (closed) {
                    completion.succeeded();
                    return;
                }
                closed = true;
            }
            custody.unregister(this);
            if (ProtosHostResourceClose.closePhysically(channel)) {
                completion.succeeded();
            } else {
                completion.failed();
            }
        }

        /**
         * Custody release: an untransferred open result, or revocation at the terminal boundary.
         * It does not take this resource's monitor, so it never waits on an in-flight operation;
         * closing the channel makes that operation fail.
         */
        void release() {
            custody.unregister(this);
            try {
                channel.close();
            } catch (IOException | RuntimeException ignored) {
                // Custody cleanup cannot create a new portable File result.
            }
        }

        private static boolean representable(long position) {
            return position >= 0;
        }
    }
}
