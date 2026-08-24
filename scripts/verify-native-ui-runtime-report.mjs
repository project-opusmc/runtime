#!/usr/bin/env node

import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

function fail(message) {
  throw new Error(`native UI runtime report: ${message}`);
}

function requireValue(report, key, expected) {
  if (report[key] !== expected) {
    fail(`${key} must be ${JSON.stringify(expected)}, got ${JSON.stringify(report[key])}`);
  }
}

function requireIncludes(values, value, label) {
  if (!Array.isArray(values) || !values.includes(value)) {
    fail(`${label} must include ${JSON.stringify(value)}`);
  }
}

function requireEvent(values, value) {
  requireIncludes(values, value, "screenEvents");
}

function hasOrderedPair(values, first, second) {
  let firstIndex = -1;
  for (let index = 0; index < values.length; index += 1) {
    if (values[index] === first) {
      firstIndex = index;
    } else if (values[index] === second && firstIndex >= 0) {
      return true;
    }
  }
  return false;
}

function parseArguments(argv) {
  let reportPath = null;
  const options = {
    expectedSession: null,
    requireFullscreenToggle: false,
    requireGuiScaleChange: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--require-fullscreen-toggle") {
      options.requireFullscreenToggle = true;
    } else if (argument === "--require-gui-scale-change") {
      options.requireGuiScaleChange = true;
    } else if (argument === "--expected-session") {
      const value = argv[index + 1];
      if (value === undefined || value.length === 0) {
        fail("--expected-session requires a value");
      }
      options.expectedSession = value;
      index += 1;
    } else if (argument.startsWith("--")) {
      fail(`unknown option: ${argument}`);
    } else if (reportPath === null) {
      reportPath = resolve(argument);
    } else {
      fail(`unexpected argument: ${argument}`);
    }
  }
  if (reportPath === null) {
    fail("usage: verify-native-ui-runtime-report.mjs [--expected-session VALUE] [--require-fullscreen-toggle] [--require-gui-scale-change] REPORT");
  }
  return { options, reportPath };
}

const { options, reportPath } = parseArguments(process.argv.slice(2));
if (!existsSync(reportPath)) fail(`report is missing: ${reportPath}`);

let report;
try {
  report = JSON.parse(readFileSync(reportPath, "utf8"));
} catch (error) {
  fail(`report is not valid JSON: ${error.message}`);
}

requireValue(report, "schemaVersion", "2");
requireValue(report, "nativeUiVersion", "0.1.0");
if (options.expectedSession !== null) {
  requireValue(report, "runSession", options.expectedSession);
}
requireValue(report, "minecraft", "1.8.9");
requireValue(report, "forge", "11.15.1.2318");
requireValue(report, "elementa", "762");
requireValue(report, "universalCraft", "516");
requireValue(report, "oneConfig", "0.2.2-alpha228-full");
requireValue(report, "opusDesignVersion", "opus-design.1");
requireValue(report, "opusDesignParity", "pass");
requireValue(report, "nativeUiEnabled", "true");
requireValue(report, "optifineExpected", "true");
requireValue(report, "optifinePresent", "true");
const normalizedOptifineVersion = String(report.optifineVersion || "")
  .replaceAll("_", " ")
  .replace(/\s+/g, " ")
  .trim();
if (!/\bHD U M5\b/i.test(normalizedOptifineVersion)) {
  fail(`optifineVersion must identify HD U M5, got ${JSON.stringify(report.optifineVersion)}`);
}
if (!/^1\.8(?:\.0)?[_./-]/.test(String(report.javaVersion || ""))) {
  fail(`javaVersion must identify Java 8, got ${JSON.stringify(report.javaVersion)}`);
}
requireValue(report, "osName", "Mac OS X");
requireValue(report, "osArch", "x86_64");
requireIncludes(report.lifecycle, "opus-native-ui-pre-init", "lifecycle");
requireIncludes(report.lifecycle, "opus-native-ui-init", "lifecycle");

for (const key of ["oneConfigCodeSource", "elementaCodeSource", "universalCraftCodeSource"]) {
  if (typeof report[key] !== "string" || report[key].length === 0 || report[key] === "unknown") {
    fail(`${key} must identify a loaded code source`);
  }
}
if (!/oneconfig-1\.8\.9-forge-0\.2\.2-alpha228-full\.jar/.test(report.oneConfigCodeSource)) {
  fail("OneConfig code source is not the locked full artifact");
}
if (!/elementa-762\.jar/.test(report.elementaCodeSource)) {
  fail("Elementa code source is not artifact 762");
}
if (!/universalcraft-1\.8\.9-forge-516\.jar/.test(report.universalCraftCodeSource)) {
  fail("UniversalCraft code source is not candidate 516");
}

const openOrder = report.openOrder;
if (!Array.isArray(openOrder) || openOrder.length < 4) {
  fail("openOrder must contain at least four actual screen openings");
}
if (!hasOrderedPair(openOrder, "opusconfig", "elementa")) {
  fail("openOrder lacks OneConfig then Elementa");
}
if (!hasOrderedPair(openOrder, "elementa", "opusconfig")) {
  fail("openOrder lacks Elementa then OneConfig");
}

for (const screen of ["opusconfig", "elementa"]) {
  requireEvent(report.screenEvents, `open:${screen}`);
  requireEvent(report.screenEvents, `draw-pre:${screen}`);
  requireEvent(report.screenEvents, `draw-post:${screen}`);
  requireEvent(report.screenEvents, `close:${screen}`);
  requireValue(report, `${screen}GlPre`, "0");
  requireValue(report, `${screen}GlPost`, "0");
}

requireEvent(report.screenEvents, "draw-inner-before:elementa");
requireEvent(report.screenEvents, "draw-inner-after:elementa");
requireValue(report, "elementaGlInnerBefore", "0");
requireValue(report, "elementaGlInnerAfter", "0");

if (!Array.isArray(report.glErrors) || report.glErrors.length !== 0) {
  fail(`glErrors must be empty, got ${JSON.stringify(report.glErrors)}`);
}
if (!Array.isArray(report.displayStates) || report.displayStates.length < 2) {
  fail("displayStates must contain at least two distinct runtime states");
}
for (const screen of ["opusconfig", "elementa"]) {
  if (!report.displayStates.some((state) => state.startsWith(`${screen}|`))) {
    fail(`displayStates must contain ${screen} evidence`);
  }
}
if (options.requireGuiScaleChange) {
  const guiScaleSettings = new Set(
    report.displayStates.map((state) => state.match(/\|guiScaleSetting=([^|]+)/)?.[1]),
  );
  guiScaleSettings.delete(undefined);
  if (guiScaleSettings.size < 2) fail("Minecraft GUI scale setting was not changed during the run");
}
if (options.requireFullscreenToggle) {
  const fullscreenStates = new Set(report.displayStates.map((state) => state.match(/\|fullscreen=([^|]+)/)?.[1]));
  fullscreenStates.delete(undefined);
  if (fullscreenStates.size < 2) fail("fullscreen state was not toggled during the run");
}

requireValue(report, "elementaAfterInitialization", "true");
requireValue(report, "elementaClosed", "true");
requireValue(report, "elementaEscape", "restore-previous-screen");
requireValue(report, "opusConfigCallback", "invoked");

console.log(
  `verified native UI runtime report: ${openOrder.length} openings, ` +
    `${report.displayStates.length} display states, OptiFine ${report.optifineVersion}`,
);
