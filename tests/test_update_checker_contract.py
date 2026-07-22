from pathlib import Path
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


class UpdateCheckerContractTest(unittest.TestCase):
    def test_attempt_is_recorded_before_background_request(self):
        source = SOURCE.read_text(encoding="utf-8")
        stamp = "prefs.edit().putLong(KEY_LAST_CHECK, now).apply()"
        thread = "Thread {"
        fetch = "val info = fetchLatest() ?: return@Thread"

        self.assertEqual(source.count(stamp), 1)
        self.assertLess(source.index(stamp), source.index(thread))
        self.assertLess(source.index(thread), source.index(fetch))

    def test_success_path_does_not_own_the_rate_limit(self):
        source = SOURCE.read_text(encoding="utf-8")
        fetch = source.index("val info = fetchLatest() ?: return@Thread")
        tail = source[fetch:]

        self.assertNotIn("putLong(KEY_LAST_CHECK", tail)

    def test_force_bypasses_rate_limit(self):
        source = SOURCE.read_text(encoding="utf-8")
        self.assertIn("if (!force && now - last < CHECK_INTERVAL_MS)", source)


if __name__ == "__main__":
    unittest.main()
