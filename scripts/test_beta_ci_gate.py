#!/usr/bin/env python3
"""beta_ci_gate.py 的单元测试（全部使用**合成 fixture**）。

声明边界：
- 本文件中的 GitHub API 响应全部是**手工构造的解析用 fixture**，用来说明 beta_ci_gate.py
  对 exact-head workflow run / job JSON 的判定逻辑；
- 它们**不是**任何真实 CI run、真机、APK 或性能结果；
- 真实实跑证据见 evidence/beta-contract/REPORT.md 的“真实执行”小节。

运行：
    python3 scripts/test_beta_ci_gate.py
    python3 -m unittest discover -s scripts -p 'test_beta_ci_gate.py'
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import beta_ci_gate as gate  # noqa: E402

REPO = "Gong-Mi/termux-app-beta"
SHA_A = "a" * 40
SHA_B = "b" * 40
CI = ".github/workflows/ci.yml"
PERF = ".github/workflows/perf.yml"
BETA = ".github/workflows/beta-certification.yml"


# --------------------------------------------------------------------------
# synthetic fixture builders (NOT real CI data)
# --------------------------------------------------------------------------
def mk_run(sha, path, run_id, attempt=1, status="completed", conclusion="success",
           created_at="2026-10-16T00:00:00Z", event="pull_request"):
    return {
        "id": run_id,
        "path": path,
        "name": os.path.basename(path),
        "head_sha": sha,
        "run_attempt": attempt,
        "status": status,
        "conclusion": conclusion,
        "created_at": created_at,
        "event": event,
        "run_number": run_id,
        "html_url": "https://github.com/%s/actions/runs/%s" % (REPO, run_id),
    }


def mk_job(name, job_id, status="completed", conclusion="success", attempt=1):
    return {
        "id": job_id,
        "name": name,
        "status": status,
        "conclusion": conclusion,
        "run_attempt": attempt,
        "html_url": "https://github.com/%s/actions/runs/1/job/%s" % (REPO, job_id),
    }


class Fixture:
    """把合成响应写进临时目录，供 beta_ci_gate.py --api-fixture 消费。"""

    def __init__(self):
        self.dir = tempfile.mkdtemp(prefix="beta-contract-fixture-")
        self.routes = []

    def route(self, path, responses, params=None):
        self.routes.append({"path": path, "params": params or {}, "responses": responses})
        return self

    def runs_route(self, sha, runs, params=None):
        """单页 runs 响应（信封与真实 API 一致：{'total_count','workflow_runs'}）。"""
        p = {"head_sha": sha, "per_page": "100"}
        p.update(params or {})
        return self.route("/repos/%s/actions/runs" % REPO,
                          [{"total_count": len(runs), "workflow_runs": runs}], p)

    def runs_pages(self, sha, pages, params=None):
        """多页 runs 响应，按顺序消费（模拟分页）。"""
        p = {"head_sha": sha, "per_page": "100"}
        p.update(params or {})
        return self.route("/repos/%s/actions/runs" % REPO, list(pages), p)

    def jobs_route(self, run_id, attempt, jobs, params=None):
        p = {"per_page": "100"}
        p.update(params or {})
        return self.route(
            "/repos/%s/actions/runs/%s/attempts/%s/jobs" % (REPO, run_id, attempt),
            [{"total_count": len(jobs), "jobs": jobs}], p)

    def jobs_pages(self, run_id, attempt, pages, params=None):
        p = {"per_page": "100"}
        p.update(params or {})
        return self.route(
            "/repos/%s/actions/runs/%s/attempts/%s/jobs" % (REPO, run_id, attempt),
            list(pages), p)

    def write(self):
        with open(os.path.join(self.dir, "index.json"), "w") as fh:
            json.dump({"routes": self.routes}, fh)
        return self.dir

    def close(self):
        shutil.rmtree(self.dir, ignore_errors=True)


class GateCase(unittest.TestCase):
    def setUp(self):
        self._fixtures = []
        self._tmp = tempfile.mkdtemp(prefix="beta-contract-out-")

    def tearDown(self):
        for f in self._fixtures:
            f.close()
        shutil.rmtree(self._tmp, ignore_errors=True)

    def fixture(self):
        f = Fixture()
        self._fixtures.append(f)
        return f

    def out(self, name):
        return os.path.join(self._tmp, name)

    def run_gate(self, expects, sha=SHA_A, fixture=None, out_name="report.json", extra=None):
        argv = ["--repo", REPO, "--sha", sha]
        for e in expects:
            argv += ["--expect", e]
        argv += ["--output", self.out(out_name)]
        if fixture is not None:
            argv += ["--api-fixture", fixture.write()]
        argv += list(extra or [])
        code = gate.main(argv)
        path = self.out(out_name)
        report = None
        if out_name.endswith(".json") and os.path.exists(path):
            with open(path) as fh:
                report = json.load(fh)
        return code, report


# --------------------------------------------------------------------------
# 负控 1：输入校验（错误输入必须先被拒，不得修补）
# --------------------------------------------------------------------------
class TestInputValidation(GateCase):
    def test_short_sha_rejected_without_repair(self):
        f = self.fixture()  # 不放任何 route：任何 API 调用都会失败
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], sha="c93d5b87",
                                     fixture=f)
        self.assertEqual(code, gate.EXIT_INVALID_INPUT)
        self.assertIsNotNone(report)
        self.assertEqual(report["verdict"], gate.VERDICT_INVALID_INPUT)
        self.assertEqual(report["api_requests"], [], "输入非法时不得发起 API 请求")
        self.assertEqual(report["sha"], "c93d5b87", "输入必须原样记录，不得补齐为 40 位")

    def test_non_hex_sha_rejected(self):
        f = self.fixture()
        code, report = self.run_gate(["%s=x" % CI], sha="z" * 40, fixture=f)
        self.assertEqual(code, gate.EXIT_INVALID_INPUT)
        self.assertEqual(report["verdict"], gate.VERDICT_INVALID_INPUT)

    def test_overlong_sha_rejected(self):
        f = self.fixture()
        code, _ = self.run_gate(["%s=x" % CI], sha=SHA_A + "a", fixture=f)
        self.assertEqual(code, gate.EXIT_INVALID_INPUT)

    def test_bad_repo_rejected(self):
        code = gate.main(["--repo", "not-a-repo", "--sha", SHA_A,
                          "--expect", "%s=Build debug AAB (x86)" % CI,
                          "--output", self.out("r.json")])
        self.assertEqual(code, gate.EXIT_INVALID_INPUT)

    def test_missing_expect_rejected(self):
        code = gate.main(["--repo", REPO, "--sha", SHA_A, "--output", self.out("r.json")])
        self.assertEqual(code, gate.EXIT_INVALID_INPUT)

    def test_malformed_expect_rejected(self):
        for bad in ("%s" % CI, "%s=" % CI, "=job-name", ""):
            code = gate.main(["--repo", REPO, "--sha", SHA_A, "--expect", bad,
                              "--output", self.out("r.json")])
            self.assertEqual(code, gate.EXIT_INVALID_INPUT, "expect=%r 应被拒" % bad)

    def test_parse_expect_keeps_job_name_with_parens(self):
        wf, job = gate.parse_expect("%s=Unit tests (ubuntu-24.04-arm)" % CI)
        self.assertEqual(wf, CI)
        self.assertEqual(job, "Unit tests (ubuntu-24.04-arm)")

    def test_validate_sha_accepts_upper_and_normalizes(self):
        self.assertEqual(gate.validate_sha("A" * 40), "a" * 40)


# --------------------------------------------------------------------------
# 负控 2：missing / stale / pending / fail / skipped
# --------------------------------------------------------------------------
class TestNonPassCases(GateCase):
    def test_missing_later_registered_workflow_is_not_run(self):
        """workflow 文件在该 head 上没有 run（后注册的 workflow 不会追溯触发）→ NOT_RUN。"""
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 101)])
        f.jobs_route(101, 1, [mk_job("Unit tests (ubuntu-latest)", 1),
                              mk_job("Build debug AAB (x86)", 2),
                              mk_job("Unit tests (ubuntu-24.04-arm)", 3)])
        code, report = self.run_gate([
            "%s=Unit tests (ubuntu-latest)" % CI,
            "%s=Source identity (combination candidate)" % BETA,
        ], fixture=f)
        self.assertEqual(code, gate.EXIT_NONPASS)
        self.assertEqual(report["verdict"], gate.VERDICT_NOT_RUN)
        statuses = {e["workflow_file"]: e["status"] for e in report["expectations"]}
        self.assertEqual(statuses[CI], gate.STATUS_OK)
        self.assertEqual(statuses[BETA], gate.STATUS_NOT_RUN)

    def test_stale_head_does_not_borrow_newer_head_runs(self):
        """查询旧 head 时，禁止把新 head 的 run 当成证据。"""
        f = self.fixture()
        f.runs_route(SHA_A, [])                      # 旧 head：没有任何 run
        f.runs_route(SHA_B, [mk_run(SHA_B, CI, 202)])  # 新 head：有 run
        f.jobs_route(202, 1, [mk_job("Unit tests (ubuntu-latest)", 21)])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], sha=SHA_A, fixture=f)
        self.assertEqual(code, gate.EXIT_NONPASS)
        self.assertEqual(report["verdict"], gate.VERDICT_NOT_RUN)
        self.assertIsNone(report["expectations"][0]["run"])

    def test_pending_job_is_incomplete(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 303, status="in_progress", conclusion=None)])
        f.jobs_route(303, 1, [mk_job("Unit tests (ubuntu-latest)", 31, status="in_progress",
                                     conclusion=None)])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(code, gate.EXIT_NONPASS)
        self.assertEqual(report["verdict"], gate.VERDICT_INCOMPLETE)

    def test_job_absent_while_run_running_is_pending_not_missing(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 304, status="queued", conclusion=None)])
        f.jobs_route(304, 1, [])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(report["verdict"], gate.VERDICT_INCOMPLETE)
        self.assertEqual(report["expectations"][0]["status"], gate.STATUS_PENDING)

    def test_job_absent_after_run_completed_is_missing_job(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 305)])
        f.jobs_route(305, 1, [mk_job("Build debug AAB (x86)", 35)])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(report["verdict"], gate.VERDICT_FAIL)
        self.assertEqual(report["expectations"][0]["status"], gate.STATUS_MISSING_JOB)

    def test_failed_job_is_fail(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 306, conclusion="failure")])
        f.jobs_route(306, 1, [mk_job("Unit tests (ubuntu-latest)", 36, conclusion="failure")])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(code, gate.EXIT_NONPASS)
        self.assertEqual(report["verdict"], gate.VERDICT_FAIL)
        self.assertEqual(report["expectations"][0]["status"], gate.STATUS_FAILED)

    def test_skipped_expected_job_does_not_pass(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 307)])
        f.jobs_route(307, 1, [mk_job("Unit tests (ubuntu-latest)", 37, conclusion="skipped")])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(report["verdict"], gate.VERDICT_FAIL)
        self.assertEqual(report["expectations"][0]["status"], gate.STATUS_SKIPPED)

    def test_cancelled_job_does_not_pass(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 308, conclusion="cancelled")])
        f.jobs_route(308, 1, [mk_job("Unit tests (ubuntu-latest)", 38, conclusion="cancelled")])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(report["verdict"], gate.VERDICT_FAIL)

    def test_startup_failure_is_fail(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 309, conclusion="startup_failure")])
        f.jobs_route(309, 1, [])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(report["verdict"], gate.VERDICT_FAIL)
        self.assertEqual(report["expectations"][0]["status"], gate.STATUS_STARTUP_FAILURE)

    def test_expected_job_in_wrong_workflow_is_missing(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, PERF, 310)])
        f.jobs_route(310, 1, [mk_job("Perf (x86_64)", 39)])
        code, report = self.run_gate(["%s=Perf (x86_64)" % CI], fixture=f)
        self.assertEqual(report["expectations"][0]["status"], gate.STATUS_NOT_RUN)

    def test_expect_basename_matches_full_run_path(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 311)])
        f.jobs_route(311, 1, [mk_job("Unit tests (ubuntu-latest)", 41)])
        code, report = self.run_gate(["ci.yml=Unit tests (ubuntu-latest)"], fixture=f)
        self.assertEqual(code, gate.EXIT_PASS)
        self.assertEqual(report["verdict"], gate.VERDICT_PASS)

    def test_unexpected_failure_not_masked_by_expected_success(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 312, conclusion="failure")])
        f.jobs_route(312, 1, [
            mk_job("Unit tests (ubuntu-latest)", 51),
            mk_job("Build debug AAB (x86)", 52, conclusion="failure"),
        ])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(report["verdict"], gate.VERDICT_FAIL)
        self.assertEqual(report["expectations"][0]["status"], gate.STATUS_OK)
        self.assertTrue(report["unexpected"], "非预期 job 的失败必须被单独记录")
        self.assertEqual(report["unexpected"][0]["job"], "Build debug AAB (x86)")

    def test_unexpected_skipped_job_is_warning_only(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 313)])
        f.jobs_route(313, 1, [
            mk_job("Unit tests (ubuntu-latest)", 61),
            mk_job("Optional leg", 62, conclusion="skipped"),
        ])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(code, gate.EXIT_PASS)
        self.assertEqual(report["verdict"], gate.VERDICT_PASS)
        self.assertTrue(any("Optional leg" in w for w in report["warnings"]))


# --------------------------------------------------------------------------
# 正控：exact-head 全绿、rerun attempt、分页、幂等
# --------------------------------------------------------------------------
class TestPassCases(GateCase):
    def test_all_expected_jobs_success_is_pass(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 401)])
        f.jobs_route(401, 1, [
            mk_job("Unit tests (ubuntu-latest)", 71),
            mk_job("Unit tests (ubuntu-24.04-arm)", 72),
            mk_job("Build debug AAB (x86)", 73),
        ])
        code, report = self.run_gate([
            "%s=Unit tests (ubuntu-latest)" % CI,
            "%s=Unit tests (ubuntu-24.04-arm)" % CI,
            "%s=Build debug AAB (x86)" % CI,
        ], fixture=f)
        self.assertEqual(code, gate.EXIT_PASS)
        self.assertEqual(report["verdict"], gate.VERDICT_PASS)
        for e in report["expectations"]:
            self.assertEqual(e["status"], gate.STATUS_OK)
            self.assertEqual(e["run"]["id"], 401)
            self.assertEqual(e["run"]["run_attempt"], 1)
            self.assertIsNotNone(e["job_evidence"]["id"])

    def test_latest_attempt_wins_over_earlier_failed_attempt(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 402, attempt=2)])
        f.jobs_route(402, 1, [mk_job("Unit tests (ubuntu-latest)", 81, conclusion="failure",
                                     attempt=1)])
        f.jobs_route(402, 2, [mk_job("Unit tests (ubuntu-latest)", 82, conclusion="success",
                                     attempt=2)])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(code, gate.EXIT_PASS)
        self.assertEqual(report["expectations"][0]["run"]["run_attempt"], 2)
        self.assertEqual(report["expectations"][0]["job_evidence"]["id"], 82)

    def test_newer_rerun_write_off_older_failed_run(self):
        """同一 workflow 在同一 head 上有两次 run：以最新 run 为准，旧 run 记为 superseded。"""
        f = self.fixture()
        f.runs_route(SHA_A, [
            mk_run(SHA_A, CI, 403, conclusion="failure", created_at="2026-10-16T01:00:00Z"),
            mk_run(SHA_A, CI, 404, conclusion="success", created_at="2026-10-16T02:00:00Z"),
        ])
        f.jobs_route(403, 1, [mk_job("Unit tests (ubuntu-latest)", 91, conclusion="failure")])
        f.jobs_route(404, 1, [mk_job("Unit tests (ubuntu-latest)", 92)])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(code, gate.EXIT_PASS)
        self.assertEqual(report["expectations"][0]["run"]["id"], 404)
        self.assertEqual([r["id"] for r in report["superseded_runs"]], [403])
        self.assertTrue(any("403" in w for w in report["warnings"]))

    def test_newer_pending_rerun_makes_gate_incomplete(self):
        f = self.fixture()
        f.runs_route(SHA_A, [
            mk_run(SHA_A, CI, 405, conclusion="success", created_at="2026-10-16T01:00:00Z"),
            mk_run(SHA_A, CI, 406, status="in_progress", conclusion=None,
                   created_at="2026-10-16T02:00:00Z"),
        ])
        f.jobs_route(405, 1, [mk_job("Unit tests (ubuntu-latest)", 93)])
        f.jobs_route(406, 1, [mk_job("Unit tests (ubuntu-latest)", 94, status="in_progress",
                                     conclusion=None)])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(code, gate.EXIT_NONPASS)
        self.assertEqual(report["verdict"], gate.VERDICT_INCOMPLETE)

    def test_run_pagination_searches_later_pages(self):
        f = self.fixture()
        page1 = [mk_run(SHA_A, PERF, 500 + i) for i in range(100)]
        page2 = [mk_run(SHA_A, CI, 601)]
        f.runs_pages(SHA_A, [{"total_count": 101, "workflow_runs": page1},
                             {"total_count": 101, "workflow_runs": page2}])
        f.jobs_route(601, 1, [mk_job("Unit tests (ubuntu-latest)", 95)])
        # The cumulative gate also reads the newest other workflow's jobs.
        f.jobs_route(599, 1, [mk_job("Perf (x86_64)", 5991)])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(code, gate.EXIT_PASS)
        self.assertEqual(report["expectations"][0]["run"]["id"], 601)
        self.assertIn("GET /repos/%s/actions/runs?head_sha=%s&page=2&per_page=100" % (REPO, SHA_A),
                      report["api_requests"])

    def test_job_pagination(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 602)])
        page1 = [mk_job("filler-%d" % i, 1000 + i) for i in range(100)]
        page2 = [mk_job("Unit tests (ubuntu-latest)", 1101)]
        f.jobs_pages(602, 1, [{"total_count": 101, "jobs": page1},
                              {"total_count": 101, "jobs": page2}])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(code, gate.EXIT_PASS)
        self.assertEqual(report["expectations"][0]["job_evidence"]["id"], 1101)

    def test_no_network_api_calls_beyond_fixture(self):
        """单次检查：只读、可枚举的 API 请求集合。"""
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 603)])
        f.jobs_route(603, 1, [mk_job("Unit tests (ubuntu-latest)", 96)])
        self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        # 记录请求便于审计；不应包含任何写操作
        for req in self._last_requests(self.out("report.json")):
            self.assertTrue(req.startswith("GET "), req)
            self.assertNotIn("POST", req)

    @staticmethod
    def _last_requests(report_path):
        with open(report_path) as fh:
            return json.load(fh)["api_requests"]

    def test_report_is_deterministic_for_same_fixture(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 604)])
        f.jobs_route(604, 1, [mk_job("Unit tests (ubuntu-latest)", 97)])
        code1, r1 = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f,
                                  out_name="a.json")
        code2, r2 = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f,
                                  out_name="b.json")
        self.assertEqual((code1, code2), (gate.EXIT_PASS, gate.EXIT_PASS))
        self.assertEqual(r1["expectations"][0]["job_evidence"]["id"],
                         r2["expectations"][0]["job_evidence"]["id"])

    def test_markdown_output(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 605)])
        f.jobs_route(605, 1, [mk_job("Unit tests (ubuntu-latest)", 98)])
        code, _ = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f,
                                out_name="report.md")
        self.assertEqual(code, gate.EXIT_PASS)
        with open(self.out("report.md")) as fh:
            body = fh.read()
        self.assertIn("PASS", body)
        self.assertIn("Unit tests (ubuntu-latest)", body)
        self.assertIn(SHA_A, body)


# --------------------------------------------------------------------------
# 负控 3：fail closed（网络/JSON/未知状态不得变绿）
# --------------------------------------------------------------------------
class TestFailClosed(GateCase):
    def test_api_error_is_infra_retry_not_pass(self):
        f = self.fixture()
        f.runs_pages(SHA_A, [{"__http_error__": 404, "message": "Not Found"}])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(code, gate.EXIT_INFRA_RETRY)
        self.assertEqual(report["verdict"], gate.VERDICT_INFRA_RETRY)

    def test_invalid_json_is_infra_retry(self):
        f = self.fixture()
        f.runs_pages(SHA_A, ["<<not-json>>"])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(code, gate.EXIT_INFRA_RETRY)

    def test_partial_api_failure_never_green(self):
        """runs 查询成功、jobs 请求失败 → 不得 PASS。"""
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 606)])
        f.jobs_pages(606, 1, [{"__http_error__": 500, "message": "Server Error"}])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertEqual(code, gate.EXIT_INFRA_RETRY)
        self.assertNotEqual(report["verdict"], gate.VERDICT_PASS)

    def test_unknown_run_status_does_not_pass(self):
        f = self.fixture()
        f.runs_route(SHA_A, [mk_run(SHA_A, CI, 607, status="weird", conclusion=None)])
        f.jobs_route(607, 1, [mk_job("Unit tests (ubuntu-latest)", 99, status="weird",
                                     conclusion=None)])
        code, report = self.run_gate(["%s=Unit tests (ubuntu-latest)" % CI], fixture=f)
        self.assertNotEqual(code, gate.EXIT_PASS)
        self.assertEqual(report["verdict"], gate.VERDICT_INCOMPLETE)

    def test_unreadable_fixture_is_infra_retry(self):
        code = gate.main(["--repo", REPO, "--sha", SHA_A, "--expect",
                          "%s=Unit tests (ubuntu-latest)" % CI,
                          "--output", self.out("r.json"),
                          "--api-fixture", os.path.join(self._tmp, "does-not-exist")])
        self.assertEqual(code, gate.EXIT_INFRA_RETRY)


# --------------------------------------------------------------------------
# 纯函数
# --------------------------------------------------------------------------
class TestHelpers(unittest.TestCase):
    def test_workflow_file_matching(self):
        self.assertTrue(gate.workflow_file_matches("ci.yml", ".github/workflows/ci.yml"))
        self.assertTrue(gate.workflow_file_matches(".github/workflows/ci.yml",
                                                  ".github/workflows/ci.yml"))
        self.assertFalse(gate.workflow_file_matches("ci.yml", ".github/workflows/perf.yml"))
        self.assertFalse(gate.workflow_file_matches("ci.yml", ".github/workflows/ci.yml.bak"))

    def test_status_to_verdict_mapping_is_closed(self):
        for status in (gate.STATUS_FAILED, gate.STATUS_MISSING_JOB, gate.STATUS_SKIPPED,
                       gate.STATUS_STARTUP_FAILURE):
            self.assertEqual(gate.verdict_for_statuses([status]), gate.VERDICT_FAIL)
        self.assertEqual(gate.verdict_for_statuses([gate.STATUS_PENDING]), gate.VERDICT_INCOMPLETE)
        self.assertEqual(gate.verdict_for_statuses([gate.STATUS_NOT_RUN]), gate.VERDICT_NOT_RUN)
        self.assertEqual(gate.verdict_for_statuses([gate.STATUS_OK]), gate.VERDICT_PASS)
        self.assertEqual(gate.verdict_for_statuses([gate.STATUS_OK, gate.STATUS_NOT_RUN]),
                         gate.VERDICT_NOT_RUN)
        self.assertEqual(gate.verdict_for_statuses([gate.STATUS_OK, gate.STATUS_NOT_RUN,
                                                   gate.STATUS_PENDING]),
                         gate.VERDICT_INCOMPLETE)
        self.assertEqual(gate.verdict_for_statuses([]), gate.VERDICT_INVALID_INPUT)


if __name__ == "__main__":
    unittest.main(verbosity=2)
