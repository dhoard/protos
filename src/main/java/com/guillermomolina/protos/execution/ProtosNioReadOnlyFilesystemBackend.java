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
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenOptions;
import com.guillermomolina.protos.runtime.ProtosFilesystemOpenFlow;
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
 * Read-only, direct-child NIO Filesystem backend with fail-closed confinement.
 *
 * <p>This is a general host backend for an already-provisioned Filesystem capability. It does not
 * know about package manifests, lockfiles, registries, stores, or package semantics. The caller
 * chooses the authority root and exact direct-child names that this capability may open.
 *
 * <p>When the host filesystem provider supplies {@link SecureDirectoryStream}, the backend retains
 * that directory handle for the capability lifetime and opens children relative to it with
 * {@link LinkOption#NOFOLLOW_LINKS}. Providers without a secure directory handle fail closed for
 * every open rather than weakening Protos Filesystem confinement.
 */
public final class ProtosNioReadOnlyFilesystemBackend
        implements ProtosStandardFilesystemProtocol.Backend, AutoCloseable {
    private static final int MAX_READ_CHUNK = 64 * 1024;

    private final Path authorityRoot;
    private final Set<String> allowedDirectChildren;
    private final SecureDirectoryStream<Path> secureRoot;
    private boolean closed;

    public ProtosNioReadOnlyFilesystemBackend(
            Path authorityRoot, Set<String> allowedDirectChildren) throws IOException {
        this.authorityRoot =
                Objects.requireNonNull(authorityRoot, "authorityRoot")
                        .toAbsolutePath()
                        .normalize();

        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String name :
                Objects.requireNonNull(allowedDirectChildren, "allowedDirectChildren")) {
            requireDirectChildName(name);
            names.add(name);
        }
        this.allowedDirectChildren = Set.copyOf(names);

        DirectoryStream<Path> opened = Files.newDirectoryStream(this.authorityRoot);
        SecureDirectoryStream<Path> secure = secureDirectoryStream(opened);
        if (secure == null) {
            opened.close();
        }
        this.secureRoot = secure;
    }

    /** True only when this host provider can preserve the required confined child-open boundary. */
    public boolean secureConfinementAvailable() {
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

        String child = permittedDirectChild(path);
        if (closed || secureRoot == null || child == null || !readOnlyExisting(options)) {
            completion.failed();
            return () -> {};
        }

        SeekableByteChannel channel = null;
        try {
            Set<OpenOption> openOptions =
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            channel =
                    secureRoot.newByteChannel(
                            authorityRoot.getFileSystem().getPath(child), openOptions);
            ReadOnlyResource resource = new ReadOnlyResource(channel);
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
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (secureRoot != null) {
            secureRoot.close();
        }
    }

    private String permittedDirectChild(ProtosPathComponents path) {
        if (path.components().size() != 1) {
            return null;
        }
        String name = path.components().get(0);
        return allowedDirectChildren.contains(name) ? name : null;
    }

    private static boolean readOnlyExisting(ProtosFilesystemOpenOptions options) {
        return options.readAccess()
                && !options.writeAccess()
                && options.creation() == ProtosFilesystemOpenOptions.Creation.EXISTING
                && !options.truncateInitialContent();
    }

    private void requireDirectChildName(String name) {
        Objects.requireNonNull(name, "allowed direct-child name");
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("invalid allowed direct-child name");
        }
        Path candidate = authorityRoot.getFileSystem().getPath(name);
        if (candidate.isAbsolute()
                || candidate.getNameCount() != 1
                || candidate.getParent() != null
                || !candidate.toString().equals(name)) {
            throw new IllegalArgumentException("allowed name is not one native direct child");
        }
    }

    private static SecureDirectoryStream<Path> secureDirectoryStream(DirectoryStream<Path> stream) {
        if (!(stream instanceof SecureDirectoryStream<?> secure)) {
            return null;
        }
        /*
         * DirectoryStream<Path> fixes the element type at this call site. Java cannot express the
         * generic instanceof check directly because the type parameter is erased.
         */
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> result = (SecureDirectoryStream<Path>) secure;
        return result;
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

    private static final class ReadOnlyResource implements ProtosFileFlow.ReadableResource {
        private final SeekableByteChannel channel;
        private boolean closed;

        ReadOnlyResource(SeekableByteChannel channel) {
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        @Override
        public synchronized ProtosFileFlow.Cancellation readAt(
                long position, int maxBytes, ProtosFileFlow.ReadCompletion completion) {
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
}
