#!/usr/bin/env node

import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const verifier = resolve(scriptDirectory, "verify-native-ui-runtime-report.mjs");
const session = "fixture-session-0.1.0";
const temporaryDirectory = mkdtempSync(resolve(tmpdir(), "opus-native-ui-report-"));

function validReport() {
  return {
    updatedAt: "2026-08-24T00:00:00.000Z",
    schemaVersion: "2",
    nativeUiVersion: "0.1.0",
    runSession: session,
    minecraft: "1.8.9",
    forge: "11.15.1.2318",
    elementa: "762",
    universalCraft: "516",
    oneConfig: "0.2.2-alpha228-full",
    opusDesignVersion: "opus-design.1",
    opusDesignParity: "pass",
    javaVersion: "1.8.0_502",
    osName: "Mac OS X",
    osArch: "x86_64",
    oneConfigCodeSource: "jar:file:/cache/oneconfig-1.8.9-forge-0.2.2-alpha228-full.jar!/cc/polyfrost/oneconfig/gui/OneConfigGui.class",
    elementaCodeSource: "jar:file:/cache/elementa-762.jar!/gg/essential/elementa/WindowScreen.class",
    universalCraftCodeSource: "jar:file:/cache/universalcraft-1.8.9-forge-516.jar!/gg/essential/universal/UScreen.class",
    nativeUiEnabled: "true",
    musicPolicy: "startup-default-off",
    musicDisabled: "true",
    musicVolumeBefore: "1.0",
    musicVolumeAfter: "0.0",
    optifineExpected: "true",
    optifinePresent: "true",
    optifineVersion: "OptiFine_1.8.9_HD_U_M5",
    opusconfigGlPre: "0",
    opusconfigGlPost: "0",
    elementaGlPre: "0",
    elementaGlPost: "0",
    elementaGlInnerBefore: "0",
    elementaGlInnerAfter: "0",
    elementaAfterInitialization: "true",
    elementaClosed: "true",
    elementaEscape: "restore-previous-screen",
    opusConfigCallback: "invoked",
    liveProofOpusConfigTimingInitial:
      "samples=60|averageMs=16.700|p95Ms=17.200|maxMs=24.400|over33Ms=0|over50Ms=0",
    liveProofOpusConfigTimingFinal:
      "samples=60|averageMs=16.800|p95Ms=18.100|maxMs=31.200|over33Ms=0|over50Ms=0",
    lifecycle: ["opus-native-ui-pre-init", "opus-native-ui-init"],
    openOrder: ["opusconfig", "elementa", "opusconfig", "elementa"],
    screenEvents: [
      "open:opusconfig",
      "draw-pre:opusconfig",
      "draw-post:opusconfig",
      "close:opusconfig",
      "open:elementa",
      "draw-pre:elementa",
      "draw-inner-before:elementa",
      "draw-inner-after:elementa",
      "draw-post:elementa",
      "close:elementa",
    ],
    displayStates: [
      "opusconfig|window=1440x900|framebuffer=2880x1800|gui=720x450|scale=4.000|guiScaleSetting=2|fullscreen=false",
      "elementa|window=2560x1440|framebuffer=5120x2880|gui=640x360|scale=8.000|guiScaleSetting=4|fullscreen=true",
    ],
    glErrors: [],
  };
}

function runFixture(name, mutate, shouldPass, expectedMessage = "") {
  const report = validReport();
  mutate(report);
  const reportPath = resolve(temporaryDirectory, `${name}.json`);
  writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 });
  const result = spawnSync(process.execPath, [
    verifier,
    "--expected-session",
    session,
    "--require-fullscreen-toggle",
    "--require-gui-scale-change",
    reportPath,
  ], { encoding: "utf8" });
  const output = `${result.stdout || ""}\n${result.stderr || ""}`;
  if (shouldPass && result.status !== 0) {
    throw new Error(`${name} should pass:\n${output}`);
  }
  if (!shouldPass && result.status === 0) {
    throw new Error(`${name} should fail`);
  }
  if (!shouldPass && expectedMessage && !output.includes(expectedMessage)) {
    throw new Error(`${name} did not report ${JSON.stringify(expectedMessage)}:\n${output}`);
  }
}

try {
  runFixture("valid", () => {}, true);
  runFixture("stale-session", (report) => { report.runSession = "old-session"; }, false, "runSession must be");
  runFixture("stale-design-version", (report) => {
    report.opusDesignVersion = "opus-design.0";
  }, false, "opusDesignVersion must be \"opus-design.1\"");
  runFixture("design-parity-failed", (report) => {
    report.opusDesignParity = "failed";
  }, false, "opusDesignParity must be \"pass\"");
  runFixture("music-not-disabled", (report) => {
    report.musicDisabled = "false";
  }, false, "musicDisabled must be \"true\"");
  runFixture("music-volume-not-zero", (report) => {
    report.musicVolumeAfter = "0.5";
  }, false, "musicVolumeAfter must be \"0.0\"");
  runFixture("missing-reverse-order", (report) => {
    report.openOrder = ["opusconfig", "opusconfig", "elementa", "elementa"];
  }, false, "openOrder lacks Elementa then OneConfig");
  runFixture("gl-error", (report) => {
    report.glErrors = ["elementa:inner-after:1282"];
  }, false, "glErrors must be empty");
  runFixture("missing-elementa-inner-before-event", (report) => {
    report.screenEvents = report.screenEvents.filter((event) => event !== "draw-inner-before:elementa");
  }, false, "screenEvents must include \"draw-inner-before:elementa\"");
  runFixture("missing-elementa-inner-after-event", (report) => {
    report.screenEvents = report.screenEvents.filter((event) => event !== "draw-inner-after:elementa");
  }, false, "screenEvents must include \"draw-inner-after:elementa\"");
  runFixture("elementa-inner-before-gl-error", (report) => {
    report.elementaGlInnerBefore = "1282";
  }, false, "elementaGlInnerBefore must be \"0\"");
  runFixture("elementa-inner-after-gl-error", (report) => {
    report.elementaGlInnerAfter = "1282";
  }, false, "elementaGlInnerAfter must be \"0\"");
  runFixture("unchanged-gui-scale", (report) => {
    report.displayStates[1] = report.displayStates[1].replace("guiScaleSetting=4", "guiScaleSetting=2");
  }, false, "GUI scale setting was not changed");
  runFixture("unchanged-fullscreen", (report) => {
    report.displayStates[1] = report.displayStates[1].replace("fullscreen=true", "fullscreen=false");
  }, false, "fullscreen state was not toggled");
  runFixture("missing-opusconfig-timing", (report) => {
    delete report.liveProofOpusConfigTimingInitial;
  }, false, "must contain OpusConfig frame timing evidence");
  runFixture("choppy-opusconfig-p95", (report) => {
    report.liveProofOpusConfigTimingFinal =
      "samples=60|averageMs=28.000|p95Ms=58.000|maxMs=72.000|over33Ms=8|over50Ms=5";
  }, false, "p95 frame time exceeds 40 ms");
  console.log("verified native UI runtime report verifier fixtures: 1 pass, 15 expected failures");
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true });
}
