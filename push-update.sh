#!/bin/sh

# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-FileCopyrightText: 2026 PixelOS
# SPDX-License-Identifier: Apache-2.0

set -u

updates_dir=/data/system_updates
package_name=net.pixelos.ota
database_path=/data/user/0/$package_name/databases/updates.db
expected_database_version=4
root_enabled=0

usage() {
    echo "Usage: $0 ZIP [UNVERIFIED] [SERIAL]"
    echo "Push a PixelOS OTA ZIP to $updates_dir and register it with Updater."
    echo
    echo "Expected filename: PixelOS_DEVICE-VERSION-*.zip"
    echo "Set UNVERIFIED to any non-empty value to make Updater verify the package."
}

die() {
    echo "Error: $*" >&2
    exit 1
}

adb_cmd() {
    if [ -n "$serial" ]; then
        adb -s "$serial" "$@"
    else
        adb "$@"
    fi
}

cleanup() {
    exit_status=$?
    trap - 0 HUP INT TERM
    if [ -n "${temporary_directory-}" ] && [ -d "$temporary_directory" ]; then
        rm -r "$temporary_directory"
    fi
    if [ "$root_enabled" -eq 1 ]; then
        adb_cmd unroot >/dev/null 2>&1 || true
    fi
    exit "$exit_status"
}

metadata_value() {
    awk -v wanted="$1" '
        index($0, wanted "=") == 1 {
            if (found) exit 2
            sub(/^[^=]*=/, "")
            print
            found = 1
        }
        END { if (!found) exit 1 }
    ' "$metadata_file"
}

ota_range() {
    printf '%s\n' "$ota_property_files" | tr ',' '\n' | awk \
        -F: -v wanted="$1" -v package_size="$size" '
        function trim(value) {
            sub(/^[[:space:]]+/, "", value)
            sub(/[[:space:]]+$/, "", value)
            return value
        }
        {
            name = trim($1)
            offset = trim($2)
            length = trim($3)
        }
        name == wanted {
            if (found || offset !~ /^[0-9]+$/ || length !~ /^[0-9]+$/ ||
                    length == 0 || offset + length > package_size) {
                exit 2
            }
            print offset, length
            found = 1
        }
        END { if (!found) exit 1 }
    '
}

case "${1-}" in
    -h|--help)
        usage
        exit 0
        ;;
esac

[ -n "${1-}" ] || { usage >&2; exit 2; }
[ -f "$1" ] || die "OTA ZIP does not exist: $1"
command -v adb >/dev/null 2>&1 || die "adb is not installed"
command -v unzip >/dev/null 2>&1 || die "unzip is not installed"
command -v python3 >/dev/null 2>&1 || die "python3 is not installed"

zip_directory=$(dirname -- "$1")
zip_name=$(basename -- "$1")
zip_path=$(cd "$zip_directory" 2>/dev/null && pwd -P)/$zip_name
serial=${3-}

case "$zip_name" in
    PixelOS_*-*-*.zip) ;;
    *) die "Filename must match PixelOS_DEVICE-VERSION-*.zip" ;;
esac

case "$zip_name" in
    *[!A-Za-z0-9._-]*) die "Filename contains unsupported characters" ;;
esac

name_remainder=${zip_name#PixelOS_}
ota_device=${name_remainder%%-*}
name_remainder=${name_remainder#*-}
version=${name_remainder%%-*}
[ -n "$ota_device" ] || die "Unable to parse device from filename"
[ -n "$version" ] || die "Unable to parse version from filename"

temporary_directory=$(mktemp -d "${TMPDIR:-/tmp}/pixelos-updater.XXXXXX") || \
    die "Unable to create temporary directory"
metadata_file=$temporary_directory/metadata
trap cleanup 0
trap 'exit 1' HUP INT TERM

unzip -p "$zip_path" META-INF/com/android/metadata >"$metadata_file" || \
    die "Unable to read OTA metadata"
[ -s "$metadata_file" ] || die "OTA metadata is empty"

timestamp=$(metadata_value post-timestamp) || die "Missing or duplicate post-timestamp"
os_patch_level=$(metadata_value post-security-patch-level) || \
    die "Missing or duplicate post-security-patch-level"
os_sdk_level=$(metadata_value post-sdk-level) || die "Missing or duplicate post-sdk-level"
ota_property_files=$(metadata_value ota-property-files 2>/dev/null || true)

case "$timestamp" in *[!0-9]*|'') die "Invalid post-timestamp" ;; esac
case "$os_sdk_level" in *[!0-9]*|'') die "Invalid post-sdk-level" ;; esac
case "$os_patch_level" in
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;;
    *) die "Invalid post-security-patch-level" ;;
esac

size=$(wc -c <"$zip_path" | tr -d '[:space:]')
[ "$size" -gt 0 ] 2>/dev/null || die "OTA ZIP is empty"
if command -v sha256sum >/dev/null 2>&1; then
    download_id=$(sha256sum "$zip_path" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
    download_id=$(shasum -a 256 "$zip_path" | awk '{print $1}')
else
    die "sha256sum or shasum is required"
fi

if [ "$(adb_cmd get-state 2>/dev/null)" != "device" ]; then
    echo "No device found. Waiting for one..."
    adb_cmd wait-for-device || die "Unable to connect to a device"
fi
adb_cmd root || die "Could not run adbd as root"
root_enabled=1
adb_cmd wait-for-device || die "Device did not reconnect after adb root"

connected_device=$(adb_cmd shell getprop ro.custom.device | tr -d '\r')
if [ -n "$connected_device" ] && [ "$connected_device" != "$ota_device" ]; then
    die "OTA is for $ota_device, but connected device is $connected_device"
fi

build_type=$(adb_cmd shell getprop net.pixelos.build_type | tr -d '\r')
[ -n "$build_type" ] || build_type=ci
case "$build_type" in *[!A-Za-z0-9._-]*) die "Invalid net.pixelos.build_type" ;; esac

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
python3 "$script_directory/tools/pixelos_feed.py" generate-ota "$zip_path" \
    --url "https://localhost/$zip_name" \
    --version "$version" \
    --type "$build_type" >/dev/null || die "OTA metadata validation failed"

payload_metadata_offset=NULL
payload_metadata_size=NULL
payload_offset=NULL
payload_size=NULL
payload_properties_offset=NULL
payload_properties_size=NULL
if [ -n "$ota_property_files" ]; then
    payload_metadata_range=$(ota_range payload_metadata.bin) || \
        die "Invalid payload_metadata.bin range"
    payload_range=$(ota_range payload.bin) || die "Invalid payload.bin range"
    payload_properties_range=$(ota_range payload_properties.txt) || \
        die "Invalid payload_properties.txt range"
    payload_metadata_offset=${payload_metadata_range%% *}
    payload_metadata_size=${payload_metadata_range#* }
    payload_offset=${payload_range%% *}
    payload_size=${payload_range#* }
    payload_properties_offset=${payload_properties_range%% *}
    payload_properties_size=${payload_properties_range#* }
fi

adb_cmd shell "test -d '$updates_dir'" || \
    die "$updates_dir does not exist; boot a build containing init.pixelos-updater.rc"
adb_cmd shell "command -v sqlite3 >/dev/null" || die "sqlite3 is unavailable on the device"
adb_cmd shell "test -f '$database_path'" || \
    die "Updater database does not exist; launch Updater once before using this script"
database_version=$(adb_cmd shell "sqlite3 '$database_path' 'PRAGMA user_version;'" | tr -d '\r')
[ "$database_version" = "$expected_database_version" ] || \
    die "Unsupported Updater database version: $database_version (expected $expected_database_version)"
existing_rows=$(adb_cmd shell \
    "sqlite3 '$database_path' \"SELECT COUNT(*) FROM updates WHERE download_id = '$download_id';\"" | \
    tr -d '\r')
[ "$existing_rows" = 0 ] || die "Updater already contains this OTA package"

zip_path_device=$updates_dir/$zip_name
if adb_cmd shell "test -e '$zip_path_device'"; then
    die "$zip_path_device already exists"
fi

if [ -n "${2-}" ]; then
    status=1
else
    status=2
fi

adb_cmd push "$zip_path" "$zip_path_device" || die "Failed to push OTA ZIP"
adb_cmd shell "chown system:cache '$zip_path_device' && chmod 0664 '$zip_path_device'" || \
    die "Failed to set OTA ZIP ownership or permissions"

adb_cmd shell "am force-stop '$package_name'" || die "Failed to stop Updater"
sql="INSERT INTO updates (download_id, status, path, timestamp, type, version, size, name, os_patch_level, os_sdk_level, payload_metadata_offset, payload_metadata_size, payload_offset, payload_size, payload_properties_offset, payload_properties_size, download_url) VALUES ('$download_id', $status, '$zip_path_device', $timestamp, '$build_type', '$version', $size, '$zip_name', '$os_patch_level', $os_sdk_level, $payload_metadata_offset, $payload_metadata_size, $payload_offset, $payload_size, $payload_properties_offset, $payload_properties_size, NULL);"
adb_cmd shell "sqlite3 '$database_path' \"$sql\"" || \
    die "Failed to add OTA ZIP to the Updater database; pushed file remains at $zip_path_device"

echo "Registered $zip_name with PixelOS Updater."
