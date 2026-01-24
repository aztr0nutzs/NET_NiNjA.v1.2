from __future__ import annotations

import ipaddress
import json
import re
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import List, Optional

from capabilities import CapabilityMatrix
from providers.base import BaseProvider, HostRecord, InterfaceRecord, NeighborRecord, RouteRecord, SocketRecord, WifiAPRecord


class LinuxProvider(BaseProvider):
    def __init__(self, capabilities: CapabilityMatrix):
        self.capabilities = capabilities

    def _run_json(self, cmd: List[str]) -> object:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=8)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or "Command failed")
        return json.loads(result.stdout or "[]")

    def _run_text(self, cmd: List[str], timeout: int = 8) -> str:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or "Command failed")
        return result.stdout or ""

    def get_interfaces(self) -> List[InterfaceRecord]:
        data = self._run_json(["ip", "-j", "addr", "show"])
        records: List[InterfaceRecord] = []
        for it in data or []:
            name = it.get("ifname") or ""
            state = it.get("operstate") or ""
            mac = it.get("address") or ""
            ipv4: List[str] = []
            ipv6: List[str] = []
            for addr in it.get("addr_info", []) or []:
                fam = addr.get("family")
                local = addr.get("local")
                if not local:
                    continue
                if fam == "inet":
                    ipv4.append(local)
                elif fam == "inet6":
                    ipv6.append(local)
            records.append(InterfaceRecord(name=name, state=state, mac=mac, ipv4=ipv4, ipv6=ipv6))
        return records

    def get_routes(self) -> List[RouteRecord]:
        data = self._run_json(["ip", "-j", "route", "show"])
        records: List[RouteRecord] = []
        for route in data or []:
            destination = route.get("dst") or "default"
            gateway = route.get("gateway") or ""
            interface = route.get("dev") or ""
            metric = str(route.get("metric", ""))
            records.append(RouteRecord(destination=destination, gateway=gateway, interface=interface, metric=metric))
        return records

    def get_sockets(self) -> List[SocketRecord]:
        output = self._run_text(["ss", "-H", "-t", "-u", "-n", "-a", "-p"])
        records: List[SocketRecord] = []
        for line in output.splitlines():
            parts = line.split()
            if len(parts) < 5:
                continue
            proto = parts[0]
            state = parts[1]
            local = parts[4] if len(parts) >= 6 else parts[3]
            remote = parts[5] if len(parts) >= 6 else parts[4]
            local_addr, local_port = _split_host_port(local)
            remote_addr, remote_port = _split_host_port(remote)
            pid = ""
            process = ""
            m = re.search(r"pid=(\d+)", line)
            if m:
                pid = m.group(1)
            m = re.search(r'\"([^\"]+)\"', line)
            if m:
                process = m.group(1)
            records.append(
                SocketRecord(
                    proto=proto,
                    local_address=local_addr,
                    local_port=local_port,
                    remote_address=remote_addr,
                    remote_port=remote_port,
                    state=state,
                    pid=pid,
                    process=process,
                )
            )
        return records

    def get_neighbors(self) -> List[NeighborRecord]:
        output = self._run_text(["ip", "neigh"])
        records: List[NeighborRecord] = []
        for line in output.splitlines():
            parts = line.split()
            if not parts:
                continue
            ip_addr = parts[0]
            mac = ""
            state = parts[-1]
            iface = ""
            if "lladdr" in parts:
                try:
                    mac = parts[parts.index("lladdr") + 1]
                except Exception:
                    mac = ""
            if "dev" in parts:
                try:
                    iface = parts[parts.index("dev") + 1]
                except Exception:
                    iface = ""
            records.append(NeighborRecord(ip=ip_addr, mac=mac, state=state, interface=iface))
        return records

    def scan_wifi(self) -> List[WifiAPRecord]:
        cmd = [
            "nmcli",
            "-t",
            "--separator",
            "|",
            "-f",
            "SSID,BSSID,CHAN,SIGNAL,SECURITY",
            "dev",
            "wifi",
            "list",
            "--rescan",
            "yes",
        ]
        output = self._run_text(cmd, timeout=12)
        records: List[WifiAPRecord] = []
        for raw in output.splitlines():
            if not raw.strip():
                continue
            parts = raw.split("|", 4)
            if len(parts) != 5:
                continue
            ssid, bssid, chan, signal, security = [p.strip() for p in parts]
            records.append(
                WifiAPRecord(
                    ssid=ssid,
                    bssid=bssid,
                    channel=chan,
                    signal=signal,
                    security=security,
                )
            )
        return records

    def discover_hosts_quick(self) -> List[HostRecord]:
        neighbors = self.get_neighbors()
        records = [HostRecord(ip=n.ip, mac=n.mac, state=n.state, source="neighbor") for n in neighbors]
        return records

    def discover_hosts_full(self, target: Optional[str] = None) -> List[HostRecord]:
        target_net = target or _infer_local_subnet(self.get_routes())
        if not target_net:
            return self.discover_hosts_quick()
        if self.capabilities.tools.get("nmap", False):
            output = self._run_text(["nmap", "-sn", target_net], timeout=60)
            return _parse_nmap_hosts(output)
        return _ping_sweep(target_net)


def _split_host_port(value: str) -> tuple[str, str]:
    if not value:
        return "", ""
    if value.startswith("[") and "]" in value:
        host, _, port = value.rpartition("]:")
        host = host.lstrip("[")
        return host, port
    if ":" in value:
        host, port = value.rsplit(":", 1)
        return host, port
    return value, ""


def _infer_local_subnet(routes: List[RouteRecord]) -> Optional[str]:
    for route in routes:
        if route.destination and route.destination != "default" and "/" in route.destination:
            return route.destination
    return None


def _parse_nmap_hosts(output: str) -> List[HostRecord]:
    records: List[HostRecord] = []
    current_ip = ""
    for line in output.splitlines():
        if line.startswith("Nmap scan report for"):
            current_ip = line.split()[-1]
            records.append(HostRecord(ip=current_ip, state="up", source="nmap"))
        elif "MAC Address:" in line and current_ip:
            parts = line.split()
            if len(parts) >= 3:
                mac = parts[2]
                records[-1].mac = mac
    return records


def _ping_sweep(target: str) -> List[HostRecord]:
    try:
        network = ipaddress.ip_network(target, strict=False)
    except Exception:
        return []
    hosts = list(network.hosts())
    max_hosts = 256
    if len(hosts) > max_hosts:
        hosts = hosts[:max_hosts]

    records: List[HostRecord] = []
    with ThreadPoolExecutor(max_workers=32) as executor:
        futures = {executor.submit(_ping_host, str(ip)): str(ip) for ip in hosts}
        for future in as_completed(futures):
            ip = futures[future]
            if future.result():
                records.append(HostRecord(ip=ip, state="up", source="ping"))
    return records


def _ping_host(ip_addr: str) -> bool:
    try:
        result = subprocess.run(
            ["ping", "-c", "1", "-W", "1", ip_addr],
            capture_output=True,
            text=True,
            timeout=2,
        )
        return result.returncode == 0
    except Exception:
        return False
