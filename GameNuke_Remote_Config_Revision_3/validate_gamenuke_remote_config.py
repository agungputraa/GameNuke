#!/usr/bin/env python3
"""Validate GameNuke remote product configuration before publishing.

Runs full Draft 2020-12 validation when the optional ``jsonschema`` package is
installed, then always runs GameNuke-specific semantic and safety checks using
only the Python standard library.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any, Iterable


RAW_SHELL_KEYS = {
    "command",
    "commands",
    "execute",
    "executeCandidates",
    "shellTemplate",
    "script",
    "argv",
}
OPERATION_STEP_TYPES = {
    "invoke_operation_if_supported",
    "invoke_if_selected_and_supported",
    "invoke_for_each_validated_selection",
    "rollback_operation_if_active",
}
PIPELINE_STEP_TYPES = {"invoke_pipeline_if_enabled", "toggle_pipeline"}
METRIC_STEP_TYPES = {"capture_metrics", "refresh_metrics"}


def read_json(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ValueError(f"File not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f"{path}: invalid JSON at line {exc.lineno}, column {exc.colno}: {exc.msg}") from exc
    if not isinstance(data, dict):
        raise ValueError(f"{path}: root must be a JSON object")
    return data


def ids(items: Iterable[dict[str, Any]], label: str, errors: list[str]) -> set[str]:
    values = [str(item.get("id", "")) for item in items]
    blanks = [index for index, value in enumerate(values) if not value]
    if blanks:
        errors.append(f"{label}: missing id at indexes {blanks}")
    duplicates = sorted({value for value in values if value and values.count(value) > 1})
    if duplicates:
        errors.append(f"{label}: duplicate ids {duplicates}")
    return {value for value in values if value}


def require_capabilities(item: dict[str, Any], label: str, capability_ids: set[str], errors: list[str]) -> None:
    for key in ("requires", "requiresAll", "requiresAny"):
        for capability in item.get(key, []):
            if capability not in capability_ids:
                errors.append(f"{label}: unresolved capability {capability}")


def full_schema_validate(config: dict[str, Any], schema: dict[str, Any], warnings: list[str]) -> list[str]:
    try:
        import jsonschema  # type: ignore
    except ImportError:
        warnings.append("Optional package 'jsonschema' not installed; semantic checks still ran")
        return []

    errors: list[str] = []
    try:
        jsonschema.Draft202012Validator.check_schema(schema)
        validator = jsonschema.Draft202012Validator(schema)
        for issue in sorted(validator.iter_errors(config), key=lambda item: list(item.absolute_path)):
            where = "$" + "".join(f"[{part}]" if isinstance(part, int) else f".{part}" for part in issue.absolute_path)
            errors.append(f"schema {where}: {issue.message}")
    except Exception as exc:  # defensive: malformed external validator environment
        errors.append(f"schema validation failed: {exc}")
    return errors


def semantic_validate(config: dict[str, Any]) -> list[str]:
    errors: list[str] = []

    expected_top_level = {
        "$schema", "schemaVersion", "configId", "revision", "publishedAt", "enabled", "channel",
        "minAppVersionCode", "compatibility", "changePolicy", "delivery", "activation", "security",
        "runtimePolicy", "privacy", "accessibility", "diagnostics", "featureFlags", "ruleEngine",
        "theme", "responsive", "appShell", "overlay", "deviceProfiles", "capabilities",
        "rendererCatalog", "resourceCatalog", "localization", "stateModel", "metricCatalog",
        "operationCatalog", "shellCatalog", "pipelines", "quickControls", "panels",
        "errorCatalog", "selfTests",
    }
    missing = sorted(expected_top_level - set(config))
    unexpected = sorted(set(config) - expected_top_level)
    if missing:
        errors.append(f"root: missing sections {missing}")
    if unexpected:
        errors.append(f"root: unexpected sections {unexpected}")
    if missing:
        return errors

    if config["schemaVersion"] != 1:
        errors.append("schemaVersion must remain 1 for this client contract")
    if not isinstance(config["revision"], int) or config["revision"] < 1:
        errors.append("revision must be a positive integer")
    if config["minAppVersionCode"] < 33:
        errors.append("minAppVersionCode must be at least 33 for the remote-config client")

    profiles = ids(config["deviceProfiles"], "deviceProfiles", errors)
    capabilities = ids(config["capabilities"], "capabilities", errors)
    renderers = ids(config["rendererCatalog"], "rendererCatalog", errors)
    metrics = ids(config["metricCatalog"], "metricCatalog", errors)
    local_operations = ids(config["operationCatalog"], "operationCatalog", errors)
    shell_operations = ids(config["shellCatalog"], "shellCatalog", errors)
    pipelines = ids(config["pipelines"], "pipelines", errors)
    flags = ids(config["featureFlags"], "featureFlags", errors)
    state_keys = ids(config["stateModel"]["settings"], "stateModel.settings", errors)
    ids(config["quickControls"], "quickControls", errors)
    ids(config["panels"], "panels", errors)
    ids(config["appShell"]["routes"], "appShell.routes", errors)
    ids(config["errorCatalog"], "errorCatalog", errors)
    ids(config["selfTests"], "selfTests", errors)

    all_operations = local_operations | shell_operations | pipelines
    icons = set(config["resourceCatalog"]["bundledIcons"])

    for profile in config["deviceProfiles"]:
        inherited = profile.get("inherits")
        if inherited and inherited not in profiles:
            errors.append(f"deviceProfile {profile['id']}: unresolved parent {inherited}")

    for collection_name in ("featureFlags", "operationCatalog", "shellCatalog", "quickControls", "panels"):
        for item in config[collection_name]:
            require_capabilities(item, f"{collection_name}.{item['id']}", capabilities, errors)

    for flag in config["featureFlags"]:
        rollout = flag.get("rolloutPercent")
        if not isinstance(rollout, int) or not 0 <= rollout <= 100:
            errors.append(f"featureFlag {flag['id']}: rolloutPercent must be 0..100")
    macro_flag = next((item for item in config["featureFlags"] if item["id"] == "macro_studio"), None)
    if macro_flag and (macro_flag.get("enabled") or macro_flag.get("rolloutPercent") != 0):
        errors.append("macro_studio must remain disabled until Play disclosure and review are completed")

    for operation in config["operationCatalog"]:
        if operation["kind"] == "reversible_session" and not operation.get("rollback"):
            errors.append(f"operation {operation['id']}: reversible session missing rollback")
        handler = operation.get("handlerId", "")
        if not handler.startswith("builtin."):
            errors.append(f"operation {operation['id']}: handler must be a built-in registry id")

    for operation in config["shellCatalog"]:
        raw_keys = sorted(set(operation) & RAW_SHELL_KEYS)
        if raw_keys:
            errors.append(f"shell {operation['id']}: raw executable fields forbidden {raw_keys}")
        if not str(operation.get("handlerId", "")).startswith("builtin.shell."):
            errors.append(f"shell {operation['id']}: handler must be a built-in shell registry id")
        if not operation.get("policyId") or not operation.get("probeId") or not operation.get("verifyId"):
            errors.append(f"shell {operation['id']}: policy, probe, and verify are required")
        if operation["kind"] == "session_mutation" and not operation.get("rollbackId"):
            errors.append(f"shell {operation['id']}: session mutation missing rollback")

    for pipeline in config["pipelines"]:
        step_ids = [step.get("id") for step in pipeline["steps"]]
        if len(step_ids) != len(set(step_ids)):
            errors.append(f"pipeline {pipeline['id']}: duplicate step ids")
        for step in pipeline["steps"]:
            step_type = step["type"]
            target = step.get("target")
            if step_type in OPERATION_STEP_TYPES and target not in local_operations | shell_operations:
                errors.append(f"pipeline {pipeline['id']}.{step['id']}: unresolved operation {target}")
            if step_type in PIPELINE_STEP_TYPES and target not in pipelines:
                errors.append(f"pipeline {pipeline['id']}.{step['id']}: unresolved pipeline {target}")
            if step_type in METRIC_STEP_TYPES:
                for metric in step.get("targets", []):
                    if metric not in metrics:
                        errors.append(f"pipeline {pipeline['id']}.{step['id']}: unresolved metric {metric}")
            if step_type.startswith("invoke_local") and target and not target.startswith("builtin."):
                errors.append(f"pipeline {pipeline['id']}.{step['id']}: local target must be built-in")

    for control in config["quickControls"]:
        if control["operationId"] not in all_operations:
            errors.append(f"quickControl {control['id']}: unresolved operation {control['operationId']}")
        if control["type"] not in renderers:
            errors.append(f"quickControl {control['id']}: unknown renderer {control['type']}")
        if control["icon"] not in icons:
            errors.append(f"quickControl {control['id']}: unknown icon {control['icon']}")
        if control.get("stateKey") and control["stateKey"] not in state_keys:
            errors.append(f"quickControl {control['id']}: unresolved stateKey {control['stateKey']}")

    home_route = config["appShell"]["homeRoute"]
    route_ids = {route["id"] for route in config["appShell"]["routes"]}
    if home_route not in route_ids:
        errors.append(f"appShell: unresolved homeRoute {home_route}")
    for route in config["appShell"]["routes"]:
        if route["icon"] not in icons:
            errors.append(f"appRoute {route['id']}: unknown icon {route['icon']}")
        if route.get("featureFlag") and route["featureFlag"] not in flags:
            errors.append(f"appRoute {route['id']}: unresolved featureFlag {route['featureFlag']}")
        require_capabilities(route, f"appRoute.{route['id']}", capabilities, errors)

    component_ids: set[str] = set()
    for panel in config["panels"]:
        if panel["icon"] not in icons:
            errors.append(f"panel {panel['id']}: unknown icon {panel['icon']}")
        if panel.get("featureFlag") and panel["featureFlag"] not in flags:
            errors.append(f"panel {panel['id']}: unresolved featureFlag {panel['featureFlag']}")
        for component in panel["components"]:
            component_id = component["id"]
            if component_id in component_ids:
                errors.append(f"component: duplicate id {component_id}")
            component_ids.add(component_id)
            if component["type"] not in renderers:
                errors.append(f"component {component_id}: unknown renderer {component['type']}")
            if component.get("operationId") and component["operationId"] not in all_operations:
                errors.append(f"component {component_id}: unresolved operation {component['operationId']}")
            if component.get("featureFlag") and component["featureFlag"] not in flags:
                errors.append(f"component {component_id}: unresolved featureFlag {component['featureFlag']}")
            if component.get("stateKey") and component["stateKey"] not in state_keys:
                errors.append(f"component {component_id}: unresolved stateKey {component['stateKey']}")
            require_capabilities(component, f"component.{component_id}", capabilities, errors)
            linked_metrics = ([component["metric"]] if component.get("metric") else []) + component.get("metrics", [])
            for metric in linked_metrics:
                if metric not in metrics:
                    errors.append(f"component {component_id}: unresolved metric {metric}")

    change_policy = config["changePolicy"]
    security = config["security"]
    if change_policy.get("remoteExecutableCodeAllowed") is not False:
        errors.append("remote executable code must remain disabled")
    if change_policy.get("remoteRawShellAllowed") is not False:
        errors.append("remote raw shell must remain disabled")
    if security.get("allowRemoteShellTemplates") is not False or security.get("allowArbitraryShell") is not False:
        errors.append("remote shell templates and arbitrary shell must remain disabled")
    if security["integrity"].get("requiredInProduction") is not True:
        errors.append("production signature verification must remain required")

    launch_policy = config["overlay"]["boostLaunchPolicy"]
    required_true = ("neverMutateResolution", "neverMutateDensity", "neverRunWmSize", "neverRunWmDensity")
    for key in required_true:
        if launch_policy.get(key) is not True:
            errors.append(f"overlay.boostLaunchPolicy.{key} must remain true")
    module_shop = config["overlay"]["mainPanel"].get("moduleShop")
    if not isinstance(module_shop, dict):
        errors.append("overlay.mainPanel.moduleShop is required")
    else:
        expected_catalog = "https://raw.githubusercontent.com/AgungDevlop/ModuleShop/main/plugins.json"
        if module_shop.get("catalogUrl") != expected_catalog:
            errors.append("moduleShop.catalogUrl must remain pinned to AgungDevlop/ModuleShop main/plugins.json")
        if not re.fullmatch(r"[0-9a-f]{64}", str(module_shop.get("catalogSha256", ""))):
            errors.append("moduleShop.catalogSha256 must be a lowercase SHA-256")
        if module_shop.get("archiveEntries") != ["exec.sh", "del.sh"]:
            errors.append("moduleShop archives must contain only exec.sh and del.sh")
        if module_shop.get("executionMode") != "android_shell_uid_only_no_root":
            errors.append("moduleShop execution must remain shell-UID-only and non-root")
        for key in ("rejectZipSlip", "rejectRedirects", "singleFlight"):
            if module_shop.get(key) is not True:
                errors.append(f"moduleShop.{key} must remain true")
    boost = next((pipeline for pipeline in config["pipelines"] if pipeline["id"] == "boost.launch"), None)
    if boost is None:
        errors.append("required pipeline boost.launch is missing")
    else:
        serialized = json.dumps(boost, sort_keys=True).lower()
        for token in ("wm size", "wm density", "wm_size", "wm_density"):
            if token in serialized and token not in json.dumps(boost.get("forbiddenTargets", [])).lower():
                errors.append(f"boost.launch contains forbidden display mutation token {token}")

    accessibility = config["accessibility"]
    if accessibility.get("minimumTouchTargetDp", 0) < 48:
        errors.append("accessibility.minimumTouchTargetDp must be at least 48")
    if accessibility.get("minimumContrastRatio", 0) < 4.5:
        errors.append("accessibility.minimumContrastRatio must be at least 4.5")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate GameNuke remote config and schema")
    parser.add_argument("config", nargs="?", default="gamenuke-remote-config.v1.json")
    parser.add_argument("schema", nargs="?", default="gamenuke-remote-config.schema.v1.json")
    args = parser.parse_args()

    try:
        config_path = Path(args.config).resolve()
        schema_path = Path(args.schema).resolve()
        config = read_json(config_path)
        schema = read_json(schema_path)
    except ValueError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1

    warnings: list[str] = []
    errors = full_schema_validate(config, schema, warnings)
    errors.extend(semantic_validate(config))

    for warning in warnings:
        print(f"WARN: {warning}")
    if errors:
        print(f"FAIL: {len(errors)} validation error(s)", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1

    digest = hashlib.sha256(config_path.read_bytes()).hexdigest()
    summary = {
        "revision": config["revision"],
        "profiles": len(config["deviceProfiles"]),
        "capabilities": len(config["capabilities"]),
        "renderers": len(config["rendererCatalog"]),
        "stateSettings": len(config["stateModel"]["settings"]),
        "metrics": len(config["metricCatalog"]),
        "localOperations": len(config["operationCatalog"]),
        "shellOperations": len(config["shellCatalog"]),
        "pipelines": len(config["pipelines"]),
        "appRoutes": len(config["appShell"]["routes"]),
        "panels": len(config["panels"]),
        "components": sum(len(panel["components"]) for panel in config["panels"]),
        "moduleShopCatalogSha256": config["overlay"]["mainPanel"]["moduleShop"]["catalogSha256"],
        "moduleShopMaxEntries": config["overlay"]["mainPanel"]["moduleShop"]["maxEntries"],
    }
    print("PASS: GameNuke remote config is valid")
    print(json.dumps(summary, sort_keys=True))
    print(f"sha256={digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
