#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# NETREAPER - Offensive Security Framework
# ═══════════════════════════════════════════════════════════════════════════════
# Copyright (c) 2025 Nerds489
# SPDX-License-Identifier: Apache-2.0
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at:
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# See LICENSE and NOTICE files in the project root for full details.
# ═══════════════════════════════════════════════════════════════════════════════
#
# Configuration library: persistent settings management
# ═══════════════════════════════════════════════════════════════════════════════

# Prevent multiple sourcing
[[ -n "${_NETREAPER_CONFIG_LOADED:-}" ]] && return 0
readonly _NETREAPER_CONFIG_LOADED=1

#═══════════════════════════════════════════════════════════════════════════════
# LOGGING FALLBACK
#═══════════════════════════════════════════════════════════════════════════════

# Graceful logging fallback if logging functions aren't available yet
# These will be overridden once core.sh is loaded
_config_log_info() {
    [[ "${NR_SUPPRESS_OUTPUT:-0}" == "1" ]] && return 0
    if declare -f log_info &>/dev/null; then
        log_info "$@"
    else
        echo "[*] $*" >&2
    fi
}

_config_log_error() {
    [[ "${NR_SUPPRESS_OUTPUT:-0}" == "1" ]] && return 0
    if declare -f log_error &>/dev/null; then
        log_error "$@"
    else
        echo "[!] $*" >&2
    fi
}

_config_log_success() {
    [[ "${NR_SUPPRESS_OUTPUT:-0}" == "1" ]] && return 0
    if declare -f log_success &>/dev/null; then
        log_success "$@"
    else
        echo "[+] $*" >&2
    fi
}

_config_log_debug() {
    [[ "${NR_SUPPRESS_OUTPUT:-0}" == "1" ]] && return 0
    if declare -f log_debug &>/dev/null; then
        log_debug "$@"
    else
        : # Silent in fallback mode
    fi
}

#═══════════════════════════════════════════════════════════════════════════════
# CONFIGURATION PATHS
#═══════════════════════════════════════════════════════════════════════════════

# Use existing paths if defined, otherwise set defaults
declare -g NETREAPER_HOME="${NETREAPER_HOME:-$HOME/.netreaper}"
declare -g NETREAPER_CONFIG_DIR="${NETREAPER_CONFIG_DIR:-$NETREAPER_HOME/config}"
declare -g NETREAPER_CONFIG_FILE="${NETREAPER_CONFIG_FILE:-$NETREAPER_CONFIG_DIR/config.conf}"

#═══════════════════════════════════════════════════════════════════════════════
# DEFAULT CONFIGURATION
#═══════════════════════════════════════════════════════════════════════════════

# Default configuration values
# Keys must be stable, lower_snake_case
declare -gA DEFAULT_CONFIG=(
    [log_level]="INFO"
    [file_logging]="true"
    [default_scan_type]="standard"
    [confirm_dangerous]="true"
    [warn_public_ip]="true"
    [default_wordlist]="/usr/share/wordlists/rockyou.txt"
    [non_interactive_default_index]="0"
    [unsafe_mode]="false"
    [lite_mode]="false"
)

# Ordered list of default keys (for consistent file output)
declare -ga DEFAULT_CONFIG_KEYS=(
    "log_level"
    "file_logging"
    "default_scan_type"
    "confirm_dangerous"
    "warn_public_ip"
    "default_wordlist"
    "non_interactive_default_index"
    "lite_mode"
    "unsafe_mode"
)

# Active configuration (populated by load_config)
declare -gA CONFIG=()

# Extra keys found in file but not in defaults (preserved on write)
declare -gA _CONFIG_EXTRA_KEYS=()

#═══════════════════════════════════════════════════════════════════════════════
# HELPER FUNCTIONS
#═══════════════════════════════════════════════════════════════════════════════

# Check if a value represents true
# Args: $1 = value to check
# Returns: 0 for true values (1, true, yes, y), 1 otherwise
config_is_true() {
    local value="${1:-}"

    # Case-insensitive comparison
    case "${value,,}" in
        1|true|yes|y)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

# Resolve a config key as boolean with default
# Args: $1 = key, $2 = default (0 or 1)
# Returns: 0 (true) or 1 (false)
config_resolve_bool() {
    local key="$1"
    local default="${2:-1}"
    local value=""

    value=$(config_get "$key")

    if [[ -z "$value" ]]; then
        return "$default"
    fi

    if config_is_true "$value"; then
        return 0
    else
        return 1
    fi
}

# Trim leading and trailing whitespace from a string
# Args: $1 = string to trim
# Returns: trimmed string
_config_trim() {
    local var="$1"
    # Remove leading whitespace
    var="${var#"${var%%[![:space:]]*}"}"
    # Remove trailing whitespace
    var="${var%"${var##*[![:space:]]}"}"
    echo "$var"
}

#═══════════════════════════════════════════════════════════════════════════════
# CONFIGURATION FILE OPERATIONS
#═══════════════════════════════════════════════════════════════════════════════

# Create default configuration file with header comments
# Uses atomic write strategy
create_default_config() {
    local tmp_file=""
    local key=""

    _config_log_debug "Creating default configuration file: $NETREAPER_CONFIG_FILE"

    # Create temp file with PID for uniqueness
    tmp_file="${NETREAPER_CONFIG_FILE}.tmp.$$"

    # Write configuration to temp file
    {
        echo "# ═══════════════════════════════════════════════════════════════════════════════"
        echo "# NETREAPER Configuration"
        echo "# ═══════════════════════════════════════════════════════════════════════════════"
        echo "#"
        echo "# This file contains persistent settings for NETREAPER."
        echo "# Edit manually or use: netreaper config set <key> <value>"
        echo "#"
        echo "# Format: key=value (whitespace around = is trimmed)"
        echo "# Lines starting with # are comments"
        echo "#"
        echo "# ═══════════════════════════════════════════════════════════════════════════════"
        echo ""
        echo "# Logging"
        echo "# Valid levels: DEBUG, INFO, WARNING, ERROR"
        echo "log_level=${DEFAULT_CONFIG[log_level]}"
        echo ""
        echo "# Enable logging to file (~/.netreaper/logs/)"
        echo "file_logging=${DEFAULT_CONFIG[file_logging]}"
        echo ""
        echo "# Scanning"
        echo "# Default scan type: quick, standard, full, stealth"
        echo "default_scan_type=${DEFAULT_CONFIG[default_scan_type]}"
        echo ""
        echo "# Safety"
        echo "# Require confirmation for dangerous operations"
        echo "confirm_dangerous=${DEFAULT_CONFIG[confirm_dangerous]}"
        echo ""
        echo "# Warn when targeting public IP addresses"
        echo "warn_public_ip=${DEFAULT_CONFIG[warn_public_ip]}"
        echo ""
        echo "# Disable safety checks (NOT RECOMMENDED)"
echo "# Enable lite mode for low-resource systems"
echo "lite_mode=${DEFAULT_CONFIG[lite_mode]}"
echo ""
        echo "# Can also be set via NR_UNSAFE_MODE=1 environment variable"
        echo "unsafe_mode=${DEFAULT_CONFIG[unsafe_mode]}"
        echo ""
        echo "# Paths"
        echo "# Default wordlist for password attacks"
        echo "default_wordlist=${DEFAULT_CONFIG[default_wordlist]}"
        echo ""
        echo "# Non-interactive mode"
        echo "# Default option index for select_option when no TTY (0-based)"
        echo "non_interactive_default_index=${DEFAULT_CONFIG[non_interactive_default_index]}"
        echo ""
    } > "$tmp_file"

    # Set restrictive permissions (best effort)
    chmod 600 "$tmp_file" 2>/dev/null || true

    # Atomic move
    if mv "$tmp_file" "$NETREAPER_CONFIG_FILE"; then
        _config_log_success "Created configuration file: $NETREAPER_CONFIG_FILE"
        return 0
    else
        _config_log_error "Failed to create configuration file"
        rm -f "$tmp_file" 2>/dev/null
        return 1
    fi
}

# Load configuration from file
# Populates CONFIG array with defaults + file values
# Preserves unknown keys in _CONFIG_EXTRA_KEYS
load_config() {
    local line=""
    local key=""
    local value=""

    # Start with defaults
    CONFIG=()
    for key in "${!DEFAULT_CONFIG[@]}"; do
        CONFIG["$key"]="${DEFAULT_CONFIG[$key]}"
    done

    # Clear extra keys
    _CONFIG_EXTRA_KEYS=()

    # If config file doesn't exist, we're done (using defaults)
    if [[ ! -f "$NETREAPER_CONFIG_FILE" ]]; then
        _config_log_debug "Config file not found, using defaults"
        return 0
    fi

    _config_log_debug "Loading configuration from: $NETREAPER_CONFIG_FILE"

    # Parse config file line by line
    while IFS= read -r line || [[ -n "$line" ]]; do
        # Skip empty lines
        [[ -z "$line" ]] && continue

        # Skip comment lines (after trimming leading whitespace)
        local trimmed_line
        trimmed_line=$(_config_trim "$line")
        [[ "$trimmed_line" == \#* ]] && continue

        # Parse key=value
        if [[ "$trimmed_line" == *=* ]]; then
            # Split on first = only
            key="${trimmed_line%%=*}"
            value="${trimmed_line#*=}"

            # Trim whitespace
            key=$(_config_trim "$key")
            value=$(_config_trim "$value")

            # Skip if key is empty
            [[ -z "$key" ]] && continue

            # Store in CONFIG
            CONFIG["$key"]="$value"

            # Track if this is an extra key (not in defaults)
            if [[ -z "${DEFAULT_CONFIG[$key]+isset}" ]]; then
                _CONFIG_EXTRA_KEYS["$key"]="$value"
                _config_log_debug "Found extra config key: $key"
            fi
        fi
    done < "$NETREAPER_CONFIG_FILE"

    _config_log_debug "Configuration loaded: ${#CONFIG[@]} keys"
    return 0
}

# Write configuration to file (atomic)
# Preserves unknown keys and maintains structure
_config_write_file() {
    local tmp_file=""
    local key=""
    local written_keys=()

    tmp_file="${NETREAPER_CONFIG_FILE}.tmp.$$"

    # Build the config file content
    {
        echo "# ═══════════════════════════════════════════════════════════════════════════════"
        echo "# NETREAPER Configuration"
        echo "# ═══════════════════════════════════════════════════════════════════════════════"
        echo "#"
        echo "# This file contains persistent settings for NETREAPER."
        echo "# Edit manually or use: netreaper config set <key> <value>"
        echo "#"
        echo "# ═══════════════════════════════════════════════════════════════════════════════"
        echo ""

        # Write default keys in defined order
        for key in "${DEFAULT_CONFIG_KEYS[@]}"; do
            if [[ -n "${CONFIG[$key]+isset}" ]]; then
                echo "$key=${CONFIG[$key]}"
            else
                echo "$key=${DEFAULT_CONFIG[$key]}"
            fi
            written_keys+=("$key")
        done

        # Write any extra keys (sorted for consistency)
        local extra_keys=()
        for key in "${!_CONFIG_EXTRA_KEYS[@]}"; do
            extra_keys+=("$key")
        done

        if [[ ${#extra_keys[@]} -gt 0 ]]; then
            echo ""
            echo "# Custom settings"
            # Sort extra keys
            local sorted_extra=()
            mapfile -t sorted_extra < <(printf '%s\n' "${extra_keys[@]}" | sort)
            for key in "${sorted_extra[@]}"; do
                echo "$key=${CONFIG[$key]}"
            done
        fi

    } > "$tmp_file"

    # Set restrictive permissions
    chmod 600 "$tmp_file" 2>/dev/null || true

    # Atomic move
    if mv "$tmp_file" "$NETREAPER_CONFIG_FILE"; then
        return 0
    else
        _config_log_error "Failed to write configuration file"
        rm -f "$tmp_file" 2>/dev/null
        return 1
    fi
}

#═══════════════════════════════════════════════════════════════════════════════
# PUBLIC API
#═══════════════════════════════════════════════════════════════════════════════

# Initialize configuration system
# Creates config directory and file if needed, then loads config
init_config() {
    # Ensure config directory exists
    if declare -f ensure_dir &>/dev/null; then
        ensure_dir "$NETREAPER_CONFIG_DIR"
    else
        if [[ ! -d "$NETREAPER_CONFIG_DIR" ]]; then
            if ! mkdir -p "$NETREAPER_CONFIG_DIR" 2>/dev/null; then
                _config_log_error "Failed to create config directory: $NETREAPER_CONFIG_DIR"
                return 1
            fi
            chmod 700 "$NETREAPER_CONFIG_DIR" 2>/dev/null || true
        fi
    fi

    # Create default config if file doesn't exist
    if [[ ! -f "$NETREAPER_CONFIG_FILE" ]]; then
        create_default_config || return 1
    fi

    # Load configuration
    load_config

    _config_log_debug "Configuration system initialized"
    return 0
}

# Get a configuration value
# Args: $1 = key
# Returns: value (prints to stdout), empty if not found
config_get() {
    local key="$1"

    [[ -z "$key" ]] && return 1

    # Check CONFIG first
    if [[ -n "${CONFIG[$key]+isset}" ]]; then
        echo "${CONFIG[$key]}"
        return 0
    fi

    # Fall back to default
    if [[ -n "${DEFAULT_CONFIG[$key]+isset}" ]]; then
        echo "${DEFAULT_CONFIG[$key]}"
        return 0
    fi

    # Key not found
    return 1
}

# Set a configuration value
# Args: $1 = key, $2 = value
# Updates both in-memory CONFIG and persists to file (atomic)
config_set() {
    local key="$1"
    local value="$2"

    if [[ -z "$key" ]]; then
        _config_log_error "config_set: key is required"
        return 1
    fi

    # Update in-memory config
    CONFIG["$key"]="$value"

    # Track as extra key if not in defaults
    if [[ -z "${DEFAULT_CONFIG[$key]+isset}" ]]; then
        _CONFIG_EXTRA_KEYS["$key"]="$value"
    fi

    # Write to file (atomic)
    if _config_write_file; then
        _config_log_success "Configuration updated: $key=$value"
        return 0
    else
        return 1
    fi
}

# Edit configuration file interactively
# Opens editor and reloads after
# Returns: 1 in non-interactive mode or on error
config_edit() {
    local editor=""

    # Check for non-interactive mode
    if [[ "${NR_NON_INTERACTIVE:-0}" == "1" ]]; then
        _config_log_error "config_edit: not available in non-interactive mode"
        return 1
    fi

    # Check for TTY
    if [[ ! -t 0 ]] || [[ ! -t 1 ]]; then
        _config_log_error "config_edit: requires interactive terminal"
        return 1
    fi

    # Determine editor
    editor="${EDITOR:-nano}"

    # Check if editor exists
    if ! command -v "$editor" &>/dev/null; then
        _config_log_error "Editor not found: $editor"
        _config_log_info "Set EDITOR environment variable to your preferred editor"
        return 1
    fi

    # Ensure config file exists
    if [[ ! -f "$NETREAPER_CONFIG_FILE" ]]; then
        create_default_config || return 1
    fi

    _config_log_info "Opening configuration in $editor..."

    # Open editor
    if "$editor" "$NETREAPER_CONFIG_FILE"; then
        # Reload configuration
        load_config
        _config_log_success "Configuration reloaded"
        return 0
    else
        _config_log_error "Editor exited with error"
        return 1
    fi
}

# Display current configuration
# Uses draw_header if available for pretty output
config_show() {
    local key=""
    local value=""
    local default_value=""
    local is_default=""

    # Draw header
    if declare -f draw_header &>/dev/null; then
        draw_header "Configuration"
    else
        echo ""
        echo "═══════════════════════════════════════════════════════════════════════════════"
        echo "  Configuration"
        echo "═══════════════════════════════════════════════════════════════════════════════"
    fi

    echo ""
    echo "  Config file: $NETREAPER_CONFIG_FILE"
    echo ""

    # Collect all keys and sort them
    local all_keys=()
    for key in "${!CONFIG[@]}"; do
        all_keys+=("$key")
    done
    local sorted_keys=()
    mapfile -t sorted_keys < <(printf '%s\n' "${all_keys[@]}" | sort)

    # Display each key
    for key in "${sorted_keys[@]}"; do
        value="${CONFIG[$key]}"

        # Check if it's the default value
        if [[ -n "${DEFAULT_CONFIG[$key]+isset}" ]]; then
            default_value="${DEFAULT_CONFIG[$key]}"
            if [[ "$value" == "$default_value" ]]; then
                is_default=" (default)"
            else
                is_default=" (modified)"
            fi
        else
            is_default=" (custom)"
        fi

        # Color coding if available
        if [[ -n "${C_CYAN:-}" ]]; then
            echo -e "  ${C_CYAN}${key}${C_RESET}=${C_GREEN}${value}${C_RESET}${C_DIM}${is_default}${C_RESET}"
        else
            echo "  ${key}=${value}${is_default}"
        fi
    done

    echo ""
}

# Reset configuration to defaults
# Removes config file and reinitializes
config_reset() {
    _config_log_info "Resetting configuration to defaults..."

    # Remove existing config file
    if [[ -f "$NETREAPER_CONFIG_FILE" ]]; then
        rm -f "$NETREAPER_CONFIG_FILE"
    fi

    # Clear in-memory config
    CONFIG=()
    _CONFIG_EXTRA_KEYS=()

    # Reinitialize with defaults
    create_default_config || return 1
    load_config

    _config_log_success "Configuration reset to defaults"
    return 0
}

# Get configuration file path
# Returns: path to config file
config_path() {
    echo "$NETREAPER_CONFIG_FILE"
}

# List all configuration keys
# Returns: newline-separated list of keys
config_keys() {
    local key=""
    for key in "${!CONFIG[@]}"; do
        echo "$key"
    done | sort
}

# Check if a configuration key exists
# Args: $1 = key
# Returns: 0 if exists, 1 if not
config_has() {
    local key="$1"
    [[ -n "${CONFIG[$key]+isset}" ]]
}

#═══════════════════════════════════════════════════════════════════════════════
# EXPORT FUNCTIONS
#═══════════════════════════════════════════════════════════════════════════════

# Configuration initialization
export -f init_config create_default_config load_config

# Configuration access
export -f config_get config_set config_edit config_show config_reset
export -f config_path config_keys config_has

# Helper functions
export -f config_is_true config_resolve_bool

# Export path variables
export NETREAPER_HOME NETREAPER_CONFIG_DIR NETREAPER_CONFIG_FILE
