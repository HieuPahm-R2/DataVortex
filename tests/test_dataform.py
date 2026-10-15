import tempfile
import unittest
from pathlib import Path
from scripts.sync_dataform import check, generated


class DataformAssetsTests(unittest.TestCase):
    def test_generated_operations_match_the_warehouse_sql_owner(self):
        check()

    def test_stale_operations_fail_with_the_owner_and_recovery_command(self):
        with tempfile.TemporaryDirectory() as directory:
            copy = Path(directory) / 'campaign_sql.js'
            copy.write_text(generated(), encoding='utf-8')
            check(copy)
            copy.write_text('module.exports = {};\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, r'publish_campaign.sql; run python -m scripts.sync_dataform'):
                check(copy)
