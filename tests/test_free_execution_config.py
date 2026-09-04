import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class FreeExecutionConfigTests(unittest.TestCase):
    def test_zero_charge_manifest(self):
        policy = json.loads((ROOT / "environment-manifest.json").read_text())["execution_cost_policy"]
        self.assertEqual(policy["spend_policy"], "FREE_ONLY")
        self.assertEqual(policy["maximum_new_service_charge"], 0)
        self.assertIs(policy["billing_activation_allowed"], False)
        self.assertIs(policy["paid_overage_authorized"], False)

    def test_monthly_tier_is_not_selected_as_permanent_solution(self):
        policy = json.loads((ROOT / "environment-manifest.json").read_text())["execution_cost_policy"]
        self.assertIs(policy["monthly_free_tier_is_permanent_solution"], False)
        self.assertEqual(policy["execution_route"], "GITHUB_PUBLIC_STANDARD")
        self.assertFalse((ROOT / "codemagic.yaml").exists())

    def test_every_hosted_job_is_guarded_before_runner_allocation(self):
        for path in (ROOT / ".github/workflows").glob("*.yml"):
            text = path.read_text().split("\njobs:\n", 1)[1]
            jobs = re.split(r"(?m)^  [a-z][a-z0-9-]*:\s*$", text)[1:]
            self.assertTrue(jobs)
            for job in jobs:
                header = job.split("    steps:", 1)[0]
                self.assertIn("github.event.repository.private == false", header, path.name)
                self.assertIn("github.repository == 'jawadresearchai-creator/appfusion-product-public'", header, path.name)
                self.assertRegex(header, r"runs-on: (ubuntu-24\.04|macos-15)\s")

    def test_no_private_checkout_or_secrets_and_no_duplicate_workflows(self):
        paths = list((ROOT / ".github/workflows").glob("*.yml"))
        self.assertEqual(len(paths), 1)
        text = paths[0].read_text()
        self.assertNotIn("secrets.", text)
        self.assertNotIn("pull_request_target", text)
        self.assertIn("cache-disabled: true", text)
        self.assertIn("run: python scripts/verify_product.py", text)
        self.assertNotIn('base="HEAD^"', text)
