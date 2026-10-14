#!/usr/bin/env python3
# THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
# ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
# DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
# DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
# OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS OF
# THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY
# OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT" ACCOMPANYING
# THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT ACCOMPANY THIS
# FILE, A COPY OF THE LICENSE MAY ALSO BE OBTAINED AT THE FOLLOWING WEB SITE:
# https://github.com/guillermomolina/protos
#
# Software distributed under the License is distributed on an "AS IS" basis,
# WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
# the specific language governing rights and limitations under the License.


"""TEST009-V single-root Truffle/Graal diagnostic: the standard targeted workflow.

Manual escalation after the strict gate (truffle_compilation_gate.py) is red. It
never changes product code or compiler policy; it only observes.

1. `catalog -- <protos test args>` runs the bounded Test Tool invocation once on
   the packaged JVM with only -Dprotos.diagnostic.stableRootIdentity=true (no
   compilation options) and lists every semantic root it lowered:

     ROOT selector=protos-root:<16 hex> kind=<TOP_LEVEL|CLOSURE> source=<uri> start=<n> length=<n> occurrences=<n>

   ordered by source, start, length and kind. A selector is a digest of kind,
   source URI and exact span only: no object address, run-local id or bytecode.
   A logical root lowered more than once (e.g. in several Contexts) keeps one row
   with its occurrence count; one selector reported with different metadata is a
   SELECTOR_COLLISION and fails the catalog.

2. `diagnose --selector protos-root:<16 hex> [--expansion method|node|none]
   [--dump-level 1|2] -- <protos test args including --case <ref>>` compiles only
   that root (engine.CompileOnly=<selector>, CompileImmediately, synchronous,
   CompilationFailureAction=Print, TraceCompilation), with at most one
   per-compilation expansion view (TraceMethodExpansion or TraceNodeExpansion,
   never both, never the run-aggregate expansion statistics) and
   -Djdk.graal.Dump=Truffle:<level> into a fresh directory under target/. The result
   is one of:

     TARGET_COMPILATION_SUCCEEDED
     TARGET_COMPILATION_FAILED_CODE_TOO_LARGE   valid evidence, not a tool failure
     TARGET_COMPILATION_FAILED_OTHER            valid evidence, not a tool failure
     TARGET_NOT_FOUND                           the selector never appeared in the trace
     TARGET_SELECTOR_AMBIGUOUS                  more than one distinct target name matched
     TOOL_ACQUISITION_FAILED                    bad input, launch/timeout/exit failure,
                                                semantic Case failure, or missing evidence

   Several lifecycle events (tiers, retries) of one target name are one target. A
   generated continuation root is named after its source root plus
   "(resume_bci=<n>)", so a root with continuations is reported as ambiguous with
   all matching names listed. The exit status is 0 only for the three
   TARGET_COMPILATION_* results.

   The tool stops at the capture boundary. A TARGET_COMPILATION_* result requires
   the requested trace evidence and at least one fresh non-empty .bgv, and ends with

     TRUFFLE_ROOT_CAPTURE_READY=YES
     TRUFFLE_ROOT_CAPTURE_BOUNDARY=BGV
     TRUFFLE_ROOT_BGV=<path>          one line per retained graph dump

   Every other result prints TRUFFLE_ROOT_CAPTURE_READY=NO and no BGV handoff. BGV
   interpretation is intentionally outside this tool: the retained BGV is the
   handoff artifact for downstream Graal graph analysis.
"""

from __future__ import print_function

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from collections import OrderedDict
from pathlib import Path
from typing import List, Optional, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

from truffle_compilation_gate import COMPILER, ENGINE, corpus_summary  # noqa: E402
from truffle_jvm_launch import CheckError, cli_command, failure_records, is_failure_line, run_cli, select_java  # noqa: E402,E501

STABLE_ROOT_IDENTITY = "-Dprotos.diagnostic.stableRootIdentity=true"
CATALOG_PREFIX = "[protos-root] "
CATALOG_FIELDS = ("selector", "kind", "source", "start", "length")
SELECTOR = re.compile(r"^protos-root:[0-9a-f]{16}$")
TARGET_NAME = re.compile(r"protos-root:[0-9a-f]{16}\[[^\]\s]*\](?:\(resume_bci=[^)\s]*\))?")
ENGINE_COMPILATION_EVENT = re.compile(r"^\s*\[engine\] opt \w+")
EXPANSION_TREE = "Expansion tree for "
CODE_TOO_LARGE_MARKER = "code is too large"
CAPTURE_BOUNDARY = "BGV"

EXPANSION_OPTIONS = OrderedDict([
    ("method", (COMPILER + "TraceMethodExpansion=truffleTier",)),
    ("node", (COMPILER + "TraceNodeExpansion=truffleTier",)),
    ("none", ()),
])
DUMP_LEVELS = ("1", "2")
DEFAULT_EXPANSION = "method"
DEFAULT_DUMP_LEVEL = "1"
CASE_OPTION = "--case"
DIAGNOSTIC_DIR = "root-diagnostic"

SUCCEEDED = "TARGET_COMPILATION_SUCCEEDED"
CODE_TOO_LARGE = "TARGET_COMPILATION_FAILED_CODE_TOO_LARGE"
FAILED_OTHER = "TARGET_COMPILATION_FAILED_OTHER"
NOT_FOUND = "TARGET_NOT_FOUND"
AMBIGUOUS = "TARGET_SELECTOR_AMBIGUOUS"
ACQUISITION_FAILED = "TOOL_ACQUISITION_FAILED"
EVIDENCE_RESULTS = (SUCCEEDED, CODE_TOO_LARGE, FAILED_OTHER)

USAGE = ("usage: make diagnose-truffle-root TRUFFLE_ROOT_SELECTOR=protos-root:<16 hex> "
         "TRUFFLE_ROOT_TEST_ARGS='--case <ref>' [TRUFFLE_ROOT_EXPANSION=method|node|none] "
         "[TRUFFLE_ROOT_DUMP_LEVEL=1|2]; list selectors first with "
         "make truffle-root-catalog TRUFFLE_ROOT_TEST_ARGS='--case <ref>'")


def target_directory(root: Path, artifacts: Path) -> Path:
    """The artifact directory, required to stay inside <root>/target."""
    resolved = artifacts.resolve()
    try:
        resolved.relative_to((root / "target").resolve())
    except ValueError:
        raise CheckError("artifact directory %s is not under %s" % (resolved, root / "target"))
    return resolved


# ---------------------------------------------------------------------------- catalog

def parse_catalog(output: str) -> "OrderedDict[str, object]":
    """Deterministic root catalog from the [protos-root] lines; collisions are explicit problems."""
    roots, problems = OrderedDict(), []
    for line in output.splitlines():
        if not line.startswith(CATALOG_PREFIX):
            continue
        fields = OrderedDict()
        for token in line[len(CATALOG_PREFIX):].split():
            key, separator, value = token.partition("=")
            if separator:
                fields[key] = value
        if tuple(fields) != CATALOG_FIELDS or not SELECTOR.match(fields["selector"]) \
                or not fields["start"].isdigit() or not fields["length"].isdigit():
            problems.append("MALFORMED_CATALOG_LINE %s" % line.strip())
            continue
        metadata = OrderedDict([("selector", fields["selector"]), ("kind", fields["kind"]),
                                ("source", fields["source"]), ("start", int(fields["start"])),
                                ("length", int(fields["length"]))])
        known = roots.get(metadata["selector"])
        if known is None:
            metadata["occurrences"] = 1
            roots[metadata["selector"]] = metadata
        elif all(known[key] == metadata[key] for key in metadata):
            known["occurrences"] += 1
        else:
            problems.append("SELECTOR_COLLISION %s" % metadata["selector"])
    ordered = sorted(roots.values(), key=lambda entry: (entry["source"], entry["start"], entry["length"],
                                                        entry["kind"], entry["selector"]))
    return OrderedDict([("roots", ordered), ("problems", problems)])


def catalog(root: Path, java: str, test_arguments: Sequence[str], timeout: int, artifacts: Path,
            out=sys.stdout) -> int:
    print("truffle-root-catalog: stable semantic-root selectors (no compilation requested)", file=out)
    entry = OrderedDict([("options", [STABLE_ROOT_IDENTITY]), ("test_arguments", list(test_arguments))])
    output, failures, directory = "", [], None
    try:
        directory = target_directory(root, artifacts) / DIAGNOSTIC_DIR
        command = cli_command(root, java, (STABLE_ROOT_IDENTITY,), ["test"] + list(test_arguments))
        exit_code, output, _ = run_cli(root, command, timeout)
        if exit_code != 0:
            failures.append("EXIT_CODE %d" % exit_code)
    except CheckError as error:
        failures.append("ARTIFACT %s" % error)
    except subprocess.TimeoutExpired as expired:
        output = (expired.output or b"").decode("utf-8", errors="replace")
        failures.append("TIMEOUT after %d s" % timeout)
    except OSError as error:
        failures.append("LAUNCH %s" % error)
    parsed = parse_catalog(output)
    failures.extend(parsed["problems"])
    if not parsed["roots"] and not failures:
        failures.append("NO_SEMANTIC_ROOT no %sline in the output" % CATALOG_PREFIX)
    entry["roots"], entry["failures"] = parsed["roots"], failures
    entry["status"] = "FAIL" if failures else "PASS"
    if directory is not None:
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "catalog.log").write_text(output, encoding="utf-8")
        report = directory / "catalog.json"
        report.write_text(json.dumps(entry, indent=2) + "\n", encoding="utf-8")
        print("TRUFFLE_ROOT_CATALOG_REPORT=%s" % report, file=out)
    for found in parsed["roots"]:
        print("ROOT " + " ".join("%s=%s" % (key, value) for key, value in found.items()), file=out)
    for failure in failures:
        print("  " + failure, file=out)
    print("TRUFFLE_ROOT_CATALOG_ROOTS=%d" % len(parsed["roots"]), file=out)
    print("TRUFFLE_ROOT_CATALOG=%s" % entry["status"], file=out)
    return 0 if entry["status"] == "PASS" else 1


# ---------------------------------------------------------------------------- single-root diagnose

def relative_to_root(root: Path, path: Path) -> str:
    return path.resolve().relative_to(root.resolve()).as_posix()


def diagnostic_options(selector: str, expansion: str, dump_level: str, dump_path: str) -> List[str]:
    """The exact single-root JVM options; no compiler limit, inlining or splitting option is touched."""
    return [
        STABLE_ROOT_IDENTITY,
        ENGINE + "AllowExperimentalOptions=true",
        ENGINE + "CompileOnly=" + selector,
        ENGINE + "CompileImmediately=true",
        ENGINE + "BackgroundCompilation=false",
        ENGINE + "CompilationFailureAction=Print",
        ENGINE + "TraceCompilation=true",
        # Observational only: never TreatPerformanceWarningsAsErrors here.
        COMPILER + "TracePerformanceWarnings=all",
    ] + list(EXPANSION_OPTIONS[expansion]) + [
        "-Djdk.graal.Dump=Truffle:" + dump_level,
        "-Djdk.graal.DumpPath=" + dump_path,
    ]


def input_problems(selector: str, expansion: str, dump_level: str, test_arguments: Sequence[str]) -> List[str]:
    problems = []
    if not SELECTOR.match(selector or ""):
        problems.append("INVALID_SELECTOR %r is not protos-root:<16 lowercase hex>" % selector)
    if expansion not in EXPANSION_OPTIONS:
        problems.append("INVALID_EXPANSION %r is not one of %s" % (expansion, "|".join(EXPANSION_OPTIONS)))
    if dump_level not in DUMP_LEVELS:
        problems.append("INVALID_DUMP_LEVEL %r is not one of %s" % (dump_level, "|".join(DUMP_LEVELS)))
    cases = [argument for argument in test_arguments
             if argument == CASE_OPTION or argument.startswith(CASE_OPTION + "=")]
    if len(cases) != 1:
        problems.append("CASE_SELECTION the Test Tool arguments must select exactly one %s <ref>" % CASE_OPTION)
    return problems


def bgv_files(dump_directory: Path) -> List[str]:
    return sorted(str(path) for path in dump_directory.rglob("*.bgv")) if dump_directory.is_dir() else []



# TEST009-V real-JVM calibration, independent of any source root selected by a Test Case.
PROBE_PREFIX = "protos-root:"
PROBE_SUBDIR = "probe"


def probe_options(dump_path: str, filtered: bool) -> List[str]:
    """The two JVM runs differ only in the presence of the CompileOnly filter."""
    options = diagnostic_options(PROBE_PREFIX, "none", "1", dump_path)
    if not filtered:
        options.remove(ENGINE + "CompileOnly=" + PROBE_PREFIX)
    return options


def assess_probe(output: str, exit_code: int, dumps: Sequence[str], filtered: bool):
    """A guest PASS without a real compiled graph is not a successful calibration."""
    from java_generated_bytecode_bci_compilation_check import EXPECTED_OUTPUT

    events = [
        line for line in output.splitlines()
        if ENGINE_COMPILATION_EVENT.match(line)
    ]
    done = [
        line for line in events
        if line.lstrip().startswith("[engine] opt done")
    ]
    named = sorted(set(
        name for line in done for name in TARGET_NAME.findall(line)
    ))
    observed = [
        line.strip() for line in output.splitlines()
        if line.strip() in EXPECTED_OUTPUT
    ]
    problems = []

    if exit_code != 0:
        problems.append("PROGRAM_EXIT_CODE %d" % exit_code)
    if observed != list(EXPECTED_OUTPUT):
        problems.append(
            "SEMANTIC_OUTPUT expected %s got %s"
            % (list(EXPECTED_OUTPUT), observed)
        )
    if not done:
        problems.append("NO_OPT_DONE no completed real Graal compilation")
    if filtered and not named:
        problems.append(
            "NO_FILTERED_SEMANTIC_TARGET CompileOnly did not compile a named semantic root"
        )
    if not dumps:
        problems.append("MISSING_BGV no fresh graph dump")
    for path in dumps:
        if not os.path.isfile(path) or os.path.getsize(path) == 0:
            problems.append("INVALID_BGV %s" % path)

    return OrderedDict([
        ("status", "PASS" if not problems else "FAIL"),
        ("compilation_events", len(events)),
        ("compilations_done", len(done)),
        ("semantic_targets_compiled", named),
        ("bgv_files", list(dumps)),
        ("problems", problems),
    ])


def probe(root: Path, java: str, timeout: int, artifacts: Path, out=sys.stdout) -> int:
    """A paired real compilation distinguishes a broken runtime from failed target naming/filtering."""
    from java_generated_bytecode_bci_compilation_check import PROGRAM

    try:
        directory = target_directory(root, artifacts) / PROBE_SUBDIR
    except CheckError as error:
        print(
            "TRUFFLE_ROOT_PROBE=ACQUISITION_FAILED ARTIFACT %s" % error,
            file=out,
        )
        return 1

    lanes = OrderedDict()

    for label, filtered in (("unfiltered", False), ("filtered", True)):
        lane = directory / label
        if lane.exists():
            shutil.rmtree(lane)
        lane.mkdir(parents=True)

        dumps = lane / "graal_dumps"
        options = probe_options(relative_to_root(root, dumps), filtered)
        log = lane / "probe.log"
        launch_failure = None
        output, code = "", -1

        try:
            command = cli_command(root, java, options, ["-e", PROGRAM])
            code, output, _ = run_cli(root, command, timeout)
        except (OSError, CheckError) as error:
            launch_failure = "LAUNCH %s" % error
        except subprocess.TimeoutExpired as error:
            launch_failure = "TIMEOUT after %d s" % timeout
            output = (error.output or b"").decode(
                "utf-8", errors="replace"
            )

        log.write_text(output, encoding="utf-8")
        record = assess_probe(
            output, code, bgv_files(dumps), filtered
        )
        record["log"] = str(log)
        record["options"] = options

        if launch_failure:
            record["status"] = "FAIL"
            record["problems"].insert(0, launch_failure)

        lanes[label] = record

        print(
            "PROBE_%s=%s" % (label.upper(), record["status"]),
            file=out,
        )
        print(
            "PROBE_%s_COMPILATIONS_DONE=%d"
            % (label.upper(), record["compilations_done"]),
            file=out,
        )
        print(
            "PROBE_%s_NAMED_TARGETS=%d"
            % (label.upper(), len(record["semantic_targets_compiled"])),
            file=out,
        )
        print(
            "PROBE_%s_BGV=%d"
            % (label.upper(), len(record["bgv_files"])),
            file=out,
        )
        print(
            "PROBE_%s_LOG=%s" % (label.upper(), log),
            file=out,
        )
        for problem in record["problems"]:
            print("  %s" % problem, file=out)

    if lanes["unfiltered"]["status"] != "PASS":
        outcome = "BASELINE_FAILED"
    elif lanes["filtered"]["status"] != "PASS":
        outcome = "FILTER_OR_NAMING_FAILED"
    else:
        outcome = "PASS"

    report = directory / "probe.json"
    report.write_text(
        json.dumps({"status": outcome, "lanes": lanes}, indent=2) + "\n",
        encoding="utf-8",
    )
    print("TRUFFLE_ROOT_PROBE_REPORT=%s" % report, file=out)
    print("TRUFFLE_ROOT_PROBE=%s" % outcome, file=out)
    return 0 if outcome == "PASS" else 1


def classify(output: str, exit_code: Optional[int], selector: str, expansion: str,
             bgv: Sequence[str]) -> "OrderedDict[str, object]":
    """Fail-closed classification of one single-root diagnostic run from its retained evidence."""
    names = []
    for line in output.splitlines():
        if ENGINE_COMPILATION_EVENT.match(line):
            for name in TARGET_NAME.findall(line):
                if selector in name and name not in names:
                    names.append(name)
    records = [record for record in failure_records(output, is_failure_line)
               if selector in record.splitlines()[0]]
    done = any(line.lstrip().startswith("[engine] opt done") and selector in line for line in output.splitlines())
    expansion_trees = sum(1 for line in output.splitlines() if EXPANSION_TREE in line and selector in line)
    summary = corpus_summary(output)
    problems = []
    if exit_code != 0:
        problems.append("EXIT_CODE %s" % exit_code)
    if summary is None or summary[1] != 0 or summary[0] == 0:
        problems.append("SEMANTIC_CORPUS %s" % ("no Test Tool summary" if summary is None
                                                 else "%d passed, %d failed" % summary))
    if problems:
        result = ACQUISITION_FAILED
    elif not names:
        result = NOT_FOUND
    elif len(names) > 1:
        result = AMBIGUOUS
    elif any(CODE_TOO_LARGE_MARKER in record.lower() for record in records):
        result = CODE_TOO_LARGE
    elif records:
        result = FAILED_OTHER
    elif done:
        result = SUCCEEDED
    else:
        result = ACQUISITION_FAILED
        problems.append("NO_TERMINAL_COMPILATION_EVENT for %s" % names[0])
    if result in EVIDENCE_RESULTS:
        if not bgv:
            problems.append("MISSING_BGV no .bgv graph dump was produced")
        for path in bgv:
            if not os.path.isfile(path):
                problems.append("MISSING_BGV %s does not exist" % path)
            elif os.path.getsize(path) == 0:
                problems.append("EMPTY_BGV %s is empty" % path)
        # A failure before the Truffle tier completes legitimately prints no expansion tree.
        if expansion != "none" and result != FAILED_OTHER and expansion_trees == 0:
            problems.append("MISSING_EXPANSION_TREE no '%s<target> after truffleTier' trace" % EXPANSION_TREE)
        if problems:
            result = ACQUISITION_FAILED
    evidence = OrderedDict()
    evidence["result"] = result
    evidence["target_names"] = names
    evidence["compilation_events_seen"] = sum(
        1 for line in output.splitlines()
        if ENGINE_COMPILATION_EVENT.match(line)
    )
    evidence["selected_root_catalog_occurrences"] = sum(
        1 for line in output.splitlines()
        if line.startswith(CATALOG_PREFIX)
        and "selector=" + selector + " " in line
    )
    if result == NOT_FOUND and evidence["compilation_events_seen"] == 0:
        problems.append(
            "NO_COMPILATION_EVENTS selected root was cataloged but no target compilation was traced; "
            "this does not prove the root was executed or that its name matched CompileOnly"
        )
    evidence["failure_records"] = records
    evidence["expansion_trees"] = expansion_trees
    evidence["bgv_files"] = list(bgv)
    evidence["problems"] = problems
    evidence["capture_ready"] = result in EVIDENCE_RESULTS
    return evidence


def diagnose(root: Path, java: str, selector: str, expansion: str, dump_level: str,
             test_arguments: Sequence[str], timeout: int, artifacts: Path, out=sys.stdout) -> int:
    print("truffle-root-diagnostic: compile one selected semantic root (observation only)", file=out)
    report = OrderedDict([("selector", selector), ("expansion", expansion), ("dump_level", dump_level),
                          ("test_arguments", list(test_arguments))])
    problems = input_problems(selector, expansion, dump_level, test_arguments)
    directory = None
    if not problems:
        try:
            directory = target_directory(root, artifacts) / DIAGNOSTIC_DIR / selector.split(":", 1)[1]
        except CheckError as error:
            problems.append("ARTIFACT %s" % error)
    if problems:
        report.update(classify("", None, selector or "", expansion, []))
        report["result"], report["problems"] = ACQUISITION_FAILED, problems
        report["capture_ready"] = False
        return finish(report, None, out, usage=True)
    dumps = directory / "graal_dumps"
    if directory.exists():
        shutil.rmtree(str(directory))
    directory.mkdir(parents=True)
    options = diagnostic_options(selector, expansion, dump_level, relative_to_root(root, dumps))
    report["options"] = options
    output, exit_code = "", None
    try:
        command = cli_command(root, java, options, ["test"] + list(test_arguments))
        exit_code, output, elapsed = run_cli(root, command, timeout)
        report["elapsed_seconds"] = round(elapsed, 1)
        launch_problem = None
    except CheckError as error:
        launch_problem = "ARTIFACT %s" % error
    except subprocess.TimeoutExpired as expired:
        output = (expired.output or b"").decode("utf-8", errors="replace")
        launch_problem = "TIMEOUT after %d s" % timeout
    except OSError as error:
        launch_problem = "LAUNCH %s" % error
    log = directory / "diagnostic.log"
    log.write_text(output, encoding="utf-8")
    report["log"], report["dump_path"] = str(log), str(dumps)
    report.update(classify(output, exit_code, selector, expansion, bgv_files(dumps)))
    if launch_problem is not None:
        report["result"] = ACQUISITION_FAILED
        report["problems"] = [launch_problem] + report["problems"]
        report["capture_ready"] = False
    return finish(report, directory, out)


def finish(report, directory: Optional[Path], out, usage: bool = False) -> int:
    if directory is not None:
        path = directory / "report.json"
        path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print("TRUFFLE_ROOT_DIAGNOSTIC_REPORT=%s" % path, file=out)
    print("TRUFFLE_ROOT_SELECTOR=%s" % report["selector"], file=out)
    for name in report.get("target_names", []):
        print("TRUFFLE_ROOT_TARGET=%s" % name, file=out)
    for record in report.get("failure_records", []):
        print("  " + record.splitlines()[0].strip(), file=out)
    if "log" in report:
        print("TRUFFLE_ROOT_DIAGNOSTIC_LOG=%s" % report["log"], file=out)
    if report["result"] == NOT_FOUND:
        print(
            "TRUFFLE_ROOT_COMPILATION_EVENTS=%d"
            % report.get("compilation_events_seen", 0),
            file=out,
        )
        print(
            "TRUFFLE_ROOT_CATALOG_OCCURRENCES=%d"
            % report.get("selected_root_catalog_occurrences", 0),
            file=out,
        )
    for problem in report["problems"]:
        print("  " + problem, file=out)
    if usage:
        print(USAGE, file=out)
    ready = report["capture_ready"]
    print("TRUFFLE_ROOT_DIAGNOSTIC=%s" % report["result"], file=out)
    print("TRUFFLE_ROOT_CAPTURE_READY=%s" % ("YES" if ready else "NO"), file=out)
    if ready:
        print("TRUFFLE_ROOT_CAPTURE_BOUNDARY=%s" % CAPTURE_BOUNDARY, file=out)
        for bgv in report["bgv_files"]:
            print("TRUFFLE_ROOT_BGV=%s" % bgv, file=out)
    return 0 if ready else 1



# TEST009-V: ordinary-Closure compiled graph acquisition
# The Test Tool's one-shot --case is NOT a compilation workload; cataloging a
# root is not evidence it was executed or compiled. Keep the existing focused
# single-root diagnostic unchanged, and use this opt-in, known-compiling guest
# program when a graph of an ordinary Closure call is specifically required.
ORDINARY_CAPTURE_NAME = "ordinary-closure-sample"
ORDINARY_CALL_SOURCE = "sum3(i, 1, 2)"


def ordinary_program():
    """Minimal C3 literal source-Closure call, repeated inside its real caller.

    Do not embed this in the complex TEST009-D exception/ensure/nonlocal-return
    stress test: that unrelated work bloats the generated machine code.
    Work's root is entered 200 times and the C3 result contributes to output.
    """
    guest = """work: (i) => {
    sum3: (x, y, z) => x + y + z
    sum3(i, 1, 2)
}
total: 0
n: 0
(() => { n < 200 }).whileTrue(() => {
    total = total + work(n)
    n = n + 1
})
print(total)
"""
    if guest.count(ORDINARY_CALL_SOURCE) != 1:
        raise CheckError("I096 ordinary call source must occur exactly once")
    # sum(i + 3) for i in 0..199 = 19900 + 600 = 20500.
    return guest, ("20500",)


def ordinary_root(catalog_data, program):
    """Find the physical owner of the C2 call, not the identity callee or Test wrapper."""
    call_offset = program.index(ORDINARY_CALL_SOURCE)
    candidates = [
        entry for entry in catalog_data["roots"]
        if entry["kind"] == "CLOSURE"
        and ("%3Ceval%3E" in entry["source"] or "<eval>" in entry["source"])
        and entry["start"] <= call_offset
        and call_offset + len(ORDINARY_CALL_SOURCE) <= entry["start"] + entry["length"]
    ]
    if not candidates:
        raise CheckError("CATALOG_NO_ENCLOSING_WORK_CLOSURE: no literal-call owner root")
    candidates.sort(key=lambda item: (item["length"], item["start"], item["selector"]))
    if len(candidates) > 1 and candidates[0]["length"] == candidates[1]["length"]:
        raise CheckError("CATALOG_AMBIGUOUS_WORK_CLOSURE: same-sized owner roots")
    return candidates[0]


def ordinary_output_problems(output, expected):
    """Require the precise guest output; compiler trace text is not a result."""
    observed = [line.strip() for line in output.splitlines() if line.strip() in set(expected)]
    return [] if observed == list(expected) else [
        "SEMANTIC_OUTPUT expected %r, observed %r" % (list(expected), observed)
    ]


def ordinary_compilation_evidence(output, selector, graph_files):
    """Attribute success, failure and BGV to the *selected* semantic root.

    Unrelated JVM targets may compile or bail out. They remain reported but
    cannot invalidate a successful selected-root capture. Names in BGV paths
    are target labels, not proof of a particular IR shape.
    """
    events = [line for line in output.splitlines() if ENGINE_COMPILATION_EVENT.match(line)]
    done = [line for line in events if line.lstrip().startswith("[engine] opt done")]
    selected_events = [line for line in events
                       if any(selector in name for name in TARGET_NAME.findall(line))]
    selected_done = [line for line in selected_events if line.lstrip().startswith("[engine] opt done")]
    records = failure_records(output, is_failure_line)
    selected_failures = [record for record in records if selector in record.splitlines()[0]]
    unrelated_failures = [record for record in records if selector not in record.splitlines()[0]]
    selected_bgv = [path for path in graph_files if selector in Path(path).name]
    valid_selected_bgv = [path for path in selected_bgv
                          if Path(path).is_file() and Path(path).stat().st_size > 0]
    invalid_bgv = [path for path in graph_files
                   if not Path(path).is_file() or Path(path).stat().st_size == 0]
    issues = []
    if not done:
        issues.append("NO_COMPILATION: no Graal 'opt done' event")
    if not selected_events:
        issues.append("SELECTED_ROOT_NOT_COMPILED: no selected target event")
    if not selected_done:
        issues.append("SELECTED_ROOT_NO_OPT_DONE: no successful selected target compilation")
    if selected_failures:
        issues.append("SELECTED_ROOT_COMPILATION_FAILURES=%d" % len(selected_failures))
    if not selected_bgv:
        issues.append("SELECTED_ROOT_MISSING_BGV: no graph filename contains selected selector")
    if selected_bgv and not valid_selected_bgv:
        issues.append("SELECTED_ROOT_INVALID_BGV: no nonempty selected graph")
    if invalid_bgv:
        issues.append("INVALID_BGV=%r" % invalid_bgv)
    return {
        "compilation_events": len(events),
        "compilations_done": len(done),
        "selected_compilation_events": len(selected_events),
        "selected_compilations_done": len(selected_done),
        "selected_compilation_failures": len(selected_failures),
        "unrelated_compilation_failures": len(unrelated_failures),
        "permanent_compilation_failures": len(records),
        "selected_bgv_files": valid_selected_bgv,
        "bgv_files": graph_files,
        "problems": issues,
    }


def ordinary_closure(root, java, timeout, artifacts, out=sys.stdout):
    """Two fresh JVMs: catalog then unfiltered real compiler graph acquisition.

    Unlike `diagnose`, does not promise a named Closure target compilation on a
    one-shot Test Tool Case. Fail closed if execution, compilation or graph proof
    is absent. Neither silently interprets BGV nor claims tier stabilization.
    """
    try:
        directory = target_directory(root, artifacts) / ORDINARY_CAPTURE_NAME
    except CheckError as error:
        print("I096_CAPTURE=INVALID_ARTIFACT %s" % error, file=out)
        return 1
    directory.mkdir(parents=True, exist_ok=True)
    report_path = directory / "report.json"
    report = OrderedDict([
        ("schema", "protos.test009-v.ordinary-closure/v2"),
        ("status", "ACQUISITION_FAILED"),
        ("phase", "START"),
    ])
    try:
        guest, expected = ordinary_program()
        call = ["-e", guest]
        # Catalog must not enable compilation. It records the same source text
        # and semantic-root spans that will be used by the capture JVM.
        catalog_command = cli_command(root, java, [STABLE_ROOT_IDENTITY], call)
        catalog_code, catalog_text, _ = run_cli(root, catalog_command, timeout)
        (directory / "catalog.log").write_text(catalog_text, encoding="utf-8")
        parsed = parse_catalog(catalog_text)
        report["catalog_roots"] = len(parsed["roots"])
        if catalog_code or parsed["problems"]:
            raise CheckError("ROOT_CATALOG_INVALID code=%s problems=%s" %
                             (catalog_code, parsed["problems"][:3]))
        if not parsed["roots"]:
            raise CheckError("ROOT_CATALOG_EMPTY")
        chosen = ordinary_root(parsed, guest)
        selector = chosen["selector"]
        report["selected_root"] = chosen
        report["phase"] = "COMPILE"
        print("I096_SELECTED_ROOT=%s" % selector, file=out)

        dumps = directory / "graal_dumps"
        if dumps.exists():
            shutil.rmtree(dumps)
        dumps.mkdir(parents=True)
        options = diagnostic_options(selector, "none", "1", relative_to_root(root, dumps))
        # Do not force a named CompileOnly target when the actual guest program
        # might inline it before Graal would separately compile it.
        options = [opt for opt in options if not opt.startswith(ENGINE + "CompileOnly=")]
        report["options"] = options
        code, output, elapsed = run_cli(root, cli_command(root, java, options, call), timeout)
        log = directory / "diagnostic.log"
        log.write_text(output, encoding="utf-8")
        report["log"] = str(log)
        report["elapsed_seconds"] = round(elapsed, 1)
        graph_files = bgv_files(dumps)
        evidence = ordinary_compilation_evidence(output, selector, graph_files)
        problems = (["GUEST_EXIT_CODE=%d" % code] if code else [])
        problems.extend(ordinary_output_problems(output, expected))
        problems.extend(evidence["problems"])
        report.update({
            "phase": "FINISHED",
            "exit_code": code,
            "expected_output": list(expected),
            "graph_attribution": "BGV_FILENAME_MATCHES_SELECTED_TARGET",
            **{key: value for key, value in evidence.items() if key != "problems"},
            "problems": problems,
            "status": "CAPTURE_READY" if not problems else "EVIDENCE_INCOMPLETE",
        })


    except (CheckError, OSError, subprocess.TimeoutExpired, ValueError, KeyError) as error:
        report["status"] = "ACQUISITION_FAILED"
        report.setdefault("problems", []).append("%s: %s" % (type(error).__name__, error))
    finally:
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print("I096_REPORT=%s" % report_path, file=out)
        print("I096_STATUS=%s" % report["status"], file=out)
        print("I096_COMPILATIONS_DONE=%s" % report.get("compilations_done", 0), file=out)
        print("I096_SELECTED_COMPILATION_EVENTS=%s" % report.get("selected_compilation_events", 0), file=out)
        print("I096_SELECTED_COMPILATIONS_DONE=%s" % report.get("selected_compilations_done", 0), file=out)
        print("I096_SELECTED_COMPILATION_FAILURES=%s" % report.get("selected_compilation_failures", 0), file=out)
        print("I096_UNRELATED_COMPILATION_FAILURES=%s" % report.get("unrelated_compilation_failures", 0), file=out)
        for bgv in report.get("selected_bgv_files", []):
            print("I096_SELECTED_BGV=%s" % bgv, file=out)
        for problem in report.get("problems", []):
            print("  %s" % problem, file=out)
    return 0 if report["status"] == "CAPTURE_READY" else 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=("catalog", "diagnose", "probe", "ordinary-closure"))
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--java", default=None)
    parser.add_argument("--timeout", type=int, default=3600, help="JVM timeout in seconds")
    parser.add_argument("--artifacts", type=Path, default=Path("target/truffle-compilation"),
                        help="must be under <root>/target")
    parser.add_argument("--selector", default="", help="diagnose only: protos-root:<16 hex> from the catalog")
    parser.add_argument("--expansion", default=DEFAULT_EXPANSION,
                        help="diagnose only: method | node | none (default: %(default)s)")
    parser.add_argument("--dump-level", default=DEFAULT_DUMP_LEVEL,
                        help="diagnose only: Graal Dump=Truffle:<level>, 1 or 2 (default: %(default)s)")
    parser.epilog = "Arguments after -- are passed to `protos test` (e.g. -- --case <ref>)."
    argv = list(sys.argv[1:] if argv is None else argv)
    split = argv.index("--") if "--" in argv else len(argv)
    args = parser.parse_args(argv[:split])
    test_arguments = argv[split + 1:]
    root = args.root.resolve()
    java = select_java(args.java)
    artifacts = args.artifacts if args.artifacts.is_absolute() else root / args.artifacts
    if args.command == "ordinary-closure":
        if test_arguments:
            parser.error("ordinary-closure accepts no Test Tool --case arguments")
        return ordinary_closure(root, java, args.timeout, artifacts)
    if args.command == "catalog":
        return catalog(root, java, test_arguments, args.timeout, artifacts)
    if args.command == "probe":
        if test_arguments:
            parser.error(
                "probe runs an internal controlled guest program and accepts no --case arguments"
            )
        return probe(root, java, args.timeout, artifacts)
    return diagnose(root, java, args.selector, args.expansion, args.dump_level, test_arguments,
                    args.timeout, artifacts)


if __name__ == "__main__":
    sys.exit(main())
