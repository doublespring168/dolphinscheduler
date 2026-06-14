#!/usr/bin/env python3
"""
Register a one-node SHELL workflow in a project, publish it, and start it.

Examples:
  python3 test/o-18-1.py \
    --base http://127.0.0.1:12345/gyyun \
    --token YOUR_ACCESS_TOKEN \
    --project-code 123456789 \
    --raw-script "echo 'hello from api'"

  python3 test/o-18-1.py \
    --username admin \
    --password gyyun123 \
    --project-code 123456789

Environment variables:
  GYYUN_API_BASE
  GYYUN_TOKEN
  GYYUN_SESSION_ID
  GYYUN_USERNAME
  GYYUN_PASSWORD
  GYYUN_PROJECT_CODE
  GYYUN_TENANT_CODE
  GYYUN_WORKER_GROUP
  GYYUN_ENVIRONMENT_CODE
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from typing import Any, Dict, Iterable, Mapping, Optional


SUCCESS_CODE = 0


class ApiError(RuntimeError):
    pass


class GyyunApi:
    def __init__(
        self,
        base_url: str,
        token: Optional[str] = None,
        session_id: Optional[str] = None,
        timeout: int = 30,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.session_id = session_id
        self.timeout = timeout

    def login(self, username: str, password: str) -> str:
        result = self.post(
            "/login",
            {"userName": username, "userPassword": password},
            use_auth=False,
            step="login",
        )
        require_success(result, "login")

        data = result.get("data") or {}
        session_id = data.get("sessionId")
        if not session_id:
            raise ApiError(f"login succeeded but response has no sessionId: {pretty(result)}")

        self.session_id = session_id
        return session_id

    def get(self, path: str, params: Optional[Mapping[str, Any]] = None, step: str = "GET") -> Dict[str, Any]:
        return self._request("GET", path, params=params, step=step)

    def post(
        self,
        path: str,
        params: Optional[Mapping[str, Any]] = None,
        use_auth: bool = True,
        step: str = "POST",
    ) -> Dict[str, Any]:
        return self._request("POST", path, params=params, use_auth=use_auth, step=step)

    def _request(
        self,
        method: str,
        path: str,
        params: Optional[Mapping[str, Any]] = None,
        use_auth: bool = True,
        step: str = "request",
    ) -> Dict[str, Any]:
        params = drop_none(params or {})
        url = f"{self.base_url}/{path.lstrip('/')}"
        body = None

        if method == "GET" and params:
            url = f"{url}?{urllib.parse.urlencode(params)}"
        elif method != "GET":
            body = urllib.parse.urlencode(params).encode("utf-8")

        headers = {
            "Accept": "application/json",
            "User-Agent": "gyyun-register-and-start-task/1.0",
        }
        if method != "GET":
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        if use_auth:
            headers.update(self._auth_headers())

        request = urllib.request.Request(url, data=body, headers=headers, method=method)

        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = response.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as exc:
            payload = exc.read().decode("utf-8", errors="replace")
            raise ApiError(f"{step} failed with HTTP {exc.code}: {payload}") from exc
        except urllib.error.URLError as exc:
            raise ApiError(f"{step} failed to connect to {url}: {exc.reason}") from exc

        try:
            return json.loads(payload)
        except json.JSONDecodeError as exc:
            raise ApiError(f"{step} returned non-JSON response: {payload}") from exc

    def _auth_headers(self) -> Dict[str, str]:
        if self.token:
            return {"token": self.token}
        if self.session_id:
            return {"sessionId": self.session_id}
        raise ApiError("missing auth: provide --token, --session-id, or --username/--password")


def drop_none(params: Mapping[str, Any]) -> Dict[str, Any]:
    return {key: value for key, value in params.items() if value is not None}


def pretty(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2)


def compact_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def require_success(result: Mapping[str, Any], step: str) -> None:
    if result.get("code") != SUCCESS_CODE:
        raise ApiError(f"{step} failed: {pretty(result)}")


def env(name: str, default: Optional[str] = None) -> Optional[str]:
    return os.environ.get(name) or default


def build_task_definition(
    task_code: int,
    task_name: str,
    raw_script: str,
    worker_group: str,
    environment_code: int,
    retry_times: int,
    retry_interval: int,
    task_timeout: int,
    timeout_strategy: str,
) -> str:
    task_definition = [
        {
            "code": task_code,
            "name": task_name,
            "version": 0,
            "description": "",
            "delayTime": 0,
            "taskType": "SHELL",
            "taskParams": {
                "localParams": [],
                "rawScript": raw_script,
                "resourceList": [],
            },
            "flag": "YES",
            "taskPriority": "MEDIUM",
            "workerGroup": worker_group,
            "environmentCode": environment_code,
            "failRetryTimes": retry_times,
            "failRetryInterval": retry_interval,
            "timeoutFlag": "CLOSE" if task_timeout <= 0 else "OPEN",
            "timeoutNotifyStrategy": "" if task_timeout <= 0 else timeout_strategy,
            "timeout": task_timeout,
            "taskGroupId": 0,
            "taskGroupPriority": 0,
            "cpuQuota": -1,
            "memoryMax": -1,
            "taskExecuteType": "BATCH",
        }
    ]
    return compact_json(task_definition)


def build_task_relation(task_code: int) -> str:
    task_relation = [
        {
            "name": "",
            "preTaskCode": 0,
            "preTaskVersion": 0,
            "postTaskCode": task_code,
            "postTaskVersion": 0,
            "conditionType": "NONE",
            "conditionParams": {},
        }
    ]
    return compact_json(task_relation)


def gen_task_code(api: GyyunApi, project_code: str) -> int:
    result = api.get(
        f"/projects/{project_code}/task-definition/gen-task-codes",
        {"genNum": 1},
        step="generate task code",
    )
    require_success(result, "generate task code")

    data = result.get("data") or []
    if not data:
        raise ApiError(f"generate task code returned empty data: {pretty(result)}")
    return int(data[0])


def create_workflow(
    api: GyyunApi,
    project_code: str,
    workflow_name: str,
    description: str,
    task_definition_json: str,
    task_relation_json: str,
) -> int:
    result = api.post(
        f"/projects/{project_code}/workflow-definition",
        {
            "name": workflow_name,
            "description": description,
            "globalParams": "[]",
            "locations": "[]",
            "timeout": 0,
            "executionType": "PARALLEL",
            "taskDefinitionJson": task_definition_json,
            "taskRelationJson": task_relation_json,
        },
        step="create workflow definition",
    )
    require_success(result, "create workflow definition")

    data = result.get("data") or {}
    workflow_code = data.get("code")
    if workflow_code is None:
        raise ApiError(f"create workflow returned no data.code: {pretty(result)}")
    return int(workflow_code)


def release_workflow(api: GyyunApi, project_code: str, workflow_code: int) -> None:
    result = api.post(
        f"/projects/{project_code}/workflow-definition/{workflow_code}/release",
        {"releaseState": "ONLINE"},
        step="release workflow definition",
    )
    require_success(result, "release workflow definition")


def start_workflow(
    api: GyyunApi,
    project_code: str,
    workflow_code: int,
    failure_strategy: str,
    warning_type: str,
    priority: str,
    worker_group: str,
    tenant_code: str,
    environment_code: int,
    dry_run: int,
) -> int:
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    result = api.post(
        f"/projects/{project_code}/executors/start-workflow-instance",
        {
            "workflowDefinitionCode": workflow_code,
            "scheduleTime": f"{now},{now}",
            "failureStrategy": failure_strategy,
            "startNodeList": "",
            "taskDependType": "TASK_POST",
            "execType": "START_PROCESS",
            "warningType": warning_type,
            "warningGroupId": 0,
            "workflowInstancePriority": priority,
            "workerGroup": worker_group,
            "tenantCode": tenant_code,
            "environmentCode": environment_code,
            "dryRun": dry_run,
        },
        step="start workflow instance",
    )
    require_success(result, "start workflow instance")

    data = result.get("data") or []
    if not data:
        raise ApiError(f"start workflow returned empty data: {pretty(result)}")
    return int(data[0])


def parse_args(argv: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Register and start a one-node SHELL task through the Gyyun/DolphinScheduler REST API."
    )
    parser.add_argument("--base", default=env("GYYUN_API_BASE", "http://127.0.0.1:12345/gyyun"))
    parser.add_argument("--token", default=env("GYYUN_TOKEN"))
    parser.add_argument("--session-id", default=env("GYYUN_SESSION_ID"))
    parser.add_argument("--username", default=env("GYYUN_USERNAME"))
    parser.add_argument("--password", default=env("GYYUN_PASSWORD"))
    parser.add_argument("--project-code", default=env("GYYUN_PROJECT_CODE"))
    parser.add_argument("--workflow-name")
    parser.add_argument("--task-name", default="shell_task")
    parser.add_argument("--raw-script", default="echo 'hello from api'")
    parser.add_argument("--description", default="created by test/o-18-1.py")
    parser.add_argument("--tenant-code", default=env("GYYUN_TENANT_CODE", "default"))
    parser.add_argument("--worker-group", default=env("GYYUN_WORKER_GROUP", "default"))
    parser.add_argument("--environment-code", type=int, default=int(env("GYYUN_ENVIRONMENT_CODE", "-1")))
    parser.add_argument("--failure-strategy", default="END", choices=["END", "CONTINUE"])
    parser.add_argument("--warning-type", default="NONE")
    parser.add_argument("--priority", default="MEDIUM")
    parser.add_argument("--retry-times", type=int, default=0)
    parser.add_argument("--retry-interval", type=int, default=1)
    parser.add_argument("--task-timeout", type=int, default=0)
    parser.add_argument("--timeout-strategy", default="WARN", choices=["WARN", "FAILED", "WARNFAILED"])
    parser.add_argument("--dry-run", action="store_true", help="Set dryRun=1 when starting the workflow.")
    parser.add_argument("--timeout", type=int, default=30, help="HTTP timeout in seconds.")

    args = parser.parse_args(list(argv))

    if not args.project_code:
        parser.error("missing --project-code or GYYUN_PROJECT_CODE")

    if not args.token and not args.session_id:
        if not args.username or not args.password:
            parser.error("provide --token, --session-id, or both --username and --password")

    if not args.workflow_name:
        args.workflow_name = f"api_shell_demo_{int(time.time() * 1000)}"

    return args


def main(argv: Iterable[str]) -> int:
    args = parse_args(argv)
    api = GyyunApi(
        base_url=args.base,
        token=args.token,
        session_id=args.session_id,
        timeout=args.timeout,
    )

    if not args.token and not args.session_id:
        api.login(args.username, args.password)

    task_code = gen_task_code(api, args.project_code)
    task_definition_json = build_task_definition(
        task_code=task_code,
        task_name=args.task_name,
        raw_script=args.raw_script,
        worker_group=args.worker_group,
        environment_code=args.environment_code,
        retry_times=args.retry_times,
        retry_interval=args.retry_interval,
        task_timeout=args.task_timeout,
        timeout_strategy=args.timeout_strategy,
    )
    task_relation_json = build_task_relation(task_code)

    workflow_code = create_workflow(
        api=api,
        project_code=args.project_code,
        workflow_name=args.workflow_name,
        description=args.description,
        task_definition_json=task_definition_json,
        task_relation_json=task_relation_json,
    )
    release_workflow(api, args.project_code, workflow_code)
    workflow_instance_id = start_workflow(
        api=api,
        project_code=args.project_code,
        workflow_code=workflow_code,
        failure_strategy=args.failure_strategy,
        warning_type=args.warning_type,
        priority=args.priority,
        worker_group=args.worker_group,
        tenant_code=args.tenant_code,
        environment_code=args.environment_code,
        dry_run=1 if args.dry_run else 0,
    )

    print(
        pretty(
            {
                "success": True,
                "projectCode": args.project_code,
                "workflowName": args.workflow_name,
                "workflowDefinitionCode": workflow_code,
                "taskCode": task_code,
                "workflowInstanceId": workflow_instance_id,
            }
        )
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except ApiError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
