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


"""Harness tests for the TEST009-V/V2 single-root Truffle/Graal diagnostic (no JVM is started)."""

from __future__ import print_function

import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import truffle_compilation_gate as gate  # noqa: E402
import truffle_root_diagnostic as diag  # noqa: E402
from test_truffle_compilation_gate import fake_root  # noqa: E402

SELECTOR = "protos-root:0123456789abcdef"
OTHER = "protos-root:fedcba9876543210"
NAME = SELECTOR + "[CLOSURE|file:///w/call.protos|10+20]"
CONTINUATION = NAME + "(resume_bci=12)"
SUMMARY = "1 passed, 0 failed"
CASE_ARGS = ["--case", "v1.AAA"]


def start(name, tier=1):
    return "[engine] opt start  engine=1  id=7     %-50s |Tier %d|Priority 1|Rate 0" % (name, tier)


def done(name, tier=1):
    return "[engine] opt done   engine=1  id=7     %-50s |Tier %d|Time 10( 8+2 )ms|AST 4" % (name, tier)


def failed(name, reason, tier=2):
    return "[engine] opt failed engine=1  id=7     %-50s |Tier %d|Time 10|Reason: %s" % (name, tier, reason)


def tree(name):
    return "[engine] Expansion tree for %s after truffleTier:" % name


CODE_TOO_LARGE = "jdk.vm.ci.code.BailoutException: Code installation failed: code is too large"
TOO_DEEP = "PermanentBailoutException: Too deep inlining, probably caused by recursive inlining."


def catalog_line(selector=SELECTOR, kind="CLOSURE", source="file:///w/call.protos", start=10, length=20):
    return "[protos-root] selector=%s kind=%s source=%s start=%d length=%d" % (selector, kind, source, start,
                                                                                length)


class OptionTest(unittest.TestCase):

    def options(self, expansion="method", level="1"):
        return diag.diagnostic_options(SELECTOR, expansion, level, "target/truffle-compilation/x/graal_dumps")

    def test_compile_only_receives_the_exact_selector_with_the_standard_workflow(self):
        options = self.options()
        self.assertIn("-Dpolyglot.engine.CompileOnly=" + SELECTOR, options)
        for option in ("AllowExperimentalOptions=true", "CompileImmediately=true", "BackgroundCompilation=false",
                       "CompilationFailureAction=Print", "TraceCompilation=true"):
            self.assertIn("-Dpolyglot.engine." + option, options)
        self.assertIn(diag.STABLE_ROOT_IDENTITY, options)

    def test_graph_dump_is_truffle_1_by_default_into_the_given_target_path(self):
        options = self.options(level=diag.DEFAULT_DUMP_LEVEL)
        self.assertIn("-Djdk.graal.Dump=Truffle:1", options)
        self.assertEqual([option for option in options if option.startswith("-Djdk.graal.DumpPath=")],
                         ["-Djdk.graal.DumpPath=target/truffle-compilation/x/graal_dumps"])

    def test_each_expansion_view_enables_only_itself_and_never_statistics(self):
        expected = {"method": ["TraceMethodExpansion"], "node": ["TraceNodeExpansion"], "none": []}
        for expansion, names in expected.items():
            with self.subTest(expansion):
                options = self.options(expansion)
                self.assertEqual([option.split(".")[2].split("=")[0] for option in options
                                  if "Expansion" in option], names)
                self.assertFalse([option for option in options if "Statistics" in option])

    def test_no_superseded_causal_trace_strict_or_policy_option(self):
        for expansion in diag.EXPANSION_OPTIONS:
            options = self.options(expansion)
            for forbidden in ("protos.compilerability", "TreatPerformanceWarningsAsErrors", "ExitVM",
                              "MaximumGraalGraphSize", "Inlining", "Splitting", "PartialBlock"):
                self.assertFalse([option for option in options if forbidden in option], forbidden)

    def test_strict_gate_modes_are_unchanged(self):
        common = (
            "-Djdk.graal.DumpPath=target/truffle-compilation/graal_dumps",
            "-Dpolyglot.engine.AllowExperimentalOptions=true",
            "-Dpolyglot.engine.CompileImmediately=true",
            "-Dpolyglot.engine.CompilationFailureAction=ExitVM",
            "-Dpolyglot.compiler.TreatPerformanceWarningsAsErrors=all",
            "-Dpolyglot.engine.TraceCompilation=true",
            "-Dpolyglot.compiler.TracePerformanceWarnings=all",
        )
        self.assertEqual(gate.MODES[gate.SYNC], common + ("-Dpolyglot.engine.BackgroundCompilation=false",))
        self.assertEqual(gate.MODES[gate.BACKGROUND], common)


class InputTest(unittest.TestCase):

    def test_inputs_are_validated(self):
        self.assertEqual(diag.input_problems(SELECTOR, "method", "1", CASE_ARGS), [])
        for selector in ("", "protos-root:0123", "ProtosSemanticBytecodeRootNodeGen@59ef67f9",
                         SELECTOR + ",x", SELECTOR.upper(), "protos-root:"):
            self.assertTrue(diag.input_problems(selector, "method", "1", CASE_ARGS), selector)
        self.assertTrue(diag.input_problems(SELECTOR, "both", "1", CASE_ARGS))
        self.assertTrue(diag.input_problems(SELECTOR, "method", "3", CASE_ARGS))
        self.assertTrue(diag.input_problems(SELECTOR, "method", "1", []))
        self.assertTrue(diag.input_problems(SELECTOR, "method", "1", CASE_ARGS + ["--case=v1.BBB"]))

    def test_artifacts_must_stay_under_target(self):
        with tempfile.TemporaryDirectory() as directory:
            root = fake_root(directory)
            self.assertEqual(diag.target_directory(root, root / "target" / "x"), (root / "target" / "x").resolve())
            with self.assertRaises(diag.CheckError):
                diag.target_directory(root, root / "elsewhere")
            with self.assertRaises(diag.CheckError):
                diag.target_directory(root, root / "target" / ".." / "out")


class ClassifyTest(unittest.TestCase):

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.dumps = Path(directory.name)
        self.BGV = [self.bgv("TruffleHotSpotCompilation-1.bgv", b"BIGV")]

    def bgv(self, name, content):
        path = self.dumps / name
        path.write_bytes(content)
        return str(path)

    def classify(self, lines, expansion="method", bgv=None, exit_code=0):
        output = "\n".join(lines + [SUMMARY]) + "\n"
        return diag.classify(output, exit_code, SELECTOR, expansion, self.BGV if bgv is None else bgv)

    def test_success(self):
        evidence = self.classify([start(NAME), tree(NAME), done(NAME)])
        self.assertEqual((evidence["result"], evidence["target_names"]), (diag.SUCCEEDED, [NAME]))

    def test_code_too_large_of_the_selected_target_is_valid_evidence(self):
        evidence = self.classify([start(NAME), done(NAME), start(NAME, 2), tree(NAME),
                                  failed(NAME, CODE_TOO_LARGE)])
        self.assertEqual((evidence["result"], evidence["problems"]), (diag.CODE_TOO_LARGE, []))
        self.assertEqual(len(evidence["failure_records"]), 1)

    def test_other_failure_is_valid_evidence_even_without_expansion_tree(self):
        evidence = self.classify([start(NAME), failed(NAME, TOO_DEEP)])
        self.assertEqual(evidence["result"], diag.FAILED_OTHER)

    def test_tier_and_retry_events_of_one_name_are_not_ambiguous(self):
        evidence = self.classify([start(NAME), done(NAME), start(NAME, 2), tree(NAME), done(NAME, 2),
                                  start(NAME, 2), done(NAME, 2)])
        self.assertEqual(evidence["result"], diag.SUCCEEDED)

    def test_zero_matches_is_target_not_found(self):
        other = OTHER + "[CLOSURE|file:///w/x.protos|1+2]"
        evidence = self.classify([start(other), done(other), "mentions " + SELECTOR + " outside the trace"])
        self.assertEqual(evidence["result"], diag.NOT_FOUND)

    def test_several_distinct_target_names_fail_closed(self):
        evidence = self.classify([start(NAME), tree(NAME), done(NAME), start(CONTINUATION), done(CONTINUATION)])
        self.assertEqual((evidence["result"], evidence["target_names"]), (diag.AMBIGUOUS, [NAME, CONTINUATION]))

    def test_missing_bgv_fails_closed(self):
        evidence = self.classify([start(NAME), tree(NAME), failed(NAME, CODE_TOO_LARGE)], bgv=[])
        self.assertEqual(evidence["result"], diag.ACQUISITION_FAILED)
        self.assertTrue(evidence["problems"][0].startswith("MISSING_BGV"))

    def test_empty_or_vanished_bgv_is_not_capture_evidence(self):
        lines = [start(NAME), tree(NAME), failed(NAME, CODE_TOO_LARGE)]
        empty = self.classify(lines, bgv=self.BGV + [self.bgv("empty.bgv", b"")])
        self.assertEqual((empty["result"], empty["capture_ready"]), (diag.ACQUISITION_FAILED, False))
        self.assertTrue(empty["problems"][0].startswith("EMPTY_BGV"))
        vanished = self.classify(lines, bgv=[str(self.dumps / "gone.bgv")])
        self.assertEqual(vanished["result"], diag.ACQUISITION_FAILED)
        self.assertTrue(vanished["problems"][0].startswith("MISSING_BGV"))

    def test_only_evidence_results_are_capture_ready(self):
        self.assertTrue(self.classify([start(NAME), tree(NAME), failed(NAME, CODE_TOO_LARGE)])["capture_ready"])
        self.assertTrue(self.classify([start(NAME), tree(NAME), done(NAME)])["capture_ready"])
        self.assertFalse(self.classify([])["capture_ready"])
        self.assertFalse(self.classify([start(NAME), done(NAME), start(CONTINUATION)])["capture_ready"])

    def test_missing_requested_expansion_tree_fails_closed(self):
        self.assertEqual(self.classify([start(NAME), done(NAME)])["result"], diag.ACQUISITION_FAILED)
        self.assertEqual(self.classify([start(NAME), done(NAME)], expansion="none")["result"], diag.SUCCEEDED)

    def test_launch_or_semantic_failure_is_acquisition_failure(self):
        self.assertEqual(self.classify([start(NAME), tree(NAME), done(NAME)], exit_code=1)["result"],
                         diag.ACQUISITION_FAILED)
        output = "\n".join([start(NAME), tree(NAME), done(NAME), "0 passed, 1 failed"])
        self.assertEqual(diag.classify(output, 0, SELECTOR, "method", self.BGV)["result"], diag.ACQUISITION_FAILED)
        self.assertEqual(self.classify([start(NAME)])["result"], diag.ACQUISITION_FAILED)


class CatalogTest(unittest.TestCase):

    def test_catalog_is_deterministic_address_free_and_counts_duplicates(self):
        lines = [catalog_line(start=40), "noise ProtosSemanticBytecodeRootNodeGen@59ef67f9",
                 catalog_line(OTHER, "TOP_LEVEL", start=0, length=99), catalog_line(start=40)]
        first = diag.parse_catalog("\n".join(lines))
        second = diag.parse_catalog("\n".join(reversed(lines)))
        self.assertEqual(first, second)
        self.assertEqual(first["problems"], [])
        self.assertEqual([(root["selector"], root["start"], root["occurrences"]) for root in first["roots"]],
                         [(OTHER, 0, 1), (SELECTOR, 40, 2)])
        self.assertNotIn("@", json.dumps(first))

    def test_collision_and_malformed_lines_are_explicit(self):
        parsed = diag.parse_catalog("\n".join([catalog_line(), catalog_line(start=11),
                                               "[protos-root] selector=bad kind=CLOSURE"]))
        self.assertEqual([problem.split()[0] for problem in parsed["problems"]],
                         ["SELECTOR_COLLISION", "MALFORMED_CATALOG_LINE"])

    def test_catalog_runs_without_compilation_options(self):
        with tempfile.TemporaryDirectory() as directory:
            root = fake_root(directory)
            out = io.StringIO()
            with mock.patch.object(diag, "run_cli", return_value=(0, catalog_line() + "\n" + SUMMARY, 1.0)) as run:
                status = diag.catalog(root, "java", CASE_ARGS, 10, root / "target" / "tc", out=out)
            command = run.call_args[0][1]
            self.assertEqual(status, 0)
            self.assertIn(diag.STABLE_ROOT_IDENTITY, command)
            self.assertFalse([option for option in command if option.startswith("-Dpolyglot.")
                              or option.startswith("-Djdk.graal.")])
            self.assertEqual(command[-3:], ["test"] + CASE_ARGS)
            self.assertIn("ROOT selector=%s kind=CLOSURE source=file:///w/call.protos start=10 length=20 "
                          "occurrences=1" % SELECTOR, out.getvalue().splitlines())
            self.assertTrue((root / "target" / "tc" / "root-diagnostic" / "catalog.json").is_file())

    def test_empty_catalog_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = fake_root(directory)
            with mock.patch.object(diag, "run_cli", return_value=(0, SUMMARY, 1.0)):
                self.assertEqual(diag.catalog(root, "java", CASE_ARGS, 10, root / "target", out=io.StringIO()), 1)


class DiagnoseTest(unittest.TestCase):

    def run_diagnose(self, output, write_bgv=True, selector=SELECTOR, arguments=CASE_ARGS, raises=None):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = fake_root(directory.name)
        commands = []

        def fake_run(_root, command, _timeout):
            commands.append(command)
            if raises is not None:
                raise raises
            dump = [option for option in command if option.startswith("-Djdk.graal.DumpPath=")][0].split("=", 1)[1]
            if write_bgv:
                (root / dump).mkdir(parents=True, exist_ok=True)
                (root / dump / "TruffleHotSpotCompilation-1.bgv").write_bytes(b"BIGV")
            return 0, output, 1.0
        out = io.StringIO()
        with mock.patch.object(diag, "run_cli", side_effect=fake_run):
            status = diag.diagnose(root, "java", selector, "method", "1", arguments, 10,
                                   root / "target" / "truffle-compilation", out=out)
        return status, commands, out.getvalue(), root

    def test_code_too_large_run_retains_bgv_log_and_report_under_target(self):
        output = "\n".join([start(NAME), tree(NAME), failed(NAME, CODE_TOO_LARGE), SUMMARY])
        status, commands, out, root = self.run_diagnose(output)
        self.assertEqual(status, 0)
        self.assertIn("TRUFFLE_ROOT_DIAGNOSTIC=%s" % diag.CODE_TOO_LARGE, out.splitlines())
        directory = root / "target" / "truffle-compilation" / "root-diagnostic" / "0123456789abcdef"
        report = json.loads((directory / "report.json").read_text())
        self.assertEqual(report["result"], diag.CODE_TOO_LARGE)
        self.assertEqual(len(report["bgv_files"]), 1)
        Path(report["bgv_files"][0]).resolve().relative_to((directory / "graal_dumps").resolve())
        self.assertIn("-Djdk.graal.DumpPath=target/truffle-compilation/root-diagnostic/0123456789abcdef/graal_dumps",
                      commands[0])
        self.assertIn("-Dpolyglot.engine.CompileOnly=" + SELECTOR, commands[0])
        self.assertEqual(commands[0][-3:], ["test"] + CASE_ARGS)
        self.assertTrue((directory / "diagnostic.log").read_text().startswith("[engine] opt start"))

    def test_stale_bgv_from_an_earlier_run_is_not_evidence(self):
        output = "\n".join([start(NAME), tree(NAME), done(NAME), SUMMARY])
        status, _, _, root = self.run_diagnose(output)
        self.assertEqual(status, 0)
        stale = root / "target" / "truffle-compilation" / "root-diagnostic" / "0123456789abcdef" / "graal_dumps"
        self.assertTrue(list(stale.glob("*.bgv")))
        with mock.patch.object(diag, "run_cli", return_value=(0, output, 1.0)):
            status = diag.diagnose(root, "java", SELECTOR, "method", "1", CASE_ARGS, 10,
                                   root / "target" / "truffle-compilation", out=io.StringIO())
        self.assertEqual(status, 1)

    def test_invalid_input_runs_nothing_and_prints_usage(self):
        status, commands, out, _ = self.run_diagnose("", selector="ProtosSemanticBytecodeRootNodeGen@59ef67f9")
        self.assertEqual((status, commands), (1, []))
        self.assertIn("TRUFFLE_ROOT_DIAGNOSTIC=%s" % diag.ACQUISITION_FAILED, out.splitlines())
        self.assertIn("make diagnose-truffle-root", out)
        status, commands, _, _ = self.run_diagnose("", arguments=[])
        self.assertEqual((status, commands), (1, []))

    def test_timeout_is_acquisition_failure(self):
        expired = subprocess.TimeoutExpired(["java"], 10, output=b"partial\n")
        status, _, out, _ = self.run_diagnose("", raises=expired)
        self.assertEqual(status, 1)
        self.assertIn("TRUFFLE_ROOT_DIAGNOSTIC=%s" % diag.ACQUISITION_FAILED, out.splitlines())

    def test_not_found_and_ambiguous_exit_nonzero(self):
        status, _, out, _ = self.run_diagnose(SUMMARY)
        self.assertEqual(status, 1)
        self.assertIn("TRUFFLE_ROOT_DIAGNOSTIC=%s" % diag.NOT_FOUND, out.splitlines())
        output = "\n".join([start(NAME), tree(NAME), done(NAME), start(CONTINUATION), SUMMARY])
        status, _, out, _ = self.run_diagnose(output)
        self.assertEqual(status, 1)
        self.assertIn("TRUFFLE_ROOT_DIAGNOSTIC=%s" % diag.AMBIGUOUS, out.splitlines())


    def test_capture_handoff_names_the_fresh_bgv_and_stops_at_it(self):
        output = "\n".join([start(NAME), tree(NAME), failed(NAME, CODE_TOO_LARGE), SUMMARY])
        _, _, out, root = self.run_diagnose(output)
        lines = out.splitlines()
        dumps = root / "target" / "truffle-compilation" / "root-diagnostic" / "0123456789abcdef" / "graal_dumps"
        self.assertEqual(lines[-4:], ["TRUFFLE_ROOT_DIAGNOSTIC=%s" % diag.CODE_TOO_LARGE,
                                      "TRUFFLE_ROOT_CAPTURE_READY=YES", "TRUFFLE_ROOT_CAPTURE_BOUNDARY=BGV",
                                      "TRUFFLE_ROOT_BGV=%s" % (dumps / "TruffleHotSpotCompilation-1.bgv")])
        self.assertNotIn("IGV", out.replace("BGV", ""))

    def test_non_evidence_results_hand_off_nothing(self):
        for output in (SUMMARY, "\n".join([start(NAME), tree(NAME), done(NAME), start(CONTINUATION), SUMMARY])):
            _, _, out, _ = self.run_diagnose(output)
            self.assertIn("TRUFFLE_ROOT_CAPTURE_READY=NO", out.splitlines())
            self.assertNotIn("TRUFFLE_ROOT_BGV=", out)
            self.assertNotIn("TRUFFLE_ROOT_CAPTURE_BOUNDARY=", out)
        _, _, out, _ = self.run_diagnose("\n".join([start(NAME), tree(NAME), done(NAME), SUMMARY]), write_bgv=False)
        self.assertIn("TRUFFLE_ROOT_CAPTURE_READY=NO", out.splitlines())


class BoundaryTest(unittest.TestCase):

    def test_tool_neither_parses_bgv_nor_runs_external_analysis(self):
        source = Path(diag.__file__).read_text(encoding="utf-8")
        code = source.split('"""', 2)[2].lower()
        for forbidden in ("igv", "docker", "subprocess.run", "subprocess.popen", "subprocess.call",
                          "check_output", "os.system", "read_bytes", "open("):
            self.assertNotIn(forbidden, code, forbidden)
        self.assertNotIn("IGV", source.replace("BGV", ""))



class RealCompilationProbeTest(unittest.TestCase):
    def test_probe_options_only_differ_by_compile_only(self):
        unfiltered = diag.probe_options(
            "target/truffle-compilation/probe/unfiltered", False
        )
        filtered = diag.probe_options(
            "target/truffle-compilation/probe/filtered", True
        )
        unfiltered = [
            part.replace("/unfiltered", "/same") for part in unfiltered
        ]
        filtered = [
            part.replace("/filtered", "/same") for part in filtered
        ]
        option = diag.ENGINE + "CompileOnly=protos-root:"
        self.assertEqual(filtered.count(option), 1)
        self.assertEqual(
            [part for part in filtered if part != option], unfiltered
        )

    def test_real_compilation_required_even_when_program_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            bgv = Path(directory) / "graph.bgv"
            bgv.write_bytes(b"BIGV")
            output = "600\n200\n"
            self.assertEqual(
                diag.assess_probe(output, 0, [str(bgv)], False)["status"],
                "FAIL",
            )
            self.assertEqual(
                diag.assess_probe(output, 0, [str(bgv)], True)["status"],
                "FAIL",
            )

    def test_unfiltered_compilation_and_filtered_naming_are_independent(self):
        with tempfile.TemporaryDirectory() as directory:
            bgv = Path(directory) / "graph.bgv"
            bgv.write_bytes(b"BIGV")
            plain = (
                "[engine] opt done   engine=1  id=7  "
                "ProtosSemanticBytecodeRootNodeGen@100 |Tier 2|"
            )
            output = plain + "\n600\n200\n"
            self.assertEqual(
                diag.assess_probe(output, 0, [str(bgv)], False)["status"],
                "PASS",
            )
            filtered = diag.assess_probe(
                output, 0, [str(bgv)], True
            )
            self.assertEqual(filtered["status"], "FAIL")
            self.assertTrue(any(
                "NO_FILTERED_SEMANTIC_TARGET" in x
                for x in filtered["problems"]
            ))
            named = diag.assess_probe(
                done(NAME) + "\n600\n200\n",
                0, [str(bgv)], True,
            )
            self.assertEqual(named["status"], "PASS")
            bgv.write_bytes(b"")
            self.assertEqual(
                diag.assess_probe(
                    done(NAME) + "\n600\n200\n",
                    0, [str(bgv)], True,
                )["status"],
                "FAIL",
            )

    def test_target_not_found_reports_missing_compilation_events_without_guessing(self):
        output = catalog_line() + "\n" + SUMMARY + "\n"
        evidence = diag.classify(
            output, 0, SELECTOR, "none", []
        )
        self.assertEqual(evidence["result"], diag.NOT_FOUND)
        self.assertEqual(
            evidence["selected_root_catalog_occurrences"], 1
        )
        self.assertEqual(evidence["compilation_events_seen"], 0)
        self.assertTrue(any(
            "NO_COMPILATION_EVENTS" in x
            for x in evidence["problems"]
        ))


class MainTest(unittest.TestCase):

    def test_arguments_reach_the_selected_command(self):
        with mock.patch.object(diag, "diagnose", return_value=0) as fake:
            diag.main(["diagnose", "--selector", SELECTOR, "--expansion", "node", "--"] + CASE_ARGS)
        args = fake.call_args[0]
        self.assertEqual((args[2], args[3], args[4], args[5]), (SELECTOR, "node", "1", CASE_ARGS))
        with mock.patch.object(diag, "catalog", return_value=0) as fake:
            diag.main(["catalog", "--"] + CASE_ARGS)
        self.assertEqual(fake.call_args[0][2], CASE_ARGS)


if __name__ == "__main__":
    unittest.main(verbosity=1)
