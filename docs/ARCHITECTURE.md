# Architecture

- **Sighting**: raw observation from a provider (IP, hostname, optional MAC, services).
- **Device**: merged entity representing a physical device across IP changes.
- **Event**: immutable audit record.

Flow:
UI -> ViewModel -> Engine -> Providers -> sightings -> merge -> repo -> UI
