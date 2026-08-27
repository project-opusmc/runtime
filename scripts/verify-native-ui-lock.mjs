#!/usr/bin/env node

import { createHash } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { basename, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const defaultLockPath = resolve(
  scriptDirectory,
  "../legacy/1.8.9/client/native-ui-dependencies.lock.json",
);

function fail(message) {
  throw new Error(`native UI dependency lock: ${message}`);
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function requireObject(value, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    fail(`${label} must be an object`);
  }
  return value;
}

function requireString(value, label, pattern = null) {
  if (typeof value !== "string" || value.length === 0) {
    fail(`${label} must be a non-empty string`);
  }
  if (pattern !== null && !pattern.test(value)) {
    fail(`${label} has an invalid value: ${value}`);
  }
  return value;
}

function requireExact(actual, expected, label) {
  if (actual !== expected) {
    fail(`${label} must be ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

function requireHttpsUrl(value, label) {
  const raw = requireString(value, label);
  let parsed;
  try {
    parsed = new URL(raw);
  } catch {
    fail(`${label} is not a valid URL: ${raw}`);
  }
  if (parsed.protocol !== "https:") {
    fail(`${label} must use HTTPS: ${raw}`);
  }
  if (/latest|snapshot|\+/i.test(parsed.pathname)) {
    fail(`${label} contains a mutable version selector: ${raw}`);
  }
  return raw;
}

function validateFileRecord(record, label) {
  const value = requireObject(record, label);
  const file = requireString(value.file, `${label}.file`, /^[A-Za-z0-9._+-]+$/);
  requireHttpsUrl(value.url, `${label}.url`);
  if (basename(new URL(value.url).pathname) !== file) {
    fail(`${label}.url filename does not match ${file}`);
  }
  if (!Number.isSafeInteger(value.size) || value.size <= 0) {
    fail(`${label}.size must be a positive safe integer`);
  }
  requireString(value.sha256, `${label}.sha256`, /^[0-9a-f]{64}$/);
}

function validateBuildInput(record, label) {
  const value = requireObject(record, label);
  validateFileRecord(value, label);
  requireString(value.version, `${label}.version`);
  if (value.vendor !== undefined) {
    requireString(value.vendor, `${label}.vendor`);
  }
}

function validateLock(lock) {
  const root = requireObject(lock, "root");
  requireExact(root.schemaVersion, 1, "schemaVersion");
  requireString(root.updatedAt, "updatedAt", /^\d{4}-\d{2}-\d{2}$/);

  const runtime = requireObject(root.runtime, "runtime");
  requireExact(runtime.minecraft, "1.8.9", "runtime.minecraft");
  requireExact(runtime.forge, "11.15.1.2318", "runtime.forge");
  requireExact(runtime.optifine, "HD U M5", "runtime.optifine");
  requireExact(runtime.javaBytecode, 8, "runtime.javaBytecode");

  const sourceBuilds = requireObject(root.sourceBuilds, "sourceBuilds");
  const oneConfigBuild = requireObject(sourceBuilds.oneConfig, "sourceBuilds.oneConfig");
  const buildHost = requireObject(oneConfigBuild.host, "sourceBuilds.oneConfig.host");
  requireExact(buildHost.os, "macos", "sourceBuilds.oneConfig.host.os");
  requireExact(
    buildHost.architecture,
    "x86_64",
    "sourceBuilds.oneConfig.host.architecture",
  );
  validateBuildInput(oneConfigBuild.gradle, "sourceBuilds.oneConfig.gradle");
  validateBuildInput(
    oneConfigBuild.launcherJdk,
    "sourceBuilds.oneConfig.launcherJdk",
  );
  validateBuildInput(
    oneConfigBuild.java8Toolchain,
    "sourceBuilds.oneConfig.java8Toolchain",
  );
  requireExact(oneConfigBuild.gradle.version, "8.7", "sourceBuilds.oneConfig.gradle.version");
  requireExact(
    oneConfigBuild.launcherJdk.version,
    "17.0.17+10",
    "sourceBuilds.oneConfig.launcherJdk.version",
  );
  requireExact(
    oneConfigBuild.java8Toolchain.version,
    "8u502-b07",
    "sourceBuilds.oneConfig.java8Toolchain.version",
  );
  requireExact(
    oneConfigBuild.task,
    ":platform:1.8.9-forge:build",
    "sourceBuilds.oneConfig.task",
  );
  requireExact(
    oneConfigBuild.artifactPath,
    "versions/1.8.9-forge/build/libs/oneconfig-1.8.9-forge-0.2.2-alpha228.jar",
    "sourceBuilds.oneConfig.artifactPath",
  );
  requireExact(
    oneConfigBuild.publishedArtifactId,
    "oneconfig",
    "sourceBuilds.oneConfig.publishedArtifactId",
  );
  requireExact(
    oneConfigBuild.expectedExtractedFiles,
    1550,
    "sourceBuilds.oneConfig.expectedExtractedFiles",
  );
  const buildProperties = requireObject(
    oneConfigBuild.gradleProjectProperties,
    "sourceBuilds.oneConfig.gradleProjectProperties",
  );
  requireExact(
    buildProperties["org.gradle.java.installations.auto-detect"],
    "false",
    "sourceBuilds.oneConfig.gradleProjectProperties auto-detect",
  );
  requireExact(
    buildProperties["org.gradle.java.installations.auto-download"],
    "false",
    "sourceBuilds.oneConfig.gradleProjectProperties auto-download",
  );

  if (!Array.isArray(root.sources) || root.sources.length !== 3) {
    fail("sources must contain exactly OneConfig, Elementa, and UniversalCraft");
  }
  const sourceIds = new Set();
  for (const source of root.sources) {
    requireObject(source, "source");
    const id = requireString(source.id, "source.id", /^[a-z][a-z0-9-]+$/);
    if (sourceIds.has(id)) {
      fail(`duplicate source id: ${id}`);
    }
    sourceIds.add(id);
    requireHttpsUrl(source.repository, `source ${id}.repository`);
    requireString(source.commit, `source ${id}.commit`, /^[0-9a-f]{40}$/);
    const license = requireObject(source.license, `source ${id}.license`);
    requireString(license.path, `source ${id}.license.path`);
    requireString(
      license.sha256,
      `source ${id}.license.sha256`,
      /^[0-9a-f]{64}$/,
    );
  }
  for (const requiredId of ["oneconfig", "elementa", "universalcraft"]) {
    if (!sourceIds.has(requiredId)) {
      fail(`missing source id: ${requiredId}`);
    }
  }

  if (!Array.isArray(root.artifacts) || root.artifacts.length < 6) {
    fail("artifacts must include the frozen baselines and compatibility candidates");
  }
  const artifacts = new Map();
  const coordinates = new Set();
  for (const artifact of root.artifacts) {
    requireObject(artifact, "artifact");
    const id = requireString(artifact.id, "artifact.id", /^[a-z][a-z0-9-]+$/);
    if (artifacts.has(id)) {
      fail(`duplicate artifact id: ${id}`);
    }
    artifacts.set(id, artifact);
    requireString(artifact.status, `artifact ${id}.status`);
    const coordinate = requireString(
      artifact.coordinate,
      `artifact ${id}.coordinate`,
      /^[^:]+:[^:]+:[^:]+(?::[^:]+)?$/,
    );
    if (/latest|snapshot|\+/i.test(coordinate)) {
      fail(`artifact ${id} uses a mutable coordinate: ${coordinate}`);
    }
    if (coordinates.has(coordinate)) {
      fail(`duplicate artifact coordinate: ${coordinate}`);
    }
    coordinates.add(coordinate);
    requireHttpsUrl(artifact.repository, `artifact ${id}.repository`);
    if (artifact.source !== undefined && !sourceIds.has(artifact.source)) {
      fail(`artifact ${id} references unknown source ${artifact.source}`);
    }
    validateFileRecord(artifact, `artifact ${id}`);
    if (artifact.metadata !== undefined) {
      validateFileRecord(artifact.metadata, `artifact ${id}.metadata`);
    }
  }

  const expectedCoordinates = new Map([
    ["oneconfig", "cc.polyfrost:oneconfig-1.8.9-forge:0.2.2-alpha228"],
    ["oneconfig-full", "cc.polyfrost:oneconfig-1.8.9-forge:0.2.2-alpha228:full"],
    ["opusconfig-full", "org.polydevs.opusmc:opusconfig-1.8.9-forge:0.2.2-alpha228-opus:full"],
    ["oneconfig-universalcraft", "cc.polyfrost:universalcraft-1.8.9-forge:246"],
    ["elementa", "gg.essential:elementa:762"],
    ["universalcraft-source-matched", "gg.essential:universalcraft-1.8.9-forge:494"],
    ["universalcraft-current-candidate", "gg.essential:universalcraft-1.8.9-forge:516"],
  ]);
  for (const [id, coordinate] of expectedCoordinates) {
    if (!artifacts.has(id)) {
      fail(`missing required artifact ${id}`);
    }
    requireExact(artifacts.get(id).coordinate, coordinate, `artifact ${id}.coordinate`);
  }

  const compatibility = requireObject(root.compatibility, "compatibility");
  requireExact(
    compatibility.oneConfigDeclaredUniversalCraft,
    246,
    "compatibility.oneConfigDeclaredUniversalCraft",
  );
  requireExact(
    compatibility.elementaSourceDeclaredUniversalCraft,
    494,
    "compatibility.elementaSourceDeclaredUniversalCraft",
  );
  if (
    !Array.isArray(compatibility.universalCraftCandidates) ||
    compatibility.universalCraftCandidates.join(",") !== "494,516"
  ) {
    fail("compatibility.universalCraftCandidates must be [494, 516]");
  }
  if (
    compatibility.selectedElementaUniversalCraft !== null &&
    !compatibility.universalCraftCandidates.includes(
      compatibility.selectedElementaUniversalCraft,
    )
  ) {
    fail("selected Elementa UniversalCraft version is not a locked candidate");
  }
  if (
    !Array.isArray(compatibility.requiredProofs) ||
    compatibility.requiredProofs.length < 5
  ) {
    fail("compatibility.requiredProofs is incomplete");
  }

  const releaseGates = requireObject(root.releaseGates, "releaseGates");
  for (const gate of [
    "oneConfigAttributionDecision",
    "transitiveLicenseInventory",
    "elementaUniversalCraftRuntimeProof",
  ]) {
    if (!new Set(["open", "resolved"]).has(releaseGates[gate])) {
      fail(`releaseGates.${gate} must be open or resolved`);
    }
  }

  return { artifacts, sources: root.sources };
}

function verifyBytes(bytes, record, label) {
  if (bytes.length !== record.size) {
    fail(`${label} size mismatch: expected ${record.size}, got ${bytes.length}`);
  }
  const actualHash = sha256(bytes);
  if (actualHash !== record.sha256) {
    fail(`${label} SHA-256 mismatch: expected ${record.sha256}, got ${actualHash}`);
  }
}

function verifyCachedFile(cacheDirectory, record, label) {
  const filePath = resolve(cacheDirectory, record.file);
  if (!existsSync(filePath)) {
    fail(`${label} is missing from cache: ${filePath}`);
  }
  if (!statSync(filePath).isFile()) {
    fail(`${label} cache entry is not a file: ${filePath}`);
  }
  verifyBytes(readFileSync(filePath), record, label);
}

async function fetchRecord(cacheDirectory, record, label) {
  mkdirSync(cacheDirectory, { recursive: true });
  const destination = resolve(cacheDirectory, record.file);
  if (existsSync(destination)) {
    verifyCachedFile(cacheDirectory, record, label);
    return;
  }
  const temporary = `${destination}.partial-${process.pid}`;
  rmSync(temporary, { force: true });
  try {
    const response = await fetch(record.url, { redirect: "follow" });
    if (!response.ok) {
      fail(`${label} download returned HTTP ${response.status}`);
    }
    const bytes = Buffer.from(await response.arrayBuffer());
    verifyBytes(bytes, record, label);
    writeFileSync(temporary, bytes, { flag: "wx", mode: 0o600 });
    renameSync(temporary, destination);
  } finally {
    rmSync(temporary, { force: true });
  }
}

function verifySource(source, sourcePath) {
  const root = resolve(sourcePath);
  const revision = spawnSync("git", ["-C", root, "rev-parse", "HEAD"], {
    encoding: "utf8",
  });
  if (revision.status !== 0) {
    fail(`cannot read ${source.id} git revision at ${root}: ${revision.stderr.trim()}`);
  }
  requireExact(revision.stdout.trim(), source.commit, `${source.id} source commit`);
  const licensePath = resolve(root, source.license.path);
  if (!existsSync(licensePath)) {
    fail(`${source.id} license is missing: ${licensePath}`);
  }
  requireExact(
    sha256(readFileSync(licensePath)),
    source.license.sha256,
    `${source.id} license SHA-256`,
  );
}

function parseArguments(argv) {
  let lockPath = defaultLockPath;
  let cacheDirectory = null;
  let fetchDirectory = null;
  const sourcePaths = new Map();
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--cache" || argument === "--fetch") {
      const value = argv[index + 1];
      if (value === undefined) {
        fail(`${argument} requires a directory`);
      }
      index += 1;
      if (argument === "--cache") {
        cacheDirectory = resolve(value);
      } else {
        fetchDirectory = resolve(value);
      }
    } else if (argument === "--source") {
      const value = argv[index + 1];
      if (value === undefined || !value.includes("=")) {
        fail("--source requires id=/absolute/or/relative/path");
      }
      index += 1;
      const separator = value.indexOf("=");
      sourcePaths.set(value.slice(0, separator), value.slice(separator + 1));
    } else if (argument.startsWith("--")) {
      fail(`unknown option: ${argument}`);
    } else if (lockPath === defaultLockPath) {
      lockPath = resolve(argument);
    } else {
      fail(`unexpected argument: ${argument}`);
    }
  }
  if (cacheDirectory !== null && fetchDirectory !== null) {
    fail("--cache and --fetch are mutually exclusive");
  }
  return { cacheDirectory, fetchDirectory, lockPath, sourcePaths };
}

const options = parseArguments(process.argv.slice(2));
const lock = JSON.parse(readFileSync(options.lockPath, "utf8"));
const validated = validateLock(lock);

for (const [sourceId, sourcePath] of options.sourcePaths) {
  const source = validated.sources.find((candidate) => candidate.id === sourceId);
  if (source === undefined) {
    fail(`--source references unknown source id: ${sourceId}`);
  }
  verifySource(source, sourcePath);
}

if (options.cacheDirectory !== null) {
  for (const artifact of validated.artifacts.values()) {
    verifyCachedFile(options.cacheDirectory, artifact, `artifact ${artifact.id}`);
    if (artifact.metadata !== undefined) {
      verifyCachedFile(
        options.cacheDirectory,
        artifact.metadata,
        `artifact ${artifact.id}.metadata`,
      );
    }
  }
}

if (options.fetchDirectory !== null) {
  for (const artifact of validated.artifacts.values()) {
    await fetchRecord(options.fetchDirectory, artifact, `artifact ${artifact.id}`);
    if (artifact.metadata !== undefined) {
      await fetchRecord(
        options.fetchDirectory,
        artifact.metadata,
        `artifact ${artifact.id}.metadata`,
      );
    }
  }
}

const selected = lock.compatibility.selectedElementaUniversalCraft;
console.log(
  `verified native UI lock: ${validated.sources.length} sources, ` +
    `${validated.artifacts.size} artifacts, Elementa UniversalCraft ` +
    `${selected === null ? "selection pending runtime proof" : selected}`,
);
