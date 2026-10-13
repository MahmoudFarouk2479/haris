package dev.haris.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import dev.haris.settings.SettingsService;
import org.springframework.stereotype.Component;

/**
 * The fence around every file tool. A path the model supplies is accepted only if, after
 * resolving "..", symbolic links and junctions, it lies inside one of the folders the user
 * granted in Settings — and it is not a secret (keys, password vaults, .env files).
 * Writes are narrower still: new files must be text types, renames may not change a file's
 * type, and nothing is created or renamed to a type Windows would execute — so the assistant
 * can never plant something that runs when double-clicked.
 */
@Component
public class PathGuard {

    /** Never read or written, wherever they sit. */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)^(\\.env(\\..*)?|id_(rsa|dsa|ecdsa|ed25519)(\\.pub)?|.*\\.(pem|key|pfx|p12|kdbx|kdb|keychain|ovpn|ppk)"
                    + "|.*credential.*|.*secret.*|.*password.*|wallet\\.dat|login data|cookies|known_hosts|authorized_keys)$");

    private static final Set<String> SECRET_DIRS = Set.of(".ssh", ".gnupg", ".aws", ".azure", ".kube", ".docker", ".haris", ".jarvis");

    /** Never created, written or renamed to: anything Windows, or a common install, will run or act on. */
    private static final Set<String> EXECUTABLE = Set.of(
            "exe", "dll", "com", "scr", "msi", "msp", "bat", "cmd", "ps1", "psm1", "psd1", "vbs", "vbe", "js", "jse",
            "wsf", "wsh", "hta", "lnk", "url", "reg", "cpl", "jar", "pif", "scf", "inf", "sys", "appx", "msix", "application",
            "py", "pyw", "pyc", "pyz", "msc", "wsc", "sct", "chm", "library-ms", "searchconnector-ms",
            "settingcontent-ms", "website", "theme", "themepack", "xll", "xlam", "docm", "xlsm", "pptm",
            "iso", "img", "vhd", "vhdx");

    /** The only types write_file creates: it writes text, so nothing else is needed. */
    private static final Set<String> TEXT_TYPES = Set.of(
            "txt", "md", "markdown", "csv", "tsv", "json", "log", "yaml", "yml", "xml", "srt");

    /** "Name.{CLSID}" turns a folder into a shell object that opens something else. */
    private static final Pattern SHELL_NAMESPACE = Pattern.compile(".*\\.\\{[0-9A-Fa-f-]+\\}$");

    private final SettingsService settings;

    public PathGuard(SettingsService settings) {
        this.settings = settings;
    }

    /** Real paths of the granted folders that currently exist. */
    public List<Path> roots() {
        List<Path> out = new ArrayList<>();
        for (String f : settings.get().allowedFolders()) {
            try {
                out.add(Path.of(f).toRealPath());
            } catch (IOException | RuntimeException ignored) {
                // folder removed since it was granted — simply not available
            }
        }
        return out;
    }

    /** An existing file or folder the user granted access to. */
    public Path existing(String raw) throws ToolException {
        Path candidate = interpret(raw);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolException("Not found: " + candidate);
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw new ToolException("Cannot access: " + candidate);
        }
        requireInside(real);
        requireNotSecret(real);
        return real;
    }

    /** A path that may not exist yet, whose parent folder is inside a granted folder. */
    public Path writable(String raw) throws ToolException {
        Path candidate = interpret(raw);
        Path parent = candidate.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ToolException("The folder for this file does not exist: " + parent);
        }
        Path real;
        try {
            real = parent.toRealPath().resolve(candidate.getFileName());
        } catch (IOException e) {
            throw new ToolException("Cannot access: " + parent);
        }
        if (Files.isSymbolicLink(real)) {
            throw new ToolException("Refusing to write through a symbolic link.");
        }
        requireInside(real);
        requireNotSecret(real);
        requireNotExecutable(real);
        for (Path segment : real) {
            if (segment.toString().startsWith(".")) {
                throw new ToolException("Hidden files and folders cannot be modified.");
            }
        }
        return real;
    }

    /** A new or existing text file for write_file: only text types may be created. */
    public Path writableTextFile(String raw) throws ToolException {
        Path real = writable(raw);
        if (!TEXT_TYPES.contains(extension(real))) {
            throw new ToolException("Only text files can be written (." + String.join(", .", new java.util.TreeSet<>(TEXT_TYPES)) + ").");
        }
        return real;
    }

    /** A new folder for create_folder. */
    public Path writableFolder(String raw) throws ToolException {
        Path real = writable(raw);
        requireNotShellNamespace(real);
        return real;
    }

    /** Where move_file may put {@code from}: a file keeps its type (or moves between text types). */
    public Path moveTarget(Path from, String raw) throws ToolException {
        Path real = writable(raw);
        if (Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
            requireNotShellNamespace(real);
            return real;
        }
        String before = extension(from);
        String after = extension(real);
        boolean bothText = TEXT_TYPES.contains(before) && TEXT_TYPES.contains(after);
        if (!before.equals(after) && !bothText) {
            throw new ToolException("A rename may not change the file's type (." + before + " → ." + after + ").");
        }
        return real;
    }

    /** Lower-case extension as Windows will store it (trailing dots and spaces dropped), or "". */
    static String extension(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[. ]+$", "");
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    private static void requireNotShellNamespace(Path real) throws ToolException {
        // Judged as Windows stores the name: trailing dots and spaces are dropped ("x.{CLSID}." → "x.{CLSID}").
        String stored = real.getFileName().toString().replaceAll("[. ]+$", "");
        if (SHELL_NAMESPACE.matcher(stored).matches()) {
            throw new ToolException("Folder names ending in .{…} turn into shell shortcuts and are not allowed.");
        }
    }

    private Path interpret(String raw) throws ToolException {
        String cleaned = raw.strip();
        if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.startsWith("\\\\") || cleaned.startsWith("//")) {
            throw new ToolException("Network paths are not allowed.");
        }
        if (cleaned.contains(":") && cleaned.indexOf(':') != 1) {
            throw new ToolException("Alternate data streams and device paths are not allowed.");
        }
        List<Path> roots = roots();
        if (roots.isEmpty()) {
            throw new ToolException("No folders are granted. Add folders in Settings → Allowed folders.");
        }
        Path p;
        try {
            p = Path.of(cleaned);
        } catch (RuntimeException e) {
            throw new ToolException("Not a valid path: " + raw);
        }
        if (!p.isAbsolute()) {
            // "Documents\notes.txt" → the granted folder named Documents; otherwise the first one.
            String first = p.getName(0).toString();
            Path base = roots.stream()
                    .filter(r -> r.getFileName() != null && r.getFileName().toString().equalsIgnoreCase(first))
                    .findFirst()
                    .map(r -> r.getParent())
                    .orElse(roots.getFirst());
            p = base.resolve(p);
        }
        return p.normalize();
    }

    private void requireInside(Path real) throws ToolException {
        for (Path root : roots()) {
            if (real.startsWith(root)) {
                return;
            }
        }
        throw new ToolException("Outside the folders you granted: " + real);
    }

    private static void requireNotSecret(Path real) throws ToolException {
        for (Path segment : real) {
            String s = segment.toString();
            if (SECRET_DIRS.contains(s.toLowerCase(Locale.ROOT))) {
                throw new ToolException("This location holds credentials and is off-limits.");
            }
        }
        Path name = real.getFileName();
        if (name != null && SECRET.matcher(name.toString()).matches()) {
            throw new ToolException("This file looks like it holds secrets, so it is off-limits.");
        }
    }

    private static void requireNotExecutable(Path real) throws ToolException {
        String ext = extension(real);
        if (EXECUTABLE.contains(ext)) {
            throw new ToolException("Creating or renaming to executable file types (." + ext + ") is not allowed.");
        }
    }
}
