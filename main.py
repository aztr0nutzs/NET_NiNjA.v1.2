import asyncio
import json
import jwt
from uuid import uuid4
import subprocess
import shlex
from datetime import datetime, timedelta
from fastapi import FastAPI, HTTPException, WebSocket, Depends, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from pathlib import Path
import os
import re
from typing import Optional
from pydantic import BaseModel, validator
import secrets
from security_utils import sanitize_command_for_display, validate_allowlisted_command

app = FastAPI(title="NetReaper Remote Server")

BASE_DIR = Path(__file__).resolve().parent

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
if (BASE_DIR / "gui").exists():
    from fastapi.staticfiles import StaticFiles
    app.mount("/static", StaticFiles(directory=BASE_DIR / "gui"), name="static")
else:
    print("[warning] Static GUI directory not found; skipping /static mount.")

# SECURITY: SECRET_KEY must be defined; do not fall back to an insecure default
SECRET_KEY = os.environ.get("NETREAPER_SECRET")
if not SECRET_KEY:
    raise RuntimeError("NETREAPER_SECRET environment variable must be set for secure token signing. Generate with: python -c 'import secrets; print(secrets.token_urlsafe(32))'")

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
    
    @validator('password')
    def password_not_empty(cls, v):
        if not v or not v.strip():
            raise ValueError('Password cannot be empty')
        return v

class PairRequest(BaseModel):
    deviceId: str
    role: str
    
    @validator('role')
    def role_must_be_valid(cls, v):
        if v not in {"remote", "gui"}:
            raise ValueError('Role must be "remote" or "gui"')
        return v
    
    @validator('deviceId')
    def device_id_not_empty(cls, v):
        if not v or not v.strip():
            raise ValueError('Device ID cannot be empty')
        return v

class CommandRequest(BaseModel):
    command: str
    
    @validator('command')
    def command_not_empty(cls, v):
        if not v or not v.strip():
            raise ValueError('Command cannot be empty')
        return v


def check_rate_limit(client_id: str) -> bool:
    """SECURITY: Check if client has exceeded rate limit for authentication."""
    now = datetime.utcnow()
    if client_id not in auth_attempts:
        auth_attempts[client_id] = []
    
    # Clean old attempts
    auth_attempts[client_id] = [
        attempt for attempt in auth_attempts[client_id]
        if (now - attempt).total_seconds() < AUTH_WINDOW_SECONDS
    ]
    
    # Check limit
    if len(auth_attempts[client_id]) >= MAX_AUTH_ATTEMPTS:
        return False
    
    auth_attempts[client_id].append(now)
    return True

def create_token(data: dict) -> str:
    """Create a signed JWT with an expiry.  The passed dictionary is copied
    and an `exp` claim is added if not present.  Tokens expire after
    one hour by default."""
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
        # Additional validation
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
    # Redact passwords
    line = re.sub(r'(password|passwd|pwd)[=:\s]+\S+', r'\1=***REDACTED***', line, flags=re.IGNORECASE)
    # Redact API keys and tokens
    line = re.sub(r'(api[_-]?key|token|secret)[=:\s]+\S+', r'\1=***REDACTED***', line, flags=re.IGNORECASE)
    # Redact authorization headers
    line = re.sub(r'(Authorization|Bearer)[:\s]+\S+', r'\1: ***REDACTED***', line, flags=re.IGNORECASE)
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

@app.post("/auth")
def authenticate(auth_req: AuthRequest):
    """
    Authenticate a user and return a JWT.  The password must be provided via
    the NETREAPER_PASSWORD environment variable.  Fallback default passwords
    are intentionally not supported to avoid insecure deployments.
    
    SECURITY: Implements rate limiting and secure password comparison.
    """
    # SECURITY: Rate limiting
    client_id = "global"  # In production, use request.client.host
    if not check_rate_limit(client_id):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many authentication attempts. Please try again later."
        )
    
    secret_pw = os.environ.get("NETREAPER_PASSWORD")
    if not secret_pw:
        raise HTTPException(status_code=500, detail="Server password is not configured")
    
    # SECURITY: Use constant-time comparison to prevent timing attacks
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
        "created_by": user.get("user", "unknown")
    }
    return {"pairCode": pair_code, "status": "paired"}

@app.post("/api/telemetry")
def telemetry(data: dict, user: dict = Depends(get_current_user)):
    """SECURITY: Requires authentication for telemetry."""
    # SECURITY: Sanitize telemetry data before logging
    sanitized_data = {k: v for k, v in data.items() if k not in ["password", "secret", "token"]}
    print(f"Telemetry from {user.get('user')}: {sanitized_data}")
    return {"ok": True}

@app.post("/api/action")
def action(data: dict, user: dict = Depends(get_current_user)):
    """SECURITY: Requires authentication for actions."""
    # SECURITY: Sanitize action data
    sanitized_data = {k: v for k, v in data.items() if k not in ["password", "secret", "token"]}
    print(f"Action from {user.get('user')}: {sanitized_data}")
    return {"ok": True}

@app.get("/", response_class=HTMLResponse)
def get_gui():
    """Serve the primary GUI. Prefer the updated gui/index.html; fall back to the legacy GPT skin."""
    primary = BASE_DIR / "gui" / "index.html"
    legacy = BASE_DIR / "Gpt_reaper.html"
    if primary.exists():
        return HTMLResponse(primary.read_text(encoding="utf-8"), headers={"Cache-Control": "no-store"})
    if legacy.exists():
        return HTMLResponse(legacy.read_text(encoding="utf-8"), headers={"Cache-Control": "no-store"})
    return HTMLResponse("<h1>GUI not found</h1>", status_code=404)

@app.get("/pair/{code}")
def query_pair(code: str, user: dict = Depends(get_current_user)):
    """SECURITY: Requires authentication to query pairing codes."""
    # SECURITY: Validate code format
    if not re.match(r'^[A-Z0-9_-]{8,16}$', code):
        raise HTTPException(status_code=400, detail="Invalid pairing code format")
    
    session = paired_sessions.get(code)
    if not session:
        raise HTTPException(status_code=404, detail="Pairing code not found")
    return session

@app.websocket("/ws/{token}")
async def websocket_endpoint(websocket: WebSocket, token: Optional[str] = None):
    """Websocket endpoint for executing permitted commands.
    
    SECURITY ENHANCEMENTS:
    - Requires API token or JWT for authentication.
    - If no API token is configured on the server, it runs in local-only mode.
    - Validates command structure and whitelists allowed commands for every command.
    - Prevents command injection via shell metacharacter filtering.
    - Sanitizes output to prevent credential leakage.
    - Implements command execution in a restricted working directory.
    - Uses shell=False equivalent for subprocess execution.
    """
    api_token = os.environ.get("NETREAPER_API_TOKEN")
    client_host = websocket.client.host
    
    await websocket.accept()

    # --- Authentication Check ---
    authenticated = False
    if api_token:
        # Mode 1: API Token Authentication
        if token and secrets.compare_digest(token, api_token):
            authenticated = True
            await websocket.send_text(json.dumps({"status": "authenticated", "user": "api_token_user"}))
        else:
            await websocket.send_text(json.dumps({"error": "Authentication failed: Invalid API token"}))
            await websocket.close(code=1008)
            return
    else:
        # Mode 2: JWT-based Authentication (for local-only mode)
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
        # This should not be reachable, but as a safeguard:
        await websocket.close(code=1008)
        return

    # Enforce local-only mode if no API token is configured
    if not api_token and client_host not in {"127.0.0.1", "::1", "localhost"}:
        await websocket.send_text(json.dumps({"error": "Remote websocket access disabled without NETREAPER_API_TOKEN"}))
        await websocket.close(code=1008)
        return

    # --- Secure Command Execution Loop ---
    try:
        workdir = os.environ.get("NETREAPER_ROOT", str(Path(__file__).resolve().parent))
        if not os.path.isdir(workdir):
            await websocket.send_text(json.dumps({"error": "Server misconfiguration: Working directory not found"}))
            return

        allowed_roots = {"netreaper", os.path.join(workdir, "netreaper")}

        while True:
            data = await websocket.receive_text()
            cmd_json = json.loads(data)
            command = cmd_json.get("command", "").strip()

            if not command:
                await websocket.send_text(json.dumps({"error": "No command provided"}))
                continue

            try:
                ok, result = validate_allowlisted_command(command, allowed_roots, max_length=240, max_args=25)
                if not ok:
                    await websocket.send_text(json.dumps({"error": result}))
                    continue
                argv = list(result)

                sanitized_log_cmd = sanitize_command_for_display(argv)
                print(f"[{datetime.utcnow().isoformat()}] Audit Log: Executing command from client {client_host}: {sanitized_log_cmd}")
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

            except json.JSONDecodeError:
                await websocket.send_text(json.dumps({"error": "Invalid JSON received"}))
            except Exception as e:
                await websocket.send_text(json.dumps({"error": f"Execution error: {str(e)}"}))

    except Exception as e:
        print(f"WebSocket error from {client_host}: {e}")
    finally:
        print(f"WebSocket connection closed for {client_host}")
        if not websocket.client_state == "DISCONNECTED":
            await websocket.close()


if __name__ == "__main__":
    import uvicorn
    
    # SECURITY: Validate environment configuration
    if not os.environ.get("NETREAPER_PASSWORD"):
        print("ERROR: NETREAPER_PASSWORD environment variable must be set for JWT-based auth.")
        print("Generate a secure password with: python -c 'import secrets; print(secrets.token_urlsafe(24))'")
        exit(1)
    
    port = int(os.environ.get("NETREAPER_PORT", "8000"))
    
    # API Token check for local-only vs. public mode
    api_token = os.environ.get("NETREAPER_API_TOKEN")
    host = "0.0.0.0" if api_token else "127.0.0.1"

    if not api_token:
        print("WARNING: NETREAPER_API_TOKEN is not set. Server will only be accessible from localhost.")

    # SSL configuration
    ssl_keyfile = os.environ.get("NETREAPER_SSL_KEY")
    ssl_certfile = os.environ.get("NETREAPER_SSL_CERT")
    
    if ssl_keyfile and ssl_certfile:
        print(f"Starting NetReaper server with HTTPS on {host}:{port}")
        uvicorn.run(
            app, 
            host=host, 
            port=port,
            ssl_keyfile=ssl_keyfile,
            ssl_certfile=ssl_certfile
        )
    else:
        print(f"WARNING: Starting NetReaper server with HTTP on {host}:{port}")
        if host == "0.0.0.0":
            print("WARNING: Server is accessible on all network interfaces without SSL.")
            print("For production use, set NETREAPER_SSL_KEY and NETREAPER_SSL_CERT environment variables.")
        uvicorn.run(app, host=host, port=port)
