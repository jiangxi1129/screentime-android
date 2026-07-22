from pathlib import Path
import re
import unittest


ROOT = Path(__file__).parents[1]


class VersionClockContractTest(unittest.TestCase):
    def test_ci_build_and_release_tag_share_github_run_number(self):
        gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        workflow = (ROOT / ".github" / "workflows" / "build.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn('environmentVariable("GITHUB_RUN_NUMBER")', gradle)
        self.assertRegex(gradle, r"versionCode\s*=\s*ciVersionCode\s*\?:\s*\d+")
        self.assertIn("tag_name: build-${{ github.run_number }}", workflow)
        self.assertIn("screentime-${{ github.run_number }}.apk", workflow)

    def test_update_checker_reads_the_same_release_clock(self):
        checker = (
            ROOT
            / "app"
            / "src"
            / "main"
            / "java"
            / "top"
            / "xixiclaire"
            / "screentime"
            / "UpdateChecker.kt"
        ).read_text(encoding="utf-8")
        self.assertIn('Regex("""build-(\\d+)""")', checker)

    def test_local_fallback_is_not_older_than_latest_existing_release(self):
        gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        fallback = re.search(r"versionCode\s*=\s*ciVersionCode\s*\?:\s*(\d+)", gradle)

        self.assertIsNotNone(fallback)
        self.assertGreaterEqual(int(fallback.group(1)), 29)


if __name__ == "__main__":
    unittest.main()
