#!/bin/sh
# Off-car checks for the cache paths used by boot, late-load and app-part loaders.
set -e
cd "$(dirname "$0")/../.."
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/src/hmgapi/hook" "$T/classes"
cat > "$T/src/hmgapi/hook/ModDexCacheTest.java" <<'J'
package hmgapi.hook;
import java.io.*;
import java.nio.file.*;
public final class ModDexCacheTest {
    static void check(boolean b, String s) { if (!b) throw new AssertionError(s); System.out.println("ok    " + s); }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        for (String scope : new String[] {"zygote", "app"}) {
            File base = new File(root, scope);
            File a = ModDexCache.directory(base, "example-a");
            File b = ModDexCache.directory(base, "ev-routing");
            File first = new File(a, "hooks.dex"), second = new File(b, "hooks.dex");
            Files.write(first.toPath(), new byte[] {1,2,3});
            Files.write(second.toPath(), new byte[] {4});
            check(!first.getCanonicalPath().equals(second.getCanonicalPath()), scope + ": same JAR basename has separate cache files");
            check(Files.readAllBytes(first.toPath()).length == 3, scope + ": second mod cannot overwrite first cache");
            check(ModDexCache.directory(base, "ev-routing").equals(b), scope + ": repeated load reuses its own directory");
        }
        for (String id : new String[] {"..", "../escape", "/absolute", "a/b", ""}) {
            try { ModDexCache.directory(root, id); throw new AssertionError("accepted " + id); }
            catch (IOException expected) { System.out.println("ok    unsafe cache id refused: " + id); }
        }
        File blocked = new File(root, "blocked"); Files.write(blocked.toPath(), new byte[] {0});
        try { ModDexCache.directory(blocked, "mod"); throw new AssertionError("accepted file as parent"); }
        catch (IOException expected) { System.out.println("ok    cache creation failure is explicit"); }
    }
}
J
javac --release 8 -Xlint:-options -d "$T/classes" core/runtime/java/hmgapi/hook/ModDexCache.java "$T/src/hmgapi/hook/ModDexCacheTest.java"
java -cp "$T/classes" hmgapi.hook.ModDexCacheTest "$T/cache"
