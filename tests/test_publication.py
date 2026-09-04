import unittest
from scripts.verify_publication import check_text


class PublicationTests(unittest.TestCase):
    def test_private_storage_is_rejected(self):
        self.assertIn("private_storage_link", check_text("https://" + "drive.google.com/file/d/example"))

    def test_private_repository_is_rejected(self):
        self.assertIn("private_repository", check_text("https://github.com/owner/" + "appfusion-product"))
        self.assertEqual(check_text("https://github.com/owner/appfusion-product-public"), [])

    def test_secret_like_material_is_rejected_without_printing_it(self):
        self.assertIn("github_token", check_text("gh" + "p_" + "A" * 36))
        self.assertIn("private_key", check_text("-----BEGIN " + "PRIVATE KEY-----"))

    def test_local_path_is_rejected(self):
        self.assertIn("local_user_path", check_text("C:" + "\\Users\\Example"))
