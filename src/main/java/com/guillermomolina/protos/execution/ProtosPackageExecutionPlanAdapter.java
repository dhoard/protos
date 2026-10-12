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

import com.guillermomolina.protos.runtime.ProtosArrayValue;
import com.guillermomolina.protos.runtime.ProtosNumericValueSupport;
import com.guillermomolina.protos.runtime.ProtosMapValue;
import com.guillermomolina.protos.runtime.ProtosObjectValue;
import com.guillermomolina.protos.runtime.ProtosStringValue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Defensive detach of ordinary Protos PackageExecutionPlanV1 data into immutable host data.
 *
 * <p>This boundary validates only the already-published F2D1 ABI. It does not parse package
 * metadata, interpret the lock, perform stale policy, select dependencies, or install a resolver.
 */
public final class ProtosPackageExecutionPlanAdapter {
    private static final Set<String> PLAN_FIELDS =
            Set.of("generation", "root", "packages", "dependencies");
    private static final Set<String> REF_FIELDS =
            Set.of("kind", "packageId");
    private static final Set<String> PACKAGE_FIELDS =
            Set.of("ref", "location", "exports");
    private static final Set<String> EDGE_FIELDS =
            Set.of("declaring", "alias", "target");

    private ProtosPackageExecutionPlanAdapter() {}

    public static ProtosPackageExecutionPlan detach(Object rawPlan, Path projectRoot)
            throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path realRoot = projectRoot.toAbsolutePath().normalize().toRealPath();
        if (!Files.isDirectory(realRoot)) {
            throw new IOException("package project root is not a directory");
        }

        ProtosObjectValue plan = requireObject(rawPlan, "plan");
        requireExactFields(plan, PLAN_FIELDS, "plan");

        int generation = requireIntInteger(plan, "generation");
        if (generation != 1) {
            throw new IOException("unsupported PackageExecutionPlan generation");
        }

        ProtosPackageExecutionPlan.WorkspaceRef root =
                requireWorkspaceRef(requireField(plan, "root"));

        ProtosArrayValue rawPackages =
                requireArray(requireField(plan, "packages"), "packages");
        ArrayList<ProtosPackageExecutionPlan.PackageNode> packages = new ArrayList<>();
        LinkedHashSet<String> packageIds = new LinkedHashSet<>();
        LinkedHashSet<String> locations = new LinkedHashSet<>();

        for (Object rawPackage : rawPackages.indexedSnapshot()) {
            ProtosObjectValue packageValue = requireObject(rawPackage, "package");
            requireExactFields(packageValue, PACKAGE_FIELDS, "package");

            ProtosPackageExecutionPlan.WorkspaceRef ref =
                    requireWorkspaceRef(requireField(packageValue, "ref"));
            String location = requireString(requireField(packageValue, "location"), "location");
            validateLocation(realRoot, location);

            if (!packageIds.add(ref.packageId())) {
                throw new IOException("duplicate workspace PackageId in execution plan");
            }
            if (!locations.add(location)) {
                throw new IOException("duplicate workspace location in execution plan");
            }

            packages.add(
                    new ProtosPackageExecutionPlan.PackageNode(
                            ref,
                            location,
                            detachExports(
                                    requireMap(
                                            requireField(packageValue, "exports"),
                                            "exports"))));
        }

        if (packages.isEmpty()) {
            throw new IOException("PackageExecutionPlan has no packages");
        }

        ProtosPackageExecutionPlan.PackageNode rootPackage = null;
        for (ProtosPackageExecutionPlan.PackageNode node : packages) {
            if (node.location().isEmpty()) {
                if (rootPackage != null) {
                    throw new IOException("PackageExecutionPlan has multiple root locations");
                }
                rootPackage = node;
            }
        }
        if (rootPackage == null || !rootPackage.ref().equals(root)) {
            throw new IOException("PackageExecutionPlan root package mismatch");
        }

        ProtosArrayValue rawDependencies =
                requireArray(requireField(plan, "dependencies"), "dependencies");
        ArrayList<ProtosPackageExecutionPlan.DependencyEdge> dependencies =
                new ArrayList<>();
        LinkedHashSet<String> edgeKeys = new LinkedHashSet<>();

        for (Object rawDependency : rawDependencies.indexedSnapshot()) {
            ProtosObjectValue edge = requireObject(rawDependency, "dependency");
            requireExactFields(edge, EDGE_FIELDS, "dependency");

            ProtosPackageExecutionPlan.WorkspaceRef declaring =
                    requireWorkspaceRef(requireField(edge, "declaring"));
            String alias = requireString(requireField(edge, "alias"), "alias");
            ProtosPackageRuntimeNames.requireAlias(alias);
            ProtosPackageExecutionPlan.WorkspaceRef target =
                    requireWorkspaceRef(requireField(edge, "target"));

            if (!packageIds.contains(declaring.packageId())
                    || !packageIds.contains(target.packageId())) {
                throw new IOException("PackageExecutionPlan dependency references unknown package");
            }

            if (!edgeKeys.add(declaring.packageId() + "\u0000" + alias)) {
                throw new IOException("duplicate PackageExecutionPlan dependency alias");
            }

            dependencies.add(
                    new ProtosPackageExecutionPlan.DependencyEdge(
                            declaring, alias, target));
        }

        return new ProtosPackageExecutionPlan(
                generation, root, packages, dependencies);
    }

    static Map<String, String> detachExports(ProtosMapValue map)
            throws IOException {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (ProtosMapValue.Entry entry : map.keyedSnapshot()) {
            String publicName = requireString(entry.key(), "export key");
            String internalName = requireString(entry.value(), "export value");
            ProtosPackageRuntimeNames.requireLogicalName(publicName);
            ProtosPackageRuntimeNames.requireLogicalName(internalName);
            if (result.putIfAbsent(publicName, internalName) != null) {
                throw new IOException("duplicate export key in PackageExecutionPlan");
            }
        }
        return Map.copyOf(result);
    }

    private static ProtosPackageExecutionPlan.WorkspaceRef requireWorkspaceRef(Object value)
            throws IOException {
        ProtosObjectValue ref = requireObject(value, "workspace ref");
        requireExactFields(ref, REF_FIELDS, "workspace ref");
        if (!requireString(requireField(ref, "kind"), "ref kind").equals("workspace")) {
            throw new IOException("PackageExecutionPlan contains a non-workspace ref");
        }
        return new ProtosPackageExecutionPlan.WorkspaceRef(
                requireString(requireField(ref, "packageId"), "PackageId"));
    }

    static void validateLocation(Path realRoot, String location)
            throws IOException {
        Path candidate = realRoot;
        if (!location.isEmpty()) {
            if (location.startsWith("/")
                    || location.contains("\\")
                    || location.indexOf('\0') >= 0) {
                throw new IOException("invalid workspace package location");
            }
            for (String component : location.split("/", -1)) {
                if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                    throw new IOException("invalid workspace package location");
                }
                candidate = candidate.resolve(component);
            }
        }
        Path realCandidate = candidate.toRealPath();
        if (!Files.isDirectory(realCandidate) || !realCandidate.startsWith(realRoot)) {
            throw new IOException("workspace package location escaped project root");
        }
    }

    static Object requireField(ProtosObjectValue object, String name)
            throws IOException {
        return object.readLocalSlot(name)
                .orElseThrow(
                        () ->
                                new IOException(
                                        "missing PackageExecutionPlan field: " + name));
    }

    static void requireExactFields(
            ProtosObjectValue object, Set<String> expected, String label)
            throws IOException {
        if (!object.localSlotsSnapshot().keySet().equals(expected)) {
            throw new IOException("unexpected " + label + " shape");
        }
    }

    static ProtosObjectValue requireObject(Object value, String label)
            throws IOException {
        if (!(value instanceof ProtosObjectValue object)
                || ProtosNumericValueSupport.isCurrentNumber(value)) {
            throw new IOException(label + " is not an ordinary object");
        }
        return object;
    }

    static ProtosArrayValue requireArray(Object value, String label)
            throws IOException {
        if (!(value instanceof ProtosArrayValue array)) {
            throw new IOException(label + " is not an Array");
        }
        return array;
    }

    static ProtosMapValue requireMap(Object value, String label)
            throws IOException {
        if (!(value instanceof ProtosMapValue map)) {
            throw new IOException(label + " is not a Map");
        }
        return map;
    }

    /** The exact semantic Integer field {@code name}. */
    static Object requireInteger(ProtosObjectValue object, String name)
            throws IOException {
        Object value = requireField(object, name);
        if (!ProtosNumericValueSupport.isCurrentInteger(value)) {
            throw new IOException(name + " is not an Integer");
        }
        return value;
    }

    /** The exact {@code int} Integer field {@code name}. */
    static int requireIntInteger(ProtosObjectValue object, String name) throws IOException {
        Object value = requireInteger(object, name);
        if (!ProtosNumericValueSupport.isIntegerInIntRange(value)) {
            throw new IOException(name + " is not an int Integer");
        }
        return ProtosNumericValueSupport.exactInt(value);
    }

    static String requireString(Object value, String label)
            throws IOException {
        if (!(value instanceof ProtosStringValue string)) {
            throw new IOException(label + " is not a String");
        }
        return string.value();
    }
}
