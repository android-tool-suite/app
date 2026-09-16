import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "ats.py"
SPEC = importlib.util.spec_from_file_location("ats_runtime_v2", SCRIPT)
ats = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = ats
SPEC.loader.exec_module(ats)


class AtsCliTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ats-cli-test-")
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def create_project(self):
        project = self.root / "plugin"
        (project / "web").mkdir(parents=True)
        (project / "ui").mkdir(parents=True)
        manifest = {
            "format": "ats-plugin",
            "formatVersion": 3,
            "plugin": {
                "id": "sample.test_tool",
                "title": "测试工具",
                "description": "用于 CLI 单元测试。",
                "version": "1.0.0",
                "versionCode": 1,
                "minHostVersionCode": 23,
                "publisher": "android_tool_suite.tests",
                "kind": "tool",
            },
            "platforms": ["android"],
            "runtime": {
                "ui": [{"id": "main", "type": "declarative", "entry": "ui/main.json"}],
                "background": [],
                "providers": [],
            },
            "requires": {"plugins": [], "capabilities": []},
            "provides": {"capabilities": []},
            "contributes": {"tools": [{"id": "main", "uiEntry": "main"}], "homeWidgets": []},
            "datasets": [],
            "tasks": [],
        }
        (project / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        (project / "ui" / "main.json").write_text(
            json.dumps({
                "formatVersion": 1,
                "body": {"type": "webview", "entry": "web/index.html"},
            }, ensure_ascii=False),
            encoding="utf-8",
        )
        (project / "web" / "index.html").write_text("<!doctype html><title>Test</title>", encoding="utf-8")
        return project

    def test_pack_is_deterministic_and_verifiable(self):
        project = self.create_project()
        first = self.root / "first.atsplugin"
        second = self.root / "second.atsplugin"

        ats.pack(str(project), str(first), None)
        ats.pack(str(project), str(second), None)

        self.assertEqual(first.read_bytes(), second.read_bytes())
        summary = ats.verify(str(first), None, False)
        self.assertEqual("sample.test_tool", summary["pluginId"])
        self.assertEqual("absent", summary["signature"])

    def test_manifest_reference_must_exist(self):
        project = self.create_project()
        (project / "web" / "index.html").unlink()

        with self.assertRaisesRegex(ats.AtsError, "载荷不存在"):
            ats.pack(str(project), str(self.root / "missing.atsplugin"), None)

    def test_ordinary_tool_cannot_hide_native_provider_payload(self):
        project = self.create_project()
        (project / "android").mkdir()
        (project / "android" / "provider.apk").write_bytes(b"not-an-apk")

        with self.assertRaisesRegex(ats.AtsError, "不得夹带"):
            ats.pack(str(project), str(self.root / "hidden-provider.atsplugin"), None)

    def test_ordinary_plugin_can_provide_capability_from_required_worker(self):
        project = self.create_project()
        manifest_path = project / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["runtime"]["background"] = [{
            "id": "echo-provider",
            "type": "javascript-worker",
            "entry": "workers/echo.js",
            "required": True,
        }]
        manifest["provides"]["capabilities"] = [{
            "id": "sample.echo",
            "version": "1.0.0",
            "workerEntry": "echo-provider",
            "methods": ["sample.echo.call"],
        }]
        manifest["requires"]["capabilities"] = [{
            "id": "sample.echo", "version": "^1.0.0", "optional": False, "scopes": {},
        }]
        manifest["contributes"]["homeWidgets"] = [{
            "id": "echo", "title": "Echo", "template": "status",
            "dataSource": "sample.echo.call", "sizes": ["2x1"],
        }]
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        (project / "workers").mkdir()
        (project / "workers" / "echo.js").write_text(
            "globalThis.atsWorkerMain=async input=>({value:input.payload.value});",
            encoding="utf-8",
        )

        package = self.root / "worker-provider.atsplugin"
        ats.pack(str(project), str(package), None)

        self.assertEqual("sample.test_tool", ats.verify(str(package), None, False)["pluginId"])

    def test_declarative_ui_is_validated_and_packaged(self):
        project = self.create_project()
        manifest_path = project / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["runtime"]["ui"] = [
            {"id": "main", "type": "declarative", "entry": "ui/main.json"}
        ]
        manifest["requires"]["capabilities"] = [
            {"id": "app", "version": "^1.0.0", "optional": False, "scopes": {}}
        ]
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        document = {
            "formatVersion": 1,
            "queries": [{
                "id": "session", "capability": "app", "method": "app.getSession",
                "payload": {}, "target": "session", "required": True,
            }],
            "actions": [{
                "id": "refresh", "capability": "app", "method": "app.getSession",
                "payload": {"platform": {"$state": "session.platform"}},
                "target": "session", "refresh": ["session"],
            }],
            "body": {
                "type": "column",
                "children": [{
                    "type": "card",
                    "children": [
                        {"type": "icon", "name": "check-circle", "tone": "success"},
                        {"type": "text", "value": {"path": "session.platform", "fallback": "未知"}},
                        {"type": "button", "label": "刷新", "action": "refresh"},
                    ],
                }],
            },
        }
        (project / "ui" / "main.json").write_text(
            json.dumps(document, ensure_ascii=False), encoding="utf-8"
        )
        package = self.root / "declarative.atsplugin"

        ats.pack(str(project), str(package), None)

        self.assertEqual("sample.test_tool", ats.verify(str(package), None, False)["pluginId"])
        document["actions"][0]["method"] = "storage.kv.get"
        (project / "ui" / "main.json").write_text(
            json.dumps(document, ensure_ascii=False), encoding="utf-8"
        )
        with self.assertRaisesRegex(ats.AtsError, "Capability 不匹配"):
            ats.pack(str(project), str(self.root / "invalid-declarative.atsplugin"), None)

    def test_tampered_payload_is_rejected(self):
        project = self.create_project()
        package = self.root / "valid.atsplugin"
        tampered = self.root / "tampered.atsplugin"
        ats.pack(str(project), str(package), None)
        with zipfile.ZipFile(package, "r") as source, zipfile.ZipFile(tampered, "w") as target:
            for info in source.infolist():
                data = source.read(info)
                if info.filename == "web/index.html":
                    data += b"tampered"
                target.writestr(info, data)

        with self.assertRaisesRegex(ats.AtsError, "完整性清单"):
            ats.verify(str(tampered), None, False)

    @unittest.skipUnless(shutil.which("openssl"), "openssl is unavailable")
    def test_signed_package_verifies(self):
        project = self.create_project()
        private_key = self.root / "private.pem"
        public_key = self.root / "public.pem"
        subprocess.run(
            ["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(private_key)],
            check=True,
            capture_output=True,
        )
        subprocess.run(
            ["openssl", "ec", "-in", str(private_key), "-pubout", "-out", str(public_key)],
            check=True,
            capture_output=True,
        )
        package = self.root / "signed.atsplugin"

        ats.pack(str(project), str(package), str(private_key))
        summary = ats.verify(str(package), str(public_key), True)

        self.assertEqual("verified", summary["signature"])

    def test_generation_outputs_are_current(self):
        ats.generate(check=True)

    def test_create_scaffolds_a_valid_packable_project(self):
        target = self.root / "created"
        arguments = type("Arguments", (), {
            "plugin_id": "sample.created_tool",
            "publisher": "android_tool_suite.tests",
            "title": "生成的工具",
            "description": "由 create 命令生成。",
            "directory": str(target),
            "min_host_version_code": 23,
        })()

        ats.command_create(arguments)
        manifest, manifest_bytes = ats.load_manifest(target / "manifest.json")
        files = ats.collect_project_files(target, manifest, manifest_bytes)
        package = self.root / "created.atsplugin"
        ats.pack(str(target), str(package), None)

        self.assertEqual("sample.created_tool", ats.verify(str(package), None, False)["pluginId"])
        self.assertIn("web/index.html", {item.path for item in files})

    def test_dev_injection_installs_transport_and_reload_channel(self):
        html = ats.inject_dev_html(b"<!doctype html><html><head></head><body></body></html>").decode("utf-8")

        self.assertIn("/__ats_dev/transport.js", html)
        self.assertIn("/__ats_dev/revision", html)

        native = ats.inject_dev_html(
            b"<!doctype html><html><head></head><body></body></html>",
            "sample.test_tool",
            "device-session",
        ).decode("utf-8")
        self.assertIn('content="device-session"', native)
        self.assertNotIn("/__ats_dev/transport.js", native)

    def test_repository_examples_are_packable(self):
        examples = SCRIPT.parents[2] / "examples" / "plugins"
        projects = sorted(path.parent for path in examples.glob("*/manifest.json"))
        self.assertGreaterEqual(len(projects), 3)
        for project in projects:
            with self.subTest(example=project.name):
                package = self.root / f"{project.name}.atsplugin"
                ats.pack(str(project), str(package), None)
                self.assertEqual(
                    json.loads((project / "manifest.json").read_text(encoding="utf-8"))["plugin"]["id"],
                    ats.verify(str(package), None, False)["pluginId"],
                )


if __name__ == "__main__":
    unittest.main()
