---
name: canvas-os
description: Canvas as an app platform. Build, store, and run rich visual apps on the OpenClaw Canvas.
homepage: https://clawhub.com/skills/canvas-os
metadata:
  openclaw:
    emoji: "🖥️"
    category: ui
    requires:
      bins: ["python3"]
---

# Canvas OS

Canvas as an app platform. Build, store, and run rich visual apps on the OpenClaw Canvas.

## Philosophy

You are an OS. Canvas is the window. Apps are built locally and run on Canvas.

**Rich HTML/CSS/JS UIs** — not just text. Full interactivity, animations, live data.

## Quick Commands

| Command | What Jarvis Does |
|---------|------------------|
| "Open [app]" | Start server, navigate Canvas, inject data |
| "Build me a [type]" | Create app from template, open it |
| "Update [element]" | Inject JS to modify live |
| "Show [data] on canvas" | Quick A2UI display |
| "Close canvas" | Stop server, hide Canvas |

## How It Works

### 1. Apps are HTML/CSS/JS files
```
~/.openclaw/workspace/apps/[app-name]/
├── index.html    # The UI (self-contained recommended)
├── data.json     # Persistent state
└── manifest.json # App metadata
```

### 2. Served via localhost
```bash
cd ~/.openclaw/workspace/apps/[app-name]
python3 -m http.server [PORT] &
```

### 3. Canvas navigates to localhost
```bash
NODE="Your Node Name"  # Get from: openclaw nodes status
openclaw nodes canvas navigate --node "$NODE" "http://localhost:[PORT]/"
```

### 4. Agent injects data via JS eval
```bash
openclaw nodes canvas eval --node "$NODE" --js "app.setData({...})"
```

## Opening an App

Full sequence:
```bash
NODE="Your Node Name"
PORT=9876
APP="my-app"

# 1. Kill any existing server on the port
lsof -ti:$PORT | xargs kill -9 2>/dev/null

# 2. Start server
cd ~/.openclaw/workspace/apps/$APP
python3 -m http.server $PORT > /dev/null 2>&1 &

# 3. Wait for server
sleep 1

# 4. Navigate Canvas
openclaw nodes canvas navigate --node "$NODE" "http://localhost:$PORT/"

# 5. Inject data
openclaw nodes canvas eval --node "$NODE" --js "app.loadData({...})"
```

## Building Apps

### App API Convention

Every app should expose a `window.app` or `window.[appname]` object:

```javascript
window.app = {
  // Update values
  setValue: (key, val) => {
    document.getElementById(key).textContent = val;
  },
  
  // Bulk update
  loadData: (data) => { /* render all */ },
  
  // Notifications
  notify: (msg) => { /* show toast */ }
};
```

### Two-Way Communication

Apps send commands back via deep links:

```javascript
function sendToAgent(message) {
  window.location.href = `openclaw://agent?message=${encodeURIComponent(message)}`;
}

// Button click → agent command
document.getElementById('btn').onclick = () => {
  sendToAgent('Refresh my dashboard');
};
```

## Templates

### Dashboard
Stats cards, progress bars, lists. Self-contained HTML.
- Default port: 9876
- API: `dashboard.setRevenue()`, `dashboard.setProgress()`, `dashboard.notify()`

### Tracker
Habits/tasks with checkboxes and streaks. Self-contained HTML.
- Default port: 9877
- API: `tracker.setItems()`, `tracker.addItem()`, `tracker.toggleItem()`

## Quick Display (A2UI)

For temporary displays without a full app:

```bash
openclaw nodes canvas a2ui push --node "$NODE" --text "
📊 QUICK STATUS

Revenue: \$500
Users: 100

Done!
"
```

## Port Assignments

| App Type | Default Port |
|----------|--------------|
| Dashboard | 9876 |
| Tracker | 9877 |
| Timer | 9878 |
| Display | 9879 |
| Custom | 9880+ |

## Design System

```css
:root {
  --bg-primary: #0a0a0a;
  --bg-card: #1a1a2e;
  --accent-green: #00d4aa;
  --accent-blue: #4a9eff;
  --accent-orange: #f59e0b;
  --text-primary: #fff;
  --text-muted: #888;
  --border: #333;
}
```

## Best Practices

1. **Self-contained HTML** — Inline CSS/JS for portability
2. **Dark theme** — Match OpenClaw aesthetic
3. **Expose app API** — Let agent update via `window.app.*`
4. **Use IDs** — On elements the agent will update
5. **Live clock** — Shows the app is alive
6. **Deep links** — For two-way communication

## Requirements

- OpenClaw with Canvas support (macOS app)
- Python 3 (for http.server)
- A paired node with canvas capability
