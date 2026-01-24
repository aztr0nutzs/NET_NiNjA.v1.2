#!/usr/bin/env bash
# ═════════════════════════════════════════════════════════════════════════════
# NETREAPER – Resource Monitor
# ═════════════════════════════════════════════════════════════════════════════
# This script provides helper functions to monitor system CPU and memory
# utilisation during long‑running scans.  When high resource usage is
# detected a warning is logged and lite mode can be automatically
# enabled by setting the NR_LITE_MODE environment variable.  The
# functions are designed to be sourced by other modules; they do not
# execute anything on their own.

# Prevent multiple sourcing
[[ -n "${_NETREAPER_MONITOR_LOADED:-}" ]] && return 0
readonly _NETREAPER_MONITOR_LOADED=1

source "${BASH_SOURCE%/*}/core.sh"
source "${BASH_SOURCE%/*}/ui.sh"

# monitor_resources() – Check current CPU and RAM usage.  When
# either utilisation exceeds the supplied thresholds a warning is
# printed.  Returns 1 if usage is high, 0 otherwise.  Args: $1 =
# CPU threshold (default 90), $2 = memory threshold (default 90).
monitor_resources() {
    local cpu_threshold="${1:-90}" mem_threshold="${2:-90}"
    local high=false
    # Use /proc/stat and /proc/meminfo to compute CPU and memory load
    # to avoid spawning heavy processes like top.  Compute CPU
    # utilisation over a short interval.
    read -r cpu user nice system idle iowait irq softirq steal guest < /proc/stat
    local idle_prev="$idle" total_prev=$((user+nice+system+idle+iowait+irq+softirq+steal))
    sleep 0.5
    read -r cpu user nice system idle iowait irq softirq steal guest < /proc/stat
    local idle_now="$idle" total_now=$((user+nice+system+idle+iowait+irq+softirq+steal))
    local delta_idle=$((idle_now-idle_prev)) delta_total=$((total_now-total_prev))
    local cpu_usage=0
    if [[ $delta_total -gt 0 ]]; then
        cpu_usage=$((100*(delta_total-delta_idle)/delta_total))
    fi

    local mem_total mem_available
    while read -r key value unit; do
        case "$key" in
            MemTotal:) mem_total="$value" ;;
            MemAvailable:) mem_available="$value" ;;
        esac
    done < /proc/meminfo
    local mem_used=$((mem_total-mem_available))
    local mem_usage=0
    if [[ $mem_total -gt 0 ]]; then
        mem_usage=$((100*mem_used/mem_total))
    fi

    if [[ $cpu_usage -ge $cpu_threshold ]]; then
        high=true
        log_warning "High CPU usage: ${cpu_usage}% (threshold: ${cpu_threshold}%)"
    fi
    if [[ $mem_usage -ge $mem_threshold ]]; then
        high=true
        log_warning "High memory usage: ${mem_usage}% (threshold: ${mem_threshold}%)"
    fi
    if [[ "$high" == true ]]; then
        return 1
    fi
    return 0
}

# monitor_loop() – Continuously monitor system resources every
# specified interval.  If usage remains high for 3 consecutive
# checks lite mode is automatically enabled by exporting
# NR_LITE_MODE=1.  This function is intended to run in the
# background (e.g. using &).  Args: $1 = interval seconds (default 10).
monitor_loop() {
    local interval="${1:-10}" high_count=0
    while true; do
        if monitor_resources; then
            high_count=0
        else
            high_count=$((high_count+1))
            if [[ $high_count -ge 3 && "${NR_LITE_MODE:-0}" != "1" ]]; then
                log_warning "Resource limits exceeded repeatedly – enabling lite mode"
                export NR_LITE_MODE=1
            fi
        fi
        sleep "$interval"
    done
}

export -f monitor_resources monitor_loop