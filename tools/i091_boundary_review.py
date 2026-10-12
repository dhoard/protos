#!/usr/bin/env python3
# THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
# ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
# DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
# DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
# OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS OF
# THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY
# OF THE LICENSE IS LOCATED AT:
# https://github.com/guillermomolina/protos
#
# Software distributed under the License is distributed on an "AS IS" basis,
# WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
# the specific language governing rights and limitations under the License.

"""I091-A source-grounded boundary queue and Candidate C static preflight.

Boundary labels identify review ownership, not architectural conclusions.
No reference is considered removable by this tool.
"""

import csv
import json
from collections import Counter
from pathlib import Path

import numeric_dependency_census as census


ROOT = Path("src/main/java/com/guillermomolina/protos")
TARGET = Path("target")


def review_boundary(path):
    name = Path(path).stem

    numeric = {
        "ProtosIntegerValue",
        "ProtosLargeIntegerValue",
        "ProtosFloatValue",
        "ProtosNumberLiteral",
        "ProtosNumericValueSupport",
        "ProtosBinary64Rounding",
        "ProtosStandardIntegerProtocol",
        "ProtosStandardFloatProtocol",
        "ProtosStandardNumericConversionProtocol",
    }

    identity = {
        "ProtosIdentity",
        "ProtosCurrentNumericRelations",
        "ProtosStandardHashSupport",
        "ProtosStandardNumberEqualityProtocol",
        "ProtosStandardNumberOrderingProtocol",
        "ProtosValueLookup",
    }

    collections = {
        "ProtosArrayValue",
        "ProtosMapValue",
        "ProtosIdentityMapValue",
        "ProtosBytesValue",
        "ProtosStandardArrayProtocol",
        "ProtosStandardMapProtocol",
        "ProtosStandardBytesProtocol",
    }

    transfers = {
        "ProtosActorValueTransfer",
        "ProtosParallelRuntime",
        "ProtosDetachedExecutionValue",
        "ProtosSemanticTransferPayload",
        "ProtosSemanticTransferRecord",
    }

    if name in numeric:
        return "NUMERIC_ALGORITHMS_AND_CARRIERS"

    if name in identity:
        return "SEMANTIC_IDENTITY_EQUALITY_HASH"

    if name in collections:
        return "COLLECTIONS_AND_INDEXING"

    if name in transfers or "Transfer" in name:
        return "ISOLATION_AND_TRANSFER"

    if "BytecodeRootNode" in name:
        return "BYTECODE_EXECUTION"

    if "Foreign" in name or "Interop" in name:
        return "FOREIGN_AND_HOST_INTEROP"

    if any(token in name for token in (
        "File", "Path", "ByteIo", "TextReader",
        "Network", "Tcp", "IpAddress", "IpEndpoint",
        "Encoding", "Nio",
    )):
        return "IO_AND_NETWORK"

    if any(token in name for token in (
        "TestTool", "Cli", "Renderer", "Diagnostic",
        "Package", "Logging", "Regex",
    )):
        return "TOOLS_AND_LIBRARY_SUPPORT"

    return "MANUAL_OWNERSHIP_REVIEW"


def source_check(check_id, relative_path, needle, interpretation):
    path = ROOT / relative_path
    text = path.read_text(encoding="utf-8")
    observed = needle in text

    return {
        "id": check_id,
        "path": path.as_posix(),
        "observation": needle,
        "source_match": observed,
        "interpretation_if_found": interpretation,
        "status": "OBSERVED" if observed else "REVIEW_SOURCE_DRIFT",
    }


CHECKS = [
    (
        "F1_SMALL_LONG_STORAGE",
        "runtime/ProtosIntegerValue.java",
        "private final long value;",
        "Small Integer has a primitive long payload",
    ),
    (
        "F1_LARGE_EXACT_STORAGE",
        "runtime/ProtosLargeIntegerValue.java",
        "private final BigInteger value;",
        "Large Integer alone retains an arbitrary-precision payload",
    ),
    (
        "F1_LARGE_FROZEN",
        "runtime/ProtosLargeIntegerValue.java",
        "freeze();",
        "Large Integer construction freezes its ordinary-object representation",
    ),
    (
        "F1_OBJECT_INTEROP_ISOLATION",
        "runtime/ProtosObjectValue.java",
        "return getClass() == ProtosObjectValue.class;",
        "Subclass numeric values do not inherit ordinary member interop",
    ),
    (
        "F1_OBJECT_NATIVE_GUARD",
        "execution/ProtosStandardObjectProtocol.java",
        "!ProtosNumericValueSupport.isCurrentNumber(receiver)",
        "Object reflection and construction reject all semantic Numbers",
    ),
    (
        "F1_SEALED_NUMERIC_STORAGE",
        "runtime/ProtosSealedValue.java",
        "value.freeze();",
        "Rich sealed values are immutable after construction",
    ),
    (
        "F2_STRUCTURED_ROOT_PRIMITIVES",
        "execution/ProtosBytecodeRootNode.java",
        "boxingEliminationTypes = {int.class, long.class, double.class}",
        "Structured Bytecode root admits primitive carriers",
    ),
    (
        "F2_SEMANTIC_ROOT_PRIMITIVES",
        "execution/ProtosSemanticBytecodeRootNode.java",
        "boxingEliminationTypes = {int.class, long.class, double.class}",
        "Semantic Bytecode root admits primitive carriers",
    ),
    (
        "F2_PRIMITIVE_NUMERIC_SEND",
        "execution/ProtosSemanticBytecodeRootNode.java",
        "Math.addExact(",
        "The numeric send contains primitive exact arithmetic",
    ),
    (
        "F2_LITERAL_RETURN_CARRIER",
        "execution/CanonicalToBytecodeLowerer.java",
        "builder.beginMaterializeReturnCarrier();",
        "Literal return has an explicit materialization boundary",
    ),
    (
        "F2_DIRECT_CALL1_RESULT",
        "execution/CanonicalToBytecodeLowerer.java",
        "return call.arguments().size() == 1\n                && !hasSpreadArgument(call.arguments());",
        "Direct Call1 admits primitive results with a non-spread argument",
    ),
    (
        "F2_DIRECT_SEND0_RESULT",
        "execution/CanonicalToBytecodeLowerer.java",
        "return send.arguments().isEmpty()",
        "Direct Send0 admits primitive results",
    ),
    (
        "F3_EXACT_RATIONAL_NORMALIZATION",
        "runtime/ProtosNumericValueSupport.java",
        "normalizeExactRational(",
        "The numeric boundary owns exact rational normalization",
    ),
    (
        "F3_PRIMITIVE_INTEGER_DIVISION",
        "runtime/ProtosBinary64Rounding.java",
        "dividePrimitiveIntegers(",
        "Signed-64 division has a primitive rounding implementation",
    ),
    (
        "F4_INTEGER_AND_RICH_IDENTITY",
        "runtime/ProtosIdentity.java",
        "ProtosNumericValueSupport.sameRichNumericIdentity(left,right)",
        "Identity distinguishes the rich numeric family contract",
    ),
    (
        "F4_NUMERIC_IDENTITY_HASH",
        "runtime/ProtosIdentity.java",
        "ProtosNumericValueSupport.currentNumericIdentityHash(value)",
        "Numeric identity hashing is owned by the numeric boundary",
    ),
    (
        "F4_ACTOR_NUMERIC_COPY",
        "runtime/ProtosActorValueTransfer.java",
        "ProtosNumericValueSupport.copyCurrentNumberOrNull(value, prelude)",
        "Actor transfer has destination-local numeric copying",
    ),
    (
        "F4_PARALLEL_NUMERIC_COPY",
        "execution/ProtosParallelRuntime.java",
        "ProtosNumericValueSupport.copyCurrentNumberOrNull(v, a.prelude().orElse(null))",
        "Parallel transfer has numeric copying",
    ),
    (
        "F4_DETACHED_NUMERIC_COPY",
        "execution/ProtosDetachedExecutionValue.java",
        "ProtosNumericValueSupport.copyCurrentNumberOrNull(value, prelude)",
        "Detached execution has numeric copying",
    ),
    (
        "F5_COMPACT_TRANSFER_LEAF",
        "runtime/ProtosSemanticTransferPayload.java",
        "public static Object integer(long value) {\n        return value;\n    }",
        "Small transfer leaves no longer require Java BigInteger",
    ),
    (
        "F5_OPTIONAL_SPI_LONG",
        "spi/foreign/ProtosForeignValueOperations.java",
        "default boolean supportsLongIntegerArguments()",
        "The public SPI has an opt-in long transport contract",
    ),
    (
        "F5_POLYGLOT_LONG",
        "spi/foreign/polyglot/ProtosPolyglotValueOperations.java",
        "polyglot.fitsInLong()",
        "Polyglot integral admission probes the compact representation",
    ),
]



F5_SOURCE_OWNERS = {'runtime/ProtosNumericValueSupport.java': ((24, 50, 9), 'NUMERIC_BOUNDARY', 'Exact algorithms, canonical carriers and semantic normalization'), 'runtime/ProtosBinary64Rounding.java': ((20, 5, 0), 'EXACT_ROUNDING', 'Primitive-first binary rounding and exceptional large arithmetic'), 'execution/ProtosCurrentNumericRelations.java': ((0, 6, 7), 'SEMANTIC_RELATIONS', 'Numeric comparison, equality and hash'), 'runtime/ProtosSemanticTransferPayload.java': ((13, 0, 0), 'TRANSFER_BOUNDARY', 'Long leaves with BigInteger reserved for large or mixed leaves'), 'execution/ProtosSemanticBytecodeRootNode.java': ((1, 5, 5), 'BYTECODE_BOUNDARY', 'Primitive carriers, large literals and observable materialization'), 'execution/ProtosStandardFloatProtocol.java': ((0, 0, 6), 'GUEST_PROTOCOL', 'Standard Float receiver and result handling'), 'execution/ProtosStandardIntegerProtocol.java': ((0, 0, 5), 'GUEST_PROTOCOL', 'Standard Integer receiver and numeric dispatch'), 'execution/ProtosStandardNumericConversionProtocol.java': ((0, 0, 5), 'GUEST_PROTOCOL', 'Explicit numeric conversions'), 'runtime/ProtosFixedIntegerInteropValue.java': ((5, 0, 0), 'EXPLICIT_HOST_INTEROP', 'Requested arbitrary-precision and unsigned host projections'), 'runtime/ProtosIntegerValue.java': ((3, 2, 0), 'COMPACT_CARRIER', 'Signed-64 representation and on-demand asBigInteger interop'), 'runtime/ProtosLargeIntegerValue.java': ((5, 0, 0), 'LARGE_CARRIER', 'Immutable outside-signed-64 payload'), 'runtime/ProtosFloatValue.java': ((2, 0, 2), 'FLOAT_CARRIER', 'Binary64 representation and requested exact-integral interop'), 'runtime/ProtosNumericHashKey.java': ((4, 0, 0), 'EXACT_HASH', 'Long small branch and exact large hash results'), 'execution/ProtosHostJavaProvider.java': ((4, 0, 0), 'EXPLICIT_HOST_ABI', 'Conversion only for a declared Java BigInteger parameter'), 'spi/foreign/polyglot/ProtosPolyglotValueOperations.java': ((3, 0, 0), 'POLYGLOT_INTEROP', 'Long-first integral admission and exceptional large projection'), 'runtime/ProtosNumberLiteral.java': ((2, 0, 0), 'LARGE_LITERAL', 'Arbitrary-precision literal descriptor'), 'spi/foreign/ProtosForeignValueClass.java': ((2, 0, 0), 'PUBLIC_SPI', 'Lossless provider classification of large integral values'), 'execution/ProtosForeignPluginValueAdapter.java': ((1, 0, 0), 'LEGACY_SPI_COMPATIBILITY', 'BigInteger only for providers lacking Long transport opt-in'), 'execution/CanonicalToBytecodeLowerer.java': ((2, 0, 0), 'LARGE_LITERAL', 'Arbitrary-precision literal lowering'), 'runtime/ProtosComplexNumericSupport.java': ((0, 0, 2), 'COMPLEX_COMPONENT', 'Validated binary64 components of canonical Complex'), 'execution/ProtosStandardFractionProtocol.java': ((0, 0, 2), 'FRACTION_COMPONENT', 'Float component and exact conversion boundaries'), 'execution/ProtosHostJavaCatalogue.java': ((2, 0, 0), 'EXPLICIT_HOST_ABI', 'Registration of the declared Java BigInteger type')}


def f5_source_owner(item):
    relative = Path(item["path"]).relative_to(ROOT).as_posix()
    owner = F5_SOURCE_OWNERS.get(relative)
    if owner is None:
        raise RuntimeError("Unclassified F5 source owner: " + relative)

    expected, category, rationale = owner
    counts = item["counts"]
    observed = tuple(
        counts.get(symbol, 0)
        for symbol in ("BigInteger", "ProtosIntegerValue", "ProtosFloatValue")
    )
    if observed != expected:
        raise RuntimeError(
            f"F5 source classification is stale: {relative}: "
            f"expected {expected}, observed {observed}"
        )
    return category, rationale

def main():
    prior_path = TARGET / "i091-numeric-census.json"

    if not prior_path.is_file():
        raise RuntimeError("Run numeric_dependency_census.py first")

    prior = json.loads(prior_path.read_text(encoding="utf-8"))
    current = census.inventory()

    for key in (
        "head",
        "production_source_fingerprint",
        "production_java_files",
        "matching_java_files",
        "total_sites",
        "symbol_counts",
    ):
        if prior[key] != current[key]:
            raise RuntimeError(
                f"Stale census or concurrent source modification: {key}"
            )

    if len(current["sites"]) != current["total_sites"]:
        raise RuntimeError("Inconsistent site total")

    files = []
    counts = Counter()

    for item in current["files"]:
        boundary = review_boundary(item["path"])
        counts[boundary] += item["total"]

        category, rationale = f5_source_owner(item)

        files.append({
            "path": item["path"],
            "module": item["module"],
            "review_boundary": boundary,
            "BigInteger": item["counts"].get("BigInteger", 0),
            "ProtosIntegerValue": item["counts"].get(
                "ProtosIntegerValue", 0
            ),
            "ProtosFloatValue": item["counts"].get(
                "ProtosFloatValue", 0
            ),
            "references": item["total"],
            "status": "SOURCE_OWNER_CLASSIFIED",
            "source_owner_category": category,
            "source_owner_rationale": rationale,
        })

    reviewed_paths = {
        Path(item["path"]).relative_to(ROOT).as_posix()
        for item in current["files"]
    }
    if reviewed_paths != set(F5_SOURCE_OWNERS):
        raise RuntimeError("F5 owner set differs from current source census")

    # Preserve every lexical occurrence. Its classification inherits the
    # source owner's reviewed responsibility, not an invented independent
    # semantic proof of this particular use.
    records_by_path = {record["path"]: record for record in files}
    classified_sites = []

    for site in current["sites"]:
        owner = records_by_path.get(site["path"])
        if owner is None:
            raise RuntimeError("F5 occurrence without source owner")

        classified_sites.append({
            "path": site["path"],
            "module": site["module"],
            "line": site["line"],
            "column": site["column"],
            "symbol": site["symbol"],
            "kind": site["kind"],
            "source": site["source"],
            "classification": "SOURCE_OWNER_CLASSIFIED",
            "owner_category": owner["source_owner_category"],
            "owner_rationale": owner["source_owner_rationale"],
        })

    site_counts = Counter(site["path"] for site in classified_sites)
    expected_counts = {
        item["path"]: item["total"] for item in current["files"]
    }
    if dict(site_counts) != expected_counts:
        raise RuntimeError("F5 per-site inventory does not reconcile")

    classified_symbols = Counter(
        site["symbol"] for site in classified_sites
    )
    if dict(classified_symbols) != current["symbol_counts"]:
        raise RuntimeError("F5 per-site symbol totals do not reconcile")

    historical = {
        "BigInteger": 447,
        "ProtosIntegerValue": 187,
        "ProtosFloatValue": 49,
    }
    difference = {
        symbol: current["symbol_counts"][symbol] - baseline
        for symbol, baseline in historical.items()
    }

    classified_report = {
        "schema": "I091-F5-CLASSIFIED-SITES-1",
        "head": current["head"],
        "fingerprint": current["production_source_fingerprint"],
        "baseline_revision":
            "76de43651079172d90be5bc6b1f535336ae47cfb",
        "baseline_counts": historical,
        "current_counts": current["symbol_counts"],
        "differences": difference,
        "files": len(files),
        "sites": len(classified_sites),
        "classification_scope":
            "Each occurrence inherits its reviewed source-owner category; "
            "architectural acceptance requires behavioral evidence.",
        "occurrences": classified_sites,
    }

    checks = [
        source_check(*specification)
        for specification in CHECKS
    ]

    if sum(counts.values()) != current["total_sites"]:
        raise RuntimeError("Review queue lost numeric references")

    report = {
        "schema": "I091-CANDIDATE-C-PREFLIGHT-1",
        "head": current["head"],
        "fingerprint": current["production_source_fingerprint"],
        "baseline_references": current["total_sites"],
        "boundary_counts": dict(sorted(counts.items())),
        "checks": checks,
        "f5_source_owner_status": "CLASSIFIED_REQUIRES_ACCEPTANCE",
        "f5_classified_references": current["total_sites"],
        "f5_classified_files": len(files),
        "decision": "SOURCE_OBSERVATIONS_ONLY",
        "pending": [
            "Reconcile F1 object observability against the full generic consumer audit",
            "Reconcile F2 primitive-carrier tests, D013 overrides and fallback",
            "Reconcile F3 overflow, normalization and exact-rounding tests",
            "Reconcile F4 equality, hash, reflection, interop and isolation tests",
            "Reconcile F5 per-occurrence classification against the current fingerprint",
            "Record applicable Native Image validation or a justified scope exclusion",
            "Publish exact validated product revision and final closure evidence",
        ],
    }

    TARGET.mkdir(parents=True, exist_ok=True)

    output = TARGET / "i091-boundary-review.tsv"
    with output.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=list(files[0]),
            delimiter="\t",
        )
        writer.writeheader()
        writer.writerows(files)

    preflight = TARGET / "i091-feasibility-preflight.json"
    preflight.write_text(
        json.dumps(report, indent=2) + "\n",
        encoding="utf-8",
    )

    site_tsv = TARGET / "i091-classified-numeric-sites.tsv"
    site_json = TARGET / "i091-classified-numeric-sites.json"

    with site_tsv.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=list(classified_sites[0]),
            delimiter="\t",
        )
        writer.writeheader()
        writer.writerows(classified_sites)

    site_json.write_text(
        json.dumps(classified_report, indent=2, ensure_ascii=False)
        + "\n",
        encoding="utf-8",
    )

    print("I091-A BOUNDARY REVIEW")
    print(f"HEAD: {report['head']}")
    print(f"Java files requiring review: {len(files)}")
    print(f"References preserved: {sum(counts.values())}")

    for boundary, count in sorted(
        counts.items(),
        key=lambda entry: (-entry[1], entry[0]),
    ):
        print(f"  {boundary}: {count}")

    print("SOURCE FEASIBILITY OBSERVATIONS")
    for item in checks:
        print(f"  {item['id']}: {item['status']}")

    print("Top 15 files by numeric references:")
    for item in sorted(
        files,
        key=lambda record: (-record["references"], record["path"]),
    )[:15]:
        print(
            f"  {item['references']:3d} "
            f"{item['review_boundary']} {item['path']}"
        )

    print(f"REVIEW QUEUE: {output}")
    print(f"PREFLIGHT: {preflight}")
    observed = sum(item["source_match"] for item in checks)
    print(f"CURRENT SOURCE OBSERVATIONS: {observed}/{len(checks)}")
    print("ARCHITECTURAL ACCEPTANCE: REQUIRES VALIDATION EVIDENCE")
    print(f"F5_SOURCE_OWNERS: {len(files)}/{len(F5_SOURCE_OWNERS)}")
    print(f"F5_CLASSIFIED_REFERENCES: {current['total_sites']}")
    print(f"F5_INDIVIDUAL_SITES: {len(classified_sites)}/{current['total_sites']}")
    print(f"F5_CLASSIFIED_TSV: {site_tsv}")
    print(f"F5_CLASSIFIED_JSON: {site_json}")
    print("F5_ARCHITECTURAL_ACCEPTANCE: REQUIRES FINAL VALIDATION")


if __name__ == "__main__":
    main()
