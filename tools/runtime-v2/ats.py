#!/usr/bin/env python3
"""Android Tool Suite Runtime v2 contract, package and code-generation CLI.

The implementation intentionally uses only the Python standard library so the same checks can run
in component CI, the outer build and a freshly cloned plugin project.
"""

from __future__ import annotations

import argparse
import hashlib
import html as html_module
import http.server
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


FORMAT_VERSION = 3
MAX_MANIFEST_BYTES = 256 * 1024
MAX_PACKAGE_BYTES = 64 * 1024 * 1024
MAX_ENTRY_BYTES = 32 * 1024 * 1024
MAX_ENTRIES = 4096
MAX_DEPTH = 32
SAFE_PATH = re.compile(r"^[A-Za-z0-9._/-]+$")
ID = re.compile(r"^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")
SEMVER = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$"
)
ALLOWED_ROOT_KEYS = {
    "$schema",
    "format",
    "formatVersion",
    "plugin",
    "platforms",
    "runtime",
    "requires",
    "provides",
    "contributes",
    "datasets",
    "tasks",
}
PACKAGE_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = PACKAGE_ROOT / "runtime-contract" / "src" / "main" / "resources" / "contracts"
GENERATED_TS = PACKAGE_ROOT / "runtime-v2" / "sdk" / "src" / "generated.ts"
GENERATED_JAVA = (
    PACKAGE_ROOT
    / "runtime-contract"
    / "src"
    / "main"
    / "java"
    / "com"
    / "androidtoolsuite"
    / "runtime"
    / "contract"
    / "GeneratedContract.java"
)
TEMPLATE_ROOT = Path(__file__).resolve().parent / "templates" / "web-tool"


class AtsError(Exception):
    pass


@dataclass(frozen=True)
class PackageFile:
    path: str
    data: bytes


def canonical_json(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode(
        "utf-8"
    )


def require_type(value: Any, expected: type | tuple[type, ...], label: str) -> Any:
    if not isinstance(value, expected) or expected is int and isinstance(value, bool):
        raise AtsError(f"{label} 类型无效")
    return value


def require_keys(value: dict[str, Any], allowed: set[str], label: str) -> None:
    unknown = sorted(set(value) - allowed)
    if unknown:
        raise AtsError(f"{label} 包含未知字段：{', '.join(unknown)}")


def require_text(value: Any, label: str, max_length: int) -> str:
    require_type(value, str, label)
    clean = value.strip()
    if not clean:
        raise AtsError(f"缺少 {label}")
    if len(clean) > max_length:
        raise AtsError(f"{label} 超出长度限制")
    return clean


def require_id(value: Any, label: str, max_length: int = 128) -> str:
    clean = require_text(value, label, max_length)
    if not ID.fullmatch(clean):
        raise AtsError(f"{label} 格式无效")
    return clean


def require_positive_int(value: Any, label: str) -> int:
    require_type(value, int, label)
    if value < 1 or value > 2_147_483_647:
        raise AtsError(f"{label} 必须是正整数")
    return value


def check_json_depth(value: Any, depth: int = 1) -> None:
    if depth > MAX_DEPTH:
        raise AtsError("JSON 嵌套层级超出限制")
    if isinstance(value, dict):
        for child in value.values():
            check_json_depth(child, depth + 1)
    elif isinstance(value, list):
        for child in value:
            check_json_depth(child, depth + 1)


def validate_package_path(raw: str) -> str:
    if not raw or len(raw) > 240 or not SAFE_PATH.fullmatch(raw):
        raise AtsError(f"插件包路径格式无效：{raw!r}")
    if raw.startswith("/") or raw.endswith("/") or "\\" in raw or ":" in raw:
        raise AtsError(f"插件包路径不是相对文件路径：{raw}")
    segments = raw.split("/")
    if len(segments) > 16 or any(segment in {"", ".", ".."} for segment in segments):
        raise AtsError(f"插件包路径包含空段、穿越段或层级过深：{raw}")
    return raw


def unique_ids(items: list[Any], label: str) -> set[str]:
    seen: set[str] = set()
    for index, raw in enumerate(items):
        require_type(raw, dict, f"{label}[{index}]")
        item_id = require_id(raw.get("id"), f"{label}[{index}].id", 64)
        if item_id in seen:
            raise AtsError(f"{label} 包含重复 id：{item_id}")
        seen.add(item_id)
    return seen


def validate_manifest(value: Any) -> dict[str, Any]:
    require_type(value, dict, "manifest")
    check_json_depth(value)
    require_keys(value, ALLOWED_ROOT_KEYS, "manifest")
    if value.get("format") != "ats-plugin" or value.get("formatVersion") != FORMAT_VERSION:
        raise AtsError("manifest 必须声明 ats-plugin formatVersion 3")

    plugin = require_type(value.get("plugin"), dict, "plugin")
    require_keys(
        plugin,
        {"id", "title", "description", "version", "versionCode", "minHostVersionCode", "publisher", "homepage", "kind"},
        "plugin",
    )
    require_id(plugin.get("id"), "plugin.id")
    require_text(plugin.get("title"), "plugin.title", 80)
    require_text(plugin.get("description"), "plugin.description", 320)
    version = require_text(plugin.get("version"), "plugin.version", 64)
    if not SEMVER.fullmatch(version):
        raise AtsError("plugin.version 必须是 SemVer")
    require_positive_int(plugin.get("versionCode"), "plugin.versionCode")
    require_positive_int(plugin.get("minHostVersionCode"), "plugin.minHostVersionCode")
    require_id(plugin.get("publisher"), "plugin.publisher")
    plugin_kind = plugin.get("kind", "tool")
    if plugin_kind not in {"tool", "trusted-provider"}:
        raise AtsError("plugin.kind 只支持 tool 或 trusted-provider")
    if "homepage" in plugin:
        homepage = require_text(plugin["homepage"], "plugin.homepage", 512)
        if not re.fullmatch(r"https?://[^\s]+", homepage):
            raise AtsError("plugin.homepage 必须是 http/https URI")

    platforms = require_type(value.get("platforms"), list, "platforms")
    if not 1 <= len(platforms) <= 8 or len(set(platforms)) != len(platforms):
        raise AtsError("platforms 数量无效或包含重复值")
    for platform in platforms:
        if not re.fullmatch(r"[a-z][a-z0-9-]{1,31}", require_text(platform, "platform", 32)):
            raise AtsError(f"platform 格式无效：{platform}")

    runtime = require_type(value.get("runtime"), dict, "runtime")
    require_keys(runtime, {"ui", "background", "providers"}, "runtime")
    ui = require_type(runtime.get("ui"), list, "runtime.ui")
    background = require_type(runtime.get("background", []), list, "runtime.background")
    providers = require_type(runtime.get("providers", []), list, "runtime.providers")
    if len(ui) > 8 or len(background) > 32 or len(providers) > 16:
        raise AtsError("runtime entry 数量超出限制")
    if not ui and not background and not providers:
        raise AtsError("runtime 至少需要一个 UI、后台或 Provider 入口")

    ui_ids = unique_ids(ui, "runtime.ui")
    declared_paths: set[str] = set()
    for index, item in enumerate(ui):
        require_keys(item, {"id", "type", "entry"}, f"runtime.ui[{index}]")
        entry_type = item.get("type")
        if entry_type not in {"web", "declarative"}:
            raise AtsError("runtime.ui.type 只支持 web 或 declarative")
        entry = validate_package_path(require_text(item.get("entry"), "runtime.ui.entry", 240))
        if entry_type == "web" and (not entry.startswith("web/") or not entry.endswith(".html")):
            raise AtsError("Web UI 入口必须位于 web/ 且以 .html 结尾")
        if entry_type == "declarative" and (not entry.startswith("ui/") or not entry.endswith(".json")):
            raise AtsError("声明式 UI 入口必须位于 ui/ 且以 .json 结尾")
        declared_paths.add(entry)

    background_ids = unique_ids(background, "runtime.background")
    background_types: dict[str, str] = {}
    background_required: dict[str, bool] = {}
    for index, item in enumerate(background):
        require_keys(item, {"id", "type", "entry", "required", "timeoutMs", "maxHeapBytes"}, f"runtime.background[{index}]")
        entry_type = item.get("type")
        background_types[item["id"]] = entry_type
        if entry_type not in {"provider-task", "javascript-worker", "wasm-worker"}:
            raise AtsError(f"未知后台入口类型：{entry_type}")
        entry = validate_package_path(require_text(item.get("entry"), "runtime.background.entry", 240))
        if entry_type == "javascript-worker":
            if not entry.startswith("workers/") or not entry.endswith(".js"):
                raise AtsError("JavaScript worker 必须位于 workers/ 且以 .js 结尾")
            declared_paths.add(entry)
        if entry_type == "wasm-worker":
            if not entry.startswith("workers/") or not entry.endswith(".wasm"):
                raise AtsError("WASM worker 必须位于 workers/ 且以 .wasm 结尾")
            declared_paths.add(entry)
        require_type(item.get("required"), bool, "runtime.background.required")
        background_required[item["id"]] = item["required"]
        timeout = item.get("timeoutMs", 30_000)
        require_type(timeout, int, "runtime.background.timeoutMs")
        if not 1_000 <= timeout <= 600_000:
            raise AtsError("runtime.background.timeoutMs 超出范围")
        heap = item.get("maxHeapBytes", 32 * 1024 * 1024)
        require_type(heap, int, "runtime.background.maxHeapBytes")
        if not 1024 * 1024 <= heap <= 256 * 1024 * 1024:
            raise AtsError("runtime.background.maxHeapBytes 超出范围")

    provider_ids = unique_ids(providers, "runtime.providers")
    for index, item in enumerate(providers):
        require_keys(item, {"id", "type", "code", "entryClass", "activation"}, f"runtime.providers[{index}]")
        if (
            item.get("type") != "android-dex"
            or item.get("code") != "android/provider.apk"
            or item.get("activation") != "cold-start"
        ):
            raise AtsError("Native Provider 首版必须使用 android-dex/android/provider.apk/cold-start")
        entry_class = require_text(item.get("entryClass"), "runtime.providers.entryClass", 240)
        if not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+", entry_class):
            raise AtsError("Provider entryClass 格式无效")
        declared_paths.add("android/provider.apk")

    requires = require_type(value.get("requires", {}), dict, "requires")
    require_keys(requires, {"plugins", "capabilities"}, "requires")
    for group_name, maximum in (("plugins", 64), ("capabilities", 128)):
        group = require_type(requires.get(group_name, []), list, f"requires.{group_name}")
        if len(group) > maximum:
            raise AtsError(f"requires.{group_name} 项目过多")
        unique_ids(group, f"requires.{group_name}")
        for item in group:
            allowed = {"id", "version", "optional"}
            if group_name == "capabilities":
                allowed.add("scopes")
            require_keys(item, allowed, f"requires.{group_name}")
            require_text(item.get("version"), f"requires.{group_name}.version", 64)
            require_type(item.get("optional"), bool, f"requires.{group_name}.optional")
            if "scopes" in item:
                require_type(item["scopes"], dict, "requires.capabilities.scopes")

    provides = require_type(value.get("provides", {}), dict, "provides")
    require_keys(provides, {"capabilities"}, "provides")
    provided_capabilities = require_type(provides.get("capabilities", []), list, "provides.capabilities")
    if len(provided_capabilities) > 64:
        raise AtsError("provides.capabilities 项目过多")
    unique_ids(provided_capabilities, "provides.capabilities")
    _, capability_contracts = load_contract_index()
    known_method_capability = {
        method: capability["id"]
        for capability in capability_contracts
        for method in capability["methods"]
    }
    for item in provided_capabilities:
        require_keys(item, {"id", "version", "providerEntry", "workerEntry", "methods"}, "provides.capabilities")
        capability_id = require_id(item.get("id"), "provides.capabilities.id")
        version = require_text(item.get("version"), "provides.capabilities.version", 64)
        if not SEMVER.fullmatch(version):
            raise AtsError("Provider capability version 必须是 SemVer")
        provider_entry = item.get("providerEntry")
        worker_entry = item.get("workerEntry")
        if (provider_entry is None) == (worker_entry is None):
            raise AtsError("Capability 必须且只能引用一个 Provider 或 Worker entry")
        if provider_entry is not None and require_id(
            provider_entry, "provides.capabilities.providerEntry", 64
        ) not in provider_ids:
            raise AtsError("Capability 引用了不存在的 Provider entry")
        if worker_entry is not None:
            worker_id = require_id(worker_entry, "provides.capabilities.workerEntry", 64)
            if background_types.get(worker_id) not in {"javascript-worker", "wasm-worker"}:
                raise AtsError("Capability 必须引用 JavaScript 或 WASM Worker entry")
            if not background_required.get(worker_id, False):
                raise AtsError("提供 Capability 的 Worker entry 必须标记 required")
        methods = require_type(item.get("methods"), list, "provides.capabilities.methods")
        if not 1 <= len(methods) <= 64 or len(set(methods)) != len(methods):
            raise AtsError("provides.capabilities.methods 数量无效或包含重复值")
        for method in methods:
            method = require_text(method, "provides.capabilities.methods", 160)
            if not re.fullmatch(r"[a-z][A-Za-z0-9]*(?:[._-][A-Za-z0-9]+)+", method):
                raise AtsError(f"Capability method 格式无效：{method}")
            known = known_method_capability.get(method)
            if known is not None and known != capability_id:
                raise AtsError(f"Capability method 与 id 不匹配：{method}")
            if known is None and method != capability_id and not method.startswith(capability_id + "."):
                raise AtsError(f"自定义 Capability method 必须以 capability id 为前缀：{method}")

    contributes = require_type(value.get("contributes", {}), dict, "contributes")
    require_keys(contributes, {"tools", "homeWidgets"}, "contributes")
    tools = require_type(contributes.get("tools", []), list, "contributes.tools")
    if len(tools) > 16:
        raise AtsError("contributes.tools 项目过多")
    unique_ids(tools, "contributes.tools")
    for item in tools:
        require_keys(item, {"id", "uiEntry", "title", "description"}, "contributes.tools")
        if require_id(item.get("uiEntry"), "contributes.tools.uiEntry", 64) not in ui_ids:
            raise AtsError("Tool 引用了不存在的 UI entry")
        if "title" in item:
            require_text(item["title"], "contributes.tools.title", 80)
        if "description" in item:
            require_text(item["description"], "contributes.tools.description", 320)
    home_widgets = require_type(contributes.get("homeWidgets", []), list, "contributes.homeWidgets")
    if len(home_widgets) > 16:
        raise AtsError("contributes.homeWidgets 项目过多")
    unique_ids(home_widgets, "contributes.homeWidgets")
    declared_capability_ids = {item["id"] for item in requires.get("capabilities", [])}
    method_capability = {
        method: capability["id"]
        for capability in capability_contracts
        for method in capability["methods"]
    }
    for item in home_widgets:
        require_keys(item, {"id", "title", "template", "dataSource", "sizes"}, "contributes.homeWidgets")
        require_text(item.get("title"), "contributes.homeWidgets.title", 80)
        if item.get("template") not in {"status", "metric", "action"}:
            raise AtsError("未知 HomeWidget template")
        data_source = require_text(item.get("dataSource"), "contributes.homeWidgets.dataSource", 160)
        if method_capability.get(data_source) not in declared_capability_ids:
            raise AtsError("HomeWidget dataSource 未声明对应 Capability")
        sizes = require_type(item.get("sizes"), list, "contributes.homeWidgets.sizes")
        if not 1 <= len(sizes) <= 4 or len(set(sizes)) != len(sizes):
            raise AtsError("HomeWidget sizes 数量无效或重复")
        if any(size not in {"1x1", "2x1", "2x2", "4x2"} for size in sizes):
            raise AtsError("未知 HomeWidget size")

    datasets = require_type(value.get("datasets", []), list, "datasets")
    tasks = require_type(value.get("tasks", []), list, "tasks")
    if len(datasets) > 64 or len(tasks) > 64:
        raise AtsError("datasets 或 tasks 项目过多")
    dataset_ids = unique_ids(datasets, "datasets")
    for item in datasets:
        require_keys(
            item,
            {
                "id", "title", "category", "formatVersion", "sensitive", "restoreModes",
                "dependsOn", "mediaType", "validator", "maxBytes",
            },
            "datasets",
        )
        require_text(item.get("title"), "datasets.title", 80)
        if item.get("category") not in {"settings", "data", "cache", "secret"}:
            raise AtsError("未知 Dataset category")
        require_positive_int(item.get("formatVersion"), "datasets.formatVersion")
        require_type(item.get("sensitive"), bool, "datasets.sensitive")
        modes = require_type(item.get("restoreModes"), list, "datasets.restoreModes")
        if not modes or len(modes) > 3 or len(set(modes)) != len(modes):
            raise AtsError("datasets.restoreModes 数量无效或重复")
        if any(mode not in {"replace", "merge", "skip"} for mode in modes):
            raise AtsError("未知 Dataset restore mode")
        media_type = item.get("mediaType", "application/json")
        require_text(media_type, "datasets.mediaType", 128)
        if not re.fullmatch(r"[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+\-]+", media_type):
            raise AtsError("datasets.mediaType 格式无效")
        validator = item.get("validator", "json" if media_type == "application/json" else "opaque")
        if validator not in {"json", "zip", "opaque"}:
            raise AtsError("未知 Dataset validator")
        max_bytes = item.get("maxBytes", 32 * 1024 * 1024)
        require_type(max_bytes, int, "datasets.maxBytes")
        if not 1 <= max_bytes <= 512 * 1024 * 1024:
            raise AtsError("datasets.maxBytes 超出范围")
        dependencies = require_type(item.get("dependsOn", []), list, "datasets.dependsOn")
        for dependency in dependencies:
            dependency_id = require_id(dependency, "datasets.dependsOn", 64)
            if dependency_id == item["id"] or dependency_id not in dataset_ids:
                raise AtsError("Dataset 依赖不存在或自引用")
    dataset_by_id = {item["id"]: item for item in datasets}
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit_dataset(dataset_id: str) -> None:
        if dataset_id in visited:
            return
        if dataset_id in visiting:
            raise AtsError(f"Dataset 依赖形成循环：{dataset_id}")
        visiting.add(dataset_id)
        for dependency in dataset_by_id[dataset_id].get("dependsOn", []):
            visit_dataset(dependency)
        visiting.remove(dataset_id)
        visited.add(dataset_id)

    for dataset_id in dataset_by_id:
        visit_dataset(dataset_id)

    task_ids = unique_ids(tasks, "tasks")
    del task_ids
    for item in tasks:
        require_keys(item, {"id", "backgroundEntry", "triggers", "constraints", "concurrency", "retry"}, "tasks")
        if require_id(item.get("backgroundEntry"), "tasks.backgroundEntry", 64) not in background_ids:
            raise AtsError("Task 引用了不存在的 background entry")
        triggers = require_type(item.get("triggers"), list, "tasks.triggers")
        if not 1 <= len(triggers) <= 16:
            raise AtsError("tasks.triggers 数量超出范围")
        for trigger in triggers:
            require_type(trigger, dict, "tasks.trigger")
            require_keys(trigger, {"type", "intervalMinutes", "capability", "event"}, "tasks.trigger")
            trigger_type = trigger.get("type")
            if trigger_type not in {
                "manual", "periodic", "network-available", "charging", "app-foreground", "provider-event"
            }:
                raise AtsError("未知 task trigger type")
            if trigger_type == "periodic":
                interval = require_positive_int(trigger.get("intervalMinutes"), "tasks.trigger.intervalMinutes")
                if not 15 <= interval <= 525_600:
                    raise AtsError("periodic intervalMinutes 超出范围")
            elif "intervalMinutes" in trigger:
                raise AtsError("只有 periodic trigger 可以声明 intervalMinutes")
            if trigger_type == "provider-event":
                require_id(trigger.get("capability"), "tasks.trigger.capability")
                require_text(trigger.get("event"), "tasks.trigger.event", 128)
            elif "capability" in trigger or "event" in trigger:
                raise AtsError("只有 provider-event trigger 可以声明 capability/event")
        constraints = require_type(item.get("constraints", {}), dict, "tasks.constraints")
        require_keys(constraints, {"network", "charging", "batteryNotLow", "storageNotLow"}, "tasks.constraints")
        if constraints.get("network", "none") not in {"none", "connected", "unmetered"}:
            raise AtsError("未知 task network constraint")
        for key in ("charging", "batteryNotLow", "storageNotLow"):
            if key in constraints:
                require_type(constraints[key], bool, f"tasks.constraints.{key}")
        concurrency = require_type(item.get("concurrency"), dict, "tasks.concurrency")
        require_keys(concurrency, {"policy", "max"}, "tasks.concurrency")
        if concurrency.get("policy") not in {"forbid", "replace", "parallel"}:
            raise AtsError("未知 task concurrency policy")
        maximum = concurrency.get("max", 1)
        require_type(maximum, int, "tasks.concurrency.max")
        if not 1 <= maximum <= 8 or (concurrency.get("policy") != "parallel" and "max" in concurrency):
            raise AtsError("task concurrency.max 仅适用于 parallel 且不能超过 8")
        retry = require_type(item.get("retry"), dict, "tasks.retry")
        require_keys(retry, {"maxAttempts", "initialBackoffMs"}, "tasks.retry")
        attempts = require_positive_int(retry.get("maxAttempts"), "tasks.retry.maxAttempts")
        backoff = require_type(retry.get("initialBackoffMs"), int, "tasks.retry.initialBackoffMs")
        if attempts > 10 or not 10_000 <= backoff <= 86_400_000:
            raise AtsError("task retry 参数超出范围")

    if tasks and "scheduler" not in declared_capability_ids:
        raise AtsError("声明后台任务时必须请求 scheduler Capability")

    if plugin_kind == "tool":
        if providers:
            raise AtsError("普通 Tool 不得携带 Native Provider")
        if bool(ui) != bool(tools):
            raise AtsError("普通插件的 UI entry 与工具贡献必须同时存在")
        if not tools and not provided_capabilities:
            raise AtsError("普通插件必须贡献 Tool 或 Worker Capability")
        if any("workerEntry" not in item for item in provided_capabilities):
            raise AtsError("普通插件提供 Capability 时必须引用受限 Worker entry")
    else:
        if not providers:
            raise AtsError("trusted-provider 必须携带 Native Provider entry")
        if bool(ui) != bool(tools):
            raise AtsError("受信 Provider 的 UI entry 与工具贡献必须同时存在")

    value["__declared_paths"] = sorted(declared_paths)
    return value


def load_manifest(path: Path) -> tuple[dict[str, Any], bytes]:
    try:
        raw = path.read_bytes()
    except OSError as error:
        raise AtsError(f"无法读取 {path}：{error}") from error
    if len(raw) > MAX_MANIFEST_BYTES:
        raise AtsError("manifest.json 超出大小限制")
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AtsError(f"manifest.json 不是有效 UTF-8 JSON：{error}") from error
    validated = validate_manifest(value)
    return validated, canonical_json({key: item for key, item in validated.items() if key != "__declared_paths"})


def resolve_project(value: str) -> tuple[Path, Path]:
    path = Path(value).resolve()
    if path.is_dir():
        return path, path / "manifest.json"
    return path.parent, path


def validate_declarative_ui(value: Any, manifest: dict[str, Any], label: str) -> set[str]:
    require_type(value, dict, label)
    check_json_depth(value)
    require_keys(value, {"$schema", "formatVersion", "initialState", "queries", "actions", "body"}, label)
    if value.get("formatVersion") != 1:
        raise AtsError(f"{label}.formatVersion 必须是 1")
    initial_state = require_type(value.get("initialState", {}), dict, f"{label}.initialState")
    if len(initial_state) > 64 or len(canonical_json(initial_state)) > 64 * 1024:
        raise AtsError(f"{label}.initialState 超出限制")

    declared_capabilities = {
        item["id"] for item in manifest.get("requires", {}).get("capabilities", [])
    }
    _, capability_contracts = load_contract_index()
    method_capability = {
        method: capability["id"]
        for capability in capability_contracts
        for method in capability["methods"]
    }
    queries = require_type(value.get("queries", []), list, f"{label}.queries")
    actions = require_type(value.get("actions", []), list, f"{label}.actions")
    if len(queries) > 32 or len(actions) > 32:
        raise AtsError(f"{label} query/action 项目过多")
    query_ids = unique_ids(queries, f"{label}.queries")
    action_ids = unique_ids(actions, f"{label}.actions")
    state_path = re.compile(r"^[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*){0,7}$")
    method_pattern = re.compile(r"^[a-z][A-Za-z0-9]*(?:\.[a-z][A-Za-z0-9]*)+$")

    def validate_invocation(item: Any, item_label: str, query: bool) -> None:
        require_type(item, dict, item_label)
        allowed = {"id", "capability", "method", "payload", "target", "deadlineMs"}
        if query:
            allowed.add("required")
        else:
            allowed.update({"refresh", "successMessage", "confirm"})
        require_keys(item, allowed, item_label)
        capability = require_id(item.get("capability"), f"{item_label}.capability")
        method = require_text(item.get("method"), f"{item_label}.method", 160)
        if not method_pattern.fullmatch(method):
            raise AtsError(f"{item_label}.method 格式无效")
        if capability not in declared_capabilities:
            raise AtsError(f"{item_label} 调用未声明 Capability：{capability}")
        expected_capability = method_capability.get(method)
        if (expected_capability is not None and expected_capability != capability) or (
            expected_capability is None and method != capability and not method.startswith(capability + ".")
        ):
            raise AtsError(f"{item_label}.method 与 Capability 不匹配")
        payload = require_type(item.get("payload", {}), dict, f"{item_label}.payload")
        if len(payload) > 64 or len(canonical_json(payload)) > 64 * 1024:
            raise AtsError(f"{item_label}.payload 超出限制")

        def validate_payload_template(raw: Any) -> None:
            if isinstance(raw, dict):
                if "$state" in raw:
                    if set(raw) != {"$state"} or not isinstance(raw["$state"], str) \
                            or not state_path.fullmatch(raw["$state"]):
                        raise AtsError(f"{item_label}.payload $state 绑定无效")
                    return
                for child in raw.values():
                    validate_payload_template(child)
            elif isinstance(raw, list):
                for child in raw:
                    validate_payload_template(child)

        validate_payload_template(payload)
        target = item.get("target")
        if query and target is None:
            raise AtsError(f"缺少 {item_label}.target")
        if target is not None and not state_path.fullmatch(require_text(target, f"{item_label}.target", 128)):
            raise AtsError(f"{item_label}.target 格式无效")
        deadline = item.get("deadlineMs", 30_000)
        require_type(deadline, int, f"{item_label}.deadlineMs")
        if not 1_000 <= deadline <= 600_000:
            raise AtsError(f"{item_label}.deadlineMs 超出范围")
        if query:
            require_type(item.get("required"), bool, f"{item_label}.required")
            return
        refresh = require_type(item.get("refresh", []), list, f"{item_label}.refresh")
        if len(refresh) > 16 or len(set(refresh)) != len(refresh):
            raise AtsError(f"{item_label}.refresh 数量无效或重复")
        for query_id in refresh:
            if require_id(query_id, f"{item_label}.refresh", 64) not in query_ids:
                raise AtsError(f"{item_label}.refresh 引用未知 query")
        if "successMessage" in item:
            require_text(item["successMessage"], f"{item_label}.successMessage", 160)
        if "confirm" in item:
            confirm = require_type(item["confirm"], dict, f"{item_label}.confirm")
            require_keys(confirm, {"title", "body", "confirmLabel"}, f"{item_label}.confirm")
            require_text(confirm.get("title"), f"{item_label}.confirm.title", 80)
            require_text(confirm.get("body"), f"{item_label}.confirm.body", 320)
            require_text(confirm.get("confirmLabel"), f"{item_label}.confirm.confirmLabel", 40)

    for index, query in enumerate(queries):
        validate_invocation(query, f"{label}.queries[{index}]", True)
    for index, action in enumerate(actions):
        validate_invocation(action, f"{label}.actions[{index}]", False)

    referenced_actions: set[str] = set()
    node_count = 0

    def validate_text_value(raw: Any, value_label: str, required: bool) -> None:
        if raw is None:
            if required:
                raise AtsError(f"缺少 {value_label}")
            return
        if isinstance(raw, str):
            if len(raw) > 320:
                raise AtsError(f"{value_label} 超出长度限制")
            return
        binding = require_type(raw, dict, value_label)
        require_keys(binding, {"path", "fallback"}, value_label)
        if not state_path.fullmatch(require_text(binding.get("path"), f"{value_label}.path", 128)):
            raise AtsError(f"{value_label}.path 格式无效")
        if "fallback" in binding and len(require_type(binding["fallback"], str, f"{value_label}.fallback")) > 320:
            raise AtsError(f"{value_label}.fallback 超出长度限制")

    def validate_condition(raw: Any, condition_label: str) -> None:
        if raw is None:
            return
        condition = require_type(raw, dict, condition_label)
        require_keys(condition, {"path", "equals"}, condition_label)
        if not state_path.fullmatch(require_text(condition.get("path"), f"{condition_label}.path", 128)):
            raise AtsError(f"{condition_label}.path 格式无效")
        if "equals" not in condition or isinstance(condition["equals"], (dict, list)):
            raise AtsError(f"{condition_label}.equals 只能是标量")

    def validate_node(raw: Any, node_label: str, depth: int) -> None:
        nonlocal node_count
        node_count += 1
        if node_count > 256 or depth > 16:
            raise AtsError(f"{label} 节点数量或层级超出限制")
        node = require_type(raw, dict, node_label)
        node_type = node.get("type")
        common_when = {"when"}
        if node_type in {"column", "row", "card"}:
            require_keys(node, {"type", "children", "gap", "tone"} | common_when, node_label)
            if node.get("gap", "medium") not in {"small", "medium", "large"}:
                raise AtsError(f"{node_label}.gap 无效")
            if node.get("tone", "surface") not in {"surface", "primary", "success", "warning", "danger", "info"}:
                raise AtsError(f"{node_label}.tone 无效")
            children = require_type(node.get("children"), list, f"{node_label}.children")
            if len(children) > 64:
                raise AtsError(f"{node_label}.children 项目过多")
            for index, child in enumerate(children):
                validate_node(child, f"{node_label}.children[{index}]", depth + 1)
        elif node_type == "section":
            require_keys(node, {"type", "title", "subtitle", "children"} | common_when, node_label)
            validate_text_value(node.get("title"), f"{node_label}.title", True)
            validate_text_value(node.get("subtitle"), f"{node_label}.subtitle", False)
            children = require_type(node.get("children"), list, f"{node_label}.children")
            if len(children) > 64:
                raise AtsError(f"{node_label}.children 项目过多")
            for index, child in enumerate(children):
                validate_node(child, f"{node_label}.children[{index}]", depth + 1)
        elif node_type in {"text", "status"}:
            require_keys(node, {"type", "value", "style", "tone"} | common_when, node_label)
            validate_text_value(node.get("value"), f"{node_label}.value", True)
            if node.get("style", "body") not in {"headline", "title", "body", "supporting", "label"}:
                raise AtsError(f"{node_label}.style 无效")
            if node.get("tone", "default") not in {"default", "primary", "muted", "success", "warning", "danger", "info"}:
                raise AtsError(f"{node_label}.tone 无效")
        elif node_type == "icon":
            require_keys(node, {"type", "name", "tone"} | common_when, node_label)
            if node.get("name") not in {"check-circle", "cloud-off"}:
                raise AtsError(f"{node_label}.name 无效")
            if node.get("tone", "default") not in {"default", "primary", "muted", "success", "warning", "danger", "info"}:
                raise AtsError(f"{node_label}.tone 无效")
        elif node_type == "metric":
            require_keys(node, {"type", "label", "value", "supporting", "tone"} | common_when, node_label)
            validate_text_value(node.get("label"), f"{node_label}.label", True)
            validate_text_value(node.get("value"), f"{node_label}.value", True)
            validate_text_value(node.get("supporting"), f"{node_label}.supporting", False)
            if node.get("tone", "default") not in {"default", "primary", "success", "warning", "danger", "info"}:
                raise AtsError(f"{node_label}.tone 无效")
        elif node_type == "notice":
            require_keys(node, {"type", "value", "tone"} | common_when, node_label)
            validate_text_value(node.get("value"), f"{node_label}.value", True)
            if node.get("tone", "info") not in {"neutral", "info", "success", "warning", "danger"}:
                raise AtsError(f"{node_label}.tone 无效")
        elif node_type == "button":
            require_keys(node, {"type", "label", "action", "style", "fullWidth", "enabledWhen"} | common_when, node_label)
            validate_text_value(node.get("label"), f"{node_label}.label", True)
            referenced_actions.add(require_id(node.get("action"), f"{node_label}.action", 64))
            if node.get("style", "primary") not in {"primary", "secondary", "text"}:
                raise AtsError(f"{node_label}.style 无效")
            if "fullWidth" in node:
                require_type(node["fullWidth"], bool, f"{node_label}.fullWidth")
            validate_condition(node.get("enabledWhen"), f"{node_label}.enabledWhen")
        elif node_type == "state":
            require_keys(node, {"type", "variant", "title", "body", "action", "actionLabel"} | common_when, node_label)
            if node.get("variant") not in {"loading", "empty", "error"}:
                raise AtsError(f"{node_label}.variant 无效")
            validate_text_value(node.get("title"), f"{node_label}.title", True)
            validate_text_value(node.get("body"), f"{node_label}.body", False)
            if "action" in node:
                referenced_actions.add(require_id(node["action"], f"{node_label}.action", 64))
                require_text(node.get("actionLabel"), f"{node_label}.actionLabel", 40)
            elif "actionLabel" in node:
                raise AtsError(f"{node_label}.actionLabel 只能与 action 同时声明")
        elif node_type == "divider":
            require_keys(node, {"type"} | common_when, node_label)
        elif node_type == "spacer":
            require_keys(node, {"type", "size"} | common_when, node_label)
            if node.get("size", "medium") not in {"small", "medium", "large"}:
                raise AtsError(f"{node_label}.size 无效")
        else:
            raise AtsError(f"{node_label}.type 无效")
        validate_condition(node.get("when"), f"{node_label}.when")

    body = require_type(value.get("body"), dict, f"{label}.body")
    if body.get("type") == "webview":
        require_keys(body, {"type", "entry"}, f"{label}.body")
        entry = validate_package_path(require_text(body.get("entry"), f"{label}.body.entry", 240))
        if not entry.startswith("web/") or not entry.endswith(".html"):
            raise AtsError(f"{label}.body WebView entry 必须位于 web/ 且以 .html 结尾")
        if initial_state or queries or actions:
            raise AtsError(f"{label} WebView 声明不能同时定义状态、Query 或 Action")
        return {entry}
    validate_node(body, f"{label}.body", 1)
    if body.get("type") != "column":
        raise AtsError(f"{label}.body 根节点必须是 column 或 webview")
    missing_actions = referenced_actions - action_ids
    if missing_actions:
        raise AtsError(f"{label} 节点引用未知 action：{sorted(missing_actions)[0]}")
    return set()


def collect_project_files(project: Path, manifest: dict[str, Any], manifest_bytes: bytes) -> list[PackageFile]:
    files = [PackageFile("manifest.json", manifest_bytes)]
    seen = {"manifest.json"}
    folded = {"manifest.json"}
    for directory in ("web", "ui", "workers", "android"):
        root = project / directory
        if not root.exists():
            continue
        if root.is_symlink() or not root.is_dir():
            raise AtsError(f"{directory} 必须是普通目录且不能是符号链接")
        for file_path in sorted(root.rglob("*")):
            if file_path.is_symlink():
                raise AtsError(f"插件项目不能包含符号链接：{file_path}")
            if not file_path.is_file():
                continue
            relative = file_path.relative_to(project).as_posix()
            validate_package_path(relative)
            folded_path = relative.casefold()
            if relative in seen or folded_path in folded:
                raise AtsError(f"插件包包含重复路径：{relative}")
            data = file_path.read_bytes()
            if len(data) > MAX_ENTRY_BYTES:
                raise AtsError(f"插件文件超出单项大小限制：{relative}")
            seen.add(relative)
            folded.add(folded_path)
            files.append(PackageFile(relative, data))
    by_path = {item.path: item.data for item in files}
    if any(path.startswith("android/") and path != "android/provider.apk" for path in by_path):
        raise AtsError("android/ 只允许受信 Provider 载荷 android/provider.apk")
    if manifest["plugin"].get("kind", "tool") != "trusted-provider" and "android/provider.apk" in by_path:
        raise AtsError("普通插件不得夹带 android/provider.apk")
    for entry in manifest.get("runtime", {}).get("ui", []):
        if entry.get("type") != "declarative":
            continue
        path = entry["entry"]
        try:
            document = json.loads(by_path[path].decode("utf-8"))
        except KeyError:
            continue
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise AtsError(f"{path} 不是有效 UTF-8 JSON：{error}") from error
        for referenced in validate_declarative_ui(document, manifest, path):
            if referenced not in by_path:
                raise AtsError(f"{path} 引用的 WebView 载荷不存在：{referenced}")
    for declared in manifest.pop("__declared_paths"):
        if declared not in seen:
            raise AtsError(f"manifest 引用的载荷不存在：{declared}")
    if len(files) > MAX_ENTRIES or sum(len(item.data) for item in files) > MAX_PACKAGE_BYTES:
        raise AtsError("插件包项目数或总大小超出限制")
    return sorted(files, key=lambda item: item.path)


def integrity_for(files: list[PackageFile]) -> bytes:
    value = {
        "algorithm": "sha256",
        "formatVersion": 1,
        "files": [
            {"path": item.path, "size": len(item.data), "sha256": hashlib.sha256(item.data).hexdigest()}
            for item in files
        ],
    }
    return canonical_json(value)


def run_openssl(arguments: list[str]) -> None:
    executable = shutil.which("openssl")
    if executable is None:
        raise AtsError("未找到 openssl，无法完成签名操作")
    result = subprocess.run([executable, *arguments], capture_output=True, text=True, check=False)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise AtsError(f"openssl 执行失败：{detail}")


def sign_bytes(data: bytes, private_key: Path) -> bytes:
    with tempfile.TemporaryDirectory(prefix="ats-sign-") as temporary:
        input_path = Path(temporary) / "integrity.json"
        signature_path = Path(temporary) / "signature.sig"
        input_path.write_bytes(data)
        run_openssl(
            ["dgst", "-sha256", "-sign", str(private_key.resolve()), "-out", str(signature_path), str(input_path)]
        )
        return signature_path.read_bytes()


def verify_signature(data: bytes, signature: bytes, public_key: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="ats-verify-") as temporary:
        input_path = Path(temporary) / "integrity.json"
        signature_path = Path(temporary) / "signature.sig"
        input_path.write_bytes(data)
        signature_path.write_bytes(signature)
        run_openssl(
            ["dgst", "-sha256", "-verify", str(public_key.resolve()), "-signature", str(signature_path), str(input_path)]
        )


def zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    return info


def pack(project_value: str, output_value: str, signing_key: str | None) -> Path:
    project, manifest_path = resolve_project(project_value)
    manifest, manifest_bytes = load_manifest(manifest_path)
    files = collect_project_files(project, manifest, manifest_bytes)
    integrity = integrity_for(files)
    package_files = [*files, PackageFile("META-INF/ats-integrity.json", integrity)]
    if signing_key:
        package_files.append(PackageFile("META-INF/ats-signature.sig", sign_bytes(integrity, Path(signing_key))))
    elif any(item.path == "android/provider.apk" for item in files):
        raise AtsError("包含 Native Provider 的包必须使用 --signing-key")

    output = Path(output_value).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".pending")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for item in sorted(package_files, key=lambda candidate: candidate.path):
                archive.writestr(zip_info(item.path), item.data)
        if temporary.stat().st_size > MAX_PACKAGE_BYTES:
            raise AtsError("生成的插件包超出总大小限制")
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    return output


def read_package(path_value: str) -> tuple[dict[str, Any], list[PackageFile], bytes, bytes | None]:
    package_path = Path(path_value).resolve()
    if package_path.stat().st_size > MAX_PACKAGE_BYTES:
        raise AtsError("插件包超出总大小限制")
    files: list[PackageFile] = []
    exact: set[str] = set()
    folded: set[str] = set()
    total = 0
    try:
        with zipfile.ZipFile(package_path, "r") as archive:
            infos = archive.infolist()
            if len(infos) > MAX_ENTRIES:
                raise AtsError("插件包项目数超出限制")
            for info in infos:
                if info.is_dir():
                    continue
                name = validate_package_path(info.filename)
                if name in exact or name.casefold() in folded:
                    raise AtsError(f"插件包包含重复路径：{name}")
                if not (
                    name == "manifest.json"
                    or name.startswith("web/")
                    or name.startswith("ui/")
                    or name.startswith("workers/")
                    or name.startswith("android/")
                    or name.startswith("META-INF/")
                ):
                    raise AtsError(f"插件包包含未知顶层路径：{name}")
                if info.file_size > MAX_ENTRY_BYTES or info.compress_size > MAX_ENTRY_BYTES:
                    raise AtsError(f"插件包文件超出单项大小限制：{name}")
                data = archive.read(info)
                if len(data) != info.file_size:
                    raise AtsError(f"插件包文件读取长度不符：{name}")
                total += len(data)
                if total > MAX_PACKAGE_BYTES:
                    raise AtsError("插件包解压后总大小超出限制")
                exact.add(name)
                folded.add(name.casefold())
                files.append(PackageFile(name, data))
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        raise AtsError(f"无法读取插件包：{error}") from error

    by_path = {item.path: item.data for item in files}
    if "manifest.json" not in by_path or "META-INF/ats-integrity.json" not in by_path:
        raise AtsError("format v3 插件包缺少 manifest.json 或完整性清单")
    try:
        manifest_value = json.loads(by_path["manifest.json"].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AtsError(f"manifest.json 无效：{error}") from error
    manifest = validate_manifest(manifest_value)
    payload_files = [item for item in files if not item.path.startswith("META-INF/")]
    expected_integrity = integrity_for(sorted(payload_files, key=lambda item: item.path))
    if by_path["META-INF/ats-integrity.json"] != expected_integrity:
        raise AtsError("插件包完整性清单与实际文件不一致")
    present = {item.path for item in payload_files}
    for entry in manifest.get("runtime", {}).get("ui", []):
        if entry.get("type") != "declarative" or entry["entry"] not in by_path:
            continue
        try:
            document = json.loads(by_path[entry["entry"]].decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise AtsError(f"{entry['entry']} 不是有效 UTF-8 JSON：{error}") from error
        for referenced in validate_declarative_ui(document, manifest, entry["entry"]):
            if referenced not in by_path:
                raise AtsError(f"{entry['entry']} 引用的 WebView 载荷不存在：{referenced}")
    for declared in manifest.pop("__declared_paths"):
        if declared not in present:
            raise AtsError(f"manifest 引用的载荷不存在：{declared}")
    signature = by_path.get("META-INF/ats-signature.sig")
    if "android/provider.apk" in present and signature is None:
        raise AtsError("Native Provider 包缺少 publisher 签名")
    return manifest, files, expected_integrity, signature


def verify(path_value: str, public_key: str | None, require_signature: bool) -> dict[str, Any]:
    manifest, files, integrity, signature = read_package(path_value)
    signature_status = "absent"
    if require_signature and signature is None:
        raise AtsError("要求签名，但插件包没有签名")
    if signature is not None:
        signature_status = "present-unverified"
        if public_key:
            verify_signature(integrity, signature, Path(public_key))
            signature_status = "verified"
    return {
        "pluginId": manifest["plugin"]["id"],
        "version": manifest["plugin"]["version"],
        "versionCode": manifest["plugin"]["versionCode"],
        "entries": len(files),
        "signature": signature_status,
        "sha256": hashlib.sha256(Path(path_value).read_bytes()).hexdigest(),
    }


def load_contract_index() -> tuple[dict[str, Any], list[dict[str, Any]]]:
    index = json.loads((CONTRACT_ROOT / "contract-index.json").read_text(encoding="utf-8"))
    capabilities = [
        json.loads((CONTRACT_ROOT / relative).read_text(encoding="utf-8"))
        for relative in index["capabilities"]
    ]
    return index, capabilities


def generated_typescript(index: dict[str, Any], capabilities: list[dict[str, Any]]) -> str:
    capability_ids = " | ".join(json.dumps(item["id"]) for item in capabilities)
    methods = sorted(method for capability in capabilities for method in capability["methods"])
    events = sorted(event for capability in capabilities for event in capability["events"])
    method_names = " | ".join(json.dumps(item) for item in methods) or "never"
    event_names = " | ".join(json.dumps(item) for item in events) or "never"
    error_schema = json.loads((CONTRACT_ROOT / index["schemas"]["rpc"]).read_text(encoding="utf-8"))
    error_codes = error_schema["$defs"]["error"]["properties"]["code"]["enum"]
    error_names = " | ".join(json.dumps(item) for item in error_codes)
    method_capabilities = ",\n".join(
        f"  {json.dumps(method)}: {json.dumps(capability['id'])}"
        for capability in capabilities
        for method in sorted(capability["methods"])
    )
    capability_permissions = ",\n".join(
        f"  {json.dumps(capability['id'])}: {json.dumps(capability['permission'], ensure_ascii=False, separators=(',', ':'))}"
        for capability in capabilities
    )
    return f"""// Generated by tools/runtime-v2/ats.py generate. Do not edit manually.
export const CONTRACT_VERSION = {json.dumps(index['contractVersion'])} as const;
export const PACKAGE_FORMAT_VERSION = {index['packageFormatVersion']} as const;
export const RPC_PROTOCOL = {json.dumps(index['rpcProtocol'])} as const;

export type CapabilityId = {capability_ids};
export type AtsMethod = {method_names};
export type AtsEvent = {event_names};
export type AtsErrorCode = {error_names};
export type CapabilityPermissionMode = "implicit" | "user";
export type CapabilityPermissionRisk = "normal" | "sensitive" | "restricted";
export const METHOD_CAPABILITY = {{
{method_capabilities}
}} as const satisfies Record<AtsMethod, CapabilityId>;
export const CAPABILITY_PERMISSIONS = {{
{capability_permissions}
}} as const satisfies Record<CapabilityId, {{
  mode: CapabilityPermissionMode;
  risk: CapabilityPermissionRisk;
  title: string;
  description: string;
}}>;
"""


def generated_java(index: dict[str, Any], capabilities: list[dict[str, Any]]) -> str:
    methods = sorted(method for capability in capabilities for method in capability["methods"])
    events = sorted(event for capability in capabilities for event in capability["events"])
    capabilities_code = "\n".join(
        f'        public static final String {constant_name(item["id"])} = "{item["id"]}";' for item in capabilities
    )
    methods_code = "\n".join(
        f'        public static final String {constant_name(method)} = "{method}";' for method in methods
    )
    events_code = "\n".join(
        f'        public static final String {constant_name(event)} = "{event}";' for event in events
    )
    method_capability_cases = "\n".join(
        f'            case "{method}": return "{capability["id"]}";'
        for capability in capabilities
        for method in sorted(capability["methods"])
    )
    event_capability_cases = "\n".join(
        f'            case "{event}": return "{capability["id"]}";'
        for capability in capabilities
        for event in sorted(capability["events"])
    )
    permission_mode_cases = "\n".join(
        f'            case "{item["id"]}": return "{item["permission"]["mode"]}";'
        for item in capabilities
    )
    permission_risk_cases = "\n".join(
        f'            case "{item["id"]}": return "{item["permission"]["risk"]}";'
        for item in capabilities
    )
    permission_title_cases = "\n".join(
        f'            case "{item["id"]}": return {json.dumps(item["permission"]["title"], ensure_ascii=False)};'
        for item in capabilities
    )
    permission_description_cases = "\n".join(
        f'            case "{item["id"]}": return {json.dumps(item["permission"]["description"], ensure_ascii=False)};'
        for item in capabilities
    )
    return f"""// Generated by tools/runtime-v2/ats.py generate. Do not edit manually.
package com.androidtoolsuite.runtime.contract;

public final class GeneratedContract {{
    public static final String CONTRACT_VERSION = "{index['contractVersion']}";
    public static final int PACKAGE_FORMAT_VERSION = {index['packageFormatVersion']};
    public static final String RPC_PROTOCOL = "{index['rpcProtocol']}";

    private GeneratedContract() {{
    }}

    public static final class Capabilities {{
{capabilities_code}

        private Capabilities() {{
        }}
    }}

    public static final class Methods {{
{methods_code}

        private Methods() {{
        }}
    }}

    public static final class Events {{
{events_code}

        private Events() {{
        }}
    }}

    public static String capabilityForMethod(String method) {{
        if (method == null) return null;
        switch (method) {{
{method_capability_cases}
            default: return null;
        }}
    }}

    public static String capabilityForEvent(String event) {{
        if (event == null) return null;
        switch (event) {{
{event_capability_cases}
            default: return null;
        }}
    }}

    public static String permissionMode(String capabilityId) {{
        if (capabilityId == null) return "user";
        switch (capabilityId) {{
{permission_mode_cases}
            default: return "user";
        }}
    }}

    public static String permissionRisk(String capabilityId) {{
        if (capabilityId == null) return "restricted";
        switch (capabilityId) {{
{permission_risk_cases}
            default: return "restricted";
        }}
    }}

    public static String permissionTitle(String capabilityId) {{
        if (capabilityId == null) return "未知能力";
        switch (capabilityId) {{
{permission_title_cases}
            default: return capabilityId;
        }}
    }}

    public static String permissionDescription(String capabilityId) {{
        if (capabilityId == null) return "";
        switch (capabilityId) {{
{permission_description_cases}
            default: return "插件请求使用此能力。";
        }}
    }}
}}
"""


def constant_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "_", value).strip("_").upper()


def generate(check: bool) -> None:
    index, capabilities = load_contract_index()
    outputs = {
        GENERATED_TS: generated_typescript(index, capabilities),
        GENERATED_JAVA: generated_java(index, capabilities),
    }
    stale: list[str] = []
    for path, content in outputs.items():
        if check:
            if not path.exists() or path.read_text(encoding="utf-8") != content:
                stale.append(str(path.relative_to(PACKAGE_ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8", newline="\n")
    if stale:
        raise AtsError("生成文件不是最新：" + ", ".join(stale))


def command_lint(arguments: argparse.Namespace) -> None:
    project, manifest_path = resolve_project(arguments.project)
    manifest, manifest_bytes = load_manifest(manifest_path)
    collect_project_files(project, manifest, manifest_bytes)
    print(f"OK {manifest['plugin']['id']} {manifest['plugin']['version']}")


def command_pack(arguments: argparse.Namespace) -> None:
    output = pack(arguments.project, arguments.output, arguments.signing_key)
    summary = verify(str(output), arguments.public_key, bool(arguments.public_key))
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))


def command_verify(arguments: argparse.Namespace) -> None:
    print(
        json.dumps(
            verify(arguments.package, arguments.public_key, arguments.require_signature),
            ensure_ascii=False,
            sort_keys=True,
        )
    )


def command_generate(arguments: argparse.Namespace) -> None:
    generate(arguments.check)
    print("OK generated contract bindings")


def command_create(arguments: argparse.Namespace) -> None:
    plugin_id = require_id(arguments.plugin_id, "plugin.id")
    publisher = require_id(arguments.publisher, "plugin.publisher")
    title = require_text(arguments.title, "plugin.title", 80)
    description = require_text(arguments.description, "plugin.description", 320)
    require_positive_int(arguments.min_host_version_code, "plugin.minHostVersionCode")
    target = Path(arguments.directory).resolve()
    if target.exists() and any(target.iterdir()):
        raise AtsError(f"目标目录不是空目录：{target}")
    target.mkdir(parents=True, exist_ok=True)
    manifest = {
        "$schema": "https://android-tool-suite.test/contracts/manifest-v3.schema.json",
        "format": "ats-plugin",
        "formatVersion": FORMAT_VERSION,
        "plugin": {
            "id": plugin_id,
            "title": title,
            "description": description,
            "version": "1.0.0",
            "versionCode": 1,
            "minHostVersionCode": arguments.min_host_version_code,
            "publisher": publisher,
            "kind": "tool",
        },
        "platforms": ["android"],
        "runtime": {
            "ui": [{"id": "main", "type": "declarative", "entry": "ui/main.json"}],
            "background": [],
            "providers": [],
        },
        "requires": {
            "plugins": [],
            "capabilities": [{"id": "app", "version": "^1.0.0", "optional": False, "scopes": {}}],
        },
        "provides": {"capabilities": []},
        "contributes": {"tools": [{"id": "main", "uiEntry": "main"}], "homeWidgets": []},
        "datasets": [],
        "tasks": [],
    }
    (target / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n"
    )
    if not TEMPLATE_ROOT.is_dir():
        raise AtsError("CLI Web Tool 模板缺失")
    shutil.copytree(TEMPLATE_ROOT / "web", target / "web", dirs_exist_ok=True)
    (target / "ui").mkdir(parents=True, exist_ok=True)
    (target / "ui" / "main.json").write_text(
        json.dumps({
            "$schema": "https://android-tool-suite.test/contracts/declarative-ui-v1.schema.json",
            "formatVersion": 1,
            "body": {"type": "webview", "entry": "web/index.html"},
        }, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    readme = (TEMPLATE_ROOT / "README.md").read_text(encoding="utf-8")
    (target / "README.md").write_text(
        readme.replace("@PLUGIN_ID@", plugin_id).replace("@PLUGIN_TITLE@", title),
        encoding="utf-8",
        newline="\n",
    )
    print(json.dumps({"created": str(target), "pluginId": plugin_id}, ensure_ascii=False, sort_keys=True))


class DevState:
    def __init__(self, project: Path, mock: dict[str, Any]):
        self.project = project
        self.mock = mock
        self.revision = 0
        self.condition = threading.Condition()
        self._snapshot = self.snapshot()
        self.stopped = False

    def snapshot(self) -> tuple[tuple[str, int, int], ...]:
        values: list[tuple[str, int, int]] = []
        for path in sorted(self.project.rglob("*")):
            if path.is_file() and not any(part.startswith(".") for part in path.relative_to(self.project).parts):
                stat = path.stat()
                values.append((path.relative_to(self.project).as_posix(), stat.st_mtime_ns, stat.st_size))
        return tuple(values)

    def watch(self) -> None:
        while not self.stopped:
            time.sleep(0.35)
            try:
                current = self.snapshot()
            except OSError:
                continue
            if current == self._snapshot:
                continue
            with self.condition:
                self._snapshot = current
                self.revision += 1
                self.condition.notify_all()


def dev_transport_script(manifest: dict[str, Any], mock: dict[str, Any]) -> str:
    plugin_id = json.dumps(manifest["plugin"]["id"])
    responses = json.dumps(mock, ensure_ascii=False, separators=(",", ":"))
    return f"""(() => {{
  const pluginId = {plugin_id};
  const sessionId = 'dev-browser';
  const responses = {responses};
  const transport = {{ onmessage: null, postMessage(raw) {{
    let message; try {{ message = JSON.parse(raw); }} catch (_) {{ return; }}
    const send = value => queueMicrotask(() => transport.onmessage?.({{data: JSON.stringify(value)}}));
    if (message.kind === 'hello') {{
      send({{protocol:'2.0',kind:'ready',pluginId,sessionId,requestId:'0',payload:{{
        platform:{{id:'browser-dev',api:0}},protocol:'2.0',capabilities:Object.keys(responses),
        theme:{{dark:matchMedia('(prefers-color-scheme: dark)').matches,css:''}},
        container:{{widthDp:innerWidth,heightDp:innerHeight}}
      }}}}); return;
    }}
    if (message.kind !== 'request') return;
    let configured = responses[message.method];
    if (message.method === 'app.getSession' && configured === undefined) configured = {{pluginId,sessionId,protocol:'2.0',platform:{{id:'browser-dev',api:0}}}};
    if (configured && typeof configured === 'object' && configured.error) {{
      send({{protocol:'2.0',kind:'response',pluginId,sessionId,requestId:message.requestId,ok:false,error:configured.error}});
    }} else if (configured !== undefined) {{
      send({{protocol:'2.0',kind:'response',pluginId,sessionId,requestId:message.requestId,ok:true,result:configured}});
    }} else {{
      send({{protocol:'2.0',kind:'response',pluginId,sessionId,requestId:message.requestId,ok:false,error:{{code:'CAPABILITY_UNAVAILABLE',message:`No dev mock for ${{message.method}}`,retryable:false}}}});
    }}
  }} }};
  window.atsTransport = transport;
  const metadata = {{'ats-plugin-id':pluginId,'ats-session-id':sessionId,'ats-protocol':'2.0'}};
  for (const [name,content] of Object.entries(metadata)) {{ const meta=document.createElement('meta');meta.name=name;meta.content=content;document.head.append(meta); }}
}})();
"""


def inject_dev_html(
    raw: bytes,
    native_plugin_id: str | None = None,
    native_session_id: str | None = None,
) -> bytes:
    try:
        html = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise AtsError("dev HTML 必须是 UTF-8") from error
    if native_plugin_id is not None and native_session_id is not None:
        bootstrap = (
            f'<meta name="ats-plugin-id" content="{html_module.escape(native_plugin_id, quote=True)}">'
            f'<meta name="ats-session-id" content="{html_module.escape(native_session_id, quote=True)}">'
            '<meta name="ats-protocol" content="2.0">'
            '<script>(()=>{let r,f=0;const t=setInterval(()=>fetch("/__ats_dev/revision",{cache:"no-store"})'
            '.then(x=>{if(!x.ok)throw new Error();return x.text();}).then(n=>{f=0;if(r===undefined)r=n;else if(r!==n)location.reload();})'
            '.catch(()=>{if(++f>=3)clearInterval(t);}),750);})();</script>'
        )
    else:
        bootstrap = (
            '<script src="/__ats_dev/transport.js"></script>'
            '<script>(()=>{let r,f=0;const t=setInterval(()=>fetch("/__ats_dev/revision",{cache:"no-store"})'
            '.then(x=>{if(!x.ok)throw new Error();return x.text();}).then(n=>{f=0;if(r===undefined)r=n;else if(r!==n)location.reload();})'
            '.catch(()=>{if(++f>=3)clearInterval(t);}),750);})();</script>'
        )
    match = re.search(r"</head\s*>", html, re.IGNORECASE)
    return (html[: match.start()] + bootstrap + html[match.start() :] if match else bootstrap + html).encode("utf-8")


def make_dev_handler(state: DevState, manifest: dict[str, Any]) -> type[http.server.SimpleHTTPRequestHandler]:
    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *args: Any, **kwargs: Any):
            super().__init__(*args, directory=str(state.project), **kwargs)

        def do_GET(self) -> None:
            parsed_url = urllib.parse.urlsplit(self.path)
            path = parsed_url.path
            if path == "/__ats_dev/events":
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Connection", "close")
                self.end_headers()
                previous = state.revision
                with state.condition:
                    state.condition.wait_for(lambda: state.revision != previous or state.stopped, timeout=25)
                try:
                    self.wfile.write(f"data: {state.revision}\n\n".encode("ascii"))
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    pass
                return
            if path == "/__ats_dev/revision":
                self._send_bytes(str(state.revision).encode("ascii"), "text/plain; charset=utf-8")
                return
            if path == "/__ats_dev/transport.js":
                self._send_bytes(dev_transport_script(manifest, state.mock).encode("utf-8"), "text/javascript; charset=utf-8")
                return
            if path == "/__ats__/theme.css":
                css = (TEMPLATE_ROOT / "web" / "dev-theme.css").read_bytes()
                self._send_bytes(css, "text/css; charset=utf-8")
                return
            relative = urllib.parse.unquote(path.lstrip("/")) or manifest["runtime"]["ui"][0]["entry"]
            try:
                normalized = validate_package_path(relative)
            except AtsError:
                self.send_error(404)
                return
            target = (state.project / normalized).resolve()
            try:
                target.relative_to(state.project)
            except ValueError:
                self.send_error(404)
                return
            if target.suffix.lower() == ".html" and target.is_file():
                query = urllib.parse.parse_qs(parsed_url.query)
                native = query.get("ats-host") == ["android"]
                plugin_id = query.get("ats-plugin-id", [None])[0] if native else None
                session_id = query.get("ats-session-id", [None])[0] if native else None
                self._send_bytes(
                    inject_dev_html(target.read_bytes(), plugin_id, session_id),
                    "text/html; charset=utf-8",
                )
                return
            super().do_GET()

        def _send_bytes(self, value: bytes, content_type: str) -> None:
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(value)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'")
            self.end_headers()
            self.wfile.write(value)

        def end_headers(self) -> None:
            self.send_header("X-Frame-Options", "DENY")
            super().end_headers()

        def log_message(self, pattern: str, *values: Any) -> None:
            if urllib.parse.urlsplit(self.path).path == "/__ats_dev/revision":
                return
            print(f"dev {self.address_string()} {pattern % values}", flush=True)

    return Handler


def command_dev(arguments: argparse.Namespace) -> None:
    project, manifest_path = resolve_project(arguments.project)
    manifest, manifest_bytes = load_manifest(manifest_path)
    collect_project_files(project, manifest.copy(), manifest_bytes)
    host = arguments.host
    if host not in {"127.0.0.1", "localhost", "::1"} and not arguments.allow_remote:
        raise AtsError("非回环 dev 地址必须显式传入 --allow-remote")
    mock: dict[str, Any] = {}
    if arguments.mock:
        try:
            mock_value = json.loads(Path(arguments.mock).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise AtsError(f"无法读取 Capability mock：{error}") from error
        require_type(mock_value, dict, "mock")
        mock = mock_value
    state = DevState(project, mock)
    handler = make_dev_handler(state, manifest)
    try:
        server = http.server.ThreadingHTTPServer((host, arguments.port), handler)
    except OSError as error:
        raise AtsError(f"无法启动 dev server：{error}") from error
    watcher = threading.Thread(target=state.watch, name="ats-dev-watcher", daemon=True)
    watcher.start()
    bound_host, bound_port = server.server_address[:2]
    display_host = "127.0.0.1" if bound_host in {"0.0.0.0", "::"} else bound_host
    entry = manifest["runtime"]["ui"][0]["entry"]
    print(f"ATS dev: http://{display_host}:{bound_port}/{entry}", flush=True)
    adb_prefix: list[str] | None = None
    if arguments.android:
        adb = shutil.which("adb")
        if adb is None:
            server.server_close()
            raise AtsError("--android 需要 PATH 中可用的 adb")
        adb_prefix = [adb]
        if arguments.serial:
            adb_prefix.extend(["-s", arguments.serial])
        configure_commands = [
            ["reverse", f"tcp:{bound_port}", f"tcp:{bound_port}"],
            [
                "shell", "am", "broadcast", "-W",
                "-a", "com.androidtoolsuite.app.debug.DEBUG_COMMAND",
                "-n", "com.androidtoolsuite.app.debug/.DebugCommandReceiver",
                "--es", "command", "set-v2-dev-server",
                "--es", "plugin", manifest["plugin"]["id"],
                "--es", "url", f"http://127.0.0.1:{bound_port}/{entry}",
            ],
        ]
        try:
            for command in configure_commands:
                result = subprocess.run([*adb_prefix, *command], capture_output=True, text=True, check=False)
                if result.returncode != 0 or "result=0" in result.stdout:
                    raise AtsError((result.stderr or result.stdout or "adb dev configuration failed").strip())
            print(f"ATS Android dev: {manifest['plugin']['id']} via adb reverse tcp:{bound_port}", flush=True)
        except Exception:
            subprocess.run([*adb_prefix, "reverse", "--remove", f"tcp:{bound_port}"], capture_output=True)
            server.server_close()
            raise
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        state.stopped = True
        with state.condition:
            state.condition.notify_all()
        server.server_close()
        if adb_prefix is not None:
            subprocess.run([
                *adb_prefix,
                "shell", "am", "broadcast", "-W",
                "-a", "com.androidtoolsuite.app.debug.DEBUG_COMMAND",
                "-n", "com.androidtoolsuite.app.debug/.DebugCommandReceiver",
                "--es", "command", "clear-v2-dev-server",
                "--es", "plugin", manifest["plugin"]["id"],
            ], capture_output=True)
            subprocess.run([*adb_prefix, "reverse", "--remove", f"tcp:{bound_port}"], capture_output=True)


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(prog="ats", description="Android Tool Suite Runtime v2 CLI")
    commands = root.add_subparsers(dest="command", required=True)

    lint = commands.add_parser("lint", help="validate a Runtime v2 plugin project")
    lint.add_argument("project")
    lint.set_defaults(handler=command_lint)

    package = commands.add_parser("pack", help="create a deterministic .atsplugin")
    package.add_argument("project")
    package.add_argument("--output", required=True)
    package.add_argument("--signing-key")
    package.add_argument("--public-key")
    package.set_defaults(handler=command_pack)

    verify_command = commands.add_parser("verify", help="verify a Runtime v2 .atsplugin")
    verify_command.add_argument("package")
    verify_command.add_argument("--public-key")
    verify_command.add_argument("--require-signature", action="store_true")
    verify_command.set_defaults(handler=command_verify)

    generate_command = commands.add_parser("generate", help="generate Java and TypeScript bindings")
    generate_command.add_argument("--check", action="store_true")
    generate_command.set_defaults(handler=command_generate)

    create_command = commands.add_parser("create", help="create a minimal Web Tool project")
    create_command.add_argument("directory")
    create_command.add_argument("--id", dest="plugin_id", required=True)
    create_command.add_argument("--title", required=True)
    create_command.add_argument("--description", default="一个 Android Tool Suite Web Tool。")
    create_command.add_argument("--publisher", required=True)
    create_command.add_argument("--min-host-version-code", type=int, default=23)
    create_command.set_defaults(handler=command_create)

    dev_command = commands.add_parser("dev", help="run a browser development harness with Capability mocks")
    dev_command.add_argument("project")
    dev_command.add_argument("--host", default="127.0.0.1")
    dev_command.add_argument("--port", type=int, default=8765)
    dev_command.add_argument("--mock")
    dev_command.add_argument("--allow-remote", action="store_true")
    dev_command.add_argument("--android", action="store_true", help="connect the Debug Android host through adb reverse")
    dev_command.add_argument("--serial", help="adb device serial used with --android")
    dev_command.set_defaults(handler=command_dev)
    return root


def main(argv: list[str] | None = None) -> int:
    try:
        arguments = parser().parse_args(argv)
        arguments.handler(arguments)
        return 0
    except AtsError as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
