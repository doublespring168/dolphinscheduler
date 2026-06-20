#!/usr/bin/env python3
"""
Register a one-node SHELL workflow in a project, publish it, and start it.

Examples:
  python3 test/o-18-1.py \
    --base http://127.0.0.1:12345/gyyun \
    --token YOUR_ACCESS_TOKEN \
    --project-code 123456789 \
    --raw-script "echo 'hello from api'"

  python3 test/o-18-2.py \
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
    startParams: list,
) -> int:

    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    result = api.post(
        f"/projects/{project_code}/executors/start-workflow-instance",
        {
            "workflowDefinitionCode": workflow_code,
            "failureStrategy": failure_strategy,
            "warningType": warning_type,
            "warningGroupId": 0,
            "execType": "START_PROCESS",
            "startNodeList": "",
            "taskDependType": "TASK_POST",

            "complementDependentMode": "OFF_MODE",
            "runMode": "RUN_MODE_SERIAL",

            "workflowInstancePriority": priority,
            "workerGroup": worker_group,
            "tenantCode": tenant_code,
            "environmentCode": environment_code,

            "startParams": startParams,

            "expectedParallelismNumber": 2,
            "dryRun": 0,
            "scheduleTime": f"{now},{now}",
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
    parser.add_argument("--project-code", default=env("GYYUN_PROJECT_CODE"))
    parser.add_argument("--workflow-code", default=env("GYYUN_WORKFLOW_CODE"))
    parser.add_argument("--description", default="created by test/o-18-1.py")
    parser.add_argument("--tenant-code", default=env("GYYUN_TENANT_CODE", "default"))
    parser.add_argument("--worker-group", default=env("GYYUN_WORKER_GROUP", "default"))
    parser.add_argument("--environment-code", type=int, default=int(env("GYYUN_ENVIRONMENT_CODE", "-1")))
    parser.add_argument("--failure-strategy", default="END", choices=["END", "CONTINUE"])
    parser.add_argument("--warning-type", default="NONE")
    parser.add_argument("--priority", default="MEDIUM")
    parser.add_argument("--retry-times", type=int, default=0)

    args = parser.parse_args(list(argv))

    if not args.project_code:
        parser.error("missing --project-code or GYYUN_PROJECT_CODE")

    if not args.token and not args.session_id:
        if not args.username or not args.password:
            parser.error("provide --token, --session-id, or both --username and --password")

    return args


def main(argv: Iterable[str]) -> int:
    args = parse_args(argv)
    api = GyyunApi(
        base_url=args.base,
        token=args.token,
    )

    workflow_instance_id = start_workflow(
        api=api,
        project_code=args.project_code,
        workflow_code=args.workflow_code,
        failure_strategy=args.failure_strategy,
        warning_type=args.warning_type,
        priority=args.priority,
        worker_group=args.worker_group,
        tenant_code=args.tenant_code,
        environment_code=args.environment_code,
        startParams=json.dumps([{"prop":"output_dir","direct":"IN","type":"VARCHAR","value":"output_dir-123"}]),
    )

    print(
        pretty(
            {
                "success": True,
                "projectCode": args.project_code,
                "workflowDefinitionCode": args.workflow_code,
                "workflowInstanceId": workflow_instance_id,
            }
        )
    )
    return 0


if __name__ == "__main__":
    try:
       main(sys.argv[1:])
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
