; =============================================================================
;  NET NiNjA v1.2 — Inno Setup Installer Script
;  Produces a single self-extracting EXE with:
;    - Bundled portable JRE (no system Java required)
;    - Server fat-JAR + all dependencies
;    - Web UI assets
;    - Desktop shortcut, Start Menu entry, system tray launcher
;    - Clean uninstall support
;
;  Build: iscc scripts\windows\netninja-setup.iss
;  Prereqs: run build-full-installer.ps1 first to stage files.
; =============================================================================

#define MyAppName      "NET NiNjA"
#define MyAppVersion   "1.2.0"
#define MyAppPublisher "NET NiNjA Project"
#define MyAppURL       "https://github.com/aztr0nutzs/NET_NiNjA.v1.2"
#define MyAppExeName   "NetNiNjA.cmd"
#define MyAppIcon      "netninja.ico"

[Setup]
AppId={{E7A3F1D2-5B4C-4E8F-9A1D-3C7B2E6F8D0A}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}/issues
AppUpdatesURL={#MyAppURL}/releases
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=..\..\build\windows-installer\out
OutputBaseFilename=NetNiNjA-v{#MyAppVersion}-Setup
SetupIconFile=staging\{#MyAppIcon}
UninstallDisplayIcon={app}\{#MyAppIcon}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
DisableWelcomePage=no
WizardImageFile=compiler:wizmodernimage.bmp
WizardSmallImageFile=compiler:wizmodernsmallimage.bmp

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon";  Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: checked
Name: "startmenu";    Description: "Create a &Start Menu shortcut"; GroupDescription: "{cm:AdditionalIcons}"; Flags: checked
Name: "launchstartup"; Description: "Launch NET NiNjA on &Windows startup"; GroupDescription: "Startup:"; Flags: unchecked

[Files]
; Server JAR + dependencies
Source: "staging\lib\*"; DestDir: "{app}\lib"; Flags: ignoreversion recursesubdirs

; Bundled JRE
Source: "staging\jre\*"; DestDir: "{app}\jre"; Flags: ignoreversion recursesubdirs

; Web UI assets
Source: "staging\web-ui\*"; DestDir: "{app}\web-ui"; Flags: ignoreversion recursesubdirs

; Launcher scripts
Source: "staging\NetNiNjA.cmd"; DestDir: "{app}"; Flags: ignoreversion
Source: "staging\NetNiNjA-launcher.ps1"; DestDir: "{app}"; Flags: ignoreversion

; Icon
Source: "staging\{#MyAppIcon}"; DestDir: "{app}"; Flags: ignoreversion

; README
Source: "staging\README.txt"; DestDir: "{app}"; Flags: ignoreversion isreadme

; LICENSE (if present)
Source: "staging\LICENSE"; DestDir: "{app}"; Flags: ignoreversion skipifsourcedoesntexist

[Icons]
; Desktop shortcut — uses the hidden launcher (no console window)
Name: "{autodesktop}\{#MyAppName}"; Filename: "powershell.exe"; \
  Parameters: "-ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\NetNiNjA-launcher.ps1"""; \
  WorkingDir: "{app}"; IconFilename: "{app}\{#MyAppIcon}"; \
  Comment: "Launch NET NiNjA Network Dashboard"; Tasks: desktopicon

; Start menu shortcut
Name: "{group}\{#MyAppName}"; Filename: "powershell.exe"; \
  Parameters: "-ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\NetNiNjA-launcher.ps1"""; \
  WorkingDir: "{app}"; IconFilename: "{app}\{#MyAppIcon}"; \
  Comment: "Launch NET NiNjA Network Dashboard"; Tasks: startmenu

; Start menu — Open Dashboard (browser only)
Name: "{group}\Open Dashboard"; Filename: "http://127.0.0.1:8787/ui/ninja_mobile_new.html"; \
  IconFilename: "{app}\{#MyAppIcon}"; Tasks: startmenu

; Start menu — Uninstall
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"; Tasks: startmenu

[Registry]
; Optional: auto-start on login
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; \
  ValueType: string; ValueName: "NET_NiNjA"; \
  ValueData: "powershell.exe -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\NetNiNjA-launcher.ps1"""; \
  Flags: uninsdeletevalue; Tasks: launchstartup

[Run]
; Launch after install
Filename: "powershell.exe"; \
  Parameters: "-ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\NetNiNjA-launcher.ps1"""; \
  WorkingDir: "{app}"; \
  Description: "Launch {#MyAppName} now"; \
  Flags: nowait postinstall skipifsilent

[UninstallRun]
; Kill the server process before uninstalling
Filename: "taskkill"; Parameters: "/F /IM java.exe /FI ""WINDOWTITLE eq NET*"""; \
  Flags: runhidden skipifdoesntexist

[UninstallDelete]
; Clean up logs and generated data (optional — user can keep DB)
Type: filesandordirs; Name: "{app}\logs"

[Code]
// Show a custom welcome message
function InitializeSetup(): Boolean;
begin
  Result := True;
end;

// Stop any running instance before upgrade
procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
begin
  if CurStep = ssInstall then
  begin
    // Try to kill any existing NET NiNjA server process
    Exec('taskkill', '/F /IM java.exe /FI "WINDOWTITLE eq NET*"',
         '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
end;

// Clean up user data directory on uninstall (ask first)
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    DataDir := ExpandConstant('{localappdata}\NET_NiNjA');
    if DirExists(DataDir) then
    begin
      if MsgBox('Do you want to remove NET NiNjA user data (database, logs)?'#13#10 +
                'Location: ' + DataDir,
                mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
      begin
        DelTree(DataDir, True, True, True);
      end;
    end;
  end;
end;
