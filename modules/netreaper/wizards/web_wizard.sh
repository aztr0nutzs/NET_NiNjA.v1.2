#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# NETREAPER - Web Application Reconnaissance Wizard
# ═══════════════════════════════════════════════════════════════════════════════
# Copyright (c) 2025 Nerds489
# SPDX-License-Identifier: Apache-2.0
#
# Web application reconnaissance wizard: DNS enum → SSL/TLS scanning → directory brute force → SQL injection testing
# ═══════════════════════════════════════════════════════════════════════════════

# Prevent multiple sourcing
[[ -n "${_NETREAPER_WEB_WIZARD_LOADED:-}" ]] && return 0
readonly _NETREAPER_WEB_WIZARD_LOADED=1

# Source library files
source "${BASH_SOURCE%/*}/../lib/core.sh"
source "${BASH_SOURCE%/*}/../lib/ui.sh"
source "${BASH_SOURCE%/*}/../lib/safety.sh"
source "${BASH_SOURCE%/*}/../lib/detection.sh"
source "${BASH_SOURCE%/*}/../lib/utils.sh"

#═══════════════════════════════════════════════════════════════════════════════
# WEB WIZARD FUNCTIONS
#═══════════════════════════════════════════════════════════════════════════════

# Web application reconnaissance wizard
run_web_wizard() {
    local arg="$1"
    # Show help text if requested
    if [[ "$arg" == "-h" || "$arg" == "--help" ]]; then
        cat <<'USAGE'
Web Application Reconnaissance Wizard
Usage: netreaper wizard web [target]

This workflow performs DNS enumeration, SSL/TLS scanning, directory brute forcing
and SQL injection testing against a target domain or IP.  It supports resuming
from checkpoints and will prompt for confirmation before each potentially
intrusive step.  When NR_LITE_MODE is set the wizard uses lighter tools and
reduced scans where possible.
USAGE
        return 0
    fi
    local target="$arg"

    operation_header "Web Application Reconnaissance Wizard" "Automated workflow"
    # Start resource monitor loop in the background to automatically switch to lite mode
    if command -v monitor_loop >/dev/null 2>&1; then
        monitor_loop 20 &
    fi

    # Step 1: Get target
    if [[ -z "$target" ]]; then
        # Auto-fill from history if available
        local history_file="$CONFIG_DIR/history"
        if [[ -f "$history_file" ]]; then
            local last_target
            last_target=$(tail -1 "$history_file" | awk '{print $1}')
            if [[ -n "$last_target" ]]; then
                target=$(get_input "Enter target domain or IP (last: $last_target)" "$last_target")
            else
                target=$(get_target_input "Enter target domain or IP")
            fi
        else
            target=$(get_target_input "Enter target domain or IP")
        fi
        if [[ -z "$target" ]]; then
            log_error "No target specified"
            return 1
        fi
        # Save to history
        echo "$target $(timestamp)" >> "$history_file"
    fi

    # Pre‑flight: confirm authorisation before performing reconnaissance
    if ! confirm "Are you authorised to test this web application?" "n"; then
        log_info "Aborting wizard: authorisation not confirmed"
        return 1
    fi

    # Check for resume
    local checkpoint_data
    checkpoint_data=$(load_checkpoint "web_wizard" "$target")
    local current_step=1
    if [[ -n "$checkpoint_data" ]]; then
        current_step=$(echo "$checkpoint_data" | python3 -c "import sys, json; data=json.load(sys.stdin); print(data.get('step', '1'))" 2>/dev/null || echo "1")
        if [[ "$current_step" -gt 1 ]]; then
            log_info "Resuming from step $current_step"
        fi
    fi

    log_info "Starting web reconnaissance on: $target"
    if [[ "${NR_LITE_MODE:-0}" == "1" ]]; then
        log_info "Lite mode enabled - using lighter tools and reduced scanning"
    fi
    echo

    local total_steps=4

    # Step 1: DNS Enumeration
    if [[ $current_step -le 1 ]]; then
        echo -e "${C_CYAN}Progress: [1/$total_steps] DNS Enumeration${C_RESET}"
        log_info "This enumerates DNS records for the target domain. Helps discover subdomains and mail servers."
        if ! confirm "Proceed with DNS enumeration?" "y"; then
            log_info "DNS enumeration skipped"
        else
            # Simple caching: reuse previous dnsenum output if present
            local existing
            existing=$(ls -1t "$OUTPUT_DIR"/dnsenum_${target}_*.txt 2>/dev/null | head -n1 || true)
            if [[ -n "$existing" ]]; then
                if confirm "Reuse existing DNS enumeration output $(basename "$existing")?" "y"; then
                    log_info "Using cached DNS enumeration results"
                else
                    existing=""
                fi
            fi
            if [[ -z "$existing" ]]; then
                if check_tool "dnsenum"; then
                    if run_dnsenum "$target"; then
                        log_success "DNS enumeration completed successfully"
                    else
                        log_warning "DNS enumeration failed, continuing with other steps"
                    fi
                elif check_tool "dnsrecon"; then
                    if run_dnsrecon "$target"; then
                        log_success "DNS enumeration completed successfully"
                    else
                        log_warning "DNS enumeration failed, continuing with other steps"
                    fi
                else
                    log_warning "No DNS enumeration tools available"
                fi
            fi
        fi
        save_checkpoint "web_wizard" "2" "$target"
    fi
    echo

    # Step 2: SSL/TLS Scanning
    if [[ $current_step -le 2 ]]; then
        echo -e "${C_CYAN}Progress: [2/$total_steps] SSL/TLS Scanning${C_RESET}"
        log_info "This analyzes SSL/TLS configuration for security issues like weak ciphers or expired certificates."
        if ! confirm "Proceed with SSL scanning?" "y"; then
            log_info "SSL scanning skipped"
        else
            # Simple caching: reuse existing SSL scan results
            local existing
            existing=$(ls -1t "$OUTPUT_DIR"/sslscan_${target}_*.txt 2>/dev/null | head -n1 || true)
            if [[ -n "$existing" ]]; then
                if confirm "Reuse existing SSL scan output $(basename "$existing")?" "y"; then
                    log_info "Using cached SSL scan results"
                else
                    existing=""
                fi
            fi
            if [[ -z "$existing" ]]; then
                # In lite mode skip deep SSL tests and rely on sslscan only
                if is_lite; then
                    if check_tool "sslscan"; then
                        run_sslscan "$target:443" || log_warning "SSL scanning failed"
                    elif check_tool "sslyze"; then
                        run_sslyze "$target:443" || log_warning "SSL scanning failed"
                    else
                        log_warning "No SSL scanning tools available"
                    fi
                else
                    if check_tool "sslscan"; then
                        if run_sslscan "$target:443"; then
                            log_success "SSL scanning completed successfully"
                        else
                            log_warning "SSL scanning failed, trying alternative"
                            if check_tool "sslyze"; then
                                run_sslyze "$target:443"
                            fi
                        fi
                    elif check_tool "sslyze"; then
                        if run_sslyze "$target:443"; then
                            log_success "SSL scanning completed successfully"
                        else
                            log_warning "SSL scanning failed"
                        fi
                    else
                        log_warning "No SSL scanning tools available"
                    fi
                fi
            fi
        fi
        save_checkpoint "web_wizard" "3" "$target"
    fi
    echo

    # Step 3: Directory Brute Force
    if [[ $current_step -le 3 ]]; then
        echo -e "${C_CYAN}Progress: [3/$total_steps] Directory Brute Force${C_RESET}"
        log_info "This attempts to discover hidden directories and files. May trigger IDS alerts; ensure authorization."
        if ! confirm "Proceed with directory brute force? (May trigger IDS)" "y"; then
            log_info "Directory brute force skipped"
        else
            # Check for existing dirb results
            local existing
            existing=$(ls -1t "$OUTPUT_DIR"/dirb_${target}_*.txt 2>/dev/null | head -n1 || true)
            if [[ -n "$existing" ]]; then
                if confirm "Reuse existing directory brute force output $(basename "$existing")?" "y"; then
                    log_info "Using cached directory enumeration results"
                else
                    existing=""
                fi
            fi
            if [[ -z "$existing" ]]; then
                # In lite mode skip deep gobuster/dirb scanning or use reduced wordlists (not yet implemented)
                run_dirb_brute "$target" || log_warning "Directory brute force failed"
            fi
        fi
        save_checkpoint "web_wizard" "4" "$target"
    fi
    echo

    # Step 4: SQL Injection Testing
    if [[ $current_step -le 4 ]]; then
        echo -e "${C_CYAN}Progress: [4/$total_steps] SQL Injection Testing${C_RESET}"
        log_info "This tests for SQL injection vulnerabilities. Can be destructive; use with caution."
        if ! confirm "Proceed with SQL injection testing? (May be destructive)" "n"; then
            log_info "SQL injection testing skipped"
        else
            local existing
            existing=$(ls -1t "$OUTPUT_DIR"/sqlmap_${target}_*.txt 2>/dev/null | head -n1 || true)
            if [[ -n "$existing" ]]; then
                if confirm "Reuse existing SQL injection test results $(basename "$existing")?" "y"; then
                    log_info "Using cached SQL injection results"
                else
                    existing=""
                fi
            fi
            if [[ -z "$existing" ]]; then
                # In lite mode we limit crawl depth to 1 and skip tamper scripts
                if is_lite; then
                    if check_tool "sqlmap"; then
                        log_command_preview "sqlmap -u http://$target --batch --crawl=1 --threads=1"
                        sqlmap -u "http://$target" --batch --crawl=1 --threads=1 | tee "$OUTPUT_DIR/sqlmap_${target}_$(timestamp_filename).txt" || true
                    else
                        log_warning "sqlmap is not installed"
                    fi
                else
                    run_sqlmap_test "$target" || log_warning "SQL injection testing failed"
                fi
            fi
        fi
    fi
    echo

    operation_summary "success" "Web reconnaissance wizard complete" "Review outputs in $OUTPUT_DIR"
    log_audit "WEB_WIZARD" "$target" "success"
}

# Directory brute force with dirb or gobuster
run_dirb_brute() {
    local target="$1"

    if ! check_tool "dirb"; then
        log_error "dirb is not installed"
        log_info "Install: sudo apt install dirb"
        return 1
    fi

    local outfile
    outfile="${OUTPUT_DIR}/dirb_${target}_$(timestamp_filename).txt"

    operation_header "Directory Brute Force" "$target"
    log_command_preview "dirb \"http://$target\" /usr/share/wordlists/dirb/common.txt"
    log_info "Brute forcing directories..."

    dirb "http://$target" /usr/share/wordlists/dirb/common.txt -o "$outfile"
    local exit_code=$?

    if [[ $exit_code -eq 0 ]]; then
        operation_summary "success" "Directory brute force complete" "Output: $outfile"
    else
        operation_summary "failed" "Brute force failed"
    fi

    return $exit_code
}

# SQL injection testing with sqlmap
run_sqlmap_test() {
    local target="$1"

    if ! check_tool "sqlmap"; then
        log_error "sqlmap is not installed"
        log_info "Install: sudo apt install sqlmap"
        return 1
    fi

    local outfile
    outfile="${OUTPUT_DIR}/sqlmap_${target}_$(timestamp_filename).txt"

    operation_header "SQL Injection Test" "$target"
    log_command_preview "sqlmap -u \"http://$target\" --batch --crawl=1"
    log_info "Testing for SQL injection vulnerabilities..."

    sqlmap -u "http://$target" --batch --crawl=1 | tee "$outfile"
    local exit_code=$?

    if [[ $exit_code -eq 0 ]]; then
        operation_summary "success" "SQL injection test complete" "Output: $outfile"
    else
        operation_summary "failed" "Test failed"
    fi

    return $exit_code
}

#═══════════════════════════════════════════════════════════════════════════════
# EXPORT FUNCTIONS
#═══════════════════════════════════════════════════════════════════════════════

export -f run_web_wizard run_dirb_brute run_sqlmap_test