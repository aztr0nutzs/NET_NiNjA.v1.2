from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional


@dataclass
class InterfaceRecord:
    name: str
    state: str
    mac: str
    ipv4: List[str] = field(default_factory=list)
    ipv6: List[str] = field(default_factory=list)
    speed: str = ""


@dataclass
class RouteRecord:
    destination: str
    gateway: str
    interface: str
    metric: str = ""


@dataclass
class SocketRecord:
    proto: str
    local_address: str
    local_port: str
    remote_address: str
    remote_port: str
    state: str
    pid: str = ""
    process: str = ""


@dataclass
class NeighborRecord:
    ip: str
    mac: str
    state: str
    interface: str = ""


@dataclass
class WifiAPRecord:
    ssid: str
    bssid: str
    channel: str
    signal: str
    security: str


@dataclass
class HostRecord:
    ip: str
    mac: str = ""
    state: str = ""
    source: str = ""


class ProviderError(RuntimeError):
    pass


class BaseProvider:
    def get_interfaces(self) -> List[InterfaceRecord]:
        raise NotImplementedError

    def get_routes(self) -> List[RouteRecord]:
        raise NotImplementedError

    def get_sockets(self) -> List[SocketRecord]:
        raise NotImplementedError

    def get_neighbors(self) -> List[NeighborRecord]:
        raise NotImplementedError

    def scan_wifi(self) -> List[WifiAPRecord]:
        raise NotImplementedError

    def discover_hosts_quick(self) -> List[HostRecord]:
        raise NotImplementedError

    def discover_hosts_full(self, target: Optional[str] = None) -> List[HostRecord]:
        raise NotImplementedError
