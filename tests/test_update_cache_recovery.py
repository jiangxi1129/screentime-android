from pathlib import Path
import re
import unittest


SOURCE = (
    Path(__file__).parents[1]
    / "app"
    / "src"
    / "main"
    / "java"
    / "top"
    / "xixiclaire"
    / "screentime"
    / "UpdateChecker.kt"
)


class UpdateCacheRecoveryContractTest(unittest.TestCase):
    def test_only_a_present_cached_apk_short_circuits_download(self):
        source = SOURCE.read_text(encoding="utf-8")
        notified = source.index("if (alreadyNotified == info.versionCode)")
        present = source.index("if (cached.exists() && cached.length() > 0)", notified)
        short_circuit = source.index("return@Thread", present)
        download = source.index("val apk = downloadApk(ctx, info)", short_circuit)

        self.assertLess(notified, present)
        self.assertLess(present, short_circuit)
        self.assertLess(short_circuit, download)

    def test_missing_cache_falls_through_to_existing_download_path(self):
        source = SOURCE.read_text(encoding="utf-8")
        pattern = re.compile(
            r"if \(cached\.exists\(\) && cached\.length\(\) > 0\) \{"
            r"\s+showNotification\(ctx, cached, info\)"
            r"\s+return@Thread"
            r"\s+\}"
            r"\s+\}"
            r"\s+val apk = downloadApk\(ctx, info\)"
        )

        self.assertRegex(source, pattern)


if __name__ == "__main__":
    unittest.main()
