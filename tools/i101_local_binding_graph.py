#!/usr/bin/env python3
# THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
# ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
# DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
# DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
# OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS
# OF THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE.
# A COPY OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT"
# ACCOMPANYING THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT
# ACCOMPANY THE CONTENTS OF THIS FILE, A COPY OF THE LICENSE MAY ALSO BE
# OBTAINED AT THE FOLLOWING WEB SITE:
# https://github.com/guillermomolina/protos
#
# Software distributed under the License is distributed on an "AS IS" basis,
# WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
# for the specific language governing rights and limitations under the License.
"""I101 optional, bounded canonical local-binding Tier-1/Tier-2 graph capture.

Uses the project Maven/Surefire test class, with bounded real calls until the
uncached bytecode interpreter naturally transitions to CACHED.
Each lane requests a single explicit OptimizedCallTarget compilation in a fresh
JVM. For bytecode comparison, records complete instructions and typed operands.
Retains original BGV; does NOT infer structural graph equality from equal counts,
bytes, or filenames. Without a trusted graph comparison implementation, the
result of two successful captures is explicitly INCONCLUSIVE.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

from truffle_root_diagnostic import bgv_files  # Reuse the maintained BGV inventory.

RUNNER = "ProtosI101CanonicalLocalGraphTest"
ARTIFACT = Path("target/truffle-compilation/i101-local-binding")
SCENARIOS = ("literal", "create_only", "create_and_read")
TIERS = (1, 2)
SELECTOR = re.compile(r"^protos-root:[0-9a-f]{16}\[CLOSURE\|[^\]\s]+\]$")
REQUIRED_MARKERS = frozenset((
    "CASE", "TARGET", "TIER", "RESULT", "BYTECODE", "GROUP_ROOTS", "COMPILED",
))
MARKER = re.compile(r"I101_LOCAL_GRAPH_([A-Z_]+)=(.*)")
TRUFFLE_COMPILER_EXPORT = "--add-exports=org.graalvm.truffle.compiler/com.oracle.truffle.compiler=ALL-UNNAMED"


def parse_markers(output):
    """Fail on conflicting marker values; do not accept a single happy printed line."""
    found = {}
    for line in output.splitlines():
        m = MARKER.search(line)
        if m:
            key, value = m.groups()
            if key in found and found[key] != value.strip():
                raise ValueError("CONFLICTING_MARKER " + key)
            found[key] = value.strip()
    if set(found) != REQUIRED_MARKERS:
        raise ValueError("MARKERS_MISSING_OR_UNKNOWN %r" % (sorted(REQUIRED_MARKERS ^ set(found)),))
    return found


def validate_lane(output, code, scenario, tier, dump_directory, signature_file):
    reasons = []
    markers = {}
    try:
        markers = parse_markers(output)
    except ValueError as error:
        reasons.append(str(error))
    if code != 0:
        reasons.append("MAVEN_RETURN_CODE=%s" % code)
    if markers:
        if markers["CASE"] != scenario:
            reasons.append("WRONG_CASE")
        if markers["TIER"] != str(tier):
            reasons.append("WRONG_TIER")
        if markers["RESULT"] != "1" or markers["COMPILED"] != "YES":
            reasons.append("SEMANTIC_OR_COMPILATION_FAILURE")
        if not SELECTOR.fullmatch(markers["TARGET"]):
            reasons.append("NOT_A_STABLE_CLOSURE_ROOT")
        if Path(markers["BYTECODE"]).resolve() != signature_file.resolve():
            reasons.append("WRONG_BYTECODE_FILE")
    if not signature_file.is_file() or not signature_file.read_text(encoding="utf-8").strip():
        reasons.append("NO_BYTECODE_SIGNATURE")
    dumps = [Path(name) for name in bgv_files(dump_directory)]
    selected = [p for p in dumps if markers.get("TARGET", "").split("[", 1)[0] in p.name]
    if not selected:
        reasons.append("SELECTED_ROOT_NO_BGV")
    # Several BGVs may represent successive snapshots of the same selected
    # compilation. Retain all; do not collapse them or guess the final stage.
    if any(not p.is_file() or p.stat().st_size == 0 for p in selected):
        reasons.append("EMPTY_SELECTED_ROOT_BGV")
    if markers and not any(line.lstrip().startswith("[engine] opt done")
                           and markers["TARGET"].split("[", 1)[0] in line
                           for line in output.splitlines()):
        reasons.append("SELECTED_ROOT_NO_OPT_DONE")
    return {
        "status": "CAPTURE_READY" if not reasons else "ACQUISITION_FAILED",
        "scenario": scenario,
        "requested_tier": tier,
        "markers": markers,
        "selected_bgv": [str(p) for p in selected],
        "all_bgv": [str(p) for p in dumps],
        "bytecode_file": str(signature_file),
        "problems": reasons,
    }


def lane_options(scenario, tier, dump_path):
    """CompileImmediately silently disables multi-tier; always use the explicit API."""
    return [
        TRUFFLE_COMPILER_EXPORT,
        "-Dpolyglot.engine.AllowExperimentalOptions=true",
        "-Dpolyglot.engine.BackgroundCompilation=false",
        "-Dpolyglot.engine.TraceCompilation=true",
        "-Dpolyglot.engine.TraceCompilationDetails=true",
        "-Dpolyglot.engine.CompilationFailureAction=Throw",
        "-Dprotos.diagnostic.stableRootIdentity=true",
        "-Dprotos.i101.graphCapture=true",
        "-Dprotos.i101.graphCase=" + scenario,
        "-Dprotos.i101.graphTier=" + str(tier),
        "-Djdk.graal.Dump=Truffle:1",
        "-Djdk.graal.DumpPath=" + dump_path.as_posix(),
    ]


def lane_command():
    return ["mvn", "-q", "-Dtest=" + RUNNER, "test"]


def lane_environment(scenario, tier, dump_path):
    # pom.xml defines a literal Surefire argLine, which -DargLine cannot replace.
    # JAVA_TOOL_OPTIONS reaches the Surefire forked JVM as well as Maven itself;
    # preserving the POM's existing native-access launcher arguments.
    environment = os.environ.copy()
    options = " ".join(lane_options(scenario, tier, dump_path))
    existing = environment.get("JAVA_TOOL_OPTIONS", "").strip()
    environment["JAVA_TOOL_OPTIONS"] = (existing + " " + options).strip()
    return environment


def run(root, timeout):
    artifact = root / ARTIFACT
    artifact.mkdir(parents=True, exist_ok=True)
    report = {
        "schema": "protos.i101.canonical-local-graph/v2",
        "result": "ACQUISITION_FAILED",
        "comparison_method": "exact bytecode, BGV capture; graph equivalence requires stage-wise review",
        "conditions": "3 real NORMAL Closure roots x 2 tiers; fresh JVM, bounded uncached-to-cached guest calls, explicit compile",
        "lanes": {},
    }
    for scenario in SCENARIOS:
        for tier in TIERS:
            key = "%s-tier%s" % (scenario, tier)
            dump_directory = artifact / key / "graal_dumps"
            dump_directory.mkdir(parents=True, exist_ok=True)
            # A run must never be satisfied by BGV retained from previous executions.
            for old in dump_directory.rglob("*.bgv"):
                old.unlink()
            command = lane_command()
            options_path = dump_directory.relative_to(root)
            environment = lane_environment(scenario, tier, options_path)
            print("I101_CAPTURE_LANE=" + key, flush=True)
            try:
                completed = subprocess.run(command, cwd=root, env=environment, text=True,
                                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                           timeout=timeout, check=False)
                output, code = completed.stdout, completed.returncode
            except (OSError, subprocess.TimeoutExpired) as error:
                output, code = str(error), -1
            log = artifact / key / "maven.log"
            log.write_text(output, encoding="utf-8")
            bytecode = artifact / ("bytecode-" + scenario + ".txt")
            lane = validate_lane(output, code, scenario, tier, dump_directory, bytecode)
            lane["log"] = str(log)
            lane["command"] = command
            lane["jvm_options"] = lane_options(scenario, tier, options_path)
            if lane["status"] == "CAPTURE_READY":
                retained_signature = artifact / key / "bytecode.txt"
                shutil.copyfile(bytecode, retained_signature)
                lane["retained_bytecode_file"] = str(retained_signature)
            report["lanes"][key] = lane
            print("I101_CAPTURE_%s=%s" % (key.upper().replace("-", "_"), lane["status"]), flush=True)
            for problem in lane["problems"]:
                print("  " + problem, flush=True)
            if lane["status"] != "CAPTURE_READY":
                report["problems"] = ["Capture failed in " + key]
                break
        if "problems" in report:
            break
    if len(report["lanes"]) == len(SCENARIOS) * len(TIERS) and all(
            lane["status"] == "CAPTURE_READY" for lane in report["lanes"].values()):
        signatures = {}
        for scenario in SCENARIOS:
            lane_signatures = []
            for tier in TIERS:
                lane_path = artifact / (scenario + "-tier" + str(tier)) / "bytecode.txt"
                lane_signatures.append(lane_path.read_bytes())
            if lane_signatures[0] != lane_signatures[1]:
                report["problems"] = ["BYTECODE_CHANGED_BETWEEN_TIERS=" + scenario]
                break
            signatures[scenario] = lane_signatures[0].decode("utf-8").splitlines()
        if "problems" not in report:
            # Equality of full source bytecode is a pre-Graal property. Graal's
            # tier1/tier2 structural graph equality cannot be inferred from
            # identical node counts, compilation success or .bgv file hashes.
            report["pre_graal_instruction_counts"] = {
                k: len(v) for k, v in signatures.items()}
            report["pre_graal_pairwise_equal"] = {
                "create_only_vs_create_and_read": (
                    signatures["create_only"] == signatures["create_and_read"]),
                "literal_vs_create_only": (
                    signatures["literal"] == signatures["create_only"]),
                "literal_vs_create_and_read": (
                    signatures["literal"] == signatures["create_and_read"]),
            }
            report["tier1_graph_equal"] = None
            report["tier2_graph_equal"] = None
            if not report["pre_graal_pairwise_equal"]["create_only_vs_create_and_read"]:
                report["problems"] = ["NORMAL_LOCAL_BYTECODE_DIVERGED"]
                report["result"] = "ACQUISITION_FAILED"
            else:
                report["result"] = "CAPTURE_READY_GRAPH_REVIEW_PENDING"
            report["reason"] = (
                "Six compiler captures and all full bytecode signatures are retained. "
                "Tier 1 and Tier 2 graph convergence requires inspecting/normalizing "
                "the selected BGV graph stages; this tool does not claim equality.")
    dest = artifact / "report.json"
    dest.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("I101_LOCAL_REPORT=" + str(dest))
    for name, equal in report.get("pre_graal_pairwise_equal", {}).items():
        print("I101_LOCAL_BYTECODE_" + name.upper() + "=" + str(equal).lower())
    print("I101_LOCAL_TIER1_GRAPH_EQUAL=UNKNOWN")
    print("I101_LOCAL_TIER2_GRAPH_EQUAL=UNKNOWN")
    print("I101_LOCAL_STATUS=" + report["result"])
    return 0 if report["result"] == "CAPTURE_READY_GRAPH_REVIEW_PENDING" else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--timeout", type=int, default=300, help="Seconds per JVM lane")
    args = parser.parse_args(argv)
    return run(args.root.resolve(), args.timeout)


if __name__ == "__main__":
    sys.exit(main())
