from __future__ import annotations

from typing import Literal, Optional

from capabilities import CapabilityMatrix
from providers.base import BaseProvider
from providers.linux import LinuxProvider
from providers.windows import WindowsProvider
from providers.wsl import WslProvider

BackendMode = Literal["native", "wsl"]


def get_provider(
    capabilities: CapabilityMatrix,
    backend_mode: BackendMode = "native",
    wsl_distro: str = "",
) -> BaseProvider:
    """
    Get the appropriate provider based on capabilities and backend mode.
    
    Args:
        capabilities: System capability matrix
        backend_mode: "native" for OS-native provider, "wsl" for WSL Bridge
        wsl_distro: WSL distribution name (empty for default)
    
    Returns:
        Provider instance
    """
    if backend_mode == "wsl":
        return WslProvider(capabilities, distro=wsl_distro)
    
    if capabilities.is_windows:
        return WindowsProvider(capabilities)
    
    return LinuxProvider(capabilities)
