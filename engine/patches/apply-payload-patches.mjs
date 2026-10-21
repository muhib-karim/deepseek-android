#!/usr/bin/env node
// Android payload patches for the DeepSeek Harness (@deepseek-ai/dsh).
//
// A port of dsh-android's patches/apply-payload-patches.sh (MIT, Copyright (c) 2026 DSH for Android contributors,
// https://github.com/yhm080315-sudo/dsh-android, commit d799c76) to Node, so the same patches run in CI when the
// payload is built and on the phone when the app installs a newer harness. The patch texts live in
// payload-patches.json; see README.md for what each one does and which ones this project rewrote.
//
// Usage: node apply-payload-patches.mjs <node_modules dir> [--arch arm64|x64] [--rg <path to the bundled rg>] [--check]
//   --check  report only, change nothing (exit 1 when a required patch could not apply and is not already applied)
//
// Every patch is idempotent: a file that already carries the patch's marker is skipped, a file whose anchor is
// missing or not unique is reported and left unchanged. A required patch that neither applies nor is present
// fails the run, so a harness upgrade that moved an anchor stops the build instead of shipping a broken runtime.
import { existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, symlinkSync, writeFileSync } from "node:fs";
import { basename, dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

export function parseArgs(argv) {
	const options = { root: undefined, arch: "arm64", rg: undefined, check: false };
	for (let i = 0; i < argv.length; i++) {
		const arg = argv[i];
		if (arg === "--arch") options.arch = argv[++i];
		else if (arg === "--rg") options.rg = argv[++i];
		else if (arg === "--check") options.check = true;
		else if (options.root === undefined) options.root = arg;
		else throw new Error(`unexpected argument: ${arg}`);
	}
	if (options.root === undefined) throw new Error("usage: apply-payload-patches.mjs <node_modules dir> [--arch arm64|x64] [--rg path] [--check]");
	if (options.arch !== "arm64" && options.arch !== "x64") throw new Error(`--arch must be arm64 or x64, not ${options.arch}`);
	return options;
}

/** Files a patch targets: an exact path, or a basename with one `*` (hashed bundle names such as profile-boot-*.js). */
export function targetsOf(root, file) {
	const name = basename(file);
	if (!name.includes("*")) return [join(root, file)];
	const dir = join(root, dirname(file));
	if (!existsSync(dir)) return [];
	const [head, tail] = name.split("*");
	return readdirSync(dir).filter((entry) => entry.startsWith(head) && entry.endsWith(tail)).sort().map((entry) => join(dir, entry));
}

function occurrences(text, needle) {
	let count = 0;
	for (let at = text.indexOf(needle); at !== -1; at = text.indexOf(needle, at + needle.length)) count++;
	return count;
}

/** Apply one patch to one file's text. Returns { status: applied|present|mismatch|ambiguous, text }. */
export function patchText(text, patch) {
	if (text.includes(patch.marker)) return { status: "present", text };
	const count = occurrences(text, patch.anchor);
	if (count === 0) return { status: "mismatch", text };
	if (count > 1) return { status: "ambiguous", text };
	return { status: "applied", text: text.replace(patch.anchor, () => patch.replacement) };
}

/** Apply every patch under root (or only report with check). Returns one result per patch and target file. */
export function applyAll(root, patches, { check = false } = {}) {
	const results = [];
	for (const patch of patches) {
		const files = targetsOf(root, patch.file).filter((path) => existsSync(path));
		if (files.length === 0) {
			results.push({ patch, status: "missing", file: patch.file });
			continue;
		}
		const outcomes = files.map((path) => ({ path, ...patchText(readFileSync(path, "utf8"), patch) }));
		// A glob matches several hashed bundles; the patch belongs to the one that carries its anchor or marker.
		const relevant = outcomes.filter((o) => o.status !== "mismatch");
		const chosen = relevant.length > 0 ? relevant : outcomes.slice(0, 1);
		for (const outcome of chosen) {
			if (outcome.status === "applied" && !check) writeFileSync(outcome.path, outcome.text);
			results.push({ patch, status: outcome.status, file: relative(root, outcome.path) });
		}
	}
	return results;
}

/**
 * P12 (dsh-android): @vscode/ripgrep resolves `@vscode/ripgrep-${process.platform}-${process.arch}/bin/rg`, and no
 * android package exists on npm, so grep/glob tools fail silently. Create it as a symlink to the bundled Termux rg
 * (a symlink, never a hard link: link(2) is refused for apps).
 */
export function installRipgrepPlatformPackage(root, arch, rg, { check = false } = {}) {
	const target = join(root, "@vscode", `ripgrep-android-${arch}`);
	const link = join(target, "bin", "rg");
	if (existsSync(link)) return { status: "present", file: relative(root, link) };
	if (rg === undefined) return { status: "missing", file: relative(root, link) };
	if (check) return { status: "mismatch", file: relative(root, link) };
	mkdirSync(join(target, "bin"), { recursive: true });
	try {
		lstatSync(link);
	} catch {
		symlinkSync(relative(dirname(link), rg), link);
	}
	writeFileSync(join(target, "package.json"), `{\n  "name": "@vscode/ripgrep-android-${arch}",\n  "version": "0.0.0",\n  "private": true\n}\n`);
	return { status: "applied", file: relative(root, link) };
}

/** Each marker must be in its own replacement, not in its anchor, and in no other patch's text for the same file. */
export function validatePatches(patches) {
	const problems = [];
	for (const p of patches) {
		if (!p.replacement.includes(p.marker)) problems.push(`${p.id}: marker is not in the replacement`);
		if (p.anchor.includes(p.marker)) problems.push(`${p.id}: marker is already in the anchor`);
		for (const q of patches) if (q !== p && q.file === p.file && (q.replacement.includes(p.marker) || q.anchor.includes(p.marker))) problems.push(`${p.id}: marker also appears in ${q.id}`);
	}
	return problems;
}

export function main(argv) {
	const options = parseArgs(argv);
	if (!existsSync(join(options.root, "@deepseek-ai"))) throw new Error(`no @deepseek-ai packages under ${options.root}`);
	const patches = JSON.parse(readFileSync(join(here, "payload-patches.json"), "utf8"));
	const problems = validatePatches(patches);
	if (problems.length > 0) throw new Error(`payload-patches.json is inconsistent:\n${problems.join("\n")}`);
	const results = applyAll(options.root, patches, { check: options.check });
	const rg = installRipgrepPlatformPackage(options.root, options.arch, options.rg, { check: options.check });
	results.push({ patch: { id: "P12", label: `@vscode/ripgrep-android-${options.arch} platform package`, required: true }, ...rg });
	let failed = 0;
	for (const r of results) {
		const bad = r.status === "missing" || r.status === "mismatch" || r.status === "ambiguous";
		if (bad && r.patch.required) failed++;
		console.log(`${bad ? (r.patch.required ? "FAIL" : "skip") : "ok  "} ${r.patch.id.padEnd(4)} ${r.status.padEnd(9)} ${r.patch.label} (${r.file})`);
	}
	console.log(failed === 0 ? `payload patches: all ${results.length} required patches in place` : `payload patches: ${failed} required patch(es) did not apply`);
	return failed === 0 ? 0 : 1;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
	try {
		process.exitCode = main(process.argv.slice(2));
	} catch (error) {
		console.error(String(error?.message ?? error));
		process.exitCode = 2;
	}
}
