# Manual Test Plan: T-Mobile G5AR Gateway Control

## Preconditions
1. Use an Android device connected to the T-Mobile G5AR gateway Wi-Fi/LAN.
2. Confirm the gateway web UI opens at `http://192.168.12.1` in a browser.
3. Confirm the admin credentials from the gateway label (default user is usually `admin`).

## Verification Steps
1. Launch NET_NiNjA and open the **Gateway** tab.
2. Confirm the connection pill changes to **Reachable**.
3. Enter username/password and tap **Login**.
4. Confirm gateway information fields populate (firmware, UI version, serial, uptime).
5. Confirm client list shows connected devices with IP/MAC/signal when available.
6. Confirm cellular fields show RSRP/RSRQ/SINR/Band if API telemetry is exposed.
7. Confirm SIM fields show non-sensitive summary (ICCID/IMEI if exposed).
8. If Wi-Fi config controls are visible:
   - Update 2.4GHz SSID/password and toggle enable state.
   - Tap **Save Wi-Fi**.
   - Re-open gateway web UI and verify applied settings.
9. If reboot button is visible:
   - Tap **Reboot Gateway** and accept confirmation.
   - Confirm gateway disconnects and then comes back online.
10. Return to app and tap **Refresh**; verify data updates after gateway is back.

## Safety Checks
- Ensure app never displays or logs the typed password after login.
- Ensure controls are hidden when capability checks fail (for example, Wi-Fi controls not shown if API endpoint fails).
- Ensure clear error messages appear for unreachable gateway or failed login.
