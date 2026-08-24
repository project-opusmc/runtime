#!/usr/bin/env bash
set -euo pipefail

# Read-only guard for the isolated Forge 1.8.9 native UI proof lane. It never
# terminates processes or removes files: its only enforcement is to refuse a
# new action when another matching client is active or resources are unsafe.

mode="${1:-report}"
requested_path="${2:-}"
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
runtime_root=$(CDPATH= cd -- "${script_dir}/.." && pwd)
native_project_dir="${runtime_root}/legacy/1.8.9/native-client"

if [[ -z "${requested_path}" ]]; then
    requested_path="${native_project_dir}"
fi

positive_integer() {
    [[ "${1}" =~ ^[1-9][0-9]*$ ]]
}

require_threshold() {
    local value="${1}"
    local name="${2}"
    if ! positive_integer "${value}"; then
        echo "Native UI safety guard: ${name} must be a positive integer MiB value." >&2
        exit 64
    fi
}

ancestor_pids() {
    local ignored=" "
    local cursor="$$"
    while [[ "${cursor}" =~ ^[0-9]+$ && "${cursor}" -gt 1 ]]; do
        ignored+="${cursor} "
        cursor=$(ps -o ppid= -p "${cursor}" | tr -d '[:space:]')
    done
    printf '%s' "${ignored}"
}

matching_processes() {
    local ignored
    ignored=$(ancestor_pids)
    ps -axo pid=,ppid=,rss=,command= | awk -v ignored="${ignored}" '
        {
            pid = $1
            if (index(ignored, " " pid " ") != 0) {
                next
            }
            command = $0
            sub(/^[[:space:]]*[0-9]+[[:space:]]+[0-9]+[[:space:]]+[0-9]+[[:space:]]+/, "", command)
            if (command ~ /net\.minecraft\.launchwrapper\.Launch/ ||
                command ~ /net\.minecraft\.client\.main\.Main/ ||
                command ~ /GradleStart/ ||
                command ~ /cc\.polyfrost\.oneconfig\.internal\.plugin\.asm\.OneConfigTweaker/ ||
                (command ~ /org\.gradle\.launcher\.GradleMain/ &&
                    command ~ /legacy\/1\.8\.9\/native-client/)) {
                print pid "\t" $2 "\t" $3 " KiB\t" command
            }
        }
    '
}

available_memory_mib() {
    case "$(uname -s)" in
        Darwin)
            local vm page_size free inactive speculative
            vm=$(vm_stat)
            page_size=$(printf '%s\n' "${vm}" | sed -n '1s/.*page size of \([0-9][0-9]*\) bytes.*/\1/p')
            free=$(printf '%s\n' "${vm}" | awk -F ':' '$1 == "Pages free" { gsub(/[[:space:].]/, "", $2); print $2; exit }')
            inactive=$(printf '%s\n' "${vm}" | awk -F ':' '$1 == "Pages inactive" { gsub(/[[:space:].]/, "", $2); print $2; exit }')
            speculative=$(printf '%s\n' "${vm}" | awk -F ':' '$1 == "Pages speculative" { gsub(/[[:space:].]/, "", $2); print $2; exit }')
            if ! positive_integer "${page_size:-0}"; then
                echo 0
                return
            fi
            free=${free:-0}
            inactive=${inactive:-0}
            speculative=${speculative:-0}
            echo $(((free + inactive + speculative) * page_size / 1024 / 1024))
            ;;
        Linux)
            awk '/^MemAvailable:/ { print int($2 / 1024); exit }' /proc/meminfo
            ;;
        *)
            echo 0
            ;;
    esac
}

free_disk_mib() {
    local path="${1}"
    while [[ ! -e "${path}" && "${path}" != / ]]; do
        path=$(dirname "${path}")
    done
    df -Pk "${path}" | awk 'NR == 2 { print int($4 / 1024); exit }'
}

print_artifact_sizes() {
    local artifact
    for artifact in "${native_project_dir}/run" "${native_project_dir}/build" "${native_project_dir}/.gradle"; do
        if [[ -e "${artifact}" ]]; then
            du -sh "${artifact}"
        fi
    done
}

print_report() {
    local matches memory_mib disk_mib
    matches=$(matching_processes)
    memory_mib=$(available_memory_mib)
    disk_mib=$(free_disk_mib "${requested_path}")

    echo "Native UI safety report:"
    if [[ -n "${matches}" ]]; then
        echo "  matching process(es):"
        printf '%s\n' "${matches}"
    else
        echo "  matching process(es): none"
    fi
    echo "  estimated available memory: ${memory_mib} MiB"
    echo "  free disk at ${requested_path}: ${disk_mib} MiB"
    echo "  native-client artifacts:"
    if ! print_artifact_sizes | sed 's/^/    /'; then
        echo "    unavailable"
    fi
}

assert_no_matching_processes() {
    local matches
    matches=$(matching_processes)
    if [[ -n "${matches}" ]]; then
        echo "Native UI safety guard: a Minecraft/native-client process is already active." >&2
        printf '%s\n' "${matches}" >&2
        echo "No Gradle task, cache change, or client launch was started." >&2
        exit 1
    fi
}

assert_resources() {
    local required_memory_mib="${1}"
    local required_disk_mib="${2}"
    local memory_mib disk_mib
    require_threshold "${required_memory_mib}" "memory threshold"
    require_threshold "${required_disk_mib}" "disk threshold"
    memory_mib=$(available_memory_mib)
    disk_mib=$(free_disk_mib "${requested_path}")
    if (( memory_mib < required_memory_mib )); then
        echo "Native UI safety guard: ${memory_mib} MiB estimated memory is below the required ${required_memory_mib} MiB." >&2
        echo "No Gradle task, cache change, or client launch was started." >&2
        exit 1
    fi
    if (( disk_mib < required_disk_mib )); then
        echo "Native UI safety guard: ${disk_mib} MiB free disk is below the required ${required_disk_mib} MiB." >&2
        echo "No Gradle task, cache change, or client launch was started." >&2
        exit 1
    fi
}

case "${mode}" in
    report)
        print_report
        ;;
    static)
        assert_no_matching_processes
        assert_resources "${OPUS_NATIVE_UI_MIN_BUILD_MEMORY_MIB:-2048}" "${OPUS_NATIVE_UI_MIN_BUILD_DISK_MIB:-2048}"
        print_report
        ;;
    run)
        assert_no_matching_processes
        assert_resources "${OPUS_NATIVE_UI_MIN_RUN_MEMORY_MIB:-6144}" "${OPUS_NATIVE_UI_MIN_RUN_DISK_MIB:-8192}"
        print_report
        ;;
    clean)
        if [[ "${OPUS_NATIVE_UI_ALLOW_CLEAN:-}" != "1" ]]; then
            echo "Native UI safety guard: clean is disabled by default." >&2
            echo "Set OPUS_NATIVE_UI_ALLOW_CLEAN=1 only after approving the exact isolated native-client targets." >&2
            exit 1
        fi
        assert_no_matching_processes
        assert_resources "${OPUS_NATIVE_UI_MIN_BUILD_MEMORY_MIB:-2048}" "${OPUS_NATIVE_UI_MIN_BUILD_DISK_MIB:-2048}"
        print_report
        ;;
    *)
        echo "Usage: $0 {report|static|run|clean} [disk-path]" >&2
        exit 64
        ;;
esac
