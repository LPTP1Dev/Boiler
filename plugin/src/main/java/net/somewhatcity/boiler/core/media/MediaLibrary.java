package net.somewhatcity.boiler.core.media;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Filesystem access stays on I/O workers; resolved links must remain in this library. */
public final class MediaLibrary {
    public enum Kind { DIRECTORY, IMAGE, VIDEO, AUDIO, OTHER }
    public record Entry(String path,String name,Kind kind,long bytes) {}
    private static final Set<String> IMAGES = Set.of("png","jpg","jpeg","gif","bmp","tif","tiff","wbmp");
    private static final Set<String> VIDEOS = Set.of("mp4","mkv","webm","mov","avi","m4v","mpeg","mpg","ts","mts","m2ts","3gp","ogv","flv","wmv","vob");
    private static final Set<String> AUDIO = Set.of("mp3","wav","flac","ogg","opus","m4a","aac","wma","aiff","aif","ac3","oga");
    private final Path root;

    public MediaLibrary(Path pluginDirectory) throws IOException {
        Files.createDirectories(pluginDirectory);
        Path plugin = pluginDirectory.toRealPath();
        Path media = plugin.resolve("media"); Files.createDirectories(media);
        root = media.toRealPath();
        if (!root.startsWith(plugin)) throw new IOException("Der media-Ordner darf nicht außerhalb des Pluginordners liegen.");
    }
    public Path resolve(String relative) throws IOException {
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) throw new IOException("Der Pfad liegt außerhalb von media.");
        Path real = candidate.toRealPath();
        if (!real.startsWith(root)) throw new IOException("Die Verknüpfung liegt außerhalb von media.");
        return real;
    }
    public String relative(Path path) { return root.relativize(path).toString().replace('\\','/'); }
    public List<Entry> list(String relative) throws IOException {
        Path directory = resolve(relative);
        if (!Files.isDirectory(directory)) throw new IOException("Kein Ordner: " + relative);
        List<Entry> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                try {
                    Path real = resolve(relative(path));
                    if (Files.isDirectory(real)) result.add(new Entry(relative(path),path.getFileName().toString(),Kind.DIRECTORY,0));
                    else if (Files.isRegularFile(real)) result.add(new Entry(relative(path),path.getFileName().toString(),kind(real),Files.size(real)));
                } catch (IOException | SecurityException ignored) { /* Unreadable or escaping links are not browsable. */ }
            }
        }
        result.sort(Comparator.comparing((Entry entry) -> entry.kind()!=Kind.DIRECTORY).thenComparing(Entry::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::name));
        return List.copyOf(result);
    }
    public static Kind kind(Path path) {
        String name = path.getFileName().toString(); int dot = name.lastIndexOf('.');
        String suffix = dot<0 ? "" : name.substring(dot+1).toLowerCase(Locale.ROOT);
        return IMAGES.contains(suffix) ? Kind.IMAGE : VIDEOS.contains(suffix) ? Kind.VIDEO : AUDIO.contains(suffix) ? Kind.AUDIO : Kind.OTHER;
    }
    public static String parent(String directory) { int slash = directory.lastIndexOf('/'); return slash<0 ? "" : directory.substring(0,slash); }
}
