#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# NETREAPER - Credential Hunting Wizard
# ═══════════════════════════════════════════════════════════════════════════════
# Copyright (c) 2025 Nerds489
# SPDX-License-Identifier: Apache-2.0
#
# Credential hunting wizard: SMB/LDAP enumeration → password spraying → brute-force attacks
# ═══════════════════════════════════════════════════════════════════════════════

# Prevent multiple sourcing
[[ -n "${_NETREAPER_CREDS_WIZARD_LOADED:-}" ]] && return 0
readonly _NETREAPER_CREDS_WIZARD_LOADED=1

# Source library files
source "${BASH_SOURCE%/*}/../lib/core.sh"
source "${BASH_SOURCE%/*}/../lib/ui.sh"
source "${BASH_SOURCE%/*}/../lib/safety.sh"
source "${BASH_SOURCE%/*}/../lib/detection.sh"
source "${BASH_SOURCE%/*}/../lib/utils.sh"

#═══════════════════════════════════════════════════════════════════════════════
# CREDS WIZARD FUNCTIONS
#═══════════════════════════════════════════════════════════════════════════════

# Credential hunting wizard
run_creds_wizard() {
    local arg="$1"
    # Help option
    if [[ "$arg" == "-h" || "$arg" == "--help" ]]; then
        cat <<'USAGE'
Credential Hunting Wizard
Usage: netreaper wizard creds [target]

This wizard guides you through SMB/LDAP enumeration, password spraying and brute force
attacks.  It supports resuming from checkpoints and will prompt before each step.
Use NR_LITE_MODE=1 or --lite to use smaller password lists and simplified attacks.
USAGE
        return 0
    fi
    local target="$arg"

    operation_header "Credential Hunting Wizard" "Automated workflow"
    # Start resource monitor to auto-enable lite mode if necessary
    if command -v monitor_loop >/dev/null 2>&1; then
        monitor_loop 20 &
    fi

    # Prompt for target if not provided
    if [[ -z "$target" ]]; then
        target=$(get_target_input "Enter target IP or domain")
        if [[ -z "$target" ]]; then
            log_error "No target specified"
            return 1
        fi
    fi

    # Pre‑flight: confirm authorisation
    if ! confirm "Are you authorised to perform credential attacks on this target?" "n"; then
        log_info "Aborting wizard: authorisation not confirmed"
        return 1
    fi

    # Resume checkpoint if available
    local current_step=1
    local cp_data
    cp_data=$(load_checkpoint "creds_wizard" "$target")
    if [[ -n "$cp_data" ]]; then
        current_step=$(echo "$cp_data" | python3 -c "import sys,json; data=json.load(sys.stdin); print(data.get('step', 1))" 2>/dev/null || echo 1)
        if [[ "$current_step" -gt 1 ]]; then
            log_info "Resuming from step $current_step"
        fi
    fi

    log_info "Starting credential hunting on: $target"
    if is_lite; then
        log_info "Lite mode enabled – using reduced wordlists and simplified attacks"
    fi
    echo

    local total_steps=3

    # Step 1: SMB/LDAP Enumeration
    if [[ $current_step -le 1 ]]; then
        echo -e "${C_CYAN}Progress: [1/$total_steps] SMB/LDAP Enumeration${C_RESET}"
        log_info "This enumerates SMB shares and LDAP users/groups."
        if confirm "Proceed with SMB/LDAP enumeration?" "y"; then
            # Caching: reuse previous enum outputs if available
            local smb_existing ldap_existing
            smb_existing=$(ls -1t "$OUTPUT_DIR"/smb_enum_${target}_*.txt 2>/dev/null | head -n1 || true)
            ldap_existing=$(ls -1t "$OUTPUT_DIR"/ldap_enum_${target}_*.txt 2>/dev/null | head -n1 || true)
            if [[ -n "$smb_existing" ]]; then
                if confirm "Reuse existing SMB enumeration output $(basename "$smb_existing")?" "y"; then
                    log_info "Using cached SMB enumeration results"
                else
                    smb_existing=""
                fi
            fi
            if [[ -z "$smb_existing" ]]; then
                run_smb_enum "$target" || log_warning "SMB enumeration failed"
            fi
            if [[ -n "$ldap_existing" ]]; then
                if confirm "Reuse existing LDAP enumeration output $(basename "$ldap_existing")?" "y"; then
                    log_info "Using cached LDAP enumeration results"
                else
                    ldap_existing=""
                fi
            fi
            if [[ -z "$ldap_existing" ]]; then
                run_ldap_enum "$target" || log_warning "LDAP enumeration failed"
            fi
        else
            log_info "Enumeration skipped"
        fi
        save_checkpoint "creds_wizard" "2" "$target"
    fi
    echo

    # Step 2: Password Spraying
    if [[ $current_step -le 2 ]]; then
        echo -e "${C_CYAN}Progress: [2/$total_steps] Password Spraying${C_RESET}"
        log_info "This attempts common passwords against discovered accounts."
        if confirm "Proceed with password spraying? (May lock accounts)" "n"; then
            # Determine wordlists
            local userlist="/usr/share/wordlists/metasploit/unix_users.txt"
            local passlist
            passlist=$(get_wordlist "/usr/share/wordlists/rockyou.txt")
            if ! [[ -f "$userlist" ]]; then
                log_warning "Userlist not found: $userlist"
            fi
            if ! [[ -f "$passlist" ]]; then
                log_warning "Password list not found: $passlist"
            fi
            # Caching: reuse previous spray results
            local spray_existing
            spray_existing=$(ls -1t "$OUTPUT_DIR"/password_spray_${target}_*.txt 2>/dev/null | head -n1 || true)
            if [[ -n "$spray_existing" ]]; then
                if confirm "Reuse existing password spray output $(basename "$spray_existing")?" "y"; then
                    log_info "Using cached password spray results"
                else
                    spray_existing=""
                fi
            fi
            if [[ -z "$spray_existing" ]]; then
                # Use patator directly to allow custom lists
                if check_tool "patator"; then
                    operation_header "Password Spray" "$target"
                    local outfile
                    outfile="${OUTPUT_DIR}/password_spray_${target}_$(timestamp_filename).txt"
                    log_command_preview "patator smb_login host=\"$target\" user=FILE0 password=FILE1 0=\"$userlist\" 1=\"$passlist\" -x ignore:fgrep='STATUS_LOGON_FAILURE'"
                    log_info "Spraying passwords..."
                    patator smb_login host="$target" user=FILE0 password=FILE1 0="$userlist" 1="$passlist" -x ignore:fgrep='STATUS_LOGON_FAILURE' | tee "$outfile"
                else
                    # Fall back to existing helper if available
                    run_password_spray "$target"
                fi
            fi
        else
            log_info "Password spraying skipped"
        fi
        save_checkpoint "creds_wizard" "3" "$target"
    fi
    echo

    # Step 3: Brute Force
    if [[ $current_step -le 3 ]]; then
        echo -e "${C_CYAN}Progress: [3/$total_steps] Brute Force${C_RESET}"
        log_info "This performs brute-force attacks on discovered services."
        if confirm "Proceed with brute-force attacks? (May trigger IDS/locks)" "n"; then
            # Determine wordlists
            local userlist="/usr/share/wordlists/metasploit/unix_users.txt"
            local passlist
            passlist=$(get_wordlist "/usr/share/wordlists/rockyou.txt")
            # Caching: reuse previous brute force results
            local brute_existing
            brute_existing=$(ls -1t "$OUTPUT_DIR"/brute_force_${target}_*.txt 2>/dev/null | head -n1 || true)
            if [[ -n "$brute_existing" ]]; then
                if confirm "Reuse existing brute-force output $(basename "$brute_existing")?" "y"; then
                    log_info "Using cached brute-force results"
                else
                    brute_existing=""
                fi
            fi
            if [[ -z "$brute_existing" ]]; then
                if check_tool "hydra"; then
                    operation_header "Brute Force Attack" "$target"
                    local outfile="${OUTPUT_DIR}/brute_force_${target}_$(timestamp_filename).txt"
                    log_command_preview "hydra -L \"$userlist\" -P \"$passlist\" \"$target\" ssh"
                    log_info "Running hydra brute force..."
                    hydra -L "$userlist" -P "$passlist" "$target" ssh | tee "$outfile"
                else
                    run_brute_force "$target"
                fi
            fi
        else
            log_info "Brute-force attacks skipped"
        fi
        # Wizard completed; remove checkpoint
        save_checkpoint "creds_wizard" "4" "$target"
    fi
    echo

    operation_summary "success" "Credential hunting wizard complete" "Review outputs in $OUTPUT_DIR"
    log_audit "CREDS_WIZARD" "$target" "success"
}

# LDAP enumeration
run_ldap_enum() {
    local target="$1"

    if ! check_tool "ldapsearch"; then
        log_error "ldapsearch is not installed"
        log_info "Install: sudo apt install ldap-utils"
        return 1
    fi

    local outfile
    outfile="${OUTPUT_DIR}/ldap_enum_${target}_$(timestamp_filename).txt"

    operation_header "LDAP Enumeration" "$target"
    log_command_preview "ldapsearch -x -h \"$target\" -b \"\" -s base"
    log_info "Enumerating LDAP directory..."

    ldapsearch -x -h "$target" -b "" -s base | tee "$outfile"
    local exit_code=$?

    if [[ $exit_code -eq 0 ]]; then
        operation_summary "success" "LDAP enumeration complete" "Output: $outfile"
    else
        operation_summary "failed" "Enumeration failed"
    fi

    return $exit_code
}

# Password spraying with patator or similar
run_password_spray() {
    local target="$1"

    if ! check_tool "patator"; then
        log_error "patator is not installed"
        log_info "Install: sudo apt install patator"
        return 1
    fi

    local userlist="/usr/share/wordlists/metasploit/unix_users.txt"
    # Use get_wordlist to select an appropriate password list (lite mode aware)
    local passlist
    passlist=$(get_wordlist "/usr/share/wordlists/rockyou.txt")
    local outfile
    outfile="${OUTPUT_DIR}/password_spray_${target}_$(timestamp_filename).txt"

    operation_header "Password Spraying" "$target"
    log_command_preview "patator smb_login host=\"$target\" user=FILE0 password=FILE1 0=\"$userlist\" 1=\"$passlist\" -x ignore:fgrep='STATUS_LOGON_FAILURE'"
    log_info "Spraying passwords..."

    patator smb_login host="$target" user=FILE0 password=FILE1 0="$userlist" 1="$passlist" -x ignore:fgrep='STATUS_LOGON_FAILURE' | tee "$outfile"
    local exit_code=$?

    if [[ $exit_code -eq 0 ]]; then
        operation_summary "success" "Password spraying complete" "Output: $outfile"
    else
        operation_summary "failed" "Spraying failed"
    fi

    return $exit_code
}

# Brute-force attacks on services
run_brute_force() {
    local target="$1"

    # Assume SSH brute force as example
    if ! check_tool "hydra"; then
        log_error "hydra is not installed"
        log_info "Install: sudo apt install hydra"
        return 1
    fi

    local userlist="/usr/share/wordlists/metasploit/unix_users.txt"
    # Use get_wordlist to select an appropriate password list (lite mode aware)
    local passlist
    passlist=$(get_wordlist "/usr/share/wordlists/rockyou.txt")
    local outfile
    outfile="${OUTPUT_DIR}/brute_force_${target}_$(timestamp_filename).txt"

    operation_header "Brute-Force Attack" "$target"
    log_command_preview "hydra -L \"$userlist\" -P \"$passlist\" \"$target\" ssh"
    log_info "Brute forcing SSH..."

    hydra -L "$userlist" -P "$passlist" "$target" ssh | tee "$outfile"
    local exit_code=$?

    if [[ $exit_code -eq 0 ]]; then
        operation_summary "success" "Brute-force complete" "Output: $outfile"
    else
        operation_summary "failed" "Attack failed"
    fi

    return $exit_code
}

#═══════════════════════════════════════════════════════════════════════════════
# EXPORT FUNCTIONS
#═══════════════════════════════════════════════════════════════════════════════

export -f run_creds_wizard run_ldap_enum run_password_spray run_brute_force