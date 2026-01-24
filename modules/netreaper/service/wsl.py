from __future__ import annotations

import ipaddress
import json
import re
import shlex
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import List, Optional

from capabilities import CapabilityMatrix
from providers.base import BaseProvider, HostRecord, InterfaceRecord, NeighborRecord, ProviderError, RouteRecord, SocketRecord, WifiAPRecord


class WslRunner:
    """Utility for executing commands inside WSL with safe argument handling."""

    def __init__(self, distro: str = "", timeout_default: int = 8):
        self.distro = distro
        self.timeout_default = timeout_default

    def run_json(self, args: List[str], timeout: Optional[int] = None) -> object:
        """Execute command in WSL and parse JSON output."""
        stdout = self.run_text(args, timeout=timeout)
        try:
            return json.loads(stdout or "[]")
        except json.JSONDecodeError as e:
            raise ProviderError(f"Failed to parse JSON from WSL command: {e}")

    def run_text(self, args: List[str], timeout: Optional[int] = None) -> str:
        """Execute command in WSL and return stdout as text."""
        timeout = timeout or self.timeout_default
        cmd = self._build_wsl_command(args)
        
        try:
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired:
            raise ProviderError(f"WSL command timed out after {timeout}s: {' '.join(args)}")
        except Exception as e:
            raise ProviderError(f"Failed to execute WSL command: {e}")

        if result.returncode != 0:
            stderr = result.stderr.strip() or "Command failed"
            raise ProviderError(f"WSL command failed (rc={result.returncode}): {stderr}")

        return result.stdout or ""

    def run_check(self, args: List[str], timeout: Optional[int] = None) -> bool:
        """Execute command in WSL and return True if successful (rc=0)."""
        timeout = timeout or self.timeout_default
        cmd = self._build_wsl_command(args)
        
        try:
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=timeout,
            )
            return result.returncode == 0
        except Exception:
            return False

    def _build_wsl_command(self, args: List[str]) -> List[str]:
        """Build the wsl.exe command with proper argument quoting."""
        wsl_cmd = ["wsl.exe"]
        
        if self.distro:
            wsl_cmd.extend(["-d", self.distro])
        
        wsl_cmd.append("--")
        
        # Quote each argument safely for shell execution
        for arg in args:
            wsl_cmd.append(arg)
        
        return wsl_cmd


class WslProvider(BaseProvider):
    """Provider that executes Linux commands via WSL Bridge Mode."""

    def __init__(self, capabilities: CapabilityMatrix, distro: str = ""):
        self.capabilities = capabilities
        self.distro = distro
        self.runner = WslRunner(distro=distro)

    def get_interfaces(self) -> List[InterfaceRecord]:
        """Get network interfaces from WSL using ip command."""
        data = self.runner.run_json(["ip", "-j", "addr", "show"])
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
        """Get routing table from WSL using ip command."""
        data = self.runner.run_json(["ip", "-j", "route", "show"])
        records: List[RouteRecord] = []
        
        for route in data or []:
            destination = route.get("dst") or "default"
            gateway = route.get("gateway") or ""
            interface = route.get("dev") or ""
            metric = str(route.get("metric", ""))
            records.append(RouteRecord(destination=destination, gateway=gateway, interface=interface, metric=metric))
        
        return records

    def get_sockets(self) -> List[SocketRecord]:
        """Get socket connections from WSL using ss command."""
        output = self.runner.run_text(["ss", "-H", "-t", "-u", "-n", "-a", "-p"])
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
        """Get ARP/neighbor table from WSL using ip command."""
        output = self.runner.run_text(["ip", "neigh"])
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
        """Scan Wi-Fi networks from WSL using nmcli."""
        cmd = ["nmcli", "-t", "-f", "SSID,BSSID,CHAN,SIGNAL,SECURITY", "dev", "wifi", "list", "--rescan", "yes"]
        output = self.runner.run_text(cmd, timeout=12)
        records: List[WifiAPRecord] = []
        
        for raw in output.splitlines():
            if not raw.strip():
                continue
            parts = raw.rsplit(":", 4)
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
        """Quick host discovery using neighbor table."""
        neighbors = self.get_neighbors()
        records = [HostRecord(ip=n.ip, mac=n.mac, state=n.state, source="neighbor") for n in neighbors]
        return records

    def discover_hosts_full(self, target: Optional[str] = None) -> List[HostRecord]:
        """Full host discovery using nmap or ping sweep."""
        target_net = target or _infer_local_subnet(self.get_routes())
        if not target_net:
            return self.discover_hosts_quick()
        
        # Check if nmap is available in WSL
        if self.runner.run_check(["which", "nmap"], timeout=2):
            output = self.runner.run_text(["nmap", "-sn", target_net], timeout=60)
            return _parse_nmap_hosts(output)
        
        return _ping_sweep_wsl(target_net, self.runner)


def _split_host_port(value: str) -> tuple[str, str]:
    """Split host:port string into components."""
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
    """Infer local subnet from routing table."""
    for route in routes:
        if route.destination and route.destination != "default" and "/" in route.destination:
            return route.destination
    return None


def _parse_nmap_hosts(output: str) -> List[HostRecord]:
    """Parse nmap scan output."""
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


def _ping_sweep_wsl(target: str, runner: WslRunner) -> List[HostRecord]:
    """Perform ping sweep via WSL."""
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
        futures = {executor.submit(_ping_host_wsl, str(ip), runner): str(ip) for ip in hosts}
        for future in as_completed(futures):
            ip = futures[future]
            if future.result():
                records.append(HostRecord(ip=ip, state="up", source="ping"))
    
    return records


def _ping_host_wsl(ip_addr: str, runner: WslRunner) -> bool:
    """Ping a single host via WSL."""
    return runner.run_check(["ping", "-c", "1", "-W", "1", ip_addr], timeout=2)
