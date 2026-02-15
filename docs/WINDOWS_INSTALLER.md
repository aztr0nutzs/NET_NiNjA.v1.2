# Windows Installer (EXE)

This guide covers building and running Net Ninja on Windows. Multiple installation options are available, from one-click to full Inno Setup EXE.

---

## Option A: One-Click Install (Easiest) — No Prerequisites

The simplest way to install. Double-click a batch file and everything is handled automatically:
- Builds the server JAR (if source is present)
- Downloads a portable JRE (if Java is not installed)
- Copies everything to `%LOCALAPPDATA%\NET_NiNjA`
- Creates Desktop and Start Menu shortcuts
- Launches the dashboard in your default browser

### Install

Double-click:
```
scripts\windows\INSTALL.cmd
```

Or from PowerShell:
```powershell
.\scripts\windows\install-netninja.ps1
```

Skip the Gradle build if the fat-JAR already exists:
```powershell
.\scripts\windows\install-netninja.ps1 -SkipBuild
```

### Uninstall

Double-click:
```
scripts\windows\UNINSTALL.cmd
```

### After Install

- Double-click **NET NiNjA** on your Desktop to launch
- Dashboard opens at `http://127.0.0.1:8787/ui/ninja_mobile_new.html`
- Server runs in the background (check Task Manager for `java.exe`)

---

## Option B: Full Installer (Recommended for Distribution) — Inno Setup

Produces a single self-extracting EXE with bundled JRE. End users need **nothing pre-installed**.

### Prerequisites (build machine only)
| Requirement | Version | Note |
|---|---|---|
| Windows 10/11 | — | Build machine OS |
| JDK 21 | `JAVA_HOME` set | Used for Gradle build + jlink JRE extraction |
| Inno Setup 6 | [Download](https://jrsoftware.org/isdl.php) | Free; installs `ISCC.exe` |
| Gradle | Bundled | `gradlew.bat` in repo root |

### Fastest EXE Build (Auto-select)

This command picks the best available pipeline automatically:
- Inno Setup present -> full self-contained EXE
- Otherwise jpackage present -> lightweight EXE

```powershell
.\scripts\windows\build-auto-exe-installer.ps1 -AppVersion "1.2.0"
```

### Full Inno Setup Build

From the repo root:

```powershell
.\scripts\windows\build-full-installer.ps1
```

With custom version:
```powershell
.\scripts\windows\build-full-installer.ps1 -AppVersion "1.2.1"
```

Skip Gradle build if fat-JAR already exists:
```powershell
.\scripts\windows\build-full-installer.ps1 -SkipBuild
```

### What the Build Script Does (Automated)
1. Builds `server-all.jar` via `gradlew :server:shadowJar`
2. Creates a minimal JRE (~45 MB) via `jlink` with only required modules
3. Converts `new_ninjacon.png` → multi-resolution `netninja.ico`
4. Stages all files (lib/, jre/, web-ui/, launchers)
5. Compiles the Inno Setup script into `NetNiNjA-v1.2.0-Setup.exe`

### Output

```
build\windows-installer\out\NetNiNjA-v1.2.0-Setup.exe
```

---

## Option B: Lightweight Installer — jpackage

Uses JDK's built-in `jpackage`. Smaller output but **requires JDK 17+ pre-installed** on the target machine.

### Prerequisites
- Windows 10/11
- JDK 17+ with `jpackage` available on `PATH`

### Build steps
From the repo root:

```powershell
.\scripts\windows\build-installer.ps1 -AppVersion "1.2.0"
```

### Output
```
build\windows-installer\out
```

---

## Install / Run (Either Option)

1. Run the generated `.exe` and complete the install wizard.
2. Launch **NET NiNjA** from the **Desktop shortcut** or **Start Menu**.
3. The server starts automatically and your default browser opens to:
   ```
   http://127.0.0.1:8787/ui/ninja_mobile_new.html
   ```

## What Gets Installed

| Component | Location |
|-----------|----------|
| Server + JRE | `C:\Users\<you>\AppData\Local\Programs\NET NiNjA\` |
| Web UI assets | `...\NET NiNjA\web-ui\` |
| Database | `%LOCALAPPDATA%\NET_NiNjA\netninja.db` |
| Server log | `%LOCALAPPDATA%\NET_NiNjA\server.log` |
| Desktop shortcut | Desktop |
| Start Menu | Start Menu → NET NiNjA |

## Configuration

Override via environment variables:

| Variable | Default | Description |
|---|---|---|
| `NET_NINJA_HOST` | `127.0.0.1` | Bind address |
| `NET_NINJA_PORT` | `8787` | HTTP port |
| `NET_NINJA_DB` | `%LOCALAPPDATA%\NET_NiNjA\netninja.db` | SQLite path |
| `NET_NINJA_TOKEN` | (auto-generated) | API auth token |

## Uninstall

Use **Add or Remove Programs** in Windows Settings, or run the uninstaller from the Start Menu. You will be asked if you want to remove user data (database, logs).

## Files Created by the Build

```
scripts/windows/
├── build-full-installer.ps1   # Master build script (Option A)
├── build-installer.ps1        # Legacy jpackage build (Option B)
├── netninja-setup.iss         # Inno Setup script
├── convert-icon.ps1           # PNG → ICO converter
├── NetNiNjA.cmd               # Console launcher
├── NetNiNjA-launcher.ps1      # Hidden-window launcher (shortcut target)
├── staging/                   # Build artifacts (gitignored)
└── .gitignore
```
