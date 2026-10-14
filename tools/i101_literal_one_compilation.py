#!/usr/bin/env python3
# THE LICENSED WORK IS PROVIDED UNDER THE TERMS OF THE ADAPTIVE PUBLIC LICENSE
# ("LICENSE") AS FIRST COMPLETED BY: Guillermo Adrián Molina. ANY USE, PUBLIC
# DISPLAY, PUBLIC PERFORMANCE, REPRODUCTION OR DISTRIBUTION OF, OR PREPARATION OF
# DERIVATIVE WORKS BASED ON, THE LICENSED WORK CONSTITUTES RECIPIENT'S ACCEPTANCE
# OF THIS LICENSE AND ITS TERMS, WHETHER OR NOT SUCH RECIPIENT READS THE TERMS OF
# THE LICENSE. "LICENSED WORK" AND "RECIPIENT" ARE DEFINED IN THE LICENSE. A COPY
# OF THE LICENSE IS LOCATED IN THE TEXT FILE ENTITLED "LICENSE.TXT" ACCOMPANYING
# THE CONTENTS OF THIS FILE. IF A COPY OF THE LICENSE DOES NOT ACCOMPANY THIS
# FILE, A COPY THEREOF MAY ALSO BE OBTAINED AT THE FOLLOWING WEB SITE:
# https://github.com/guillermomolina/protos
#
# Software distributed under the License is distributed on an "AS IS" basis,
# WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
# the specific language governing rights and limitations under the License.

"""I101-B: inspect the actual compiled root for a one-literal Protos Closure.

Uses the existing target-root catalog, Truffle compiler tracing and BGV capture.
No changes to guest execution, lowering, tests or compilation policy.
"""

import argparse
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from truffle_root_diagnostic import (
    STABLE_ROOT_IDENTITY,
    bgv_files,
    diagnostic_options,
    ordinary_compilation_evidence,
    parse_catalog,
    relative_to_root,
    target_directory,
)
from truffle_jvm_launch import cli_command, run_cli, select_java


GUEST = """run: () => { 1 }
total: 0
n: 0
(() => { n < 200 }).whileTrue(() => {
    total = total + run()
    n = n + 1
})
print(total)
"""
EXPECTED = "200"


def select_literal_root(catalog):
    witness = "run: () => { 1 }"
    if GUEST.count(witness) != 1:
        raise ValueError("AMBIGUOUS_LITERAL_SOURCE")
    index = GUEST.index(witness) + witness.index("1")
    candidates = [
        item for item in catalog["roots"]
        if item["kind"] == "CLOSURE"
        and ("%3Ceval%3E" in item["source"] or "<eval>" in item["source"])
        and item["start"] <= index
        and index + 1 <= item["start"] + item["length"]
    ]
    if not candidates:
        raise ValueError("LITERAL_ROOT_NOT_CATALOGED")
    candidates.sort(key=lambda item: (item["length"], item["start"]))
    if len(candidates) > 1 and candidates[0]["length"] == candidates[1]["length"]:
        raise ValueError("AMBIGUOUS_LITERAL_ROOT")
    return candidates[0]


def run_to_file(root, command, path, timeout):
    env = dict(os.environ, PROTOS_HOME=str(root))
    with path.open("wb") as output:
        result = subprocess.run(
            command, cwd=root, env=env,
            stdout=output, stderr=subprocess.STDOUT,
            timeout=timeout, check=False,
        )
    return result.returncode


def inspect_log(path, selector):
    """Keep the potentially huge assembly log on disk; parse only evidence lines."""
    trace = []
    guest_results = []
    disassembly = False
    hsdis_missing = False
    selected_assembly = False
    matched_headers = []
    with path.open("r", encoding="utf-8", errors="replace") as stream:
        for line in stream:
            trimmed = line.strip()
            if line.lstrip().startswith("[engine]"):
                trace.append(line.rstrip("\n"))
            if trimmed == EXPECTED:
                guest_results.append(trimmed)
            low = trimmed.lower()
            if "hsdis" in low and any(s in low for s in ("could not", "failed", "unable", "not found")):
                hsdis_missing = True
            if "[machcode]" in low or "[verified entry point]" in low:
                disassembly = True
            if selector in trimmed and ("compiled method" in low or "{method}" in low):
                selected_assembly = True
                matched_headers.append(trimmed[:350])
    return trace, guest_results, {
        "hsdis_missing": hsdis_missing,
        "disassembly_detected": disassembly,
        "selected_assembly_attributed": selected_assembly,
        "matching_assembly_headers": matched_headers[:6],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--java", default=None)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--assembly", action="store_true", help="request JVM assembly disassembly (needs hsdis)")
    args = parser.parse_args()

    root = args.root.resolve()
    artifacts = root / "target" / "truffle-compilation"
    parent = target_directory(root, artifacts) / "i101-b-literal-one"
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    directory = parent / f"{stamp}-{os.getpid()}"
    directory.mkdir(parents=True, exist_ok=False)
    (directory / "source.protos").write_text(GUEST, encoding="utf-8")
    report_path = directory / "report.json"
    report = {"status": "ACQUISITION_FAILED", "source": GUEST, "assembly_requested": args.assembly}
    try:
        java = select_java(args.java)
        call = ["-e", GUEST]
        catalog_command = cli_command(root, java, [STABLE_ROOT_IDENTITY], call)
        code, output, _ = run_cli(root, catalog_command, args.timeout)
        (directory / "catalog.log").write_text(output, encoding="utf-8")
        catalog = parse_catalog(output)
        if code or catalog["problems"]:
            raise ValueError(f"INVALID_CATALOG code={code} problems={catalog['problems']}")
        selected = select_literal_root(catalog)
        selector = selected["selector"]
        print("I101_B_SELECTED_ROOT=" + selector)
        print(f"I101_B_SELECTED_SOURCE_SPAN={selected['start']}:{selected['length']}")
        dump_dir = directory / "graal_dumps"
        dump_dir.mkdir()
        options = diagnostic_options(selector, "node", "2", relative_to_root(root, dump_dir))
        options.append("-Djdk.graal.PrintBackendCFG=true")
        if args.assembly:
            options.extend(("-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintAssembly"))
        report["jvm_options"] = options
        report["selected_root"] = selected
        capture_log = directory / "compilation.log"
        code = run_to_file(root, cli_command(root, java, options, call), capture_log, args.timeout)
        trace, outputs, asm = inspect_log(capture_log, selector)
        report["assembly"] = asm
        (directory / "compilation-events.log").write_text(
            "\n".join(trace) + "\n", encoding="utf-8"
        )
        graphs = bgv_files(dump_dir)
        cfgs = sorted(str(path) for path in dump_dir.rglob("*.cfg"))
        report["backend_cfg_files"] = cfgs
        evidence = ordinary_compilation_evidence("\n".join(trace), selector, graphs)
        issues = ([] if code == 0 else [f"JVM_EXIT_CODE={code}"])
        if outputs != [EXPECTED]:
            issues.append(f"GUEST_RESULT expected {[EXPECTED]} got {outputs}")
        issues.extend(evidence["problems"])
        report.update({
            "status": "CAPTURE_READY" if not issues else "EVIDENCE_INCOMPLETE",
            "capture_log": str(capture_log),
            "exit_code": code,
            "expected_output": EXPECTED,
            "observed_output": outputs,
            "graph_evidence": evidence,
            "problems": issues,
        })
        print("I101_B_COMPILED=" + str(evidence["selected_compilations_done"]))
        print("I101_B_BGV_COUNT=" + str(len(evidence["selected_bgv_files"])))
        for path in evidence["selected_bgv_files"]:
            print("I101_B_SELECTED_BGV=" + path)
        print("I101_B_BACKEND_CFG_COUNT=" + str(len(cfgs)))
        for path in cfgs:
            print("I101_B_BACKEND_CFG=" + path)
        print("I101_B_ASSEMBLY_OBSERVED=" + str(asm["disassembly_detected"]))
        print("I101_B_FULL_MACHINE_LOG=" + str(capture_log))
        print("I101_B_ASSEMBLY_ATTRIBUTED=" + str(asm["selected_assembly_attributed"]))
        print("I101_B_HSDIS_MISSING=" + str(asm["hsdis_missing"]))
        for issue in issues:
            print("I101_B_ISSUE=" + issue)
    except (OSError, ValueError, subprocess.TimeoutExpired) as exc:
        report.setdefault("problems", []).append(f"{type(exc).__name__}: {exc}")
    finally:
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print("I101_B_REPORT=" + str(report_path))
        print("I101_B_STATUS=" + report["status"])

    return 0 if report["status"] == "CAPTURE_READY" else 1


if __name__ == "__main__":
    raise SystemExit(main())
