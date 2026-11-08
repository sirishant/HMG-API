package hmgapi.engine;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** MemRecipe on a fake process memory. Arguments: (stock, patched build, recipe) triples. */
public final class MemRecipeTest {
    static int fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    /** a process: the library at base, code pages mapped where the test says */
    static final class Fake implements MemRecipe.Memory {
        final List<long[]> at = new ArrayList<long[]>();
        final List<byte[]> mem = new ArrayList<byte[]>();
        long shift;          // mapCode puts pages at hint + shift
        long refuseBelow;    // ... and refuses hints under this (0: none)

        Fake(long base, byte[] lib) {
            at.add(new long[] { base });
            mem.add(lib.clone());
        }

        int find(long addr, int len) {
            for (int k = 0; k < at.size(); k++) {
                long a = at.get(k)[0];
                if (addr >= a && addr + len <= a + mem.get(k).length) return k;
            }
            throw new IllegalStateException("unmapped 0x" + Long.toHexString(addr));
        }

        public byte[] peek(long addr, int len) {
            int k = find(addr, len);
            byte[] r = new byte[len];
            System.arraycopy(mem.get(k), (int) (addr - at.get(k)[0]), r, 0, len);
            return r;
        }

        public boolean poke(long addr, byte[] d) {
            int k = find(addr, d.length);
            System.arraycopy(d, 0, mem.get(k), (int) (addr - at.get(k)[0]), d.length);
            return true;
        }

        public long mapCode(long hint, int len) {
            if (refuseBelow != 0 && hint < refuseBelow) return 0;
            long a = hint + shift;
            at.add(new long[] { a });
            mem.add(new byte[(len + 0xfff) & ~0xfff]);
            return a;
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    static long bwTarget(long at, byte[] b) {
        int hw1 = (b[0] & 0xff) | (b[1] & 0xff) << 8, hw2 = (b[2] & 0xff) | (b[3] & 0xff) << 8;
        if ((hw1 & 0xf800) != 0xf000 || (hw2 & 0xd000) != 0x9000) return -1;
        long s = (hw1 >> 10) & 1, j1 = (hw2 >> 13) & 1, j2 = (hw2 >> 11) & 1;
        long i1 = 1 - (j1 ^ s), i2 = 1 - (j2 ^ s);
        long off = (s << 24) | (i1 << 23) | (i2 << 22) | ((hw1 & 0x3ffL) << 12) | ((hw2 & 0x7ffL) << 1);
        if (s == 1) off -= 1L << 25;
        return at + 4 + off;
    }

    static int i32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    /** no arguments: the synthetic libraries; else <stock lib> <patched lib> <recipe> triples (a mod's real recipes) */
    public static void main(String[] a) throws Exception {
        if (a.length == 0) synthetic();
        for (int k = 0; k + 2 < a.length; k += 3) real(a[k], a[k + 1], a[k + 2]);
        System.exit(fail);
    }

    static void synthetic() {
        byte[] lib = new byte[0x4000];
        for (int i = 0; i < lib.length; i++) lib[i] = (byte) (i * 7 + 3);
        byte[] after = lib.clone();
        after[0x110] = (byte) 0xaa;
        after[0x111] = (byte) 0xbb;
        String region = MemRecipe.md5(java.util.Arrays.copyOfRange(lib, 0x100, 0x120));
        String regionAfter = MemRecipe.md5(java.util.Arrays.copyOfRange(after, 0x100, 0x120));
        String text = "# test\nid t\nfile /system/lib/libt.so\nnative 0123456789abcdef0123456789abcdef already\n"
                + "verify 0x100 32 " + region + " " + regionAfter + "\npatch 0x110 aabb\n";
        MemRecipe r = MemRecipe.parse(text);
        long base = 0x40000000L;
        Fake m = new Fake(base, lib);
        MemRecipe.Result res = r.apply(m, base, lib.length, "x");
        check("in-place patch applied", res.status == MemRecipe.Result.APPLIED && hex(m.peek(base + 0x110, 2)).equals("aabb"));
        check("applied twice: already", r.apply(m, base, lib.length, "x").status == MemRecipe.Result.ALREADY);
        check("a library with the feature (native md5): nothing to do",
                r.apply(new Fake(base, lib), base, lib.length, "0123456789abcdef0123456789abcdef").status == MemRecipe.Result.NATIVE);
        byte[] other = lib.clone();
        other[0x105] ^= 1;
        Fake mo = new Fake(base, other);
        res = r.apply(mo, base, lib.length, "x");
        check("other bytes: mismatch, nothing written", res.status == MemRecipe.Result.MISMATCH && mo.peek(base + 0x110, 1)[0] == other[0x110]);
        check("a region past the code segment: mismatch", r.apply(new Fake(base, lib), base, 0x110, "x").status == MemRecipe.Result.MISMATCH);

        // added code: segment with a table entry back into the library and a branch into it
        String br = MemRecipe.md5(java.util.Arrays.copyOfRange(lib, 0x200, 0x210));
        String seg = "0000000011223344";
        MemRecipe s = MemRecipe.parse("id s\nverify 0x200 16 " + br + " -\nsegment 0x5010 " + seg + "\nrel32 0x0 0x0 0x205\nbranch 0x204 0x4\n");
        Fake ms = new Fake(base, lib);
        res = s.apply(ms, base, lib.length, "x");
        long segAt = res.segment;
        check("segment at its preferred place", segAt == base + 0x5010);
        check("rel32: table entry points back at the library", segAt + i32(ms.peek(segAt, 4), 0) == base + 0x205);
        check("branch into the segment", bwTarget(base + 0x204, ms.peek(base + 0x204, 4)) == segAt + 4);
        check("segment code kept", hex(ms.peek(segAt + 4, 4)).equals("11223344"));
        Fake far = new Fake(base, lib);
        far.refuseBelow = base + 0x100000;   // the preferred place is taken: the next hint (after the library) is used
        res = s.apply(far, base, lib.length, "x");
        check("preferred place taken: placed after the library, still in range",
                res.ok() && res.segment >= base + 0x100000 && bwTarget(base + 0x204, far.peek(base + 0x204, 4)) == res.segment + 4
                        && res.segment + i32(far.peek(res.segment, 4), 0) == base + 0x205);
        Fake none = new Fake(base, lib);
        none.shift = 0x40000000L;            // the kernel puts every page far away
        check("no place in branch range: failed, library untouched",
                s.apply(none, base, lib.length, "x").status == MemRecipe.Result.FAILED && none.peek(base + 0x204, 4)[0] == lib[0x204]);

        check("b.w forward and back", bwTarget(0x1000, MemRecipe.branchW(0x1000, 0x123456)) == 0x123456
                && bwTarget(0x800000, MemRecipe.branchW(0x800000, 0x10)) == 0x10);
        check("b.w out of range: none", MemRecipe.branchW(0, 0x2000000) == null);
        boolean threw = false;
        try {
            MemRecipe.parse("id x\nverify 0x100 16 a b\npatch 0x200 00\n");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("a patch outside every verify region is refused", threw);
    }

    /** the recipe on the stock library = the file-patched build, with the code in place or moved */
    static void real(String stockPath, String patchedPath, String recipePath) throws Exception {
        byte[] stock = Files.readAllBytes(Paths.get(stockPath)), patched = Files.readAllBytes(Paths.get(patchedPath));
        MemRecipe r = MemRecipe.parse(new String(Files.readAllBytes(Paths.get(recipePath)), "UTF-8"));
        int textEnd = 0, ph = i32(stock, 0x1c);
        for (int k = 0; textEnd == 0; k++) if (i32(stock, ph + 32 * k) == 1) textEnd = (i32(stock, ph + 32 * k + 16) + 0xfff) & ~0xfff;   // the first PT_LOAD, as mapped (whole pages)
        long base = 0x48000000L;
        Fake m = new Fake(base, java.util.Arrays.copyOf(stock, textEnd));
        MemRecipe.Result res = r.apply(m, base, textEnd, MemRecipe.md5(stock));
        if (!res.ok()) System.out.println("      " + res.message);
        boolean same = res.status == MemRecipe.Result.APPLIED;
        byte[] text = m.peek(base, textEnd);
        for (int i = 0x34; same && i < textEnd; i++) same = text[i] == patched[i];
        if (r.segment == null) {
            check(r.id + ": in memory = the file lpatch makes", same);
            return;
        }
        byte[] code = m.peek(res.segment, r.segment.length);
        long segFile = r.segPreferred;
        for (int i = 0; same && i < code.length; i++) same = code[i] == patched[(int) segFile + i];
        check(r.id + ": in memory = the patched build (code at its own place)", same && res.segment == base + segFile);

        Fake moved = new Fake(base, java.util.Arrays.copyOf(stock, textEnd));
        moved.shift = 0x300000;
        res = r.apply(moved, base, textEnd, MemRecipe.md5(stock));
        boolean ok = res.status == MemRecipe.Result.APPLIED;
        for (long[] b : r.branches) ok &= bwTarget(base + b[0], moved.peek(base + b[0], 4)) == res.segment + b[1];
        byte[] mc = moved.peek(res.segment, r.segment.length);
        for (long[] x : r.rel32) {
            long want = base + (i32(patched, (int) (segFile + x[1]) + (int) (x[0] - x[1])) + segFile + x[1]);   // target in the build
            ok &= res.segment + x[1] + i32(mc, (int) x[0]) == want;
        }
        check(r.id + ": code moved 3 MB: branches and table follow it", ok && res.segment == base + segFile + 0x300000);
    }
}
