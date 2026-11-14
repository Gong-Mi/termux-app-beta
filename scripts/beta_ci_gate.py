#!/usr/bin/env python3
"""beta_ci_gate.py — exact-head 全量 CI 门（单次检查，非常驻 poll）。

用途
----
用一次检查判定：**指定 full SHA** 上，**逐个枚举**的 (workflow 文件, job 显示名) 是否都已经
completed + success，并且同一 run 里没有"非预期 job"的失败被成功项掩盖。

判定语义（fail-closed）
----------------------
- 必须显式 `--expect FILE=JOB`，一一枚举；不做"最少成功数/总数"判断。
- 预期 job 只有 `status=completed` 且 `conclusion=success` 才算 OK；
  `skipped / cancelled / timed_out / failure / action_required / stale / startup_failure /
  neutral` 一律不 pass。
- 预期 workflow 在该 SHA 上没有 run → NOT_RUN（后注册的 workflow 不会追溯触发，不能拿别的
  head 的 run 顶替）。
- 同一 workflow 同一 SHA 有多次 run（重复触发/重跑）→ 以**最新 run**（created_at, id）为准，
  旧的记为 superseded_runs 并给出 warning；run 内部的多次 attempt 以 **run_attempt 最大值**
  的 jobs 为准（走 `/attempts/{n}/jobs`）。
- 同一 run 中非预期 job 的 failure/cancelled/timed_out → 同样判 FAIL（不被预期 success 掩盖）；
  非预期 job 的 skipped/neutral → warning，不判失败。
- 网络/JSON/API 错误（含分页中途失败）→ INFRA_RETRY，绝不因为"部分 API 成功"而变绿。
- `--sha` 必须是 40 位十六进制 full SHA：只校验、**不修补**（短 SHA/非十六进制/超长一律拒）。

退出码
------
0 = PASS
1 = 非 PASS（FAIL / INCOMPLETE / NOT_RUN）
2 = INVALID_INPUT（参数/SHA/--expect 非法）
3 = INFRA_RETRY（gh/API/JSON/分页/fixture 读取失败）

CI 用法
-------
    python3 scripts/beta_ci_gate.py \
      --repo Gong-Mi/termux-app-beta \
      --sha "$GITHUB_SHA" \
      --expect ci.yml="Unit tests (ubuntu-latest)" \
      --expect ci.yml="Unit tests (ubuntu-24.04-arm)" \
      --output "$RUNNER_TEMP/beta-ci-gate.json"

测试注入
--------
`--api-fixture DIR`（**仅测试用**）：从 `DIR/index.json` 读取合成响应，不访问网络。
index.json 结构：

    {"routes": [{"path": "/repos/O/R/actions/runs",
                 "params": {"head_sha": "<sha>"},
                 "responses": [ {...}, {...} ]}]}

- `params` 为子集匹配（实际请求可多带 per_page/page）；多个 route 命中时取声明参数最多的。
- 同一 route 的 responses 按顺序消费，用尽后重复最后一条（用于模拟分页与幂等重读）。
- 单条响应若为 `{"__http_error__": <code>, "message": "..."}` 则模拟 gh 失败（API 错误）。
  若为字符串则按 gh 原始输出处理（非 JSON 即 INFRA_RETRY）。

契约边界：本门只声明"CI 上这些 job 在 exact head 上真实跑过并成功"。它不声明 AOT、不声明
性能、不声明真机行为（见 docs/BETA_CERTIFICATION.md 的分层声明）。
"""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import urllib.parse

SCHEMA = "beta-ci-gate/1"

EXIT_PASS = 0
EXIT_NONPASS = 1
EXIT_INVALID_INPUT = 2
EXIT_INFRA_RETRY = 3

VERDICT_PASS = "PASS"
VERDICT_FAIL = "FAIL"
VERDICT_INCOMPLETE = "INCOMPLETE"
VERDICT_NOT_RUN = "NOT_RUN"
VERDICT_INFRA_RETRY = "INFRA_RETRY"
VERDICT_INVALID_INPUT = "INVALID_INPUT"

STATUS_OK = "OK"
STATUS_PENDING = "PENDING"
STATUS_NOT_RUN = "NOT_RUN"
STATUS_MISSING_JOB = "MISSING_JOB"
STATUS_FAILED = "FAILED"
STATUS_SKIPPED = "SKIPPED"
STATUS_NOT_SUCCESS = "NOT_SUCCESS"
STATUS_STARTUP_FAILURE = "STARTUP_FAILURE"

FAIL_STATUSES = (STATUS_FAILED, STATUS_MISSING_JOB, STATUS_SKIPPED, STATUS_NOT_SUCCESS,
                 STATUS_STARTUP_FAILURE)

JOB_FAILURE_CONCLUSIONS = ("failure", "timed_out", "action_required", "stale", "cancelled")
JOB_NEUTRAL_CONCLUSIONS = ("neutral",)
RUN_INFRA_CONCLUSIONS = ("startup_failure",)

# 非预期 job 只有在"真的失败"时才判 FAIL；skipped/neutral 只记 warning（见文档的语义表）。
UNEXPECTED_FAIL_STATUSES = (STATUS_FAILED, STATUS_STARTUP_FAILURE)

PER_PAGE = 100
MAX_PAGES = 50

SHA_RE = re.compile(r"^[0-9a-fA-F]{40}$")
REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


class InvalidInput(Exception):
    """参数/SHA/--expect 非法 → 退出码 2。"""


class ApiError(Exception):
    """gh/API/JSON/分页/fixture 失败 → 退出码 3（INFRA_RETRY）。"""


# --------------------------------------------------------------------------
# 纯函数
# --------------------------------------------------------------------------
def validate_sha(raw):
    """只校验 full SHA，不修补。返回小写规范化值（仅大小写归一，不补齐/不截断）。"""
    if not isinstance(raw, str) or not SHA_RE.match(raw):
        raise InvalidInput("--sha 必须是 40 位十六进制 full SHA，收到 %r（不做补齐/截断）" % (raw,))
    return raw.lower()


def validate_repo(raw):
    if not isinstance(raw, str) or not REPO_RE.match(raw):
        raise InvalidInput("--repo 必须是 OWNER/REPO，收到 %r" % (raw,))
    return raw


def parse_expect(raw):
    """'FILE=JOB' → (FILE, JOB)。FILE 必须是工作流文件名，JOB 是其 job 显示名。"""
    if not isinstance(raw, str) or "=" not in raw:
        raise InvalidInput("--expect 必须是 FILE=JOB，收到 %r" % (raw,))
    wf, job = raw.split("=", 1)
    wf = wf.strip()
    job = job.strip()
    if not wf or not job:
        raise InvalidInput("--expect 的 FILE 与 JOB 都不得为空，收到 %r" % (raw,))
    if not (wf.endswith(".yml") or wf.endswith(".yaml")):
        raise InvalidInput("--expect 的 FILE 必须是 *.yml/*.yaml 工作流文件名，收到 %r" % (wf,))
    if "/" in wf and not wf.startswith(".github/workflows/"):
        raise InvalidInput("--expect 的 FILE 只允许裸文件名或 .github/workflows/ 前缀，收到 %r"
                           % (wf,))
    return wf, job


def normalize_workflow_file(wf):
    """把 FILE 归一为 'xxx.yml'（裸文件名）。"""
    name = wf.strip()
    if name.startswith("./"):
        name = name[2:]
    if name.startswith(".github/workflows/"):
        name = name[len(".github/workflows/"):]
    return name


def workflow_file_matches(expected, run_path):
    """expected 可为裸文件名或完整路径；run_path 是 run JSON 的 path 字段。"""
    if not run_path:
        return False
    exp = normalize_workflow_file(expected)
    run_name = normalize_workflow_file(run_path)
    return exp == run_name


def verdict_for_statuses(statuses):
    """状态集合 → 全局 verdict。空集合是契约错误（INVALID_INPUT）。"""
    if not statuses:
        return VERDICT_INVALID_INPUT
    if any(s in FAIL_STATUSES for s in statuses):
        return VERDICT_FAIL
    if any(s == STATUS_PENDING for s in statuses):
        return VERDICT_INCOMPLETE
    if any(s == STATUS_NOT_RUN for s in statuses):
        return VERDICT_NOT_RUN
    if all(s == STATUS_OK for s in statuses):
        return VERDICT_PASS
    return VERDICT_FAIL  # 未知状态一律不 pass


def _job_status_for(job):
    status = job.get("status")
    conclusion = job.get("conclusion")
    if status != "completed":
        return STATUS_PENDING
    if conclusion == "success":
        return STATUS_OK
    if conclusion == "skipped":
        return STATUS_SKIPPED
    if conclusion in JOB_FAILURE_CONCLUSIONS:
        return STATUS_FAILED
    if conclusion in JOB_NEUTRAL_CONCLUSIONS:
        return STATUS_NOT_SUCCESS
    return STATUS_NOT_SUCCESS


def _worse(a, b):
    """两个 job 状态取更严重的一个（同名校验用）。"""
    order = [STATUS_OK, STATUS_PENDING, STATUS_NOT_SUCCESS, STATUS_SKIPPED,
             STATUS_MISSING_JOB, STATUS_FAILED, STATUS_STARTUP_FAILURE]
    ia = order.index(a) if a in order else len(order)
    ib = order.index(b) if b in order else len(order)
    return a if ia >= ib else b


def _run_sort_key(run):
    return (str(run.get("created_at") or ""), int(run.get("id") or 0))


def _run_identity(run):
    if not run:
        return None
    return {
        "id": run.get("id"),
        "name": run.get("name"),
        "path": run.get("path"),
        "event": run.get("event"),
        "status": run.get("status"),
        "conclusion": run.get("conclusion"),
        "run_attempt": run.get("run_attempt"),
        "run_number": run.get("run_number"),
        "head_sha": run.get("head_sha"),
        "created_at": run.get("created_at"),
        "html_url": run.get("html_url"),
    }


def _job_identity(job):
    if not job:
        return None
    return {
        "id": job.get("id"),
        "name": job.get("name"),
        "status": job.get("status"),
        "conclusion": job.get("conclusion"),
        "run_attempt": job.get("run_attempt"),
        "html_url": job.get("html_url"),
    }


def _json_load(text, ctx):
    try:
        data = json.loads(text)
    except (TypeError, ValueError) as exc:
        raise ApiError("%s: 响应不是合法 JSON（%s）" % (ctx, exc))
    if not isinstance(data, dict):
        raise ApiError("%s: 响应不是 JSON 对象，收到 %s" % (ctx, type(data).__name__))
    return data


# --------------------------------------------------------------------------
# API 层：真实 gh / fixture
# --------------------------------------------------------------------------
class GhApi(object):
    """真实只读 gh api 调用。任何非零退出/非法 JSON 都抛 ApiError。"""

    def __init__(self, runner=None, requests_log=None):
        self.runner = runner or self._default_runner
        self.requests_log = requests_log if requests_log is not None else []

    @staticmethod
    def _default_runner(url):
        proc = subprocess.run(["gh", "api", url], capture_output=True, text=True)
        return proc.returncode, proc.stdout, proc.stderr

    def get(self, path, params):
        url = path
        if params:
            url = "%s?%s" % (path, urllib.parse.urlencode(sorted(params.items())))
        self.requests_log.append("GET %s" % _display_url(path, params))
        code, out, err = self.runner(url)
        if code != 0:
            raise ApiError("gh api %s 退出码 %s：%s" % (url, code, (err or out or "").strip()[:500]))
        return _json_load(out, "gh api %s" % url)


class FixtureApi(object):
    """测试注入：从 dir/index.json 读合成响应，不访问网络。"""

    def __init__(self, fixture_dir, requests_log=None):
        self.dir = fixture_dir
        self.requests_log = requests_log if requests_log is not None else []
        index_path = os.path.join(fixture_dir, "index.json")
        if not os.path.isfile(index_path):
            raise ApiError("fixture 目录缺少 index.json：%s" % index_path)
        try:
            with open(index_path) as fh:
                index = json.load(fh)
        except ValueError as exc:
            raise ApiError("fixture index.json 不是合法 JSON：%s" % exc)
        routes = index.get("routes")
        if not isinstance(routes, list):
            raise ApiError("fixture index.json 缺少 routes 列表")
        # 预先按声明参数数量降序排序，命中多个时取最具体的一个
        self.routes = sorted(routes, key=lambda r: -len(r.get("params") or {}))
        self.cursor = {}

    def _match(self, path, params):
        hits = []
        for i, route in enumerate(self.routes):
            if route.get("path") != path:
                continue
            declared = route.get("params") or {}
            if all(str(params.get(k)) == str(v) for k, v in declared.items()):
                hits.append(i)
        return hits[0] if hits else None

    def get(self, path, params):
        self.requests_log.append("GET %s" % _display_url(path, params))
        idx = self._match(path, params)
        if idx is None:
            raise ApiError("fixture 未声明路由：%s" % _display_url(path, params))
        responses = self.routes[idx].get("responses") or []
        if not responses:
            raise ApiError("fixture 路由无响应：%s" % _display_url(path, params))
        pos = self.cursor.get(idx, 0)
        if pos >= len(responses):
            pos = len(responses) - 1
        self.cursor[idx] = pos + 1
        resp = responses[pos]
        if isinstance(resp, str):
            return _json_load(resp, "fixture %s" % _display_url(path, params))
        if not isinstance(resp, dict):
            raise ApiError("fixture 响应必须是对象或字符串")
        if "__http_error__" in resp:
            raise ApiError("fixture 模拟 HTTP 错误 %s：%s"
                           % (resp.get("__http_error__"), resp.get("message", "")))
        return resp


def _display_url(path, params):
    if not params:
        return path
    return "%s?%s" % (path, urllib.parse.urlencode(sorted(params.items())))


def collect_pages(api, path, params, key):
    """按 per_page=PER_PAGE 分页收集；中途任何错误直接抛 ApiError（不允许部分成功）。"""
    items = []
    page = 1
    while True:
        query = dict(params)
        query["per_page"] = str(PER_PAGE)
        query["page"] = str(page)
        data = api.get(path, query)
        chunk = data.get(key)
        if chunk is None:
            raise ApiError("%s 响应缺少 %r 字段" % (path, key))
        if not isinstance(chunk, list):
            raise ApiError("%s 的 %r 不是列表" % (path, key))
        items.extend(chunk)
        if len(chunk) < PER_PAGE:
            return items
        page += 1
        if page > MAX_PAGES:
            raise ApiError("分页超过 %s 页，疑似异常，fail closed" % MAX_PAGES)


# --------------------------------------------------------------------------
# 报告
# --------------------------------------------------------------------------
def build_report(repo, sha_input, sha_norm, verdict, exit_code, expectations, unexpected,
                 superseded, warnings, notes, api_requests, errors):
    return {
        "schema": SCHEMA,
        "generated_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "repo": repo,
        "sha": sha_input,
        "sha_normalized": sha_norm,
        "verdict": verdict,
        "exit_code": exit_code,
        "expectations": expectations,
        "unexpected": unexpected,
        "superseded_runs": superseded,
        "warnings": warnings,
        "notes": notes,
        "errors": errors,
        "api_requests": api_requests,
        "claim_boundary": (
            "只声明：这些 job 在该 exact head 的 CI run 上 completed+success。"
            "不声明 AOT/性能/真机行为；non-debuggable 不等于 AOT，pm compile Success 不等于"
            "speed 已生效。"
        ),
    }


def render_markdown(report):
    lines = []
    lines.append("# beta CI gate report")
    lines.append("")
    lines.append("- repo: `%s`" % report["repo"])
    lines.append("- sha: `%s`" % report["sha"])
    if report.get("sha_normalized"):
        lines.append("- sha_normalized: `%s`" % report["sha_normalized"])
    lines.append("- verdict: **%s** (exit %s)" % (report["verdict"], report["exit_code"]))
    lines.append("- generated_at: %s" % report["generated_at"])
    lines.append("")
    lines.append("## expectations")
    lines.append("")
    lines.append("| workflow | job | status | run | attempt | job id | conclusion | url |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for e in report["expectations"]:
        run = e.get("run") or {}
        job = e.get("job_evidence") or {}
        lines.append("| %s | %s | %s | %s | %s | %s | %s | %s |" % (
            e.get("workflow_file"), e.get("job"), e.get("status"),
            run.get("id"), run.get("run_attempt"), job.get("id"),
            job.get("conclusion") or "-", job.get("html_url") or "-"))
    if report["unexpected"]:
        lines.append("")
        lines.append("## unexpected jobs (non-expected, same run)")
        lines.append("")
        for u in report["unexpected"]:
            lines.append("- severity=%s run=%s attempt=%s job=%s (%s) conclusion=%s %s" % (
                u.get("severity"), u.get("run_id"), u.get("run_attempt"), u.get("job"),
                u.get("job_id"), u.get("conclusion"), u.get("html_url") or ""))
    if report["superseded_runs"]:
        lines.append("")
        lines.append("## superseded runs (older run at same head)")
        lines.append("")
        for s in report["superseded_runs"]:
            lines.append("- run=%s workflow=%s attempt=%s status=%s conclusion=%s %s" % (
                s.get("id"), s.get("path"), s.get("run_attempt"), s.get("status"),
                s.get("conclusion"), s.get("html_url") or ""))
    if report["warnings"]:
        lines.append("")
        lines.append("## warnings")
        lines.append("")
        for w in report["warnings"]:
            lines.append("- %s" % w)
    if report["errors"]:
        lines.append("")
        lines.append("## errors")
        lines.append("")
        for err in report["errors"]:
            lines.append("- %s" % err)
    lines.append("")
    lines.append("## notes")
    lines.append("")
    for n in report["notes"]:
        lines.append("- %s" % n)
    lines.append("")
    lines.append("## api requests (read-only)")
    lines.append("")
    for r in report["api_requests"]:
        lines.append("- `%s`" % r)
    lines.append("")
    lines.append("claim boundary: %s" % report["claim_boundary"])
    lines.append("")
    return "\n".join(lines)


def write_report(path, report):
    body = render_markdown(report) if path.lower().endswith((".md", ".markdown")) else \
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    parent = os.path.dirname(os.path.abspath(path))
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)
    with open(path, "w") as fh:
        fh.write(body)
    return body


def print_summary(report, stream=None):
    stream = stream or sys.stdout
    stream.write("beta-ci-gate: verdict=%s exit=%s repo=%s sha=%s\n"
                 % (report["verdict"], report["exit_code"], report["repo"], report["sha"]))
    for e in report["expectations"]:
        run = e.get("run") or {}
        job = e.get("job_evidence") or {}
        stream.write("  [%-15s] %s :: %s  run=%s attempt=%s job=%s conclusion=%s\n" % (
            e["status"], e["workflow_file"], e["job"], run.get("id"), run.get("run_attempt"),
            job.get("id"), job.get("conclusion")))
    for err in report["errors"]:
        stream.write("  error: %s\n" % err)
    for w in report["warnings"]:
        stream.write("  warning: %s\n" % w)


# --------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------
def _empty_report(repo, sha_input, sha_norm, verdict, exit_code, expects, api_requests, errors,
                  warnings=None, notes=None):
    expectations = []
    for wf, job in expects:
        expectations.append({
            "workflow_file": wf, "workflow_file_normalized": normalize_workflow_file(wf),
            "job": job, "status": STATUS_NOT_RUN, "run": None, "job_evidence": None,
            "detail": "未检查",
        })
    return build_report(repo, sha_input, sha_norm, verdict, exit_code, expectations, [],
                        [], list(warnings or []), list(notes or []), list(api_requests),
                        list(errors))


def evaluate(repo, sha_norm, expects, api, requests_log):
    """单次检查。返回 (verdict, expectations, unexpected, superseded, warnings, notes, errors)。"""
    expectations = []
    unexpected = []
    superseded = []
    warnings = []
    notes = []
    errors = []

    runs = collect_pages(api, "/repos/%s/actions/runs" % repo,
                         {"head_sha": sha_norm}, "workflow_runs")
    notes.append("head_sha=%s 上共取到 %d 个 workflow run（含分页）" % (sha_norm, len(runs)))

    by_file = {}
    for run in runs:
        by_file.setdefault(normalize_workflow_file(run.get("path") or ""), []).append(run)

    # 按 workflow 文件分组处理，避免重复取同一 run 的 jobs
    grouped = {}
    for wf, job in expects:
        grouped.setdefault(normalize_workflow_file(wf), []).append((wf, job))
    # Inspect all other workflows at this SHA too; an unlisted red or running
    # workflow cannot be hidden by an already-green expected job.
    for key, run_group in by_file.items():
        if any(r.get("head_sha") == sha_norm for r in run_group):
            grouped.setdefault(key, [])
    workflow_statuses = []

    for key in sorted(grouped):
        wanted = grouped[key]
        candidates = [r for r in by_file.get(key, []) if r.get("head_sha") == sha_norm]
        if not candidates:
            for wf, job in wanted:
                expectations.append({
                    "workflow_file": wf, "workflow_file_normalized": key, "job": job,
                    "status": STATUS_NOT_RUN, "run": None, "job_evidence": None,
                    "detail": "该 SHA 上没有 %s 的 workflow run（后注册/未触发均视为无证据）" % key,
                })
            notes.append("%s: 该 head 无 run → NOT_RUN" % key)
            continue

        candidates.sort(key=_run_sort_key)
        authoritative = candidates[-1]
        workflow_statuses.append(_job_status_for(authoritative))
        for older in candidates[:-1]:
            identity = _run_identity(older)
            identity["workflow_file"] = key
            superseded.append(identity)
            warnings.append(
                "superseded run: workflow=%s run=%s attempt=%s status=%s conclusion=%s（同一 head "
                "存在更新的 run，判定以最新 run 为准）" % (
                    key, older.get("id"), older.get("run_attempt"), older.get("status"),
                    older.get("conclusion")))

        attempt = int(authoritative.get("run_attempt") or 1)
        jobs = collect_pages(
            api, "/repos/%s/actions/runs/%s/attempts/%s/jobs" % (repo, authoritative.get("id"),
                                                               attempt),
            {}, "jobs")
        notes.append("authoritative run: workflow=%s run=%s attempt=%s status=%s conclusion=%s "
                     "(jobs=%d)" % (key, authoritative.get("id"), attempt,
                                    authoritative.get("status"),
                                    authoritative.get("conclusion"), len(jobs)))
        if len(jobs) and max(int(j.get("run_attempt") or 1) for j in jobs) != attempt:
            warnings.append("workflow=%s run=%s 的 jobs.run_attempt 与 run.run_attempt(%s) 不一致"
                            % (key, authoritative.get("id"), attempt))

        consumed = set()
        for wf, job_name in wanted:
            matches = [j for j in jobs if j.get("name") == job_name]
            for m in matches:
                consumed.add(id(m))
            if not matches:
                if authoritative.get("status") == "completed":
                    if authoritative.get("conclusion") in RUN_INFRA_CONCLUSIONS:
                        status = STATUS_STARTUP_FAILURE
                        detail = ("run 启动失败（conclusion=%s），预期 job 未产生"
                                  % authoritative.get("conclusion"))
                    else:
                        status = STATUS_MISSING_JOB
                        detail = "run 已完成但工作流里不存在该 job 显示名（名字写错或未定义）"
                else:
                    status = STATUS_PENDING
                    detail = "run 尚未结束，该 job 还未出现"
                expectations.append({
                    "workflow_file": wf, "workflow_file_normalized": key, "job": job_name,
                    "status": status, "run": _run_identity(authoritative), "job_evidence": None,
                    "detail": detail,
                })
                continue

            if len(matches) > 1:
                warnings.append("workflow=%s run=%s 有 %d 个同名 job %r，按最严重结论判定"
                                % (key, authoritative.get("id"), len(matches), job_name))
            chosen = matches[0]
            status = _job_status_for(chosen)
            for m in matches:
                status = _worse(status, _job_status_for(m))
            expectations.append({
                "workflow_file": wf, "workflow_file_normalized": key, "job": job_name,
                "status": status, "run": _run_identity(authoritative),
                "job_evidence": _job_identity(chosen),
                "detail": "job status=%s conclusion=%s" % (chosen.get("status"),
                                                           chosen.get("conclusion")),
            })

        for j in jobs:
            if id(j) in consumed:
                continue
            jstatus = _job_status_for(j)
            if jstatus == STATUS_OK:
                continue
            if jstatus == STATUS_PENDING:
                workflow_statuses.append(jstatus)
            severity = "fail" if jstatus in UNEXPECTED_FAIL_STATUSES else "warn"
            unexpected.append({
                "workflow_file": key, "run_id": authoritative.get("id"),
                "run_attempt": attempt, "job": j.get("name"), "job_id": j.get("id"),
                "status": jstatus, "job_status": j.get("status"),
                "conclusion": j.get("conclusion"), "severity": severity,
                "html_url": j.get("html_url"),
            })
            if severity == "fail":
                warnings.append("非预期 job 失败（不被预期 success 掩盖）：workflow=%s run=%s "
                                "job=%s conclusion=%s" % (key, authoritative.get("id"),
                                                          j.get("name"), j.get("conclusion")))
            else:
                warnings.append("非预期 job 非成功但非失败：workflow=%s job=%s conclusion=%s"
                                % (key, j.get("name"), j.get("conclusion")))

    statuses = [e["status"] for e in expectations] + workflow_statuses
    verdict = verdict_for_statuses(statuses)
    if any(u["severity"] == "fail" for u in unexpected):
        verdict = VERDICT_FAIL
    return verdict, expectations, unexpected, superseded, warnings, notes, errors


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="exact-head 全量 CI 门（单次检查，不 poll）")
    parser.add_argument("--repo", required=True, help="OWNER/REPO")
    parser.add_argument("--sha", required=True, help="40 位十六进制 full SHA（只校验不修补）")
    parser.add_argument("--expect", action="append", default=[],
                        help="FILE=JOB，可重复；必须逐个枚举 job 显示名")
    parser.add_argument("--output", required=True, help="报告输出路径（.json 或 .md）")
    parser.add_argument("--api-fixture", default=None,
                        help="[仅测试] 合成 API 响应目录，替代 gh 网络调用")
    args = parser.parse_args(list(argv) if argv is not None else sys.argv[1:])

    requests_log = []
    errors = []

    # ---- 输入校验：只校验，不修补 ----
    try:
        repo = validate_repo(args.repo)
        sha_norm = validate_sha(args.sha)
        if not args.expect:
            raise InvalidInput("至少需要一个 --expect FILE=JOB（一一枚举，不做总数判断）")
        expects = []
        seen = set()
        for raw in args.expect:
            pair = parse_expect(raw)
            if pair in seen:
                continue
            seen.add(pair)
            expects.append(pair)
    except InvalidInput as exc:
        report = _empty_report(args.repo, args.sha, None, VERDICT_INVALID_INPUT,
                               EXIT_INVALID_INPUT, [], requests_log, [str(exc)])
        write_report(args.output, report)
        print_summary(report)
        return EXIT_INVALID_INPUT

    # ---- API 层 ----
    try:
        api = FixtureApi(args.api_fixture, requests_log) if args.api_fixture \
            else GhApi(requests_log=requests_log)
    except ApiError as exc:
        report = _empty_report(repo, args.sha, sha_norm, VERDICT_INFRA_RETRY, EXIT_INFRA_RETRY,
                               expects, requests_log, [str(exc)])
        write_report(args.output, report)
        print_summary(report)
        return EXIT_INFRA_RETRY

    # ---- 单次检查 ----
    try:
        verdict, expectations, unexpected, superseded, warnings, notes, errs = evaluate(
            repo, sha_norm, expects, api, requests_log)
        errors.extend(errs)
    except ApiError as exc:
        report = _empty_report(repo, args.sha, sha_norm, VERDICT_INFRA_RETRY, EXIT_INFRA_RETRY,
                               expects, requests_log, [str(exc)])
        report["notes"].append("API 失败，判定为 INFRA_RETRY（可重跑；不得当作 PASS）")
        write_report(args.output, report)
        print_summary(report)
        return EXIT_INFRA_RETRY

    exit_code = EXIT_PASS if verdict == VERDICT_PASS else EXIT_NONPASS
    report = build_report(repo, args.sha, sha_norm, verdict, exit_code, expectations, unexpected,
                          superseded, warnings, notes, requests_log, errors)
    write_report(args.output, report)
    print_summary(report)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
