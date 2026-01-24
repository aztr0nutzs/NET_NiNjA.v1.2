#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# NETREAPER - Context-Aware Recommendations
# ═══════════════════════════════════════════════════════════════════════════════
# Copyright (c) 2025 Nerds489
# SPDX-License-Identifier: Apache-2.0
#
# Context-aware recommendations: parse scan outputs and suggest next actions
# ═══════════════════════════════════════════════════════════════════════════════

# Prevent multiple sourcing
[[ -n "${_NETREAPER_RECOMMENDATION_LOADED:-}" ]] && return 0
readonly _NETREAPER_RECOMMENDATION_LOADED=1

# Source library files
source "${BASH_SOURCE%/*}/../lib/core.sh"
source "${BASH_SOURCE%/*}/../lib/ui.sh"
source "${BASH_SOURCE%/*}/../lib/safety.sh"
source "${BASH_SOURCE%/*}/../lib/detection.sh"
source "${BASH_SOURCE%/*}/../lib/utils.sh"

#═══════════════════════════════════════════════════════════════════════════════
# RECOMMENDATION FUNCTIONS
#═══════════════════════════════════════════════════════════════════════════════

# Mapping of services to recommended tools
declare -gA SERVICE_RECOMMENDATIONS=(
    [http]="nikto gobuster dirb"
    [https]="nikto sslscan sslyze"
    [ftp]="hydra nmap_ftp"
    [ssh]="hydra nmap_ssh"
    [smb]="enum4linux smbclient"
    [ldap]="ldapsearch"
    [mysql]="sqlmap hydra"
    [postgres]="sqlmap hydra"
)

# Parse nmap output and generate recommendations
# Args: $1 = nmap output file
# Returns: recommendations as string
parse_nmap_recommendations() {
    local nmap_file="$1"
    local recommendations=""

    if [[ ! -f "$nmap_file" ]]; then
        log_error "Nmap file not found: $nmap_file"
        return 1
    fi

    # Extract open ports and services
    local services
    services=$(grep -E "^[0-9]+/.*open" "$nmap_file" | awk '{print $3}' | sort | uniq)

    for service in $services; do
        if [[ -n "${SERVICE_RECOMMENDATIONS[$service]+isset}" ]]; then
            recommendations+="For $service service: ${SERVICE_RECOMMENDATIONS[$service]}\n"
        fi
    done

    if [[ -z "$recommendations" ]]; then
        recommendations="No specific recommendations based on open services."
    fi

    echo -e "$recommendations"
}

# Show recommendations for a target
# Args: $1 = target
show_recommendations() {
    local target="$1"

    if [[ -z "$target" ]]; then
        log_error "No target specified"
        return 1
    fi

    log_info "Generating recommendations for: $target"

    # Look for recent scan files
    local scan_files
    scan_files=$(find "$OUTPUT_DIR" -name "*${target}*" -type f -mtime -1 | head -5)

    if [[ -z "$scan_files" ]]; then
        log_info "No recent scan files found for $target"
        log_info "Run a scan first: netreaper scan $target"
        return 0
    fi

    for file in $scan_files; do
        if [[ "$file" == *nmap* ]]; then
            log_info "Recommendations based on $file:"
            parse_nmap_recommendations "$file"
        fi
    done
}

#═══════════════════════════════════════════════════════════════════════════════
# EXPORT FUNCTIONS
#═══════════════════════════════════════════════════════════════════════════════

export -f parse_nmap_recommendations show_recommendations