# NET_NiNjA.v1.2 — Quick Start (proposed improvements)

Overview
- Local network dashboard + Android local-server packaged UI.
- Server: Ktor (Kotlin). Web UI: static HTML/JS in /web-ui.

Quick dev run
- With Java/Gradle:
  ./gradlew :server:run
  Open http://localhost:8787/ui/ninja_dash.html

- With Docker:
  docker-compose up --build
  Open http://localhost:8787/ui/ninja_dash.html

Development notes
- API base defaults to http://127.0.0.1:8787
- DB default: SQLite file netninja.db in server working directory

Contributing
- See CONTRIBUTING.md for details.

Security
- See SECURITY.md.
