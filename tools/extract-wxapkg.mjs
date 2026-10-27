import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const [inputArg, outputArg] = process.argv.slice(2);

if (!inputArg || !outputArg) {
  console.error("Usage: node tools/extract-wxapkg.mjs <input.wxapkg> <output-dir>");
  process.exit(1);
}

const inputPath = path.resolve(inputArg);
const outputDir = path.resolve(outputArg);
const source = fs.readFileSync(inputPath);

function inferAppId(filePath) {
  return path.basename(path.dirname(path.dirname(filePath)));
}

function decryptV1MMWX(data, appId) {
  if (!/^wx[0-9a-f]{16}$/.test(appId)) {
    throw new Error(`Invalid app id: ${appId}`);
  }
  if (data.length < 1031) {
    throw new Error("Encrypted package is too small");
  }

  const key = crypto.pbkdf2Sync(appId, "saltiest", 1000, 32, "sha1");
  const decipher = crypto.createDecipheriv(
    "aes-256-cbc",
    key,
    Buffer.from("the iv: 16 bytes", "utf8"),
  );
  decipher.setAutoPadding(false);

  const prefix = Buffer.concat([
    decipher.update(data.subarray(6, 1030)),
    decipher.final(),
  ]);
  const xorKey = appId.charCodeAt(appId.length - 2);
  const tail = Buffer.allocUnsafe(data.length - 1030);

  for (let index = 1030; index < data.length; index += 1) {
    tail[index - 1030] = data[index] ^ xorKey;
  }

  return Buffer.concat([prefix.subarray(0, 1023), tail]);
}

function parsePackage(data) {
  if (data.length < 18) {
    throw new Error("Package header is incomplete");
  }

  const firstMark = data[0];
  const lastMark = data[13];
  if (firstMark !== 0xbe || lastMark !== 0xed) {
    throw new Error(
      `Invalid package markers: first=0x${firstMark.toString(16)}, last=0x${lastMark.toString(16)}`,
    );
  }

  const fileCount = data.readUInt32BE(14);
  if (fileCount > 102400) {
    throw new Error(`Unreasonable file count: ${fileCount}`);
  }

  let cursor = 18;
  const files = [];

  for (let index = 0; index < fileCount; index += 1) {
    if (cursor + 4 > data.length) {
      throw new Error(`Truncated name length at entry ${index}`);
    }

    const nameLength = data.readUInt32BE(cursor);
    cursor += 4;
    if (nameLength > 4096 || cursor + nameLength + 8 > data.length) {
      throw new Error(`Invalid name length at entry ${index}: ${nameLength}`);
    }

    const rawName = data.subarray(cursor, cursor + nameLength).toString("utf8");
    cursor += nameLength;
    const offset = data.readUInt32BE(cursor);
    cursor += 4;
    const size = data.readUInt32BE(cursor);
    cursor += 4;

    if (offset + size > data.length) {
      throw new Error(`File range is outside package: ${rawName}`);
    }

    const parts = rawName.replaceAll("\\", "/").split("/").filter(Boolean);
    if (parts.some((part) => part === "..")) {
      throw new Error(`Unsafe package path: ${rawName}`);
    }

    files.push({
      name: parts.join("/"),
      offset,
      size,
    });
  }

  return files;
}

const appId = inferAppId(inputPath);
const decrypted =
  source.subarray(0, 6).toString("ascii") === "V1MMWX"
    ? decryptV1MMWX(source, appId)
    : source;
const files = parsePackage(decrypted);

fs.mkdirSync(outputDir, { recursive: true });

for (const file of files) {
  const destination = path.resolve(outputDir, file.name);
  const outputRoot = `${outputDir}${path.sep}`;
  if (!destination.startsWith(outputRoot)) {
    throw new Error(`Resolved path escaped output directory: ${file.name}`);
  }

  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(
    destination,
    decrypted.subarray(file.offset, file.offset + file.size),
  );
}

const manifest = {
  input: inputPath,
  inputSha256: crypto.createHash("sha256").update(source).digest("hex"),
  decryptedSha256: crypto
    .createHash("sha256")
    .update(decrypted)
    .digest("hex"),
  appId,
  encrypted: source.subarray(0, 6).toString("ascii") === "V1MMWX",
  fileCount: files.length,
  files,
};

fs.writeFileSync(
  path.join(outputDir, "extract-manifest.json"),
  `${JSON.stringify(manifest, null, 2)}\n`,
);

console.log(
  `Extracted ${files.length} files from ${inputPath} to ${outputDir}`,
);
