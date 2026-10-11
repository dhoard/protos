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
import com.guillermomolina.protos.runtime.ProtosPathComponents;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Confined direct-child NIO Filesystem backend with independent read, staging-write, and
 * namespace authority.
 *
 * <p>Existing-file read opens delegate to {@link ProtosNioReadOnlyFilesystemBackend}; namespace
 * mutation is separately restricted to an explicit set of direct-child names. Optional
 * staging-write authority is a third independent allowlist and accepts only write-only positioned
 * createNew opens.
 * These allowlists therefore do not accidentally grant one another's authority.
 *
 * <p>Namespace operations retain a {@link SecureDirectoryStream} for the authority root and use
 * only relative entry operations on that pinned directory. They never pre-classify a mutable final
 * name before acting on it. {@code move} supplies the D042 atomic replacement attempt; a provider
 * that cannot replace the selected target atomically fails the operation instead. {@code deleteFile}
 * acts on the final entry itself, including a symbolic link rather than its referent, and never
 * performs recursive deletion.
 *
 * <p>This backend is intentionally general and contains no package-manifest, lockfile, staging-name,
 * or package-command policy. Hosts choose the readable, staging-writable, and namespace-mutable
 * direct-child sets when provisioning a Filesystem capability.
 */
public final class ProtosNioConfinedFilesystemBackend
        implements ProtosStandardFilesystemProtocol.Backend, AutoCloseable {
    private final Path authorityRoot;
    private final Set<String> writableDirectChildren;
    private final Set<String> mutableDirectChildren;
    private final ProtosNioReadOnlyFilesystemBackend readBackend;
    private final SecureDirectoryStream<Path> secureRoot;
    private boolean closed;

    public ProtosNioConfinedFilesystemBackend(
            Path authorityRoot,
            Set<String> readableDirectChildren,
            Set<String> mutableDirectChildren)
            throws IOException {
        this(authorityRoot, readableDirectChildren, Set.of(), mutableDirectChildren);
    }

    public ProtosNioConfinedFilesystemBackend(
            Path authorityRoot,
            Set<String> readableDirectChildren,
            Set<String> writableDirectChildren,
            Set<String> mutableDirectChildren)
            throws IOException {
        this.authorityRoot =
                Objects.requireNonNull(authorityRoot, "authorityRoot")
                        .toAbsolutePath()
                        .normalize();

        LinkedHashSet<String> writableNames = new LinkedHashSet<>();
        for (String name :
                Objects.requireNonNull(writableDirectChildren, "writableDirectChildren")) {
            requireDirectChildName(name);
            writableNames.add(name);
        }
        this.writableDirectChildren = Set.copyOf(writableNames);

        LinkedHashSet<String> mutableNames = new LinkedHashSet<>();
        for (String name : Objects.requireNonNull(mutableDirectChildren, "mutableDirectChildren")) {
            requireDirectChildName(name);
            mutableNames.add(name);
        }
        this.mutableDirectChildren = Set.copyOf(mutableNames);

        this.readBackend =
                new ProtosNioReadOnlyFilesystemBackend(
                        this.authorityRoot,
                        Objects.requireNonNull(readableDirectChildren, "readableDirectChildren"));

        SecureDirectoryStream<Path> secured = null;
        try {
            DirectoryStream<Path> opened = Files.newDirectoryStream(this.authorityRoot);
            secured = secureDirectoryStream(opened);
            if (secured == null) {
                opened.close();
            }
        } catch (IOException | RuntimeException failure) {
            try {
                readBackend.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        this.secureRoot = secured;
    }

    /** True only when namespace operations can stay relative to a pinned authority directory. */
    public boolean secureNamespaceConfinementAvailable() {
        return secureRoot != null;
    }

    @Override
    public ProtosFilesystemOpenFlow.Cancellation open(
            ProtosPathComponents path,
            ProtosFilesystemOpenOptions options,
            ProtosStandardFilesystemProtocol.OpenCompletion completion) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(completion, "completion");

        String writableChild = permittedDirectChild(path, writableDirectChildren);
        if (writableChild != null && writeOnlyCreateNew(options)) {
            return openWritableCreateNew(writableChild, completion);
        }
        return readBackend.open(path, options, completion);
    }

    private ProtosFilesystemOpenFlow.Cancellation openWritableCreateNew(
            String child, ProtosStandardFilesystemProtocol.OpenCompletion completion) {
        if (closed || secureRoot == null) {
            completion.failed();
            return () -> {};
        }

        if (!completion.commitPortableEffect()) {
            return () -> {};
        }

        SeekableByteChannel channel = null;
        try {
            Set<OpenOption> openOptions =
                    Set.of(
                            StandardOpenOption.WRITE,
                            StandardOpenOption.CREATE_NEW,
                            LinkOption.NOFOLLOW_LINKS);
            channel = secureRoot.newByteChannel(nativeChild(child), openOptions);
            WritableResource resource = new WritableResource(channel);
            channel = null;
            completion.succeeded(
                    resource,
                    new ProtosFileFlow.Capabilities(
                            false, true, false, false, false, false),
                    resource::closeSilently);
        } catch (IOException | RuntimeException failure) {
            closeSilently(channel);
            completion.failed();
        }
        return () -> {};
    }

    @Override
    public ProtosFilesystemNamespaceMutationFlow.Cancellation replace(
            ProtosPathComponents sourcePath,
            ProtosPathComponents targetPath,
            ProtosFilesystemNamespaceMutationFlow.MutationCompletion completion) {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(targetPath, "targetPath");
        Objects.requireNonNull(completion, "completion");

        String sourceChild = permittedMutableDirectChild(sourcePath);
        String targetChild = permittedMutableDirectChild(targetPath);
        if (closed || secureRoot == null || sourceChild == null || targetChild == null) {
            completion.failed();
            return () -> {};
        }

        completion.commitPortableEffect(
                () ->
                        secureRoot.move(
                                nativeChild(sourceChild), secureRoot, nativeChild(targetChild)));
        return () -> {};
    }

    @Override
    public ProtosFilesystemNamespaceMutationFlow.Cancellation remove(
            ProtosPathComponents path,
            ProtosFilesystemNamespaceMutationFlow.MutationCompletion completion) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(completion, "completion");

        String child = permittedMutableDirectChild(path);
        if (closed || secureRoot == null || child == null) {
            completion.failed();
            return () -> {};
        }

        completion.commitPortableEffect(() -> secureRoot.deleteFile(nativeChild(child)));
        return () -> {};
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        IOException failure = null;
        if (secureRoot != null) {
            try {
                secureRoot.close();
            } catch (IOException closeFailure) {
                failure = closeFailure;
            }
        }
        try {
            readBackend.close();
        } catch (IOException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private String permittedMutableDirectChild(ProtosPathComponents path) {
        return permittedDirectChild(path, mutableDirectChildren);
    }

    private static String permittedDirectChild(ProtosPathComponents path, Set<String> allowedNames) {
        if (path.components().size() != 1) {
            return null;
        }
        String name = path.components().get(0);
        return allowedNames.contains(name) ? name : null;
    }

    private static boolean writeOnlyCreateNew(ProtosFilesystemOpenOptions options) {
        return !options.readAccess()
                && options.writeAccess()
                && options.creation() == ProtosFilesystemOpenOptions.Creation.CREATE_NEW
                && !options.truncateInitialContent();
    }

    private Path nativeChild(String name) {
        return authorityRoot.getFileSystem().getPath(name);
    }

    private void requireDirectChildName(String name) {
        Objects.requireNonNull(name, "mutable direct-child name");
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("invalid mutable direct-child name");
        }
        Path candidate = authorityRoot.getFileSystem().getPath(name);
        if (candidate.isAbsolute()
                || candidate.getNameCount() != 1
                || candidate.getParent() != null
                || !candidate.toString().equals(name)) {
            throw new IllegalArgumentException("mutable name is not one native direct child");
        }
    }

    private static void closeSilently(SeekableByteChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Failed acquisition retains no Protos-visible resource custody.
        }
    }

    private static final class WritableResource implements ProtosFileFlow.WritableResource {
        private final SeekableByteChannel channel;
        private boolean closed;

        WritableResource(SeekableByteChannel channel) {
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        @Override
        public synchronized ProtosFileFlow.Cancellation writeAt(
                long position, byte[] bytes, ProtosFileFlow.WriteCompletion completion) {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(completion, "completion");
            if (closed || position < 0) {
                completion.failed(0);
                return () -> {};
            }
            if (bytes.length == 0) {
                completion.succeeded();
                return () -> {};
            }

            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            try {
                channel.position(position);
                if (!completion.commitFirstContribution()) {
                    return () -> {};
                }
                while (buffer.hasRemaining()) {
                    int written = channel.write(buffer);
                    if (written <= 0) {
                        completion.failed(buffer.position());
                        return () -> {};
                    }
                }
                completion.succeeded();
            } catch (IOException | RuntimeException failure) {
                completion.failed(buffer.position());
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
            if (ProtosHostResourceClose.closePhysically(channel)) {
                completion.succeeded();
            } else {
                completion.failed();
            }
        }

        void closeSilently() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                channel.close();
            } catch (IOException ignored) {
                // Backend custody cleanup cannot create a new portable File result.
            }
        }
    }

    private static SecureDirectoryStream<Path> secureDirectoryStream(DirectoryStream<Path> stream) {
        if (!(stream instanceof SecureDirectoryStream<?> secure)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> result = (SecureDirectoryStream<Path>) secure;
        return result;
    }
}
