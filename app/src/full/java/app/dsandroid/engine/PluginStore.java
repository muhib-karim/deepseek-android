package app.dsandroid.engine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * User plugins for the harness: Settings > Plugins' "Installed plugins" section.
 *
 * In DeepSeek's harness everything is a plugin, and a skill is one too. Two shapes are installed:
 * - a dsh plugin package (package.json with a "dsh" declaration or the dsh-plugin keyword) goes into the web profile's
 *   node_modules and gets one loader row in the profile's cordis.patch.yml, the same row shape the harness's own bundles
 *   declare (see dsh-web-mobile's cordis.patch.yml); its dependencies are installed with the bundled npm;
 * - a skill (a directory with SKILL.md, or a package/repo whose skills/ holds several) goes into $DSH_HOME/skills,
 *   the harness's user skill root, which it watches: the skill reaches the catalog and the "/" palette without a restart.
 *
 * Sources: a GitHub URL (https://github.com/owner/repo[/tree/ref[/dir]] or github:owner/repo[#ref]), an npm package name
 * ([@scope/]name[@version]), an https .tgz/.tar.gz/.zip URL, or a file/folder the person picked. The harness's own plugin
 * manager runs pnpm, which a phone does not have; this store installs the same shapes without it. What it installed is
 * recorded in $DSH_HOME/android-plugins.json so update, enable/disable and remove work later.
 */
public final class PluginStore {
    public static final String MANIFEST = "android-plugins.json";
    private static final Pattern GITHUB_URL = Pattern.compile(
            "^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:/tree/([^/]+)(/.+?)?)?/?$");
    private static final Pattern GITHUB_SHORT = Pattern.compile("^github:([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:#(.+))?$");
    private static final Pattern NPM_NAME = Pattern.compile("^((?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*)(?:@([A-Za-z0-9.+_-]+))?$");
    private static final Pattern SKILL_NAME = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final long MAX_DOWNLOAD = 200L * 1024 * 1024;

    /** Installs a package's runtime dependencies in place (the bundled npm on the phone; a fake in tests). */
    public interface DependencyInstaller {
        void install(File packageDir) throws IOException;
    }

    public enum Kind { PLUGIN, SKILL }

    /** What a source string names. */
    public static final class Spec {
        public final String type; // github | npm | url | local
        public final String location;
        public final String ref;
        public final String subdir;

        Spec(String type, String location, String ref, String subdir) {
            this.type = type;
            this.location = location;
            this.ref = ref;
            this.subdir = subdir;
        }
    }

    /** One installed entry; for a skills repo there is one entry per skill. */
    public static final class Entry {
        public String name;
        public Kind kind;
        public String source;
        public String version;
        public boolean enabled = true;

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("name", name).put("kind", kind.name().toLowerCase(Locale.US)).put("source", source)
                    .put("version", version == null ? "" : version).put("enabled", enabled);
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.name = o.optString("name");
            e.kind = "skill".equals(o.optString("kind")) ? Kind.SKILL : Kind.PLUGIN;
            e.source = o.optString("source");
            e.version = o.optString("version");
            e.enabled = o.optBoolean("enabled", true);
            return e;
        }
    }

    /** A plain-language reason an install, update or removal did not happen (shown inline under the field). */
    public static final class PluginException extends Exception {
        public PluginException(String message) {
            super(message);
        }

        public PluginException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final File dshHome;
    private final File profile;
    private final File work;
    private final DependencyInstaller dependencies;
    private String registry = "https://registry.npmjs.org";
    private String codeload = "https://codeload.github.com";

    /**
     * @param dshHome      $DSH_HOME (skills root and manifest live here)
     * @param profile      the web profile directory ($DSH_HOME/profiles/web)
     * @param work         scratch directory for downloads (emptied per install)
     * @param dependencies installs a plugin's npm dependencies, or null to refuse plugins that declare any
     */
    public PluginStore(File dshHome, File profile, File work, DependencyInstaller dependencies) {
        this.dshHome = dshHome;
        this.profile = profile;
        this.work = work;
        this.dependencies = dependencies;
    }

    /** Test seam: point downloads at a mock registry / GitHub. */
    public PluginStore endpoints(String registry, String codeload) {
        this.registry = trimSlash(registry);
        this.codeload = trimSlash(codeload);
        return this;
    }

    public File skillsDir() {
        return new File(dshHome, "skills");
    }

    File disabledSkillsDir() {
        return new File(dshHome, "skills-disabled");
    }

    File modulesDir() {
        return new File(profile, "node_modules");
    }

    File patchFile() {
        return new File(profile, "cordis.patch.yml");
    }

    // ------------------------------------------------------------------ parsing

    public static Spec parse(String input) throws PluginException {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) {
            throw new PluginException("Enter a GitHub URL, an npm package name, or pick a file or folder.");
        }
        Matcher m = GITHUB_URL.matcher(s);
        if (m.matches()) {
            String subdir = m.group(4) == null ? null : m.group(4).substring(1);
            return new Spec("github", m.group(1) + "/" + m.group(2), m.group(3), subdir);
        }
        m = GITHUB_SHORT.matcher(s);
        if (m.matches()) {
            return new Spec("github", m.group(1) + "/" + m.group(2), m.group(3), null);
        }
        if (s.startsWith("/")) {
            return new Spec("local", s, null, null);
        }
        if (s.startsWith("https://")) {
            String lower = s.toLowerCase(Locale.US);
            if (lower.endsWith(".tgz") || lower.endsWith(".tar.gz") || lower.endsWith(".zip")) {
                return new Spec("url", s, null, null);
            }
            throw new PluginException("That link is not a GitHub repository or a .tgz/.zip file.");
        }
        if (s.startsWith("http://")) {
            throw new PluginException("Use an https:// link.");
        }
        m = NPM_NAME.matcher(s);
        if (m.matches()) {
            return new Spec("npm", m.group(1), m.group(2), null);
        }
        throw new PluginException("Not a GitHub URL, npm package name or file. Example: https://github.com/owner/repo");
    }

    // ------------------------------------------------------------------ install

    /** Install from a source string; returns the entries added or replaced. */
    public synchronized List<Entry> install(String source) throws PluginException {
        Spec spec = parse(source);
        File staging = new File(work, "install-" + System.nanoTime());
        try {
            File root = fetch(spec, staging);
            if (spec.subdir != null) {
                root = safeChild(root, spec.subdir);
                if (!root.isDirectory()) {
                    throw new PluginException("The repository has no folder " + spec.subdir + ".");
                }
            }
            return installFrom(root, source.trim());
        } catch (IOException e) {
            throw new PluginException("Download or unpack failed: " + e.getMessage(), e);
        } finally {
            deleteTree(staging);
        }
    }

    /** Re-install an entry from the source it came from (an update). */
    public synchronized List<Entry> update(String name) throws PluginException {
        Entry e = find(name);
        if (e == null) {
            throw new PluginException("No installed plugin named " + name + ".");
        }
        if (e.source == null || e.source.startsWith("/")) {
            throw new PluginException(name + " was installed from a file; pick the new version to update it.");
        }
        boolean wasEnabled = e.enabled;
        List<Entry> updated = install(e.source);
        if (!wasEnabled) {
            for (Entry u : updated) {
                if (u.name.equals(name)) {
                    setEnabled(name, false);
                }
            }
        }
        return updated;
    }

    List<Entry> installFrom(File root, String source) throws PluginException, IOException {
        File pkgJson = new File(root, "package.json");
        JSONObject pkg = null;
        if (pkgJson.isFile()) {
            try {
                pkg = new JSONObject(read(pkgJson));
            } catch (JSONException e) {
                throw new PluginException("package.json is not valid JSON: " + e.getMessage());
            }
        }
        List<Entry> added = new ArrayList<Entry>();
        if (pkg != null && isDshPlugin(pkg)) {
            added.add(installPlugin(root, pkg, source));
            return added;
        }
        // Skills: SKILL.md at the root, else every skills/<name>/SKILL.md (or <name>/SKILL.md) below it.
        if (new File(root, "SKILL.md").isFile()) {
            added.add(installSkill(root, skillName(root, pkg), source));
            return added;
        }
        for (File parent : new File[]{new File(root, "skills"), root}) {
            File[] kids = parent.listFiles();
            if (kids == null) {
                continue;
            }
            java.util.Arrays.sort(kids);
            for (File kid : kids) {
                if (kid.isDirectory() && new File(kid, "SKILL.md").isFile()) {
                    added.add(installSkill(kid, skillName(kid, null), source));
                }
            }
            if (!added.isEmpty()) {
                return added;
            }
        }
        if (pkg != null) {
            throw new PluginException("This package is not a DeepSeek Harness plugin (its package.json has no \"dsh\" section) "
                    + "and has no SKILL.md.");
        }
        throw new PluginException("Nothing to install: no package.json with a \"dsh\" section and no SKILL.md.");
    }

    static boolean isDshPlugin(JSONObject pkg) {
        if (pkg.has("dsh")) {
            return true;
        }
        JSONArray keywords = pkg.optJSONArray("keywords");
        if (keywords != null) {
            for (int i = 0; i < keywords.length(); i++) {
                if ("dsh-plugin".equals(keywords.optString(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Entry installPlugin(File root, JSONObject pkg, String source) throws PluginException, IOException {
        String name = pkg.optString("name", "");
        if (!NPM_NAME.matcher(name).matches() || name.indexOf('@', 1) >= 0) {
            throw new PluginException("package.json has no valid \"name\" (" + (name.isEmpty() ? "missing" : name) + ").");
        }
        JSONObject deps = pkg.optJSONObject("dependencies");
        boolean needsDeps = deps != null && deps.length() > 0;
        if (needsDeps && dependencies == null) {
            throw new PluginException(name + " needs npm dependencies, which cannot be installed here.");
        }
        File modules = modulesDir();
        File target = safeChild(modules, name);
        Entry existing = find(name);
        if (target.exists() && !java.nio.file.Files.isSymbolicLink(target.toPath()) && existing == null) {
            throw new PluginException(name + " is already part of the harness; it cannot be replaced.");
        }
        if (java.nio.file.Files.isSymbolicLink(target.toPath())) {
            throw new PluginException(name + " is a built-in plugin; it cannot be replaced.");
        }
        File next = new File(modules, ".install-" + System.nanoTime());
        copyTree(root, next);
        try {
            if (needsDeps) {
                dependencies.install(next);
            }
        } catch (IOException e) {
            deleteTree(next);
            throw new PluginException("Installing the dependencies of " + name + " failed: " + e.getMessage(), e);
        }
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            deleteTree(next);
            throw new IOException("cannot create " + parent);
        }
        File old = new File(modules, ".old-" + System.nanoTime());
        if (target.exists() && !target.renameTo(old)) {
            deleteTree(next);
            throw new IOException("cannot replace " + target);
        }
        if (!next.renameTo(target)) {
            old.renameTo(target);
            throw new IOException("cannot install " + target);
        }
        deleteTree(old);
        Entry e = existing != null ? existing : new Entry();
        e.name = name;
        e.kind = Kind.PLUGIN;
        e.source = source;
        e.version = pkg.optString("version", "");
        e.enabled = existing == null || existing.enabled;
        writeRow(name, e.enabled);
        save(e);
        return e;
    }

    private Entry installSkill(File dir, String name, String source) throws PluginException, IOException {
        if (!SKILL_NAME.matcher(name).matches()) {
            throw new PluginException("Skill name \"" + name + "\" must be kebab-case (letters, digits and dashes).");
        }
        if (!frontmatterHasDescription(new File(dir, "SKILL.md"))) {
            throw new PluginException("SKILL.md for " + name + " needs a front matter block with a description "
                    + "(---, name: " + name + ", description: …, ---); the harness skips skills without one.");
        }
        Entry existing = find(name);
        if (existing != null && existing.kind != Kind.SKILL) {
            throw new PluginException("A plugin named " + name + " is already installed.");
        }
        File live = safeChild(skillsDir(), name);
        File parked = safeChild(disabledSkillsDir(), name);
        if (existing == null && (live.exists() || parked.exists())) {
            throw new PluginException("A skill named " + name + " already exists in the harness's skills folder.");
        }
        boolean enabled = existing == null || existing.enabled;
        File target = enabled ? live : parked;
        File next = new File(target.getParentFile(), "." + name + ".install-" + System.nanoTime());
        copyTree(dir, next);
        deleteTree(live);
        deleteTree(parked);
        if (!next.renameTo(target)) {
            deleteTree(next);
            throw new IOException("cannot install skill " + name);
        }
        Entry e = existing != null ? existing : new Entry();
        e.name = name;
        e.kind = Kind.SKILL;
        e.source = source;
        e.version = "";
        e.enabled = enabled;
        save(e);
        return e;
    }

    static String skillName(File dir, JSONObject pkg) throws IOException {
        String fromFrontmatter = frontmatterValue(new File(dir, "SKILL.md"), "name");
        if (fromFrontmatter != null && !fromFrontmatter.isEmpty()) {
            return fromFrontmatter;
        }
        if (pkg != null && pkg.optString("name", "").length() > 0) {
            String n = pkg.optString("name");
            return n.substring(n.lastIndexOf('/') + 1);
        }
        return dir.getName().toLowerCase(Locale.US);
    }

    static String frontmatterValue(File skillMd, String key) throws IOException {
        if (!skillMd.isFile()) {
            return null;
        }
        String[] lines = read(skillMd).split("\n");
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return null;
        }
        for (int i = 1; i < lines.length && !lines[i].trim().equals("---"); i++) {
            String line = lines[i];
            if (line.startsWith(key + ":")) {
                String v = line.substring(key.length() + 1).trim();
                if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return null;
    }

    static boolean frontmatterHasDescription(File skillMd) throws IOException {
        String d = frontmatterValue(skillMd, "description");
        return d != null && !d.isEmpty();
    }

    // ------------------------------------------------------------------ manage

    public synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<Entry>();
        JSONArray arr = manifest().optJSONArray("plugins");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(Entry.fromJson(o));
                }
            }
        }
        return out;
    }

    public synchronized JSONArray listJson() throws JSONException {
        JSONArray arr = new JSONArray();
        for (Entry e : list()) {
            arr.put(e.toJson());
        }
        return arr;
    }

    public synchronized void setEnabled(String name, boolean enabled) throws PluginException {
        Entry e = find(name);
        if (e == null) {
            throw new PluginException("No installed plugin named " + name + ".");
        }
        try {
            if (e.kind == Kind.SKILL) {
                File from = safeChild(enabled ? disabledSkillsDir() : skillsDir(), name);
                File to = safeChild(enabled ? skillsDir() : disabledSkillsDir(), name);
                if (from.isDirectory()) {
                    File parent = to.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("cannot create " + parent);
                    }
                    deleteTree(to);
                    if (!from.renameTo(to)) {
                        throw new IOException("cannot move " + from);
                    }
                }
            } else {
                writeRow(name, enabled);
            }
            e.enabled = enabled;
            save(e);
        } catch (IOException io) {
            throw new PluginException("Could not " + (enabled ? "enable " : "disable ") + name + ": " + io.getMessage(), io);
        }
    }

    public synchronized void remove(String name) throws PluginException {
        Entry e = find(name);
        if (e == null) {
            throw new PluginException("No installed plugin named " + name + ".");
        }
        try {
            if (e.kind == Kind.SKILL) {
                deleteTree(safeChild(skillsDir(), name));
                deleteTree(safeChild(disabledSkillsDir(), name));
            } else {
                deleteTree(safeChild(modulesDir(), name));
                removeRow(name);
            }
            JSONObject m = manifest();
            JSONArray arr = m.optJSONArray("plugins");
            JSONArray kept = new JSONArray();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null && !name.equals(o.optString("name"))) {
                        kept.put(o);
                    }
                }
            }
            m.put("plugins", kept);
            writeManifest(m);
        } catch (IOException | JSONException io) {
            throw new PluginException("Could not remove " + name + ": " + io.getMessage(), io);
        }
    }

    /** Whether a change needs the harness to recompose (plugins do; skills are hot-reloaded by the harness). */
    public static boolean needsRestart(List<Entry> entries) {
        for (Entry e : entries) {
            if (e.kind == Kind.PLUGIN) {
                return true;
            }
        }
        return false;
    }

    Entry find(String name) {
        for (Entry e : list()) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ profile rows

    static String rowBegin(String name) {
        return "# deepseek-android plugin " + name + " (begin)";
    }

    static String rowEnd(String name) {
        return "# deepseek-android plugin " + name + " (end)";
    }

    /** One `- insert:` row per user plugin, between our own markers, the shape dsh bundles use; disabled stays listed. */
    static String withRow(String existing, String name, boolean enabled) {
        String without = withoutRow(existing, name);
        StringBuilder sb = new StringBuilder();
        boolean emptyList = true;
        for (String line : without.split("\n")) {
            String t = line.trim();
            if (!(t.isEmpty() || t.startsWith("#") || t.equals("[]"))) {
                emptyList = false;
                break;
            }
        }
        if (!emptyList) {
            sb.append(without);
            if (!without.endsWith("\n")) {
                sb.append('\n');
            }
        } else {
            // A patch holding nothing but comments and an empty list is replaced: two documents would not parse.
            for (String line : without.split("\n")) {
                if (line.trim().startsWith("#")) {
                    sb.append(line).append('\n');
                }
            }
        }
        sb.append(rowBegin(name)).append('\n')
                .append("- insert:\n")
                .append("    - id: ").append(rowId(name)).append('\n')
                .append("      name: '").append(name).append("'\n");
        if (!enabled) {
            sb.append("      disabled: true\n");
        }
        sb.append(rowEnd(name)).append('\n');
        return sb.toString();
    }

    static String withoutRow(String existing, String name) {
        if (existing == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean skipping = false;
        for (String line : existing.split("\n", -1)) {
            if (line.equals(rowBegin(name))) {
                skipping = true;
                continue;
            }
            if (skipping) {
                if (line.equals(rowEnd(name))) {
                    skipping = false;
                }
                continue;
            }
            sb.append(line).append('\n');
        }
        String s = sb.toString();
        while (s.endsWith("\n\n")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.equals("\n") ? "" : s;
    }

    /** Loader ids: the package name without scope punctuation (dsh ids are plain words joined by dashes). */
    static String rowId(String name) {
        return "user-" + name.replace("@", "").replace('/', '-').replace('.', '-').replace('_', '-');
    }

    private void writeRow(String name, boolean enabled) throws IOException {
        File patch = patchFile();
        String existing = patch.isFile() ? read(patch) : "";
        writeAtomic(patch, withRow(existing, name, enabled));
    }

    private void removeRow(String name) throws IOException {
        File patch = patchFile();
        if (patch.isFile()) {
            writeAtomic(patch, withoutRow(read(patch), name));
        }
    }

    // ------------------------------------------------------------------ manifest

    private JSONObject manifest() {
        File f = new File(dshHome, MANIFEST);
        if (f.isFile()) {
            try {
                return new JSONObject(read(f));
            } catch (IOException | JSONException ignored) {
                // a damaged manifest is rebuilt from scratch; installed files stay where they are
            }
        }
        try {
            return new JSONObject().put("version", 1).put("plugins", new JSONArray());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private void save(Entry e) throws IOException {
        try {
            JSONObject m = manifest();
            JSONArray arr = m.optJSONArray("plugins");
            JSONArray next = new JSONArray();
            boolean replaced = false;
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    if (e.name.equals(o.optString("name"))) {
                        next.put(e.toJson());
                        replaced = true;
                    } else {
                        next.put(o);
                    }
                }
            }
            if (!replaced) {
                next.put(e.toJson());
            }
            m.put("plugins", next);
            writeManifest(m);
        } catch (JSONException je) {
            throw new IOException(je);
        }
    }

    private void writeManifest(JSONObject m) throws IOException {
        try {
            writeAtomic(new File(dshHome, MANIFEST), m.toString(2) + "\n");
        } catch (JSONException je) {
            throw new IOException(je);
        }
    }

    // ------------------------------------------------------------------ fetching

    /** Download and unpack a spec; returns the directory holding the plugin/skill (single top folder stripped). */
    File fetch(Spec spec, File staging) throws IOException, PluginException {
        File unpacked = new File(staging, "src");
        if (!unpacked.mkdirs()) {
            throw new IOException("cannot create " + unpacked);
        }
        switch (spec.type) {
            case "local": {
                File local = new File(spec.location);
                if (local.isDirectory()) {
                    return local;
                }
                if (!local.isFile()) {
                    throw new PluginException("The picked file is no longer there.");
                }
                unpackArchive(local, local.getName(), unpacked);
                return stripSingleTop(unpacked);
            }
            case "github": {
                String ref = spec.ref == null ? "HEAD" : spec.ref;
                File archive = new File(staging, "repo.tar.gz");
                download(codeload + "/" + spec.location + "/tar.gz/" + URLEncoder.encode(ref, "UTF-8").replace("+", "%20"), archive, null);
                unpackArchive(archive, "repo.tar.gz", unpacked);
                return stripSingleTop(unpacked);
            }
            case "url": {
                File archive = new File(staging, "download");
                download(spec.location, archive, null);
                unpackArchive(archive, spec.location, unpacked);
                return stripSingleTop(unpacked);
            }
            case "npm": {
                JSONObject version = npmVersion(spec.location, spec.ref);
                JSONObject dist = version.optJSONObject("dist");
                String tarball = dist == null ? "" : dist.optString("tarball", "");
                if (!tarball.startsWith("https://") && !tarball.startsWith(registry)) {
                    throw new PluginException("The registry gave no download for " + spec.location + ".");
                }
                File archive = new File(staging, "package.tgz");
                download(tarball, archive, dist.optString("integrity", ""));
                unpackArchive(archive, "package.tgz", unpacked);
                return stripSingleTop(unpacked);
            }
            default:
                throw new PluginException("Unsupported source.");
        }
    }

    JSONObject npmVersion(String name, String wanted) throws IOException, PluginException {
        String path = name.startsWith("@") ? "@" + URLEncoder.encode(name.substring(1), "UTF-8") : URLEncoder.encode(name, "UTF-8");
        JSONObject meta;
        try {
            meta = new JSONObject(httpText(registry + "/" + path));
        } catch (JSONException e) {
            throw new PluginException("The npm registry answered something unreadable for " + name + ".");
        } catch (NotFound e) {
            throw new PluginException("No npm package named " + name + ".");
        }
        String v = wanted;
        JSONObject tags = meta.optJSONObject("dist-tags");
        if (v == null || (tags != null && tags.has(v))) {
            v = tags == null ? null : tags.optString(v == null ? "latest" : v, null);
        }
        JSONObject versions = meta.optJSONObject("versions");
        if (v == null || versions == null || !versions.has(v)) {
            throw new PluginException("No version " + (wanted == null ? "latest" : wanted) + " of " + name + ".");
        }
        return versions.optJSONObject(v);
    }

    static final class NotFound extends IOException {
        NotFound(String url) {
            super("not found: " + url);
        }
    }

    static String httpText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            return new String(readAll(in, 64L * 1024 * 1024), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** Download to a file; when integrity is an npm "sha512-<base64>" it must match. */
    static void download(String url, File to, String integrity) throws IOException, PluginException {
        HttpURLConnection c = open(url);
        MessageDigest sha512;
        try {
            sha512 = MessageDigest.getInstance("SHA-512");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        long total = 0;
        try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_DOWNLOAD) {
                    throw new PluginException("The download is larger than 200 MB.");
                }
                sha512.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
        if (integrity != null && integrity.startsWith("sha512-")) {
            String got = "sha512-" + Base64.getEncoder().encodeToString(sha512.digest());
            if (!got.equals(integrity.split(" ")[0])) {
                throw new PluginException("The download does not match the registry's checksum.");
            }
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "deepseek-android-engine");
        int code = c.getResponseCode();
        if (code == 404) {
            c.disconnect();
            throw new NotFound(url);
        }
        if (code / 100 != 2) {
            c.disconnect();
            throw new IOException("HTTP " + code + " from " + new URL(url).getHost());
        }
        return c;
    }

    static void unpackArchive(File archive, String name, File into) throws IOException, PluginException {
        String lower = name.toLowerCase(Locale.US);
        try (InputStream raw = new BufferedInputStream(new FileInputStream(archive))) {
            raw.mark(4);
            int b0 = raw.read();
            int b1 = raw.read();
            raw.reset();
            if (b0 == 0x1f && b1 == 0x8b) {
                TarExtractor.extract(new GZIPInputStream(raw, 1 << 16), into, null);
            } else if (b0 == 'P' && b1 == 'K' || lower.endsWith(".zip")) {
                unzip(raw, into);
            } else {
                throw new PluginException("The file is not a .tgz, .tar.gz or .zip archive.");
            }
        }
    }

    static void unzip(InputStream in, File into) throws IOException {
        String root = into.getCanonicalPath();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                File out = new File(into, e.getName());
                String path = out.getCanonicalPath();
                if (!path.startsWith(root + File.separator)) {
                    throw new IOException("zip entry escapes the folder: " + e.getName());
                }
                if (e.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) {
                        throw new IOException("cannot create " + out);
                    }
                    continue;
                }
                File parent = out.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent);
                }
                try (OutputStream o = new FileOutputStream(out)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = zip.read(buf)) > 0) {
                        o.write(buf, 0, n);
                    }
                }
            }
        }
    }

    /** GitHub and npm archives wrap everything in one folder (repo-ref/, package/): step into it. */
    static File stripSingleTop(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null && kids.length == 1 && kids[0].isDirectory()) {
            return kids[0];
        }
        return dir;
    }

    // ------------------------------------------------------------------ files

    static File safeChild(File parent, String relative) throws IOException {
        File child = new File(parent, relative);
        String root = parent.getCanonicalPath();
        String path = child.getCanonicalPath();
        if (relative.isEmpty() || !path.startsWith(root + File.separator)) {
            throw new IOException("path escapes " + parent.getName() + ": " + relative);
        }
        return child;
    }

    static void copyTree(File from, File to) throws IOException {
        if (Files.isSymbolicLink(from.toPath())) {
            return; // a picked folder's links are not followed out of it
        }
        if (from.isDirectory()) {
            if (from.getName().equals(".git")) {
                return;
            }
            if (!to.isDirectory() && !to.mkdirs()) {
                throw new IOException("cannot create " + to);
            }
            File[] kids = from.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    copyTree(kid, new File(to, kid.getName()));
                }
            }
        } else if (from.isFile()) {
            Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deleteTree(File f) {
        if (f == null) {
            return;
        }
        if (Files.isSymbolicLink(f.toPath())) {
            f.delete();
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static void writeAtomic(File f, String body) throws IOException {
        File parent = f.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        File tmp = new File(parent, "." + f.getName() + ".tmp-" + System.nanoTime());
        Files.write(tmp.toPath(), body.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static byte[] readAll(InputStream in, long max) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) {
                throw new IOException("response too large");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
