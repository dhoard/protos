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
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only NIO Filesystem backend confined to one complete directory tree.
 *
 * <p>I024-C adds D046 direct-child no-follow observation and recursive immutable capture; after
 * D171 capture is reachable only through host/runtime custody, never a guest selector. Source
 * traversal stays relative to pinned {@code SecureDirectoryStream} handles; regular bytes stream
 * into implementation-managed backing rather than accumulating the complete payload in heap.
 */
public final class ProtosNioReadOnlyTreeFilesystemBackend
        implements ProtosStandardFilesystemProtocol.Backend, AutoCloseable {
    private static final int MAX_READ_CHUNK = 64 * 1024;

    private final Path authorityRoot;
    private final SecureDirectoryStream<Path> secureRoot;
    private boolean closed;

    public ProtosNioReadOnlyTreeFilesystemBackend(Path authorityRoot) throws IOException {
        this.authorityRoot =
                Objects.requireNonNull(authorityRoot, "authorityRoot")
                        .toAbsolutePath()
                        .normalize();
        DirectoryStream<Path> opened = Files.newDirectoryStream(this.authorityRoot);
        SecureDirectoryStream<Path> secure = secureDirectoryStream(opened);
        if (secure == null) {
            opened.close();
        }
        this.secureRoot = secure;
    }

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

        List<String> components = permittedRelativeComponents(path);
        if (closed
                || secureRoot == null
                || components == null
                || !readOnlyExisting(options)) {
            completion.failed();
            return () -> {};
        }

        SeekableByteChannel channel = null;
        ArrayList<SecureDirectoryStream<Path>> openedDirectories = new ArrayList<>();
        try {
            SecureDirectoryStream<Path> current = secureRoot;
            for (int index = 0; index < components.size() - 1; index++) {
                SecureDirectoryStream<Path> next =
                        current.newDirectoryStream(
                                nativeComponent(components.get(index)),
                                LinkOption.NOFOLLOW_LINKS);
                openedDirectories.add(next);
                current = next;
            }

            channel =
                    current.newByteChannel(
                            nativeComponent(components.get(components.size() - 1)),
                            Set.<OpenOption>of(
                                    StandardOpenOption.READ,
                                    LinkOption.NOFOLLOW_LINKS));

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
        } finally {
            closeDirectories(openedDirectories);
        }
        return () -> {};
    }


    @Override
    public ProtosFilesystemTreeObservationFlow.Cancellation entries(
            ProtosPathComponents path,
            ProtosFilesystemTreeObservationFlow.EntriesCompletion completion) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(completion, "completion");

        List<String> components = permittedDirectoryComponents(path);
        if (closed || secureRoot == null || components == null) {
            completion.failed();
            return () -> {};
        }

        ArrayList<SecureDirectoryStream<Path>> openedDirectories = new ArrayList<>();
        List<ProtosFilesystemTreeObservationFlow.Entry> result;
        try {
            SecureDirectoryStream<Path> directory =
                    selectDirectory(components, openedDirectories);
            result = observeEntries(directory);
        } catch (IOException | RuntimeException failure) {
            completion.failed();
            return () -> {};
        } finally {
            closeDirectories(openedDirectories);
        }

        completion.succeeded(result);
        return () -> {};
    }

    /** Host-only exact root capture used when custody must outlive the source backend. */
    ProtosNioCapturedTreeFilesystemBackend captureRootForHostCustody() throws IOException {
        if (closed || secureRoot == null) {
            throw new IOException("secure selected-root capture unavailable");
        }
        return captureSelectedDirectory(List.of());
    }

    private ProtosNioCapturedTreeFilesystemBackend captureSelectedDirectory(
            List<String> components) throws IOException {
        ArrayList<SecureDirectoryStream<Path>> openedDirectories = new ArrayList<>();
        try (ProtosNioCapturedTreeFilesystemBackend.Builder builder =
                new ProtosNioCapturedTreeFilesystemBackend.Builder()) {
            SecureDirectoryStream<Path> directory =
                    selectDirectory(components, openedDirectories);
            ProtosNioCapturedTreeFilesystemBackend.DirectoryNode root =
                    captureDirectory(directory, builder);
            return builder.complete(root);
        } finally {
            closeDirectories(openedDirectories);
        }
    }

    private SecureDirectoryStream<Path> selectDirectory(
            List<String> components,
            List<SecureDirectoryStream<Path>> openedDirectories)
            throws IOException {
        SecureDirectoryStream<Path> current =
                secureRoot.newDirectoryStream(
                        nativeComponent("."), LinkOption.NOFOLLOW_LINKS);
        openedDirectories.add(current);
        for (String name : components) {
            SecureDirectoryStream<Path> next =
                    current.newDirectoryStream(nativeComponent(name), LinkOption.NOFOLLOW_LINKS);
            openedDirectories.add(next);
            current = next;
        }
        return current;
    }

    private List<ProtosFilesystemTreeObservationFlow.Entry> observeEntries(
            SecureDirectoryStream<Path> directory)
            throws IOException {
        ArrayList<ProtosFilesystemTreeObservationFlow.Entry> result = new ArrayList<>();
        HashSet<String> exactNames = new HashSet<>();
        try {
            for (Path observed : directory) {
                String name = exactChildName(observed);
                if (!exactNames.add(name)) {
                    throw new IOException("duplicate exact direct-child name");
                }
                result.add(
                        new ProtosFilesystemTreeObservationFlow.Entry(
                                name, classify(directory, name)));
            }
        } catch (DirectoryIteratorException failure) {
            throw failure.getCause();
        }
        return List.copyOf(result);
    }

    private ProtosNioCapturedTreeFilesystemBackend.DirectoryNode captureDirectory(
            SecureDirectoryStream<Path> root,
            ProtosNioCapturedTreeFilesystemBackend.Builder builder)
            throws IOException {
        Deque<CaptureFrame> stack = new ArrayDeque<>();
        stack.push(new CaptureFrame(root, null, false));
        try {
            while (!stack.isEmpty()) {
                CaptureFrame frame = stack.peek();
                if (frame.iterator().hasNext()) {
                    Path observed = frame.iterator().next();
                    String name = exactChildName(observed);
                    if (frame.children().containsKey(name)) {
                        throw new IOException("duplicate exact captured child name");
                    }

                    ProtosFilesystemTreeObservationFlow.EntryKind kind =
                            classify(frame.directory(), name);
                    switch (kind) {
                        case REGULAR -> {
                            try (SeekableByteChannel source =
                                    frame.directory()
                                            .newByteChannel(
                                                    nativeComponent(name),
                                                    Set.<OpenOption>of(
                                                            StandardOpenOption.READ,
                                                            LinkOption.NOFOLLOW_LINKS))) {
                                frame.children()
                                        .put(
                                                name,
                                                ProtosNioCapturedTreeFilesystemBackend.EntryNode
                                                        .regular(
                                                                builder.captureBlob(source)));
                            }
                        }
                        case DIRECTORY -> {
                            SecureDirectoryStream<Path> child =
                                    frame.directory()
                                            .newDirectoryStream(
                                                    nativeComponent(name),
                                                    LinkOption.NOFOLLOW_LINKS);
                            try {
                                stack.push(new CaptureFrame(child, name, true));
                            } catch (RuntimeException frameFailure) {
                                closeSilently(child);
                                throw frameFailure;
                            }
                        }
                        case LINK, OTHER ->
                                frame.children()
                                        .put(
                                                name,
                                                ProtosNioCapturedTreeFilesystemBackend.EntryNode
                                                        .opaque(kind));
                    }
                    continue;
                }

                ProtosNioCapturedTreeFilesystemBackend.DirectoryNode completed =
                        new ProtosNioCapturedTreeFilesystemBackend.DirectoryNode(
                                frame.children());
                stack.pop();
                if (frame.closeWhenDone()) {
                    closeSilently(frame.directory());
                }
                if (stack.isEmpty()) {
                    return completed;
                }
                stack.peek()
                        .children()
                        .put(
                                frame.nameInParent(),
                                ProtosNioCapturedTreeFilesystemBackend.EntryNode.directory(
                                        completed));
            }
        } catch (DirectoryIteratorException failure) {
            throw failure.getCause();
        } finally {
            while (!stack.isEmpty()) {
                CaptureFrame frame = stack.pop();
                if (frame.closeWhenDone()) {
                    closeSilently(frame.directory());
                }
            }
        }
        throw new IOException("capture traversal ended without a root result");
    }

    private ProtosFilesystemTreeObservationFlow.EntryKind classify(
            SecureDirectoryStream<Path> directory, String name)
            throws IOException {
        BasicFileAttributeView view =
                directory.getFileAttributeView(
                        nativeComponent(name),
                        BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("basic no-follow attributes unavailable");
        }
        BasicFileAttributes attributes = view.readAttributes();
        if (attributes.isSymbolicLink()) {
            return ProtosFilesystemTreeObservationFlow.EntryKind.LINK;
        }
        if (attributes.isDirectory()) {
            return ProtosFilesystemTreeObservationFlow.EntryKind.DIRECTORY;
        }
        if (attributes.isRegularFile()) {
            return ProtosFilesystemTreeObservationFlow.EntryKind.REGULAR;
        }
        return ProtosFilesystemTreeObservationFlow.EntryKind.OTHER;
    }

    private String exactChildName(Path observed) throws IOException {
        Path fileName = observed.getFileName();
        if (fileName == null) {
            throw new IOException("directory entry has no exact child name");
        }
        String name = fileName.toString();
        if (!portableNativeComponent(name)) {
            throw new IOException("directory entry is not one representable Path component");
        }
        return name;
    }

    private List<String> permittedDirectoryComponents(ProtosPathComponents path) {
        ArrayList<String> result = new ArrayList<>(path.components().size());
        for (String name : path.components()) {
            if (!portableNativeComponent(name)) {
                return null;
            }
            result.add(name);
        }
        return List.copyOf(result);
    }

    private record CaptureFrame(
            SecureDirectoryStream<Path> directory,
            String nameInParent,
            boolean closeWhenDone,
            Iterator<Path> iterator,
            LinkedHashMap<String, ProtosNioCapturedTreeFilesystemBackend.EntryNode> children) {
        CaptureFrame(
                SecureDirectoryStream<Path> directory,
                String nameInParent,
                boolean closeWhenDone) {
            this(
                    Objects.requireNonNull(directory, "directory"),
                    nameInParent,
                    closeWhenDone,
                    directory.iterator(),
                    new LinkedHashMap<>());
        }
    }

    private static void closeSilently(SecureDirectoryStream<Path> directory) {
        if (directory == null) {
            return;
        }
        try {
            directory.close();
        } catch (IOException ignored) {
            // Source traversal handles remain implementation-owned acquisition machinery.
        }
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

    private List<String> permittedRelativeComponents(ProtosPathComponents path) {
        if (path.components().isEmpty()) {
            return null;
        }
        ArrayList<String> result = new ArrayList<>(path.components().size());
        for (String name : path.components()) {
            if (!portableNativeComponent(name)) {
                return null;
            }
            result.add(name);
        }
        return List.copyOf(result);
    }

    private boolean portableNativeComponent(String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return false;
        }
        Path candidate = authorityRoot.getFileSystem().getPath(name);
        return !candidate.isAbsolute()
                && candidate.getNameCount() == 1
                && candidate.getParent() == null
                && candidate.toString().equals(name);
    }

    private Path nativeComponent(String name) {
        return authorityRoot.getFileSystem().getPath(name);
    }

    private static boolean readOnlyExisting(ProtosFilesystemOpenOptions options) {
        return options.readAccess()
                && !options.writeAccess()
                && options.creation() == ProtosFilesystemOpenOptions.Creation.EXISTING
                && !options.truncateInitialContent();
    }

    private static SecureDirectoryStream<Path> secureDirectoryStream(
            DirectoryStream<Path> stream) {
        if (!(stream instanceof SecureDirectoryStream<?> secure)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> result = (SecureDirectoryStream<Path>) secure;
        return result;
    }

    private static void closeDirectories(List<SecureDirectoryStream<Path>> directories) {
        for (int index = directories.size() - 1; index >= 0; index--) {
            try {
                directories.get(index).close();
            } catch (IOException ignored) {
                // The selected File channel already owns its stable resource binding.
            }
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

    private static final class ReadOnlyResource implements ProtosFileFlow.ReadableResource {
        private final SeekableByteChannel channel;
        private boolean closed;

        ReadOnlyResource(SeekableByteChannel channel) {
            this.channel = Objects.requireNonNull(channel, "channel");
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
                // Runtime cleanup cannot create a new portable File result.
            }
        }
    }
}
