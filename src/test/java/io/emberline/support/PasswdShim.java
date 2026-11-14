package io.emberline.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Test-only workaround for containers whose uid has no /etc/passwd entry: PostgreSQL's initdb calls getpwuid() and
 * stops with "could not look up effective user ID". There is no compiler, unshare or nss_wrapper in such pods, so this
 * writes a minimal x86-64 ELF shared object exporting getpwuid/getpwuid_r (fixed user "postgres") and wraps the
 * extracted initdb with LD_PRELOAD. Never used where the uid resolves, never part of the application.
 */
final class PasswdShim {

    private PasswdShim() {
    }

    /** Wraps every extracted initdb under {@code workDir} (zonky's PG-* directories) once. */
    static void install(Path workDir) throws IOException {
        Path lib = workDir.resolve("fake-getpwuid.so");
        Files.write(lib, elf());
        try (Stream<Path> dirs = Files.list(workDir)) {
            for (Path dir : dirs.filter(d -> d.getFileName().toString().startsWith("PG-")).toList()) {
                Path initdb = dir.resolve("bin/initdb");
                Path real = dir.resolve("bin/initdb.real");
                if (Files.exists(initdb) && !Files.exists(real)) {
                    Files.move(initdb, real);
                    Files.writeString(initdb, "#!/bin/sh\nLD_PRELOAD=" + lib.toAbsolutePath()
                            + " exec \"$(dirname \"$0\")/initdb.real\" \"$@\"\n");
                    Files.setPosixFilePermissions(initdb, PosixFilePermissions.fromString("rwxr-xr-x"));
                }
            }
        }
    }

    static byte[] elf() {
        Map<String, byte[]> strs = new LinkedHashMap<>();
        strs.put("name", "postgres\0".getBytes());
        strs.put("passwd", "x\0".getBytes());
        strs.put("gecos", "\0".getBytes());
        strs.put("dir", "/tmp\0".getBytes());
        strs.put("shell", "/bin/sh\0".getBytes());
        byte[] dynstr = "\0getpwuid\0getpwuid_r\0".getBytes();
        int nsym = 3;
        int off = 64 + 56 * 3;
        int hashOff = off;
        off += 24;
        off = (off + 7) & ~7;
        int symOff = off;
        off += 24 * nsym;
        int strOff = off;
        off += dynstr.length;
        off = (off + 15) & ~15;
        int pwOff = off;
        off += 48;
        Map<String, Integer> sOff = new LinkedHashMap<>();
        ByteArrayOutputStream dataStrs = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> e : strs.entrySet()) {
            sOff.put(e.getKey(), off + dataStrs.size());
            dataStrs.writeBytes(e.getValue());
        }
        off += dataStrs.size();
        off = (off + 15) & ~15;
        Code code = new Code(off);
        int f1 = code.here();
        code.leaRax(sOff.get("name"));
        code.movRipRax(pwOff);
        code.leaRax(sOff.get("passwd"));
        code.movRipRax(pwOff + 8);
        code.movRipEdi(pwOff + 16);
        code.movRipEdi(pwOff + 20);
        code.leaRax(sOff.get("gecos"));
        code.movRipRax(pwOff + 24);
        code.leaRax(sOff.get("dir"));
        code.movRipRax(pwOff + 32);
        code.leaRax(sOff.get("shell"));
        code.movRipRax(pwOff + 40);
        code.leaRax(pwOff);
        code.raw(0xc3);
        int f1Size = code.here() - f1;
        int f2 = code.here();
        code.leaRax(sOff.get("name"));
        code.raw(0x48, 0x89, 0x06);
        code.leaRax(sOff.get("passwd"));
        code.raw(0x48, 0x89, 0x46, 0x08);
        code.raw(0x89, 0x7e, 0x10, 0x89, 0x7e, 0x14);
        code.leaRax(sOff.get("gecos"));
        code.raw(0x48, 0x89, 0x46, 24);
        code.leaRax(sOff.get("dir"));
        code.raw(0x48, 0x89, 0x46, 32);
        code.leaRax(sOff.get("shell"));
        code.raw(0x48, 0x89, 0x46, 40);
        code.raw(0x49, 0x89, 0x30, 0x31, 0xc0, 0xc3);
        int f2Size = code.here() - f2;
        int dynOff = (code.here() + 7) & ~7;
        long[][] dt = {{4, hashOff}, {5, strOff}, {6, symOff}, {10, dynstr.length}, {11, 24}, {0, 0}};
        int total = dynOff + 16 * dt.length;
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {0x7f, 'E', 'L', 'F', 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        b.putShort((short) 3).putShort((short) 0x3e).putInt(1).putLong(0).putLong(64).putLong(0).putInt(0)
                .putShort((short) 64).putShort((short) 56).putShort((short) 3).putShort((short) 64)
                .putShort((short) 0).putShort((short) 0);
        b.putInt(1).putInt(7).putLong(0).putLong(0).putLong(0).putLong(total).putLong(total).putLong(0x1000);
        b.putInt(2).putInt(6).putLong(dynOff).putLong(dynOff).putLong(dynOff).putLong(16L * dt.length)
                .putLong(16L * dt.length).putLong(8);
        b.putInt(0x6474e551).putInt(6).putLong(0).putLong(0).putLong(0).putLong(0).putLong(0).putLong(16);
        b.position(hashOff);
        b.putInt(1).putInt(nsym).putInt(1).putInt(0).putInt(2).putInt(0);
        b.position(symOff + 24);
        b.putInt(1).put((byte) 0x12).put((byte) 0).putShort((short) 1).putLong(f1).putLong(f1Size);
        b.putInt(10).put((byte) 0x12).put((byte) 0).putShort((short) 1).putLong(f2).putLong(f2Size);
        b.position(strOff);
        b.put(dynstr);
        b.position(sOff.get("name"));
        b.put(dataStrs.toByteArray());
        b.position(code.start);
        b.put(code.bytes());
        b.position(dynOff);
        for (long[] e : dt) {
            b.putLong(e[0]).putLong(e[1]);
        }
        return b.array();
    }

    /** Tiny x86-64 emitter with RIP-relative addressing (no relocations needed). */
    private static final class Code {
        final int start;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Code(int start) {
            this.start = start;
        }

        int here() {
            return start + out.size();
        }

        void raw(int... bytes) {
            for (int v : bytes) {
                out.write(v);
            }
        }

        private void rel32(int target) {
            int disp = target - (here() + 4);
            raw(disp & 0xff, (disp >>> 8) & 0xff, (disp >>> 16) & 0xff, (disp >>> 24) & 0xff);
        }

        void leaRax(int target) {
            raw(0x48, 0x8d, 0x05);
            rel32(target);
        }

        void movRipRax(int target) {
            raw(0x48, 0x89, 0x05);
            rel32(target);
        }

        void movRipEdi(int target) {
            raw(0x89, 0x3d);
            rel32(target);
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
