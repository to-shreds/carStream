"""Cross-repository snapshot tests. Set CARSTREAM_CANONICAL_REPO to a full clone."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('web_sync', ROOT / 'scripts/sync_torbox_web.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class WebSyncTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.canonical = Path(os.environ['CARSTREAM_CANONICAL_REPO']).resolve()
        cls.revision = (ROOT / 'web/TORBOX_WEB_REVISION').read_text().strip()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='carstream-sync-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'carstream'
        (self.root / 'web').mkdir(parents=True)
        (self.root / 'web/TORBOX_WEB_REVISION').write_text(self.revision + '\n')
        self.destination = self.root / module.GENERATED

    def update(self):
        return module.sync(self.canonical, root=self.root, check=False)

    def test_update_and_check_are_deterministic(self):
        result = self.update()
        self.assertTrue(result['verified'])
        self.assertGreater(result['assets'], 20)
        first = module.inventory(self.destination)
        self.update()
        self.assertEqual(first, module.inventory(self.destination))
        module.sync(self.canonical, root=self.root, check=True)

    def test_missing_modified_and_extra_assets_are_rejected(self):
        self.update()
        for operation in ['modified', 'missing', 'extra']:
            with self.subTest(operation=operation):
                path = self.destination / 'app.js'
                if operation == 'modified': path.write_text('// independently edited UI')
                elif operation == 'missing': path.unlink()
                else: (self.destination / 'unreviewed.js').write_text('// extra')
                with self.assertRaises(ValueError):
                    module.sync(self.canonical, root=self.root, check=True)
                self.update()

    def test_partial_revision_is_rejected(self):
        (self.root / 'web/TORBOX_WEB_REVISION').write_text(self.revision[:8])
        with self.assertRaises(ValueError): self.update()
        self.assertFalse(self.destination.exists())

    def test_unmanaged_directory_is_preserved(self):
        self.destination.mkdir()
        sentinel = self.destination / 'keep.txt'
        sentinel.write_text('preserve me')
        with self.assertRaises(ValueError): self.update()
        self.assertEqual(sentinel.read_text(), 'preserve me')

    def test_symlink_destination_is_preserved(self):
        outside = self.root / 'outside'
        outside.mkdir()
        self.destination.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError): self.update()
        self.assertTrue(self.destination.is_symlink())
        self.assertEqual(list(outside.iterdir()), [])

    def test_failed_replacement_restores_previous_snapshot(self):
        self.update()
        before = module.inventory(self.destination)
        replace = os.replace
        def fail_new(source, target):
            if Path(source).name == 'new': raise OSError('simulated rename failure')
            return replace(source, target)
        with patch.object(module.os, 'replace', side_effect=fail_new):
            with self.assertRaises(OSError): self.update()
        self.assertEqual(before, module.inventory(self.destination))

    def test_unrelated_android_assets_remain_unchanged(self):
        legacy = self.root / 'app/src/main/assets/index.html'
        legacy.parent.mkdir(parents=True)
        legacy.write_text('existing fallback')
        self.update()
        self.assertEqual(legacy.read_text(), 'existing fallback')

    def test_dirty_canonical_files_and_builder_do_not_change_snapshot(self):
        clone = self.root / 'canonical-worktree'
        subprocess.run(['git', 'clone', '--quiet', '--no-hardlinks', str(self.canonical), str(clone)], check=True)
        (clone / 'tools').mkdir(exist_ok=True)
        (clone / 'tools/build-carstream.mjs').write_text("throw new Error('dirty builder must never execute');")
        (clone / 'public/app.js').write_text('// dirty UI must not ship')
        self.update()
        expected = module.inventory(self.destination)
        module.sync(clone, root=self.root, check=False)
        self.assertEqual(expected, module.inventory(self.destination))


if __name__ == '__main__':
    unittest.main()
