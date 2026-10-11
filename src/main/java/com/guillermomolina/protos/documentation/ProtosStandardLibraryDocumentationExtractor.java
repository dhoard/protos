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

package com.guillermomolina.protos.documentation;

import static com.guillermomolina.protos.documentation.ProtosDocumentationModel.Artifact;
import static com.guillermomolina.protos.documentation.ProtosDocumentationModel.Callable;
import static com.guillermomolina.protos.documentation.ProtosDocumentationModel.Generator;
import static com.guillermomolina.protos.documentation.ProtosDocumentationModel.Module;
import static com.guillermomolina.protos.documentation.ProtosDocumentationModel.RepositoryRevisionScope;
import static com.guillermomolina.protos.documentation.ProtosDocumentationModel.SourceProvenance;
import static com.guillermomolina.protos.documentation.ProtosDocumentationModel.SourceRange;
import static com.guillermomolina.protos.documentation.ProtosDocumentationModel.StandardModuleIdentity;
import static com.guillermomolina.protos.documentation.ProtosDocumentationModel.Symbol;
import static com.guillermomolina.protos.documentation.ProtosDocumentationModel.SymbolIdentity;

import com.guillermomolina.protos.parser.ProtosParser;
import com.guillermomolina.protos.parser.ast.SurfaceCall;
import com.guillermomolina.protos.parser.ast.SurfaceClosure;
import com.guillermomolina.protos.parser.ast.SurfaceIntrinsic;
import com.guillermomolina.protos.parser.ast.SurfaceLiteral;
import com.guillermomolina.protos.parser.ast.SurfaceMember;
import com.guillermomolina.protos.parser.ast.SurfaceExpression;
import com.guillermomolina.protos.parser.ast.SurfaceGroup;
import com.guillermomolina.protos.parser.ast.SurfaceName;
import com.guillermomolina.protos.parser.ast.SurfaceParameter;
import com.guillermomolina.protos.parser.ast.SurfaceSequence;
import com.guillermomolina.protos.parser.ast.SurfaceSlotCreation;
import com.guillermomolina.protos.source.SourceSpan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * D061/D064/D067 Standard Library documentation extraction path.
 *
 * <p>This tool is intentionally Standard-Library-specific. It statically reads
 * canonical {@code protos/lib} source, excludes physical Core bootstrap source,
 * uses the real Protos parser for structural facts, delegates source-local
 * documentation ownership and association to the D138 documentation layer,
 * and never executes a module.</p>
 *
 * <p>D064/D067 publication remains limited to the top-level Standard Library
 * surface; valid nested D138 documentation is accepted without creating an
 * additional published symbol.</p>
 */
public final class ProtosStandardLibraryDocumentationExtractor {
    private static final String REPOSITORY = "guillermomolina/protos";
    private static final String GENERATOR_NAME = "protos-stdlib-doc-extractor";
    private static final String GENERATOR_VERSION = "1";
    private static final String SOURCE_SUFFIX = ".protos";
    private static final Set<String> WINDOWS_RESERVED_SEGMENTS =
            Set.of(
                    "CON", "PRN", "AUX", "NUL",
                    "COM1", "COM2", "COM3", "COM4", "COM5",
                    "COM6", "COM7", "COM8", "COM9",
                    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5",
                    "LPT6", "LPT7", "LPT8", "LPT9");

    private ProtosStandardLibraryDocumentationExtractor() {
    }

    public record Extraction(Artifact artifact, Coverage coverage) {
        public Extraction {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(coverage, "coverage");
        }
    }

    public record Coverage(
            int moduleCount,
            int documentedModuleCount,
            List<String> missingModuleDocumentation,
            int symbolCount,
            int documentedSymbolCount,
            List<String> missingSymbolDocumentation) {
        public Coverage {
            if (moduleCount < 0 || documentedModuleCount < 0
                    || documentedModuleCount > moduleCount) {
                throw new IllegalArgumentException("invalid module documentation coverage");
            }
            if (symbolCount < 0 || documentedSymbolCount < 0
                    || documentedSymbolCount > symbolCount) {
                throw new IllegalArgumentException("invalid symbol documentation coverage");
            }
            missingModuleDocumentation =
                    sortedCopy(missingModuleDocumentation, "missingModuleDocumentation");
            missingSymbolDocumentation =
                    sortedCopy(missingSymbolDocumentation, "missingSymbolDocumentation");
            if (missingModuleDocumentation.size() != moduleCount - documentedModuleCount) {
                throw new IllegalArgumentException("module documentation coverage mismatch");
            }
            if (missingSymbolDocumentation.size() != symbolCount - documentedSymbolCount) {
                throw new IllegalArgumentException("symbol documentation coverage mismatch");
            }
        }

        private static List<String> sortedCopy(List<String> values, String label) {
            Objects.requireNonNull(values, label);
            List<String> copy = new ArrayList<>(values.size());
            for (String value : values) {
                copy.add(Objects.requireNonNull(value, label + " entry"));
            }
            copy.sort(Comparator.naturalOrder());
            return List.copyOf(copy);
        }
    }

    /**
     * Extract one exact repository revision from the supplied checkout.
     *
     * @param repositoryRoot checkout root containing {@code protos/lib}
     * @param revision exact lowercase full Git object id represented by the checkout
     */
    public static Extraction extract(Path repositoryRoot, String revision) throws IOException {
        Path root = Objects.requireNonNull(repositoryRoot, "repositoryRoot")
                .toAbsolutePath()
                .normalize();
        requireExactRevision(revision);

        Path libraryRoot = root.resolve("protos").resolve("lib");
        if (!Files.isDirectory(libraryRoot)) {
            throw new IOException("canonical Standard Library root not found: protos/lib");
        }

        Path realLibraryRoot = libraryRoot.toRealPath();
        List<Path> sources = discoverSources(libraryRoot);
        List<Module> modules = new ArrayList<>(sources.size());
        List<Symbol> symbols = new ArrayList<>();
        List<String> missingModules = new ArrayList<>();
        List<String> missingSymbols = new ArrayList<>();
        int documentedModules = 0;
        int documentedSymbols = 0;

        for (Path sourcePath : sources) {
            Path realSource = sourcePath.toRealPath();
            if (!realSource.startsWith(realLibraryRoot)) {
                throw new IOException("Standard Library source escaped protos/lib: " + sourcePath);
            }

            Path relativeLibraryPath = libraryRoot.relativize(sourcePath);
            validateNoCaseFoldAmbiguity(libraryRoot, relativeLibraryPath);
            String logicalName = logicalModuleName(relativeLibraryPath);
            String moduleName = "std:" + logicalName;
            StandardModuleIdentity moduleIdentity = new StandardModuleIdentity(moduleName);

            Path repositoryRelativePath = root.relativize(sourcePath.toAbsolutePath().normalize());
            String sourcePathText = slashPath(repositoryRelativePath);
            String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
            ParsedModule parsed = parseModule(source, moduleIdentity, sourcePathText);

            modules.add(parsed.module());
            symbols.addAll(parsed.symbols());

            if (parsed.module().documentation() == null) {
                missingModules.add(moduleName);
            } else {
                documentedModules++;
            }

            for (Symbol symbol : parsed.symbols()) {
                String symbolName = moduleName + "::" + symbol.identity().slotName();
                if (symbol.documentation() == null) {
                    missingSymbols.add(symbolName);
                } else {
                    documentedSymbols++;
                }
            }
        }

        Artifact artifact = new Artifact(
                new Generator(GENERATOR_NAME, GENERATOR_VERSION),
                new RepositoryRevisionScope(REPOSITORY, revision),
                modules,
                symbols);
        Coverage coverage = new Coverage(
                modules.size(),
                documentedModules,
                missingModules,
                symbols.size(),
                documentedSymbols,
                missingSymbols);
        return new Extraction(artifact, coverage);
    }

    /**
     * Deterministic human-readable D067 coverage signal.
     */
    public static String renderCoverage(Coverage coverage) {
        Objects.requireNonNull(coverage, "coverage");
        StringBuilder output = new StringBuilder();
        output.append("modules.total=").append(coverage.moduleCount()).append('\n');
        output.append("modules.documented=").append(coverage.documentedModuleCount()).append('\n');
        output.append("modules.undocumented=")
                .append(coverage.missingModuleDocumentation().size())
                .append('\n');
        output.append("symbols.total=").append(coverage.symbolCount()).append('\n');
        output.append("symbols.documented=").append(coverage.documentedSymbolCount()).append('\n');
        output.append("symbols.undocumented=")
                .append(coverage.missingSymbolDocumentation().size())
                .append('\n');
        for (String module : coverage.missingModuleDocumentation()) {
            output.append("missing.module=").append(module).append('\n');
        }
        for (String symbol : coverage.missingSymbolDocumentation()) {
            output.append("missing.symbol=").append(symbol).append('\n');
        }
        return output.toString();
    }

    /**
     * Emit the exact-checkout D064 JSON artifact on stdout and D067 coverage on stderr.
     *
     * <p>The checkout must be clean under {@code protos/lib}; the exact revision
     * is obtained from Git rather than supplied independently.</p>
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException(
                    "usage: ProtosStandardLibraryDocumentationExtractor [repository-root]");
        }
        Path root = args.length == 0 ? Path.of(".") : Path.of(args[0]);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        String revision = exactCleanStandardLibraryRevision(normalizedRoot);
        Extraction extraction = extract(normalizedRoot, revision);
        byte[] artifact = ProtosDocumentationJson.serialize(extraction.artifact());
        System.out.write(artifact);
        System.err.print(renderCoverage(extraction.coverage()));
    }

    private static ParsedModule parseModule(
            String source,
            StandardModuleIdentity moduleIdentity,
            String sourcePath) {
        SurfaceSequence program = new ProtosParser(source).parseProgram();
        List<TopLevelSlot> topLevelSlots = topLevelSlots(program);

        String moduleDocumentation =
                ProtosSourceDocumentation.moduleDocumentation(source);

        Map<Integer, String> symbolDocumentation = new HashMap<>();
        for (ProtosSourceDocumentation.SlotDocumentation documented :
                ProtosSourceDocumentation.slotDocumentation(source)) {
            symbolDocumentation.put(
                    documented.owner().span().startOffset(),
                    documented.documentation());
        }

        SourceProvenance moduleSource =
                new SourceProvenance(sourcePath, sourceRange(source, new SourceSpan(0, source.length())));
        Module module = new Module(moduleIdentity, moduleDocumentation, moduleSource);

        List<Symbol> symbols = new ArrayList<>(topLevelSlots.size());
        for (TopLevelSlot slot : topLevelSlots) {
            SurfaceName name = (SurfaceName) slot.creation().target();
            SymbolIdentity identity = new SymbolIdentity(moduleIdentity, name.name());
            String documentation =
                    symbolDocumentation.get(slot.creation().span().startOffset());
            Callable callable = callable(slot.creation().value());
            SourceProvenance provenance =
                    new SourceProvenance(sourcePath, sourceRange(source, slot.expressionSpan()));
            symbols.add(new Symbol(identity, documentation, provenance, callable));
        }
        return new ParsedModule(module, List.copyOf(symbols));
    }

    private static List<Path> discoverSources(Path libraryRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(libraryRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(SOURCE_SUFFIX))
                    .filter(path -> !isCorePath(libraryRoot.relativize(path)))
                    .sorted(Comparator.comparing(path -> slashPath(libraryRoot.relativize(path))))
                    .toList();
        }
    }

    private static boolean isCorePath(Path relativePath) {
        return relativePath.getNameCount() > 0
                && relativePath.getName(0).toString().equalsIgnoreCase("core");
    }

    private static String logicalModuleName(Path relativePath) throws IOException {
        String relative = slashPath(relativePath);
        if (!relative.endsWith(SOURCE_SUFFIX)) {
            throw new IOException("Standard Library source lacks .protos suffix: " + relative);
        }
        String logicalName = relative.substring(0, relative.length() - SOURCE_SUFFIX.length());
        String[] segments = logicalName.split("/", -1);
        if (segments.length == 0 || segments[0].equalsIgnoreCase("core")) {
            throw new IOException("invalid importable Standard Library module: " + logicalName);
        }
        for (String segment : segments) {
            if (!isPortableSegment(segment) || isWindowsReservedSegment(segment)) {
                throw new IOException("invalid importable Standard Library module: " + logicalName);
            }
        }
        return logicalName;
    }

    private static void validateNoCaseFoldAmbiguity(
            Path libraryRoot,
            Path relativePath) throws IOException {
        Path current = libraryRoot;
        for (Path segment : relativePath) {
            String expected = segment.toString();
            int matches = 0;
            try (Stream<Path> children = Files.list(current)) {
                for (Path child : children.toList()) {
                    if (equalsAsciiIgnoreCase(child.getFileName().toString(), expected)) {
                        matches++;
                    }
                }
            }
            if (matches > 1) {
                throw new IOException(
                        "ambiguous Standard Library path spelling: "
                                + slashPath(relativePath));
            }
            current = current.resolve(expected);
        }
    }

    private static boolean equalsAsciiIgnoreCase(String left, String right) {
        if (left.length() != right.length()) {
            return false;
        }
        for (int index = 0; index < left.length(); index++) {
            if (asciiLower(left.charAt(index)) != asciiLower(right.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static char asciiLower(char value) {
        return value >= 'A' && value <= 'Z'
                ? (char) (value + ('a' - 'A'))
                : value;
    }

    private static boolean isPortableSegment(String segment) {
        if (segment.isEmpty() || !isAsciiLetter(segment.charAt(0))) {
            return false;
        }
        for (int index = 1; index < segment.length(); index++) {
            char current = segment.charAt(index);
            if (!isAsciiLetter(current) && !isAsciiDigit(current) && current != '_') {
                return false;
            }
        }
        return true;
    }

    private static boolean isWindowsReservedSegment(String segment) {
        return WINDOWS_RESERVED_SEGMENTS.contains(segment.toUpperCase(Locale.ROOT));
    }

    private static boolean isAsciiLetter(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    private static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static List<TopLevelSlot> topLevelSlots(SurfaceSequence program) {
        List<TopLevelSlot> slots = new ArrayList<>();
        for (SurfaceExpression expression : program.expressions()) {
            SurfaceExpression unwrapped = unwrapGroups(expression);
            if (unwrapped instanceof SurfaceSlotCreation creation
                    && creation.target() instanceof SurfaceName) {
                slots.add(new TopLevelSlot(creation, expression.span()));
            } else {
                String removed = directlyRemovedModuleSlot(unwrapped);
                if (removed != null) {
                    slots.removeIf(slot ->
                            ((SurfaceName) slot.creation().target())
                                    .name().equals(removed));
                }
            }
        }
        slots.sort(Comparator.comparingInt(slot -> slot.expressionSpan().startOffset()));
        return List.copyOf(slots);
    }

    /**
     * A direct top-level context.removeSlot("name") removes that binding
     * from the module's published surface. A call nested inside a Closure,
     * conditional or another expression is not an unconditional removal.
     * Process expressions in source order so a later creation is retained.
     */
    private static String directlyRemovedModuleSlot(SurfaceExpression expression) {
        if (!(expression instanceof SurfaceCall call)
                || !(unwrapGroups(call.receiver()) instanceof SurfaceMember member)
                || !member.name().equals("removeSlot")
                || !(unwrapGroups(member.receiver()) instanceof SurfaceIntrinsic intrinsic)
                || intrinsic.kind() != SurfaceIntrinsic.Kind.CONTEXT
                || call.arguments().size() != 1
                || call.arguments().get(0).spread()
                || !(unwrapGroups(call.arguments().get(0).expression())
                        instanceof SurfaceLiteral literal)
                || literal.kind() != SurfaceLiteral.Kind.STRING) {
            return null;
        }
        return literal.value();
    }

    private static SurfaceExpression unwrapGroups(SurfaceExpression expression) {
        SurfaceExpression current = expression;
        while (current instanceof SurfaceGroup group) {
            current = group.expression();
        }
        return current;
    }

    private static Callable callable(SurfaceExpression expression) {
        SurfaceExpression current = unwrapGroups(expression);
        if (!(current instanceof SurfaceClosure closure)) {
            return null;
        }

        List<String> parameters = new ArrayList<>();
        String restParameter = null;
        for (SurfaceParameter parameter : closure.parameters()) {
            if (parameter.rest()) {
                restParameter = parameter.name();
            } else {
                parameters.add(parameter.name());
            }
        }
        return new Callable(parameters, restParameter);
    }

    private static SourceRange sourceRange(String source, SourceSpan span) {
        Position start = position(source, span.startOffset());
        Position end = position(source, span.endOffset());
        return new SourceRange(start.line(), start.column(), end.line(), end.column());
    }

    private static Position position(String source, int offset) {
        if (offset < 0 || offset > source.length()) {
            throw new IllegalArgumentException("source offset outside source");
        }

        int line = 1;
        int column = 1;
        int index = 0;
        while (index < offset) {
            char current = source.charAt(index);
            if (current == '\r') {
                index++;
                if (index < offset && index < source.length() && source.charAt(index) == '\n') {
                    index++;
                }
                line++;
                column = 1;
                continue;
            }
            if (current == '\n') {
                index++;
                line++;
                column = 1;
                continue;
            }

            int codePoint = source.codePointAt(index);
            int width = Character.charCount(codePoint);
            if (index + width > offset) {
                throw new IllegalArgumentException("source offset splits a Unicode scalar");
            }
            index += width;
            column++;
        }
        return new Position(line, column);
    }

    private static String exactCleanStandardLibraryRevision(Path repositoryRoot)
            throws IOException, InterruptedException {
        String trackedStatus = runGit(
                repositoryRoot,
                "status",
                "--porcelain=v1",
                "--untracked-files=no");
        if (!trackedStatus.isBlank()) {
            throw new IOException(
                    "tracked checkout state must match HEAD before documentation extraction");
        }

        String libraryStatus = runGit(
                repositoryRoot,
                "status",
                "--porcelain=v1",
                "--untracked-files=all",
                "--",
                "protos/lib");
        if (!libraryStatus.isBlank()) {
            throw new IOException(
                    "protos/lib must contain no untracked source before documentation extraction");
        }

        String revision = runGit(repositoryRoot, "rev-parse", "HEAD").strip();
        requireExactRevision(revision);
        return revision;
    }

    private static String runGit(Path repositoryRoot, String... arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(arguments.length + 3);
        command.add("git");
        command.add("-C");
        command.add(repositoryRoot.toString());
        for (String argument : arguments) {
            command.add(argument);
        }

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git command failed (" + exitCode + "): " + output.strip());
        }
        return output;
    }

    private static void requireExactRevision(String revision) {
        Objects.requireNonNull(revision, "revision");
        if (!revision.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new IllegalArgumentException(
                    "revision must be an exact lowercase full Git object id");
        }
    }

    private static String slashPath(Path path) {
        StringBuilder result = new StringBuilder();
        for (Path segment : path) {
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(segment);
        }
        return result.toString();
    }

    private record TopLevelSlot(SurfaceSlotCreation creation, SourceSpan expressionSpan) {
    }

    private record ParsedModule(Module module, List<Symbol> symbols) {
    }

    private record Position(int line, int column) {
    }
}
