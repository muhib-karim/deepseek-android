// CI: node --test engine/patches/test/ (no payload needed; uses small fixture texts).
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, readlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { applyAll, installRipgrepPlatformPackage, parseArgs, patchText, targetsOf, validatePatches } from "../apply-payload-patches.mjs";

const patch = { id: "T1", label: "test", required: true, file: "@deepseek-ai/x/lib/index.js", anchor: "await link(a, b);",
	replacement: "await publish(a, b); /* t1 */", marker: "/* t1 */" };

test("a patch applies once on a unique anchor and is idempotent", () => {
	const first = patchText("x();\nawait link(a, b);\ny();\n", patch);
	assert.equal(first.status, "applied");
	assert.match(first.text, /await publish\(a, b\); \/\* t1 \*\//);
	assert.equal(patchText(first.text, patch).status, "present");
});

test("a moved anchor is a mismatch and a repeated anchor is refused", () => {
	assert.equal(patchText("await link(c, d);", patch).status, "mismatch");
	assert.equal(patchText("await link(a, b);\nawait link(a, b);", patch).status, "ambiguous");
});

test("replacement text with $ sequences is inserted literally", () => {
	const dollar = { ...patch, replacement: "x = `${a}$&$1`; /* t1 */" };
	assert.equal(patchText("await link(a, b);", dollar).text, "x = `${a}$&$1`; /* t1 */");
});

test("the shipped patch set is consistent", () => {
	const shipped = JSON.parse(readFileSync(new URL("../payload-patches.json", import.meta.url), "utf8"));
	assert.deepEqual(validatePatches(shipped), []);
	for (const id of ["P1", "P2", "P3", "P3a", "P3b", "P4", "P5", "P9", "P9h", "P11", "P13", "P17", "P17b"]) {
		assert.ok(shipped.some((p) => p.id === id && p.required), `required patch ${id}`);
	}
	assert.ok(!shipped.some((p) => /copyFile\(/.test(p.replacement) && !/copyFile would|never a copyFile|Never a copyFile/i.test(p.replacement)), "no copyFile fallback");
});

test("glob targets pick the hashed bundle that carries the anchor; required failures are reported", () => {
	const root = mkdtempSync(join(tmpdir(), "patches-"));
	mkdirSync(join(root, "@deepseek-ai/x/lib"), { recursive: true });
	writeFileSync(join(root, "@deepseek-ai/x/package.json"), "{}");
	writeFileSync(join(root, "@deepseek-ai/x/lib/boot-AAA.js"), "nothing here\n");
	writeFileSync(join(root, "@deepseek-ai/x/lib/boot-BBB.js"), "await link(a, b);\n");
	const globbed = { ...patch, file: "@deepseek-ai/x/lib/boot-*.js" };
	assert.equal(targetsOf(root, globbed.file).length, 2);
	const [result] = applyAll(root, [globbed]);
	assert.equal(result.status, "applied");
	assert.match(result.file, /boot-BBB\.js$/);
	assert.equal(applyAll(root, [{ ...patch, file: "@deepseek-ai/missing/index.js" }])[0].status, "missing");
});

test("nested copies of a package (npm version conflicts) are all patched", () => {
	const root = mkdtempSync(join(tmpdir(), "nested-"));
	for (const dir of ["@deepseek-ai/x", "@deepseek-ai/dsh/node_modules/@deepseek-ai/x", "other/node_modules/@deepseek-ai/x"]) {
		mkdirSync(join(root, dir, "lib"), { recursive: true });
		writeFileSync(join(root, dir, "package.json"), "{}");
		writeFileSync(join(root, dir, "lib/index.js"), "await link(a, b);\n");
	}
	mkdirSync(join(root, "other"), { recursive: true });
	writeFileSync(join(root, "other/package.json"), "{}");
	const results = applyAll(root, [patch]);
	assert.equal(results.length, 3);
	assert.ok(results.every((r) => r.status === "applied"));
	const nestedOnly = mkdtempSync(join(tmpdir(), "nested-only-"));
	mkdirSync(join(nestedOnly, "@deepseek-ai/dsh/node_modules/@deepseek-ai/x/lib"), { recursive: true });
	writeFileSync(join(nestedOnly, "@deepseek-ai/dsh/package.json"), "{}");
	writeFileSync(join(nestedOnly, "@deepseek-ai/dsh/node_modules/@deepseek-ai/x/package.json"), "{}");
	writeFileSync(join(nestedOnly, "@deepseek-ai/dsh/node_modules/@deepseek-ai/x/lib/index.js"), "await link(a, b);\n");
	assert.equal(applyAll(nestedOnly, [patch])[0].status, "applied");
});

test("P12 links the android ripgrep platform package to the bundled rg", () => {
	const root = mkdtempSync(join(tmpdir(), "rg-"));
	const rg = join(root, "..", "rg-bin");
	writeFileSync(rg, "");
	assert.equal(installRipgrepPlatformPackage(root, "x64", rg).status, "applied");
	assert.ok(readlinkSync(join(root, "@vscode/ripgrep-android-x64/bin/rg")).endsWith("rg-bin"));
	assert.equal(installRipgrepPlatformPackage(root, "x64", rg).status, "present");
	assert.throws(() => parseArgs(["dir", "--arch", "mips"]));
});
