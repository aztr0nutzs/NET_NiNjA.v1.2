# Windows Web Installer (EXE)

This guide covers the **web-only** Windows installer build. It packages the Ktor server plus the `web-ui` assets into a single interactive `.exe` installer using `jpackage`.

## What the installer does
- Installs a desktop entry that launches the local web server.
- Opens the dashboard in your default browser once the server is ready.
- Runs as a per-user install with a simple directory chooser.

## Prerequisites
- Windows 10/11
- JDK 17+ with `jpackage` available on `PATH`

## Build steps
From the repo root:

```powershell
.\scripts\windows\build-installer.ps1 -AppVersion "1.2.0"
```

### Output
The installer will be created under:

```
build\windows-installer\out
```

## Install/run
1. Run the generated `.exe` and complete the install prompts.
2. Launch **NET_NiNjA** from the Start Menu or Desktop shortcut.
3. The default browser will open to:
   ```
   http://127.0.0.1:8787/ui/ninja_mobile_new.html
   ```

## Notes
- The installer bundles the `web-ui` directory into the app image, so no additional setup is required.
- Configuration can still be overridden using environment variables:
  - `NET_NINJA_HOST`
  - `NET_NINJA_PORT`
  - `NET_NINJA_DB`
