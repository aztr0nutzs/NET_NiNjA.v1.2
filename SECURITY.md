# Security Policy

Responsible disclosure:
- Report security issues by opening an email to security@<replace-with-your-domain> or create a private issue including reproducible steps and impact.
- Do not post public details until patched or coordinated disclosure is agreed.

Supported scope:
- server binary and REST API
- Android local server packaging and interactions
- web UI and any included third-party libs

Response:
- We will acknowledge within 72 hours and provide a mitigation timeline.

Hardening guidance for contributors:
- Prefer secure defaults (CORS restricted, CSP headers, do not allow anyHost() in production).
- Validate inputs server-side. Use parameterized queries for DB interactions.
- Hash passwords with bcrypt/Argon2. Use secure storage for tokens.
