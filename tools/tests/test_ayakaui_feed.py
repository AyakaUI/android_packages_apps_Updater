# SPDX-FileCopyrightText: 2026 PixelOS
# SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "ayakaui_feed.py"
SPEC = importlib.util.spec_from_file_location("ayakaui_feed", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
ayakaui_feed = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ayakaui_feed)


class AyakaUIFeedTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_directory.cleanup)
        self.directory = Path(self.temp_directory.name)

    def create_ota(self, metadata_overrides: dict[str, str] | None = None) -> Path:
        metadata = {
            "post-timestamp": "1781858358",
            "post-security-patch-level": "2026-06-01",
            "post-sdk-level": "36",
            "ota-property-files": (
                "payload_metadata.bin:0:1,payload.bin:1:2,"
                "payload_properties.txt:3:1"
            ),
        }
        metadata.update(metadata_overrides or {})
        artifact = self.directory / "AyakaUI_device-17.0-20260619-0000.zip"
        rendered = "\n".join(f"{key}={value}" for key, value in metadata.items()) + "\n"
        with zipfile.ZipFile(artifact, "w") as archive:
            archive.writestr(ayakaui_feed.OTA_METADATA_PATH, rendered)
            archive.writestr("payload.bin", b"payload")
        return artifact

    def test_generate_and_validate_ota_feed(self) -> None:
        artifact = self.create_ota()
        feed = ayakaui_feed.build_ota_feed(
            artifact,
            "https://downloads.ayakaui.net/device/update.zip",
            "17.0",
        )

        ayakaui_feed.validate_ota_feed(feed, artifact=artifact)
        update = feed[0]
        self.assertEqual(1781858358, update["datetime"])
        self.assertEqual(ayakaui_feed.sha256_file(artifact), update["files"][0]["sha256"])
        self.assertEqual(artifact.stat().st_size, update["files"][0]["size"])

    def test_ota_feed_rejects_more_than_one_file(self) -> None:
        feed = ayakaui_feed.build_ota_feed(
            self.create_ota(),
            "https://downloads.ayakaui.net/device/update.zip",
            "17.0",
        )
        feed[0]["files"].append(dict(feed[0]["files"][0]))

        with self.assertRaisesRegex(ayakaui_feed.ValidationError, "exactly one"):
            ayakaui_feed.validate_ota_feed(feed)

    def test_ota_feed_rejects_uppercase_hash(self) -> None:
        feed = ayakaui_feed.build_ota_feed(
            self.create_ota(),
            "https://downloads.ayakaui.net/device/update.zip",
            "17.0",
        )
        feed[0]["files"][0]["sha256"] = "A" * 64

        with self.assertRaisesRegex(ayakaui_feed.ValidationError, "lowercase"):
            ayakaui_feed.validate_ota_feed(feed)

    def test_ota_feed_rejects_unknown_keys(self) -> None:
        feed = ayakaui_feed.build_ota_feed(
            self.create_ota(),
            "https://downloads.ayakaui.net/device/update.zip",
            "17.0",
        )
        feed[0]["unsupported"] = True

        with self.assertRaisesRegex(ayakaui_feed.ValidationError, "unknown keys"):
            ayakaui_feed.validate_ota_feed(feed)

    def test_ota_ranges_must_fit_artifact(self) -> None:
        artifact = self.create_ota(
            {"ota-property-files": "payload_metadata.bin:0:1,payload.bin:1:999999,payload_properties.txt:3:1"}
        )

        with self.assertRaisesRegex(ayakaui_feed.ValidationError, "exceeds package size"):
            ayakaui_feed.build_ota_feed(
                artifact,
                "https://downloads.ayakaui.net/device/update.zip",
                "17.0",
            )

    def test_artifact_comparison_detects_hash_mismatch(self) -> None:
        artifact = self.create_ota()
        feed = ayakaui_feed.build_ota_feed(
            artifact,
            "https://downloads.ayakaui.net/device/update.zip",
            "17.0",
        )
        feed[0]["files"][0]["sha256"] = "0" * 64

        with self.assertRaisesRegex(ayakaui_feed.ValidationError, "does not match artifact"):
            ayakaui_feed.validate_ota_feed(feed, artifact=artifact)

if __name__ == "__main__":
    unittest.main()
