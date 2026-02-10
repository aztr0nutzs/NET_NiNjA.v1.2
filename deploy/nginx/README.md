# Nginx Reverse Proxy (Optional)

This folder is used by `docker-compose.proxy.yml` to run an Nginx reverse proxy in front of `netninja-server`.

## Certificates

Place your TLS cert and key here:

- `deploy/nginx/certs/fullchain.pem`
- `deploy/nginx/certs/privkey.pem`

The repo does not generate or ship certificates.

## Usage

Run the app + proxy together:

```bash
docker compose -f docker-compose.yml -f docker-compose.proxy.yml up --build
```

