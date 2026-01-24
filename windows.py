from __future__ import annotations

import ipaddress
import json
import re
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Dict, List, Optional

from capabilities import CapabilityMatrix
from providers.base import BaseProvider, HostRecord, InterfaceRecord, NeighborRecord, RouteRecord, SocketRecord, WifiAPRecord


class WindowsProvider(BaseProvider):
    def __init__(self, capabilities: CapabilityMatrix):
        self.capabilities = capabilities

    def _run_powershell_json(self, script: str, timeout: int = 8) -> object:
        result = subprocess.run(
            ["powershell", "-NoProfile", "-Command", script],
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or "PowerShell command failed")
        return json.loads(result.stdout or "[]")

    def _run_text(self, cmd: List[str], timeout: int = 8) -> str:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip() or "Command failed")
        return result.stdout or ""

    def get_interfaces(self) -> List[InterfaceRecord]:
        try:
            adapters = self._run_powershell_json(
                "Get-NetAdapter | Select-Object Name,Status,MacAddress,LinkSpeed | ConvertTo-Json -Depth 3"
            )
            ips = self._run_powershell_json(
                "Get-NetIPAddress | Select-Object InterfaceAlias,IPAddress,AddressFamily | ConvertTo-Json -Depth 3"
            )
            adapter_list = adapters if isinstance(adapters, list) else [adapters]
            ip_list = ips if isinstance(ips, list) else [ips]
            ip_map: Dict[str, Dict[str, List[str]]] = {}
            for entry in ip_list:
                alias = entry.get("InterfaceAlias") or ""
                family = entry.get("AddressFamily") or ""
                ip_addr = entry.get("IPAddress") or ""
                if not alias or not ip_addr:
                    continue
                bucket = ip_map.setdefault(alias, {"ipv4": [], "ipv6": []})
                if family == "IPv4":
                    bucket["ipv4"].append(ip_addr)
                elif family == "IPv6":
                    bucket["ipv6"].append(ip_addr)

            records: List[InterfaceRecord] = []
            for entry in adapter_list:
                name = entry.get("Name") or ""
                state = entry.get("Status") or ""
                mac = entry.get("MacAddress") or ""
                speed = str(entry.get("LinkSpeed") or "")
                ip_info = ip_map.get(name, {"ipv4": [], "ipv6": []})
                records.append(
                    InterfaceRecord(
                        name=name,
                        state=state,
                        mac=mac,
                        ipv4=ip_info.get("ipv4", []),
                        ipv6=ip_info.get("ipv6", []),
                        speed=speed,
                    )
                )
            return records
        except Exception:
            return _fallback_interfaces_ipconfig()

    def get_routes(self) -> List[RouteRecord]:
        try:
            routes = self._run_powershell_json(
                "Get-NetRoute | Select-Object DestinationPrefix,NextHop,InterfaceAlias,RouteMetric | ConvertTo-Json -Depth 3"
            )
            route_list = routes if isinstance(routes, list) else [routes]
            records: List[RouteRecord] = []
            for entry in route_list:
                records.append(
                    RouteRecord(
                        destination=str(entry.get("DestinationPrefix") or ""),
                        gateway=str(entry.get("NextHop") or ""),
                        interface=str(entry.get("InterfaceAlias") or ""),
                        metric=str(entry.get("RouteMetric") or ""),
                    )
                )
            return records
        except Exception:
            return _fallback_routes_route_print()

    def get_sockets(self) -> List[SocketRecord]:
        records: List[SocketRecord] = []
        try:
            tcp_entries = self._run_powershell_json(
                "Get-NetTCPConnection | Select-Object LocalAddress,LocalPort,RemoteAddress,RemotePort,State,OwningProcess | ConvertTo-Json -Depth 3",
                timeout=10,
            )
            tcp_list = tcp_entries if isinstance(tcp_entries, list) else [tcp_entries]
            for entry in tcp_list:
                records.append(
                    SocketRecord(
                        proto="tcp",
                        local_address=str(entry.get("LocalAddress") or ""),
                        local_port=str(entry.get("LocalPort") or ""),
                        remote_address=str(entry.get("RemoteAddress") or ""),
                        remote_port=str(entry.get("RemotePort") or ""),
                        state=str(entry.get("State") or ""),
                        pid=str(entry.get("OwningProcess") or ""),
                    )
                )
            udp_entries = self._run_powershell_json(
                "Get-NetUDPEndpoint | Select-Object LocalAddress,LocalPort,OwningProcess | ConvertTo-Json -Depth 3",
                timeout=10,
            )
            udp_list = udp_entries if isinstance(udp_entries, list) else [udp_entries]
            for entry in udp_list:
                records.append(
                    SocketRecord(
                        proto="udp",
                        local_address=str(entry.get("LocalAddress") or ""),
                        local_port=str(entry.get("LocalPort") or ""),
                        remote_address="",
                        remote_port="",
                        state="",
                        pid=str(entry.get("OwningProcess") or ""),
                    )
                )
            return records
        except Exception:
            return _fallback_sockets_netstat()

    def get_neighbors(self) -> List[NeighborRecord]:
        try:
            neighbors = self._run_powershell_json(
                "Get-NetNeighbor | Select-Object IPAddress,LinkLayerAddress,State,InterfaceAlias | ConvertTo-Json -Depth 3"
            )
            entries = neighbors if isinstance(neighbors, list) else [neighbors]
            records: List[NeighborRecord] = []
            for entry in entries:
                records.append(
                    NeighborRecord(
                        ip=str(entry.get("IPAddress") or ""),
                        mac=str(entry.get("LinkLayerAddress") or ""),
                        state=str(entry.get("State") or ""),
                        interface=str(entry.get("InterfaceAlias") or ""),
                    )
                )
            return records
        except Exception:
            return _fallback_neighbors_arp()

    def scan_wifi(self) -> List[WifiAPRecord]:
        output = self._run_text(["netsh", "wlan", "show", "networks", "mode=bssid"], timeout=12)
        return _parse_netsh_wifi(output)

    def discover_hosts_quick(self) -> List[HostRecord]:
        neighbors = self.get_neighbors()
        records = [HostRecord(ip=n.ip, mac=n.mac, state=n.state, source="neighbor") for n in neighbors if n.ip]
        return records

    def discover_hosts_full(self, target: Optional[str] = None) -> List[HostRecord]:
        target_net = target or _infer_windows_subnet()
        if not target_net:
            return self.discover_hosts_quick()
        if self.capabilities.tools.get("nmap", False):
            output = self._run_text(["nmap", "-sn", target_net], timeout=60)
            return _parse_nmap_hosts(output)
        return _ping_sweep(target_net)


def _fallback_interfaces_ipconfig() -> List[InterfaceRecord]:
    records: List[InterfaceRecord] = []
    try:
        result = subprocess.run(["ipconfig", "/all"], capture_output=True, text=True, timeout=6)
    except Exception:
        return records
    if result.returncode != 0:
        return records
    current: Optional[InterfaceRecord] = None
    for line in result.stdout.splitlines():
        if "adapter" in line.lower():
            if current:
                records.append(current)
            name = line.split("adapter", 1)[-1].strip(" :")
            current = InterfaceRecord(name=name, state="", mac="", ipv4=[], ipv6=[])
        if current is None:
            continue
        if "Physical Address" in line:
            parts = line.split(":")
            if len(parts) > 1:
                current.mac = parts[1].strip()
        if "IPv4 Address" in line or "IPv4" in line and "Address" in line:
            parts = line.split(":")
            if len(parts) > 1:
                ip = parts[1].replace("(Preferred)", "").strip()
                if ip:
                    current.ipv4.append(ip)
        if "IPv6 Address" in line:
            parts = line.split(":")
            if len(parts) > 1:
                ip = parts[1].replace("(Preferred)", "").strip()
                if ip:
                    current.ipv6.append(ip)
    if current:
        records.append(current)
    return records


def _fallback_routes_route_print() -> List[RouteRecord]:
    records: List[RouteRecord] = []
    try:
        output = subprocess.run(["route", "print"], capture_output=True, text=True, timeout=6)
    except Exception:
        return records
    if output.returncode != 0:
        return records
    capture = False
    for line in output.stdout.splitlines():
        if "IPv4 Route Table" in line:
            capture = True
        if capture and re.match(r"^\s*Network Destination", line):
            continue
        if capture and re.match(r"^\s*0\.0\.0\.0", line):
            parts = line.split()
            if len(parts) >= 5:
                records.append(
                    RouteRecord(
                        destination=parts[0],
                        gateway=parts[2],
                        interface=parts[3],
                        metric=parts[4],
                    )
                )
        if capture and line.strip().startswith("==="):
            capture = False
    return records


def _fallback_sockets_netstat() -> List[SocketRecord]:
    records: List[SocketRecord] = []
    try:
        result = subprocess.run(["netstat", "-ano"], capture_output=True, text=True, timeout=8)
    except Exception:
        return records
    if result.returncode != 0:
        return records
    for line in result.stdout.splitlines():
        if not line.strip() or line.strip().startswith("Proto"):
            continue
        parts = line.split()
        if len(parts) < 4:
            continue
        proto = parts[0].lower()
        local = parts[1]
        remote = parts[2]
        state = parts[3] if proto == "tcp" and len(parts) > 3 else ""
        pid = parts[-1] if parts else ""
        local_addr, local_port = _split_host_port(local)
        remote_addr, remote_port = _split_host_port(remote)
        records.append(
            SocketRecord(
                proto=proto,
                local_address=local_addr,
                local_port=local_port,
                remote_address=remote_addr,
                remote_port=remote_port,
                state=state,
                pid=pid,
            )
        )
    return records


def _fallback_neighbors_arp() -> List[NeighborRecord]:
    records: List[NeighborRecord] = []
    try:
        result = subprocess.run(["arp", "-a"], capture_output=True, text=True, timeout=6)
    except Exception:
        return records
    if result.returncode != 0:
        return records
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 3 and re.match(r"\d+\.\d+\.\d+\.\d+", parts[0]):
            records.append(NeighborRecord(ip=parts[0], mac=parts[1], state=parts[2], interface=""))
    return records


def _parse_netsh_wifi(output: str) -> List[WifiAPRecord]:
    records: List[WifiAPRecord] = []
    ssid = ""
    security = ""
    for line in output.splitlines():
        line = line.strip()
        if line.startswith("SSID "):
            parts = line.split(" : ", 1)
            ssid = parts[1].strip() if len(parts) > 1 else ""
        elif line.startswith("Authentication"):
            parts = line.split(" : ", 1)
            security = parts[1].strip() if len(parts) > 1 else ""
        elif line.startswith("BSSID "):
            bssid = line.split(" : ", 1)[-1].strip()
            records.append(WifiAPRecord(ssid=ssid, bssid=bssid, channel="", signal="", security=security))
        elif line.startswith("Signal") and records:
            records[-1].signal = line.split(" : ", 1)[-1].strip()
        elif line.startswith("Channel") and records:
            records[-1].channel = line.split(" : ", 1)[-1].strip()
    return records


def _infer_windows_subnet() -> Optional[str]:
    try:
        result = subprocess.run(
            ["powershell", "-NoProfile", "-Command", "Get-NetIPAddress -AddressFamily IPv4 | Select-Object IPAddress,PrefixLength | ConvertTo-Json -Depth 3"],
            capture_output=True,
            text=True,
            timeout=4,
        )
        if result.returncode == 0:
            data = json.loads(result.stdout or "[]")
            entries = data if isinstance(data, list) else [data]
            for entry in entries:
                ip_addr = entry.get("IPAddress")
                prefix = entry.get("PrefixLength")
                if ip_addr and prefix:
                    return str(ipaddress.ip_network(f"{ip_addr}/{prefix}", strict=False))
    except Exception:
        pass
    try:
        result = subprocess.run(["ipconfig"], capture_output=True, text=True, timeout=4)
        if result.returncode != 0:
            return None
        for line in result.stdout.splitlines():
            if "IPv4" in line and ":" in line:
                ip_addr = line.split(":")[-1].strip()
                if ip_addr:
                    return str(ipaddress.ip_network(f"{ip_addr}/24", strict=False))
    except Exception:
        return None
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
                records[-1].mac = parts[2]
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
            ["ping", "-n", "1", "-w", "500", ip_addr],
            capture_output=True,
            text=True,
            timeout=2,
        )
        return result.returncode == 0
    except Exception:
        return False


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
