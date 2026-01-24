from __future__ import annotations

import json
import time
import traceback
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional
from uuid import uuid4

from PyQt6.QtCore import QObject, QRunnable, QThreadPool, pyqtSignal


@dataclass
class ExecutionResult:
    returncode: int
    stdout: List[str] = field(default_factory=list)
    stderr: List[str] = field(default_factory=list)
    error: Optional[str] = None
    elapsed: float = 0.0
    payload: Any = None


@dataclass
class JobSpec:
    name: str
    category: str
    execute: Callable[[], ExecutionResult]
    parse: Callable[[ExecutionResult], Dict[str, Any]]
    ui_update: Callable[[Dict[str, Any]], None]
    precheck: Optional[Callable[[], tuple[bool, str, str]]] = None
    job_id: str = field(default_factory=lambda: uuid4().hex[:8].upper())
    feature_key: Optional[str] = None


class DiagnosticsLog:
    def __init__(self, limit: int = 500):
        self.limit = limit
        self.events: List[Dict[str, Any]] = []
        self.errors: List[Dict[str, Any]] = []

    def log_event(self, event: Dict[str, Any]) -> None:
        self.events.append(event)
        if len(self.events) > self.limit:
            self.events = self.events[-self.limit :]

    def log_error(self, exc: BaseException) -> None:
        self.errors.append(
            {
                "type": exc.__class__.__name__,
                "message": str(exc),
                "trace": traceback.format_exc(),
            }
        )
        if len(self.errors) > self.limit:
            self.errors = self.errors[-self.limit :]

    def export(self, path: str, context: Dict[str, Any]) -> None:
        payload = {
            "context": context,
            "events": self.events,
            "errors": self.errors,
        }
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, indent=2)


class JobSignals(QObject):
    event = pyqtSignal(dict)
    result = pyqtSignal(dict)


class JobWorker(QRunnable):
    def __init__(self, job: JobSpec, diagnostics: DiagnosticsLog):
        super().__init__()
        self.job = job
        self.signals = JobSignals()
        self.diagnostics = diagnostics

    def _emit(self, event_type: str, detail: Dict[str, Any]) -> None:
        payload = {
            "ts": time.time(),
            "type": event_type,
            "job_id": self.job.job_id,
            "name": self.job.name,
            "category": self.job.category,
            "feature_key": self.job.feature_key,
            "detail": detail,
        }
        self.signals.event.emit(payload)
        self.diagnostics.log_event(payload)

    def run(self) -> None:
        self._emit("JOB_START", {})
        if self.job.precheck:
            ok, reason, guidance = self.job.precheck()
            if not ok:
                self._emit("PRECHECK", {"status": "fail", "reason": reason, "guidance": guidance})
                self._emit("PRECHECK_FAIL", {"reason": reason, "guidance": guidance})
                if self.job.feature_key:
                    self._emit(
                        "BLOCKED_BY_CAPABILITY",
                        {"feature": self.job.feature_key, "reason": reason, "guidance": guidance},
                    )
                self.signals.result.emit(
                    {
                        "job_id": self.job.job_id,
                        "name": self.job.name,
                        "category": self.job.category,
                        "feature_key": self.job.feature_key,
                        "status": "failed",
                        "error": reason,
                        "guidance": guidance,
                        "payload": {},
                        "summary": {},
                        "raw": {},
                    }
                )
                self._emit("JOB_FAIL", {"reason": reason})
                return
            self._emit("PRECHECK", {"status": "ok"})
            self._emit("PRECHECK_OK", {})

        self._emit("EXEC_START", {})
        try:
            result = self.job.execute()
        except Exception as exc:
            self.diagnostics.log_error(exc)
            self._emit("EXEC_END", {"returncode": 1, "error": str(exc)})
            self.signals.result.emit(
                {
                    "job_id": self.job.job_id,
                    "name": self.job.name,
                    "category": self.job.category,
                    "feature_key": self.job.feature_key,
                    "status": "failed",
                    "error": str(exc),
                    "guidance": "",
                    "payload": {},
                    "summary": {},
                    "raw": {},
                }
            )
            self._emit("JOB_FAIL", {"reason": str(exc)})
            return

        self._emit("EXEC_END", {"returncode": result.returncode, "elapsed": result.elapsed})
        try:
            payload = self.job.parse(result)
            self._emit("PARSE_OK", {})
        except Exception as exc:
            self.diagnostics.log_error(exc)
            self._emit("PARSE_FAIL", {"reason": str(exc)})
            payload = {
                "summary": {"error": "Parse failed", "details": str(exc)},
                "items": [],
            }
        payload["_ui_update"] = self.job.ui_update

        summary = payload.get("summary", {})
        counts = payload.get("counts", {})
        self._emit("RESULT_COUNT", {"counts": counts})
        self._emit("RESULT", {"summary": summary, "counts": counts})
        self.signals.result.emit(
            {
                "job_id": self.job.job_id,
                "name": self.job.name,
                "category": self.job.category,
                "feature_key": self.job.feature_key,
                "status": "success" if result.returncode == 0 else "failed",
                "error": result.error or "",
                "guidance": "",
                "payload": payload,
                "summary": summary,
                "raw": {"stdout": result.stdout, "stderr": result.stderr},
                "elapsed": result.elapsed,
                "returncode": result.returncode,
            }
        )
        if result.returncode == 0:
            self._emit("JOB_END", {})
        else:
            self._emit("JOB_FAIL", {"reason": f"Return code {result.returncode}"})


class JobManager(QObject):
    result_emitted = pyqtSignal(dict)
    event_emitted = pyqtSignal(dict)

    def __init__(self, log_callback: Callable[[str], None]):
        super().__init__()
        self.pool = QThreadPool.globalInstance()
        self.diagnostics = DiagnosticsLog()
        self.log_callback = log_callback
        self.job_history: List[Dict[str, Any]] = []

    def run_job(self, job: JobSpec) -> None:
        worker = JobWorker(job, self.diagnostics)
        worker.signals.event.connect(self._handle_event)
        worker.signals.result.connect(self._handle_result)
        self.pool.start(worker)

    def _handle_event(self, event: Dict[str, Any]) -> None:
        event_type = event.get("type")
        name = event.get("name")
        self.event_emitted.emit(event)
        if event_type == "JOB_START":
            self.log_callback(f"[job] {name} started")
        elif event_type == "PRECHECK_FAIL":
            detail = event.get("detail", {})
            self.log_callback(f"[job] {name} blocked: {detail.get('reason')}")
        elif event_type == "JOB_END":
            self.log_callback(f"[job] {name} completed")
        elif event_type == "JOB_FAIL":
            detail = event.get("detail", {})
            self.log_callback(f"[job] {name} failed: {detail.get('reason')}")
        elif event_type == "BLOCKED_BY_CAPABILITY":
            detail = event.get("detail", {})
            feature = detail.get("feature", "unknown")
            reason = detail.get("reason", "blocked")
            self.log_callback(f"[job] {name} blocked by {feature}: {reason}")

    def _handle_result(self, result: Dict[str, Any]) -> None:
        self.job_history.append(result)
        if len(self.job_history) > 500:
            self.job_history = self.job_history[-500:]
        self.result_emitted.emit(result)
        try:
            payload = result.get("payload", {})
            ui_update = payload.get("_ui_update")
            if callable(ui_update):
                ui_update(payload)
        except Exception as exc:
            self.diagnostics.log_error(exc)

    def export_diagnostics(self, path: str, context: Dict[str, Any]) -> None:
        self.diagnostics.export(path, context)
