#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# NETREAPER - Version Resolution Helper
# ═══════════════════════════════════════════════════════════════════════════════
# Copyright (c) 2025 Nerds489
# SPDX-License-Identifier: Apache-2.0
#
# Shared helper for resolving NETREAPER_ROOT and VERSION.
# This centralizes version handling to prevent drift between CLI output,
# VERSION file, documentation, and release workflow.
#
# Usage:
#   source "$NETREAPER_ROOT/lib/version.sh"   # If NETREAPER_ROOT is already set
#   source "/path/to/lib/version.sh"          # Auto-detects NETREAPER_ROOT
# ═══════════════════════════════════════════════════════════════════════════════

# Prevent multiple sourcing
[[ -n "${_NETREAPER_VERSION_LOADED:-}" ]] && return 0
readonly _NETREAPER_VERSION_LOADED=1

# --- NETREAPER_ROOT Resolution ------------------------------------------------
# If NETREAPER_ROOT is not already set, compute it from this file's location.
# This file lives in lib/, so the root is one directory up.
if [[ -z "${NETREAPER_ROOT:-}" ]]; then
    if ! NETREAPER_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." 2>/dev/null && pwd)"; then
        echo "ERROR: Failed to resolve NETREAPER_ROOT from ${BASH_SOURCE[0]}" >&2
        return 1
    fi
fi
readonly NETREAPER_ROOT 2>/dev/null || true  # May already be readonly

# --- VERSION Resolution -------------------------------------------------------
# Read VERSION file (first line, strip whitespace). Fallback to "unknown".
if [[ -z "${VERSION:-}" ]]; then
    if [[ -f "$NETREAPER_ROOT/VERSION" ]]; then
        IFS= read -r VERSION < "$NETREAPER_ROOT/VERSION"
        VERSION="${VERSION//[[:space:]]/}"
    else
        VERSION="unknown"
    fi
fi
readonly VERSION 2>/dev/null || true  # May already be readonly

# --- Exports ------------------------------------------------------------------
export NETREAPER_ROOT VERSION
