package com.example.apklab;

import android.content.Context;

import com.android.apksig.ApkSigner;

import java.io.*;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.zip.*;

/** APK ko zip ki tarah edit karta hai (replace / delete / add) aur phir re-sign karta hai. */
public class ApkEngine {

    public final File original;
    public final Map<String, File> replaced = new LinkedHashMap<>(); // name -> new content
    public final Set<String> deleted = new HashSet<>();

    public ApkEngine(File original) { this.original = original; }

    public static boolean isSignatureEntry(String n) {
        if (!n.startsWith("META-INF/")) return false;
        String u = n.toUpperCase(Locale.ROOT);
        return u.equals("META-INF/MANIFEST.MF") || u.endsWith(".SF") || u.endsWith(".RSA")
                || u.endsWith(".DSA") || u.endsWith(".EC");
    }

    /** Entry names (original + added), signature files hidden. */
    public List<String> listEntries() throws IOException {
        TreeSet<String> names = new TreeSet<>();
        try (ZipFile z = new ZipFile(original)) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                if (!ze.isDirectory() && !isSignatureEntry(ze.getName())) names.add(ze.getName());
            }
        }
        names.addAll(replaced.keySet());
        return new ArrayList<>(names);
    }

    public void extract(String name, OutputStream out) throws IOException {
        File r = replaced.get(name);
        if (r != null) { copy(new FileInputStream(r), out); return; }
        try (ZipFile z = new ZipFile(original)) {
            ZipEntry ze = z.getEntry(name);
            if (ze == null) throw new FileNotFoundException(name);
            copy(z.getInputStream(ze), out);
        }
    }

    /** Naya zip likhta hai (4-byte aligned STORED entries) aur apksig se sign karta hai. */
    public void buildAndSign(Context ctx, File outApk) throws Exception {
        File unsigned = new File(outApk.getParentFile(), "unsigned.apk");
        writeZip(unsigned);

        PrivateKey key;
        try (InputStream in = ctx.getAssets().open("key.pk8")) {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readAll(in)));
        }
        X509Certificate cert;
        try (InputStream in = ctx.getAssets().open("cert.x509.pem")) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder(
                "APKLAB", key, Collections.singletonList(cert)).build();
        if (outApk.exists()) outApk.delete();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(unsigned)
                .setOutputApk(outApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
        unsigned.delete();
    }

    private void writeZip(File dest) throws IOException {
        Set<String> written = new HashSet<>();
        try (ZipFile zin = new ZipFile(original);
             CountingOutputStream cos = new CountingOutputStream(new BufferedOutputStream(new FileOutputStream(dest)));
             ZipOutputStream zos = new ZipOutputStream(cos)) {

            for (Enumeration<? extends ZipEntry> e = zin.entries(); e.hasMoreElements(); ) {
                ZipEntry src = e.nextElement();
                String name = src.getName();
                if (src.isDirectory() || isSignatureEntry(name) || deleted.contains(name)) continue;
                written.add(name);
                File rep = replaced.get(name);
                boolean stored = src.getMethod() == ZipEntry.STORED;
                if (rep != null) {
                    put(zos, cos, name, new FileInputStream(rep), stored || name.equals("resources.arsc"));
                } else {
                    put(zos, cos, name, zin.getInputStream(src), stored);
                }
            }
            for (Map.Entry<String, File> en : replaced.entrySet()) {
                if (written.contains(en.getKey())) continue;
                put(zos, cos, en.getKey(), new FileInputStream(en.getValue()), false);
            }
        }
    }

    private static void put(ZipOutputStream zos, CountingOutputStream cos, String name,
                            InputStream in, boolean stored) throws IOException {
        zos.closeEntry(); // pichli entry ka data descriptor pehle flush ho jaye, tabhi offset sahi milega
        ZipEntry ze = new ZipEntry(name);
        if (stored) {
            byte[] data = readAll(in);
            CRC32 crc = new CRC32();
            crc.update(data);
            ze.setMethod(ZipEntry.STORED);
            ze.setSize(data.length);
            ze.setCompressedSize(data.length);
            ze.setCrc(crc.getValue());
            // zipalign jaisa 4-byte alignment (resources.arsc ke liye zaroori, targetSdk >= 30)
            int nameLen = name.getBytes("UTF-8").length;
            int base = (int) ((cos.count + 30 + nameLen + 4) % 4);
            int pad = (4 - base) % 4;
            byte[] extra = new byte[4 + pad];
            extra[0] = 0x35; extra[1] = (byte) 0xD9; extra[2] = (byte) pad; extra[3] = 0;
            ze.setExtra(extra);
            zos.putNextEntry(ze);
            zos.write(data);
        } else {
            ze.setMethod(ZipEntry.DEFLATED);
            zos.putNextEntry(ze);
            copy(in, zos, false);
            in.close();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        copy(in, b, true);
        return b.toByteArray();
    }

    static void copy(InputStream in, OutputStream out) throws IOException { copy(in, out, true); }

    static void copy(InputStream in, OutputStream out, boolean close) throws IOException {
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        if (close) in.close();
    }

    static class CountingOutputStream extends FilterOutputStream {
        long count;
        CountingOutputStream(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }
}
