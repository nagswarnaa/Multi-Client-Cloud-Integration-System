import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

class FileMonitor extends Thread {

    private final WatchService watcher;
    private final Path dir;
    private final ScheduledExecutorService executor;
    private final Map<WatchKey, Path> keyMap;

    public FileMonitor(Path dir) throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.dir = dir;
        this.keyMap = new HashMap<>();
        this.executor = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void run() {
        try {
            registerAll(dir);

            while (true) {
                WatchKey key = watcher.take();
                Path parent = keyMap.get(key);
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path fileName = ev.context();
                    Path child = parent.resolve(fileName);

                    if (kind == StandardWatchEventKinds.ENTRY_MODIFY || kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        System.out.println("File " + fileName + " has changed or been created!");
                        Future<?> future = executor.submit(() -> syncFile(child));
                        executor.schedule(() -> handleSyncCompletion(future), 2, TimeUnit.SECONDS);
                    }
                    else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        System.out.println("File " + fileName + " has been deleted!");
                        new Client(fileName.toString(), dir.toString()).deleteFile();

                    }

                }

                boolean valid = key.reset();
                if (!valid) {
                    keyMap.remove(key);
                    if (keyMap.isEmpty()) {
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            executor.shutdown();
        }
    }


    private void registerAll(final Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
                keyMap.put(key, dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void syncFile(Path filePath) {
        Path fileName = filePath.getFileName();
        if (fileName != null) {
            new Client(fileName.toString(), dir.toString()).sendFile();
            System.out.println("Syncing file: " + fileName);
        }
    }


    private void handleSyncCompletion(Future<?> future) {
        if (!future.isDone()) {
            future.cancel(false);
        }
    }
}


public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Please provide one directory to monitor as an argument");
            return;
        }
        // Create a new FileMonitor instance and start it for the directory provided
        Path dir = Paths.get(args[0]);
        FileMonitor fileMonitor = new FileMonitor(dir);
        fileMonitor.start();
        System.out.println("Monitoring started for directory: " + args[0]);
    }
}


