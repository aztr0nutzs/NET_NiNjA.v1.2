import asyncio
import json
import os
import re
import secrets
import subprocess
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional
from uuid import uuid4

import jwt
from fastapi import Depends, FastAPI, HTTPException, WebSocket, WebSocketDisconnect, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, validator

from .security_utils import sanitize_command_for_display, validate_allowlisted_command

app = FastAPI(title="NetReaper Remote Server")

NETREAPER_ROOT = Path(__file__).resolve().parents[1]
SERVICE_ROOT = NETREAPER_ROOT / "service"
OUTPUT_DIR = Path(os.environ.get("NETREAPER_OUTPUT_DIR", NETREAPER_ROOT / "output"))
NETREAPER_BIN = NETREAPER_ROOT / "bin" / "netreaper"

OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

# SECURITY: Strict CORS configuration
allowed_origins = os.environ.get("NETREAPER_ALLOWED_ORIGINS", "").split(",")
allowed_origins = [origin.strip() for origin in allowed_origins if origin.strip()]
if not allowed_origins:
    # Default to localhost only if not configured
    allowed_origins = ["http://localhost", "https://localhost", "http://127.0.0.1", "https://127.0.0.1"]

app.add_middleware(
    CORSMiddleware,
    allow_origins=allowed_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)

# Mount static files for GUI if available
if (NETREAPER_ROOT / "gui").exists():
    from fastapi.staticfiles import StaticFiles

    app.mount("/static", StaticFiles(directory=NETREAPER_ROOT / "gui"), name="static")
else:
    print("[warning] Static GUI directory not found; skipping /static mount.")

# SECURITY: SECRET_KEY must be defined; do not fall back to an insecure default
SECRET_KEY = os.environ.get("NETREAPER_SECRET")
if not SECRET_KEY:
    raise RuntimeError(
        "NETREAPER_SECRET environment variable must be set for secure token signing. "
        "Generate with: python -c 'import secrets; print(secrets.token_urlsafe(32))'"
    )

# SECURITY: Validate secret key strength
if len(SECRET_KEY) < 32:
    raise RuntimeError("NETREAPER_SECRET must be at least 32 characters for security")

paired_sessions: dict[str, dict] = {}

# SECURITY: Rate limiting for authentication
auth_attempts: dict[str, list] = {}
MAX_AUTH_ATTEMPTS = 5
AUTH_WINDOW_SECONDS = 300  # 5 minutes

# Security models
security = HTTPBearer()


class AuthRequest(BaseModel):
    password: str

    @validator("password")
    def password_not_empty(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("Password cannot be empty")
        return value


class PairRequest(BaseModel):
    deviceId: str
    role: str

    @validator("role")
    def role_must_be_valid(cls, value: str) -> str:
        if value not in {"remote", "gui"}:
            raise ValueError('Role must be "remote" or "gui"')
        return value

    @validator("deviceId")
    def device_id_not_empty(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("Device ID cannot be empty")
        return value


class CommandRequest(BaseModel):
    command: str

    @validator("command")
    def command_not_empty(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("Command cannot be empty")
        return value


class ScanRequest(BaseModel):
    target: str
    mode: str = "quick"

    @validator("target")
    def target_not_empty(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("Target cannot be empty")
        return value

    @validator("mode")
    def mode_valid(cls, value: str) -> str:
        if value not in {"quick", "wifi"}:
            raise ValueError("Mode must be quick or wifi")
        return value


class ReportResponse(BaseModel):
    ok: bool
    target: str | None = None
    output_file: str | None = None
    scanned_at: str | None = None
    device_count: int = 0
    ports_open: int = 0
    note: str | None = None


def check_rate_limit(client_id: str) -> bool:
    """SECURITY: Check if client has exceeded rate limit for authentication."""
    now = datetime.utcnow()
    if client_id not in auth_attempts:
        auth_attempts[client_id] = []

    # Clean old attempts
    auth_attempts[client_id] = [
        attempt for attempt in auth_attempts[client_id] if (now - attempt).total_seconds() < AUTH_WINDOW_SECONDS
    ]

    # Check limit
    if len(auth_attempts[client_id]) >= MAX_AUTH_ATTEMPTS:
        return False

    auth_attempts[client_id].append(now)
    return True


def create_token(data: dict) -> str:
    """Create a signed JWT with an expiry."""
    payload = data.copy()
    if "exp" not in payload:
        payload["exp"] = datetime.utcnow() + timedelta(hours=1)
    # SECURITY: Add issued-at and jti claims
    payload["iat"] = datetime.utcnow()
    payload["jti"] = secrets.token_urlsafe(16)
    return jwt.encode(payload, SECRET_KEY, algorithm="HS256")


def verify_token(token: str) -> dict | None:
    """SECURITY: Verify JWT token with comprehensive validation."""
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=["HS256"])
        if "exp" not in payload or "iat" not in payload:
            return None
        return payload
    except jwt.ExpiredSignatureError:
        return None
    except jwt.InvalidTokenError:
        return None
    except Exception:
        return None


def sanitize_output(line: str) -> str:
    """SECURITY: Redact sensitive patterns from command output."""
    line = re.sub(r"(password|passwd|pwd)[=:\s]+\S+", r"\1=***REDACTED***", line, flags=re.IGNORECASE)
    line = re.sub(r"(api[_-]?key|token|secret)[=:\s]+\S+", r"\1=***REDACTED***", line, flags=re.IGNORECASE)
    line = re.sub(r"(Authorization|Bearer)[:\s]+\S+", r"\1: ***REDACTED***", line, flags=re.IGNORECASE)
    return line


async def get_current_user(credentials: HTTPAuthorizationCredentials = Depends(security)) -> dict:
    """SECURITY: Dependency to validate JWT tokens."""
    token = credentials.credentials
    payload = verify_token(token)
    if not payload:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return payload


log_subscribers: set[asyncio.Queue[str]] = set()
scan_jobs: dict[str, dict] = {}
last_scan_meta: dict[str, str] = {}


def enqueue_log(line: str) -> None:
    if not log_subscribers:
        return
    for queue in list(log_subscribers):
        queue.put_nowait(line)


def find_latest_output(pattern: str) -> Optional[Path]:
    candidates = list(OUTPUT_DIR.glob(pattern))
    if not candidates:
        return None
    return max(candidates, key=lambda path: path.stat().st_mtime)


def parse_nmap_devices(output_file: Path) -> list[dict]:
    import xml.etree.ElementTree as ET

    devices: list[dict] = []
    tree = ET.parse(output_file)
    root = tree.getroot()
    for host in root.findall("host"):
        status = host.find("status")
        if status is not None and status.get("state") != "up":
            continue
        addr = host.find("address")
        ip = addr.get("addr") if addr is not None else "unknown"
        ports = []
        for port in host.findall("ports/port"):
            state = port.find("state")
            if state is None or state.get("state") != "open":
                continue
            ports.append(
                {
                    "port": port.get("portid"),
                    "protocol": port.get("protocol"),
                    "service": (port.find("service").get("name") if port.find("service") is not None else ""),
                }
            )
        devices.append({"ip": ip, "ports": ports})
    return devices


async def run_netreaper_scan(target: str, mode: str, job_id: str) -> None:
    env = os.environ.copy()
    env["NETREAPER_ROOT"] = str(NETREAPER_ROOT)
    env["OUTPUT_DIR"] = str(OUTPUT_DIR)

    if not NETREAPER_BIN.exists():
        scan_jobs[job_id]["status"] = "failed"
        scan_jobs[job_id]["error"] = "NetReaper binary not found"
        enqueue_log("NetReaper binary not found")
        return

    command = [str(NETREAPER_BIN)]
    if mode == "wifi":
        command += ["wifi", "scan"]
    else:
        command += ["scan", target]

    enqueue_log(f"Starting NetReaper scan: {' '.join(command)}")
    scan_jobs[job_id]["status"] = "running"

    process = await asyncio.create_subprocess_exec(
        *command,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
        cwd=str(NETREAPER_ROOT),
        env=env,
    )

    if process.stdout:
        async for line in process.stdout:
            enqueue_log(sanitize_output(line.decode(errors="ignore").rstrip()))

    return_code = await process.wait()
    scan_jobs[job_id]["status"] = "success" if return_code == 0 else "failed"
    scan_jobs[job_id]["returncode"] = return_code
    scan_jobs[job_id]["completed_at"] = datetime.utcnow().isoformat()

    if mode == "wifi":
        output_file = find_latest_output("wifi_scan_*.json")
    else:
        output_file = find_latest_output("nmap_quick_*.xml")

    if output_file:
        scan_jobs[job_id]["output_file"] = str(output_file)
        last_scan_meta["output_file"] = str(output_file)
        last_scan_meta["target"] = target
        last_scan_meta["scanned_at"] = scan_jobs[job_id]["completed_at"]

    enqueue_log(f"NetReaper scan completed with code {return_code}")


@app.post("/auth")
def authenticate(auth_req: AuthRequest):
    """
    Authenticate a user and return a JWT.
    """
    client_id = "global"  # In production, use request.client.host
    if not check_rate_limit(client_id):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many authentication attempts. Please try again later.",
        )

    secret_pw = os.environ.get("NETREAPER_PASSWORD")
    if not secret_pw:
        raise HTTPException(status_code=500, detail="Server password is not configured")

    if not secrets.compare_digest(auth_req.password, secret_pw):
        raise HTTPException(status_code=401, detail="Invalid password")

    token = create_token({"user": "admin", "role": "admin"})
    return {"token": token}


@app.post("/pair")
def pair_device(pair_req: PairRequest, user: dict = Depends(get_current_user)):
    """SECURITY: Requires authentication to pair devices."""
    pair_code = secrets.token_urlsafe(8).upper()
    paired_sessions[pair_code] = {
        "device": pair_req.deviceId,
        "role": pair_req.role,
        "status": "paired",
        "created_at": datetime.utcnow().isoformat(),
        "created_by": user.get("user", "unknown"),
    }
    return {"pairCode": pair_code, "status": "paired"}


@app.post("/api/telemetry")
def telemetry(data: dict, user: dict = Depends(get_current_user)):
    """SECURITY: Requires authentication for telemetry."""
    sanitized_data = {k: v for k, v in data.items() if k not in ["password", "secret", "token"]}
    print(f"Telemetry from {user.get('user')}: {sanitized_data}")
    return {"ok": True}


@app.post("/api/action")
def action(data: dict, user: dict = Depends(get_current_user)):
    """SECURITY: Requires authentication for actions."""
    sanitized_data = {k: v for k, v in data.items() if k not in ["password", "secret", "token"]}
    print(f"Action from {user.get('user')}: {sanitized_data}")
    return {"ok": True}


@app.post("/api/netreaper/scan")
async def netreaper_scan(req: ScanRequest, user: dict = Depends(get_current_user)):
    if not NETREAPER_BIN.exists():
        raise HTTPException(status_code=500, detail="NetReaper binary not found")

    job_id = uuid4().hex[:8].upper()
    scan_jobs[job_id] = {
        "job_id": job_id,
        "target": req.target,
        "mode": req.mode,
        "status": "queued",
        "created_at": datetime.utcnow().isoformat(),
    }

    asyncio.create_task(run_netreaper_scan(req.target, req.mode, job_id))
    enqueue_log(f"Queued NetReaper scan job {job_id} for {req.target}")
    return {"ok": True, "job_id": job_id}


@app.get("/api/netreaper/jobs/{job_id}")
def netreaper_job(job_id: str, user: dict = Depends(get_current_user)):
    job = scan_jobs.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    return job


@app.get("/api/netreaper/devices")
def netreaper_devices(user: dict = Depends(get_current_user)):
    output_file = last_scan_meta.get("output_file")
    if not output_file:
        return {"devices": [], "note": "No scan output available"}
    file_path = Path(output_file)
    if not file_path.exists():
        return {"devices": [], "note": "Scan output file missing"}

    if file_path.suffix == ".json":
        data = json.loads(file_path.read_text(encoding="utf-8"))
        return {"devices": data}

    devices = parse_nmap_devices(file_path)
    return {"devices": devices}


@app.get("/api/netreaper/report", response_model=ReportResponse)
def netreaper_report(user: dict = Depends(get_current_user)):
    output_file = last_scan_meta.get("output_file")
    if not output_file:
        return ReportResponse(ok=False, note="No scan output available")
    file_path = Path(output_file)
    if not file_path.exists():
        return ReportResponse(ok=False, note="Scan output file missing")

    device_count = 0
    ports_open = 0
    if file_path.suffix == ".xml":
        devices = parse_nmap_devices(file_path)
        device_count = len(devices)
        ports_open = sum(len(d.get("ports", [])) for d in devices)
    elif file_path.suffix == ".json":
        data = json.loads(file_path.read_text(encoding="utf-8"))
        device_count = len(data) if isinstance(data, list) else 0

    return ReportResponse(
        ok=True,
        target=last_scan_meta.get("target"),
        output_file=output_file,
        scanned_at=last_scan_meta.get("scanned_at"),
        device_count=device_count,
        ports_open=ports_open,
    )


@app.get("/", response_class=HTMLResponse)
def get_gui():
    primary = NETREAPER_ROOT / "gui" / "index.html"
    legacy = NETREAPER_ROOT / "Gpt_reaper.html"
    if primary.exists():
        return HTMLResponse(primary.read_text(encoding="utf-8"), headers={"Cache-Control": "no-store"})
    if legacy.exists():
        return HTMLResponse(legacy.read_text(encoding="utf-8"), headers={"Cache-Control": "no-store"})
    return HTMLResponse("<h1>GUI not found</h1>", status_code=404)


@app.get("/pair/{code}")
def query_pair(code: str, user: dict = Depends(get_current_user)):
    if not re.match(r"^[A-Z0-9_-]{8,16}$", code):
        raise HTTPException(status_code=400, detail="Invalid pairing code format")

    session = paired_sessions.get(code)
    if not session:
        raise HTTPException(status_code=404, detail="Pairing code not found")
    return session


@app.websocket("/ws/{token}")
async def websocket_endpoint(websocket: WebSocket, token: Optional[str] = None):
    api_token = os.environ.get("NETREAPER_API_TOKEN")
    client_host = websocket.client.host

    await websocket.accept()

    authenticated = False
    if api_token:
        if token and secrets.compare_digest(token, api_token):
            authenticated = True
            await websocket.send_text(json.dumps({"status": "authenticated", "user": "api_token_user"}))
        else:
            await websocket.send_text(json.dumps({"error": "Authentication failed: Invalid API token"}))
            await websocket.close(code=1008)
            return
    else:
        try:
            auth_data = await websocket.receive_text()
            auth_json = json.loads(auth_data)
            jwt_token = auth_json.get("token")
            claims = verify_token(jwt_token) if jwt_token else None
            if claims:
                authenticated = True
                await websocket.send_text(json.dumps({"status": "authenticated", "user": claims.get("user")}))
            else:
                await websocket.send_text(json.dumps({"error": "Authentication failed"}))
                await websocket.close(code=1008)
                return
        except (json.JSONDecodeError, AttributeError):
            await websocket.send_text(json.dumps({"error": "Invalid authentication request"}))
            await websocket.close(code=1008)
            return

    if not authenticated:
        await websocket.close(code=1008)
        return

    if not api_token and client_host not in {"127.0.0.1", "::1", "localhost"}:
        await websocket.send_text(json.dumps({"error": "Remote websocket access disabled without NETREAPER_API_TOKEN"}))
        await websocket.close(code=1008)
        return

    try:
        workdir = os.environ.get("NETREAPER_ROOT", str(NETREAPER_ROOT))
        if not os.path.isdir(workdir):
            await websocket.send_text(json.dumps({"error": "Server misconfiguration: Working directory not found"}))
            return

        allowed_roots = {str(NETREAPER_BIN), "netreaper"}

        while True:
            data = await websocket.receive_text()
            cmd_json = json.loads(data)
            command = cmd_json.get("command", "").strip()

            if not command:
                await websocket.send_text(json.dumps({"error": "No command provided"}))
                continue

            ok, result = validate_allowlisted_command(command, allowed_roots, max_length=240, max_args=25)
            if not ok:
                await websocket.send_text(json.dumps({"error": result}))
                continue
            argv = list(result)

            sanitized_log_cmd = sanitize_command_for_display(argv)
            await websocket.send_text(json.dumps({"output": f"Executing: {sanitized_log_cmd}"}))

            creationflags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
            preexec_fn = None if os.name == "nt" else os.setsid

            process = await asyncio.create_subprocess_exec(
                *argv,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.STDOUT,
                cwd=workdir,
                creationflags=creationflags,
                preexec_fn=preexec_fn,
            )

            while True:
                line = await process.stdout.readline()
                if not line:
                    break
                output_line = sanitize_output(line.decode().rstrip())
                await websocket.send_text(json.dumps({"output": output_line}))

            return_code = await process.wait()
            await websocket.send_text(json.dumps({"output": f"Command completed with code: {return_code}"}))

    except WebSocketDisconnect:
        return
    except Exception as exc:
        await websocket.send_text(json.dumps({"error": f"Server error: {exc}"}))


@app.websocket("/ws/netreaper")
async def netreaper_logs(websocket: WebSocket):
    api_token = os.environ.get("NETREAPER_API_TOKEN")
    client_host = websocket.client.host

    await websocket.accept()

    if not api_token and client_host not in {"127.0.0.1", "::1", "localhost"}:
        await websocket.send_text(json.dumps({"error": "Remote websocket access disabled without NETREAPER_API_TOKEN"}))
        await websocket.close(code=1008)
        return

    try:
        auth_data = await websocket.receive_text()
        auth_json = json.loads(auth_data)
        jwt_token = auth_json.get("token")
        claims = verify_token(jwt_token) if jwt_token else None
        if not claims:
            await websocket.send_text(json.dumps({"error": "Authentication failed"}))
            await websocket.close(code=1008)
            return
    except (json.JSONDecodeError, AttributeError):
        await websocket.send_text(json.dumps({"error": "Invalid authentication request"}))
        await websocket.close(code=1008)
        return

    queue: asyncio.Queue[str] = asyncio.Queue()
    log_subscribers.add(queue)
    await websocket.send_text(json.dumps({"status": "subscribed"}))

    try:
        while True:
            line = await queue.get()
            await websocket.send_text(json.dumps({"output": line}))
    except WebSocketDisconnect:
        log_subscribers.discard(queue)
    except Exception:
        log_subscribers.discard(queue)
