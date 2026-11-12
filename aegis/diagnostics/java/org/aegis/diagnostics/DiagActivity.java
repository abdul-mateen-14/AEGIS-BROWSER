// Aegis Diagnostics - standalone on-device triage for the Aegis Browser APK.
//
// Answers, with ONE screen and no PC/adb needed:
//   1. Is the device kernel 4 KB or 16 KB page size?  (Os.sysconf)
//   2. Which Aegis build is installed, and where?
//   3. Are the installed APK's stored lib/*.so entries 4 KB / 16 KB
//      page-aligned in the ZIP?  (the loader mmap requirement for
//      extractNativeLibs=false builds)
//   4. Are the ELF binaries themselves 16 KB-ready?  (max PT_LOAD p_align)
//   5. Can this kernel's linker actually System.load() a real Chromium ELF?
//      (copies two small libs out of the installed APK and dlopens them)
//
// 3+4+5 together separate the two launch-crash families:
//   loader/alignment failure  -> crash with UnsatisfiedLinkError / mmap error
//   runtime (patch) crash     -> alignment + load all clean, crash elsewhere
//
// Pure java.* + android.framework APIs, no permissions, no native code of
// its own. Reusable for every future Aegis Android build: install, open,
// screenshot (long-press the text to copy/share it).

package org.aegis.diagnostics;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DiagActivity extends Activity {

    private static final String TARGET_PKG = "org.chromium.chrome";

    private final StringBuilder sb = new StringBuilder();
    private TextView tv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12.5f);
        tv.setTextIsSelectable(true);
        tv.setPadding(28, 28, 28, 64);
        tv.setTextColor(Color.parseColor("#d7e0e8"));
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Color.parseColor("#101418"));
        sc.addView(tv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sc);
        run();
    }

    private void line(String s) {
        sb.append(s).append('\n');
        tv.setText(sb);
    }

    private void run() {
        line("AEGIS DIAGNOSTICS v1.0");
        line("======================");
        line("device : " + Build.MANUFACTURER + " " + Build.MODEL);
        line("android: " + Build.VERSION.RELEASE
                + " (SDK " + Build.VERSION.SDK_INT + ")");
        line("abi    : " + Build.SUPPORTED_ABIS[0]);
        line("fingpr : " + Build.FINGERPRINT);
        try {
            int ps = (int) Os.sysconf(OsConstants._SC_PAGESIZE);
            line("page   : " + ps + " bytes  ->  "
                    + (ps >= 16384 ? "16K KERNEL" : "4K kernel"));
        } catch (Throwable t) {
            line("page   : ERROR " + t);
        }
        line("");

        PackageInfo pi = null;
        try {
            pi = getPackageManager().getPackageInfo(TARGET_PKG, 0);
        } catch (Exception ignored) {
        }
        if (pi == null) {
            line("Aegis Browser: NOT INSTALLED (" + TARGET_PKG + ")");
            line("Install the Aegis APK, then reopen this app.");
            return;
        }
        line("Aegis  : v" + pi.versionName + "  (versionCode " + pi.versionCode + ")");
        ApplicationInfo ai = pi.applicationInfo;
        line("apk    : " + ai.sourceDir);
        line("nativelib: " + ai.nativeLibraryDir);
        line("");

        File apk = new File(ai.sourceDir);
        auditApk(apk);
        line("");
        elfCheck(apk);
        line("");
        loadTest(apk, "lib/arm64-v8a/libchromium_android_linker.so");
        loadTest(apk, "lib/arm64-v8a/libcrashpad_handler_trampoline.so");
        line("");
        line("== done ==");
    }

    /** EOCD -> central directory -> per-entry local-header data offsets. */
    private void auditApk(File apk) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(apk, "r");
            long len = raf.length();
            int tail = (int) Math.min(len, 66000L);
            byte[] tb = new byte[tail];
            raf.seek(len - tail);
            raf.readFully(tb);
            int eocd = -1;
            for (int i = tb.length - 22; i >= 0; i--) {
                if ((tb[i] & 0xff) == 0x50 && (tb[i + 1] & 0xff) == 0x4b
                        && (tb[i + 2] & 0xff) == 0x05 && (tb[i + 3] & 0xff) == 0x06) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                line("audit: no EOCD found?!");
                return;
            }
            long cdSize = u32(tb, eocd + 12);
            long cdOfs = u32(tb, eocd + 16);
            byte[] cd = new byte[(int) cdSize];
            raf.seek(cdOfs);
            raf.readFully(cd);

            line(".so zip audit of installed APK (data offsets):");
            int count = 0, n16 = 0, nbad = 0;
            int p = 0;
            while (p + 46 <= cd.length && u32(cd, p) == 0x02014b50L) {
                int nameLen = (int) u16(cd, p + 28);
                int extraLen = (int) u16(cd, p + 30);
                int cmtLen = (int) u16(cd, p + 32);
                long lho = u32(cd, p + 42);
                String name = new String(cd, p + 46, nameLen);
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    byte[] lh = new byte[30];
                    raf.seek(lho);
                    raf.readFully(lh);
                    int lnl = (int) u16(lh, 26);
                    int lel = (int) u16(lh, 28);
                    long data = lho + 30 + lnl + lel;
                    boolean a4 = (data % 4096) == 0;
                    boolean a16 = (data % 16384) == 0;
                    if (a16) n16++;
                    if (!a4 || !a16) nbad++;
                    count++;
                    line(String.format("  %-44s data@%-11d %%4K=%-3s %%16K=%-3s",
                            shortName(name), data,
                            a4 ? "ok" : "BAD", a16 ? "ok" : "BAD"));
                }
                p += 46 + nameLen + extraLen + cmtLen;
            }
            line("  => " + count + " .so entries: " + n16 + " 16K-aligned, "
                    + nbad + " problems");
        } catch (Throwable t) {
            line("audit ERROR: " + t);
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Read each .so's first bytes from the APK stream and find max PT_LOAD p_align. */
    private void elfCheck(File apk) {
        ZipFile z = null;
        try {
            z = new ZipFile(apk);
            line("ELF PT_LOAD p_align of installed libs:");
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.startsWith("lib/") || !n.endsWith(".so")) {
                    continue;
                }
                long maxAlign = 0;
                InputStream in = z.getInputStream(e);
                try {
                    byte[] head = new byte[16384];
                    int got = 0;
                    while (got < head.length) {
                        int r = in.read(head, got, head.length - got);
                        if (r < 0) {
                            break;
                        }
                        got += r;
                    }
                    if (got >= 64 && head[0] == 0x7f && head[1] == 'E'
                            && head[2] == 'L' && head[3] == 'F') {
                        long phoff = u64(head, 32);
                        int phentsize = (int) u16(head, 54);
                        int phnum = (int) u16(head, 56);
                        for (int i = 0; i < phnum; i++) {
                            long off = phoff + (long) i * phentsize;
                            if (off + 56 > got) {
                                break;
                            }
                            int ptype = (int) u32(head, (int) off);
                            if (ptype != 1) { // PT_LOAD
                                continue;
                            }
                            long al = u64(head, (int) off + 48);
                            if (al > maxAlign) {
                                maxAlign = al;
                            }
                        }
                    }
                } finally {
                    in.close();
                }
                line(String.format("  %-44s max p_align=0x%-5x %s",
                        shortName(n), maxAlign,
                        maxAlign >= 16384 ? "16K-ready"
                                : (maxAlign == 0 ? "unreadable" : "NOT 16K-ready")));
            }
        } catch (Throwable t) {
            line("elf ERROR: " + t);
        } finally {
            if (z != null) {
                try {
                    z.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Copy one small lib out of the installed APK and System.load() the copy. */
    private void loadTest(File apk, String entryName) {
        ZipFile z = null;
        try {
            z = new ZipFile(apk);
            ZipEntry e = z.getEntry(entryName);
            if (e == null) {
                line("load " + shortName(entryName) + ": NOT IN APK");
                return;
            }
            File out = new File(getFilesDir(), "t_" + shortName(entryName));
            FileOutputStream fo = new FileOutputStream(out);
            InputStream in = z.getInputStream(e);
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) {
                fo.write(buf, 0, r);
            }
            fo.close();
            in.close();
            System.load(out.getAbsolutePath());
            line("load " + shortName(entryName) + " (" + e.getSize()
                    + " B): LOADED OK");
        } catch (Throwable t) {
            line("load " + shortName(entryName) + ": FAILED -> "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (z != null) {
                try {
                    z.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static long u16(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8)
                | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    private static long u64(byte[] b, int o) {
        return u32(b, o) | (u32(b, o + 4) << 32);
    }

    private static String shortName(String n) {
        int i = n.lastIndexOf('/');
        return i >= 0 ? n.substring(i + 1) : n;
    }
}
