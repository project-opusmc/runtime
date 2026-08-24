#!/usr/bin/env node

import { createHash } from "node:crypto";
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { homedir, tmpdir } from "node:os";
import { basename, dirname, relative, resolve, sep } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const runtimeRoot = resolve(scriptDirectory, "..");
const lockPath = resolve(
  runtimeRoot,
  "legacy/1.8.9/client/native-ui-dependencies.lock.json",
);
const lockVerifier = resolve(runtimeRoot, "scripts/verify-native-ui-lock.mjs");

function fail(message) {
  throw new Error(`stock OneConfig build: ${message}`);
}

function sha256File(filePath) {
  const digest = createHash("sha256");
  digest.update(readFileSync(filePath));
  return digest.digest("hex");
}

function shellQuote(value) {
  return `'${String(value).replaceAll("'", "'\\''")}'`;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    encoding: "utf8",
    ...options,
  });
  if (result.error) {
    fail(`${command} could not start: ${result.error.message}`);
  }
  if (result.status !== 0) {
    const detail = [result.stdout, result.stderr]
      .filter((part) => part && part.length > 0)
      .join("\n")
      .trim();
    fail(`${command} ${args.join(" ")} failed with ${result.status}${detail ? `\n${detail}` : ""}`);
  }
  return result;
}

function parseArguments(argv) {
  const options = {
    cache: process.env.OPUS_ONECONFIG_BUILD_CACHE ||
      resolve(process.env.XDG_CACHE_HOME || resolve(homedir(), ".cache"), "opus-oneconfig-build"),
    evidence: resolve(runtimeRoot, "build/evidence/oneconfig-stock-build.json"),
    source: process.env.OPUS_ONECONFIG_SOURCE || null,
    publishedJar: null,
    noBuild: false,
  };

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--source" || argument === "--cache" || argument === "--evidence" || argument === "--published-jar") {
      const value = argv[index + 1];
      if (value === undefined) fail(`${argument} requires a value`);
      index += 1;
      if (argument === "--source") options.source = resolve(value);
      if (argument === "--cache") options.cache = resolve(value);
      if (argument === "--evidence") options.evidence = resolve(value);
      if (argument === "--published-jar") options.publishedJar = resolve(value);
    } else if (argument === "--no-build") {
      options.noBuild = true;
    } else if (argument === "--help" || argument === "-h") {
      console.log([
        "Usage: node scripts/build-stock-oneconfig.mjs --source /path/to/OneConfig [options]",
        "",
        "Options:",
        "  --source PATH         frozen OneConfig checkout (or OPUS_ONECONFIG_SOURCE)",
        "  --cache PATH          private archive/toolchain cache",
        "  --published-jar PATH  use a verified published baseline instead of downloading it",
        "  --evidence PATH       write JSON build evidence to this path",
        "  --no-build            compare an existing source artifact without invoking Gradle",
      ].join("\n"));
      process.exit(0);
    } else {
      fail(`unknown option: ${argument}`);
    }
  }
  if (options.source === null) {
    fail("--source is required; pass the audited OneConfig checkout explicitly");
  }
  return options;
}

function validateRecord(record, label) {
  if (!record || typeof record !== "object") fail(`${label} is missing`);
  for (const key of ["file", "url", "size", "sha256"]) {
    if (!(key in record)) fail(`${label}.${key} is missing`);
  }
  if (!Number.isSafeInteger(record.size) || record.size <= 0) {
    fail(`${label}.size is invalid`);
  }
  if (!/^[0-9a-f]{64}$/.test(record.sha256)) {
    fail(`${label}.sha256 is invalid`);
  }
}

function verifyRecordFile(filePath, record, label) {
  validateRecord(record, label);
  if (!existsSync(filePath) || !statSync(filePath).isFile()) {
    fail(`${label} is missing: ${filePath}`);
  }
  const size = statSync(filePath).size;
  if (size !== record.size) {
    fail(`${label} size mismatch: expected ${record.size}, got ${size}`);
  }
  const actual = sha256File(filePath);
  if (actual !== record.sha256) {
    fail(`${label} SHA-256 mismatch: expected ${record.sha256}, got ${actual}`);
  }
}

function downloadRecord(record, destination, label) {
  validateRecord(record, label);
  mkdirSync(dirname(destination), { recursive: true, mode: 0o700 });
  if (!existsSync(destination)) {
    const partial = `${destination}.partial-${process.pid}`;
    rmSync(partial, { force: true });
    run("curl", [
      "--fail",
      "--location",
      "--proto",
      "=https",
      "--tlsv1.2",
      "--silent",
      "--show-error",
      "--output",
      partial,
      record.url,
    ], { stdio: "inherit" });
    verifyRecordFile(partial, record, `${label} download`);
    renameSync(partial, destination);
  }
  verifyRecordFile(destination, record, label);
  return destination;
}

function ensureTarExtracted(archive, root, expectedPath, label, cacheRoot) {
  const expected = resolve(cacheRoot, expectedPath);
  if (!existsSync(expected)) {
    if (existsSync(root)) {
      fail(`${label} extraction directory exists but is incomplete: ${root}`);
    }
    run("tar", ["-xzf", archive, "-C", cacheRoot]);
  }
  if (!existsSync(expected)) fail(`${label} did not contain ${expectedPath}`);
  return expected;
}

function ensureZipExtracted(archive, root, expectedPath, label, cacheRoot) {
  const expected = resolve(cacheRoot, expectedPath);
  if (!existsSync(expected)) {
    if (existsSync(root)) {
      fail(`${label} extraction directory exists but is incomplete: ${root}`);
    }
    run("unzip", ["-q", archive, "-d", cacheRoot]);
  }
  if (!existsSync(expected)) fail(`${label} did not contain ${expectedPath}`);
  return expected;
}

function architecturePrefix() {
  if (process.platform !== "darwin") {
    fail("the locked stock build lane currently targets macOS x86_64 toolchains");
  }
  const hostArchitecture = process.arch;
  if (hostArchitecture === "x64") return [];
  if (hostArchitecture === "arm64") return ["-x86_64"];
  fail(`unsupported host architecture: ${hostArchitecture}`);
}

function runArchitecture(command, args, options = {}) {
  const prefix = architecturePrefix();
  if (prefix.length === 0) return run(command, args, options);
  return run("arch", [...prefix, command, ...args], options);
}

function checkJava(javaHome, expectedMajor, label) {
  const java = resolve(javaHome, "bin/java");
  const javac = resolve(javaHome, "bin/javac");
  if (!existsSync(java) || !existsSync(javac)) {
    fail(`${label} is missing java or javac: ${javaHome}`);
  }
  const result = runArchitecture(java, ["-version"]);
  const versionText = `${result.stdout}\n${result.stderr}`;
  if (expectedMajor === 8 && !versionText.includes('version "1.8.')) {
    fail(`${label} is not Java 8: ${versionText.trim()}`);
  }
  if (expectedMajor === 17 && !versionText.includes('version "17.')) {
    fail(`${label} is not Java 17: ${versionText.trim()}`);
  }
  return { java, javac };
}

function listFiles(root) {
  const result = [];
  function visit(current) {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const path = resolve(current, entry.name);
      if (entry.isDirectory()) {
        visit(path);
      } else {
        const relativePath = relative(root, path).split(sep).join("/");
        const info = lstatSync(path);
        if (info.isSymbolicLink()) {
          result.push({ path: relativePath, kind: "symlink", value: readFileSync(path, "utf8") });
        } else {
          result.push({ path: relativePath, kind: "file", value: sha256File(path), size: info.size });
        }
      }
    }
  }
  visit(root);
  return result.sort((left, right) => left.path.localeCompare(right.path));
}

function extractAndCompare(jarA, jarB, expectedFileCount) {
  const extractionRoot = resolve(tmpdir(), `opus-oneconfig-content-${process.pid}-${Date.now()}`);
  const left = resolve(extractionRoot, "left");
  const right = resolve(extractionRoot, "right");
  mkdirSync(left, { recursive: true, mode: 0o700 });
  mkdirSync(right, { recursive: true, mode: 0o700 });
  try {
    run("unzip", ["-qq", "-o", jarA, "-d", left]);
    run("unzip", ["-qq", "-o", jarB, "-d", right]);
    const leftFiles = listFiles(left);
    const rightFiles = listFiles(right);
    if (leftFiles.length !== expectedFileCount || rightFiles.length !== expectedFileCount) {
      fail(`content comparison expected ${expectedFileCount} files, got ${leftFiles.length} and ${rightFiles.length}`);
    }
    const leftJson = JSON.stringify(leftFiles);
    const rightJson = JSON.stringify(rightFiles);
    if (leftJson !== rightJson) {
      const leftByPath = new Map(leftFiles.map((entry) => [entry.path, entry]));
      const rightByPath = new Map(rightFiles.map((entry) => [entry.path, entry]));
      const differences = [];
      for (const path of new Set([...leftByPath.keys(), ...rightByPath.keys()])) {
        if (JSON.stringify(leftByPath.get(path)) !== JSON.stringify(rightByPath.get(path))) {
          differences.push(path);
          if (differences.length >= 12) break;
        }
      }
      fail(`extracted content differs at: ${differences.join(", ")}`);
    }
    return leftFiles.length;
  } finally {
    rmSync(extractionRoot, { recursive: true, force: true });
  }
}

function sourceRevision(source) {
  const revision = run("git", ["-C", source, "rev-parse", "HEAD"]);
  const status = run("git", ["-C", source, "status", "--porcelain", "--untracked-files=no"]);
  if (status.stdout.trim() !== "") {
    fail(`source checkout has tracked changes:\n${status.stdout}`);
  }
  return revision.stdout.trim();
}

const options = parseArguments(process.argv.slice(2));
const lock = JSON.parse(readFileSync(lockPath, "utf8"));
const build = lock.sourceBuilds.oneConfig;
const oneConfigSource = resolve(options.source);
if (!existsSync(oneConfigSource)) fail(`source checkout does not exist: ${oneConfigSource}`);

const revision = sourceRevision(oneConfigSource);
const expectedSource = lock.sources.find((source) => source.id === "oneconfig");
if (expectedSource === undefined || revision !== expectedSource.commit) {
  fail(`source revision is ${revision}, expected ${expectedSource?.commit || "the locked commit"}`);
}
run(process.execPath, [lockVerifier, "--source", `oneconfig=${oneConfigSource}`], {
  stdio: "inherit",
});

const cacheRoot = resolve(options.cache);
const downloads = resolve(cacheRoot, "downloads");
const toolchains = resolve(cacheRoot, "toolchains");
mkdirSync(downloads, { recursive: true, mode: 0o700 });
mkdirSync(toolchains, { recursive: true, mode: 0o700 });

const gradleArchive = downloadRecord(
  build.gradle,
  resolve(downloads, build.gradle.file),
  "Gradle distribution",
);
const launcherJdkArchive = downloadRecord(
  build.launcherJdk,
  resolve(downloads, build.launcherJdk.file),
  "Java 17 launcher archive",
);
const java8Archive = downloadRecord(
  build.java8Toolchain,
  resolve(downloads, build.java8Toolchain.file),
  "Java 8 toolchain archive",
);

const gradleHome = resolve(toolchains, "gradle-8.7");
const launcherJdkRoot = resolve(toolchains, "jdk-17.0.17+10");
const java8Root = resolve(toolchains, "jdk8u502-b07");
const gradleBinary = ensureZipExtracted(
  gradleArchive,
  gradleHome,
  "gradle-8.7/bin/gradle",
  "Gradle distribution",
  toolchains,
);
const launcherJdkHome = ensureTarExtracted(
  launcherJdkArchive,
  launcherJdkRoot,
  "jdk-17.0.17+10/Contents/Home/bin/java",
  "Java 17 launcher archive",
  toolchains,
).replace(/\/bin\/java$/, "");
const java8Home = ensureTarExtracted(
  java8Archive,
  java8Root,
  "jdk8u502-b07/Contents/Home/bin/java",
  "Java 8 toolchain archive",
  toolchains,
).replace(/\/bin\/java$/, "");
checkJava(launcherJdkHome, 17, "Java 17 launcher JDK");
checkJava(java8Home, 8, "Java 8 compiler toolchain");

const publishedRecord = lock.artifacts.find((artifact) => artifact.id === build.publishedArtifactId);
if (publishedRecord === undefined) fail(`locked artifact ${build.publishedArtifactId} is missing`);
const publishedJar = options.publishedJar === null
  ? downloadRecord(publishedRecord, resolve(downloads, publishedRecord.file), "published OneConfig JAR")
  : resolve(options.publishedJar);
verifyRecordFile(publishedJar, publishedRecord, "published OneConfig JAR");

const buildStartedAt = new Date().toISOString();
const sourceArtifact = resolve(oneConfigSource, build.artifactPath);
if (!options.noBuild) {
  const gradleArguments = [
    "--no-daemon",
    "-Porg.gradle.java.installations.auto-detect=false",
    "-Porg.gradle.java.installations.auto-download=false",
    `-Porg.gradle.java.installations.paths=${java8Home}`,
    build.task,
    "--stacktrace",
  ];
  const command = [
    `exec ${shellQuote(gradleBinary)}`,
    ...gradleArguments.map(shellQuote),
  ].join(" ");
  runArchitecture("/bin/bash", ["-lc", command], {
    cwd: oneConfigSource,
    env: {
      ...process.env,
      JAVA_HOME: launcherJdkHome,
      GRADLE_USER_HOME: resolve(cacheRoot, "gradle-user-home"),
      PATH: `${resolve(launcherJdkHome, "bin")}:${process.env.PATH || ""}`,
    },
    stdio: "inherit",
  });
}

if (!existsSync(sourceArtifact)) fail(`source build artifact is missing: ${sourceArtifact}`);
const sourceSize = statSync(sourceArtifact).size;
const sourceSha256 = sha256File(sourceArtifact);
const publishedSha256 = sha256File(publishedJar);
const extractedFileCount = extractAndCompare(
  sourceArtifact,
  publishedJar,
  build.expectedExtractedFiles,
);
const completedAt = new Date().toISOString();

const evidence = {
  schemaVersion: 1,
  kind: "stock-oneconfig-source-build",
  recordedAt: completedAt,
  source: {
    repository: expectedSource.repository,
    commit: revision,
    checkout: oneConfigSource,
    artifactPath: build.artifactPath,
  },
  runtime: lock.runtime,
  host: {
    platform: process.platform,
    architecture: process.arch,
    lockedExecutionArchitecture: build.host.architecture,
  },
  toolchains: {
    gradle: {
      version: build.gradle.version,
      sha256: build.gradle.sha256,
      home: gradleHome,
    },
    launcherJdk: {
      version: build.launcherJdk.version,
      sha256: build.launcherJdk.sha256,
      home: launcherJdkHome,
    },
    java8Toolchain: {
      version: build.java8Toolchain.version,
      sha256: build.java8Toolchain.sha256,
      home: java8Home,
    },
  },
  build: {
    task: build.task,
    startedAt: buildStartedAt,
    completedAt,
    invoked: !options.noBuild,
    gradleProjectProperties: build.gradleProjectProperties,
  },
  artifact: {
    size: sourceSize,
    sha256: sourceSha256,
    publishedSize: publishedRecord.size,
    publishedSha256,
    byteIdentical: sourceSize === publishedRecord.size && sourceSha256 === publishedSha256,
    extractedFileCount,
    extractedContentIdentical: true,
  },
};
mkdirSync(dirname(options.evidence), { recursive: true, mode: 0o700 });
writeFileSync(options.evidence, `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o600 });
console.log(
  `stock OneConfig build verified: ${sourceSize} bytes, ${sourceSha256}, ` +
    `${extractedFileCount} extracted files content-identical to the published baseline; ` +
    `byte-identical=${evidence.artifact.byteIdentical}`,
);
