package com.example.apklab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;

import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 1, REQ_REPLACE = 2, REQ_ADD = 3, REQ_SAVE = 4, REQ_EXTRACT = 5;

    private ApkEngine engine;
    private final List<String> names = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView info;
    private Button btnAdd, btnSave;
    private String pendingName; // jis entry par kaam chal raha hai

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        info = findViewById(R.id.info);
        btnAdd = findViewById(R.id.btnAdd);
        btnSave = findViewById(R.id.btnSave);
        ListView list = findViewById(R.id.list);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);

        findViewById(R.id.btnPick).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(i, REQ_PICK);
        });
        btnAdd.setOnClickListener(v -> {
            EditText et = new EditText(this);
            et.setHint("assets/naya_file.txt");
            new AlertDialog.Builder(this).setTitle("APK ke andar ka path").setView(et)
                    .setPositiveButton("Next", (d, w) -> {
                        String n = et.getText().toString().trim();
                        if (n.isEmpty()) return;
                        pendingName = n;
                        pickFile(REQ_ADD);
                    }).setNegativeButton("Cancel", null).show();
        });
        btnSave.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_TITLE, "edited-signed.apk");
            startActivityForResult(i, REQ_SAVE);
        });
        list.setOnItemClickListener((p, v, pos, id) -> showEntryMenu(names.get(pos)));

        Uri data = getIntent().getData();
        if (data != null) loadApk(data);
    }

    private void pickFile(int req) {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), req);
    }

    private void showEntryMenu(String name) {
        String[] items = {"Extract (save as)", "Replace", "Delete"};
        new AlertDialog.Builder(this).setTitle(name).setItems(items, (d, which) -> {
            pendingName = name;
            if (which == 0) {
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*").putExtra(Intent.EXTRA_TITLE, new File(name).getName()), REQ_EXTRACT);
            } else if (which == 1) {
                pickFile(REQ_REPLACE);
            } else {
                engine.deleted.add(name);
                engine.replaced.remove(name);
                refresh();
            }
        }).show();
    }

    private void loadApk(Uri uri) {
        try {
            File work = new File(getCacheDir(), "work");
            deleteRec(work);
            work.mkdirs();
            File orig = new File(work, "orig.apk");
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(orig)) {
                ApkEngine.copy(in, out, false);
            }
            engine = new ApkEngine(orig);
            engine.listEntries(); // valid zip hai ya nahi
            showApkInfo(orig);
            btnAdd.setEnabled(true);
            btnSave.setEnabled(true);
            refresh();
        } catch (Exception e) {
            toast("APK nahi khuli: " + e.getMessage());
        }
    }

    private void showApkInfo(File apk) {
        PackageManager pm = getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_PERMISSIONS);
        StringBuilder sb = new StringBuilder();
        if (pi != null) {
            pi.applicationInfo.sourceDir = apk.getPath();
            pi.applicationInfo.publicSourceDir = apk.getPath();
            sb.append(pi.applicationInfo.loadLabel(pm)).append('\n')
              .append(pi.packageName).append("  v").append(pi.versionName).append('\n');
            int np = pi.requestedPermissions == null ? 0 : pi.requestedPermissions.length;
            sb.append(np).append(" permissions");
        } else sb.append("Package info padh nahi paya");
        info.setText(sb);
    }

    private void refresh() {
        try {
            names.clear();
            for (String n : engine.listEntries()) {
                String tag = engine.deleted.contains(n) ? "[DEL] " : engine.replaced.containsKey(n) ? "[MOD] " : "";
                names.add(tag + n);
            }
            // click par asli naam chahiye, isliye tag hata kar map karte hain
            adapter.clear();
            adapter.addAll(names);
            adapter.notifyDataSetChanged();
            // names list ko plain naam me rakho
            List<String> plain = new ArrayList<>();
            for (String n : names) plain.add(n.replaceFirst("^\\[(DEL|MOD)\\] ", ""));
            names.clear();
            names.addAll(plain);
        } catch (IOException e) { toast(e.getMessage()); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            switch (req) {
                case REQ_PICK: loadApk(uri); break;
                case REQ_REPLACE: case REQ_ADD: {
                    File f = new File(getCacheDir(), "work/rep_" + System.nanoTime());
                    try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(f)) {
                        ApkEngine.copy(in, out, false);
                    }
                    engine.deleted.remove(pendingName);
                    engine.replaced.put(pendingName, f);
                    refresh();
                    break;
                }
                case REQ_EXTRACT:
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        engine.extract(pendingName, out);
                    }
                    toast("Extract ho gaya");
                    break;
                case REQ_SAVE: save(uri); break;
            }
        } catch (Exception e) { toast("Error: " + e.getMessage()); }
    }

    private void save(Uri uri) {
        btnSave.setEnabled(false);
        toast("Build + sign ho raha hai...");
        new Thread(() -> {
            try {
                File out = new File(getCacheDir(), "work/out.apk");
                engine.buildAndSign(this, out);
                try (InputStream in = new FileInputStream(out); OutputStream o = getContentResolver().openOutputStream(uri)) {
                    ApkEngine.copy(in, o, false);
                }
                runOnUiThread(() -> toast("Saved! Ab install karke test karo."));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Sign fail: " + e));
            } finally {
                runOnUiThread(() -> btnSave.setEnabled(true));
            }
        }).start();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private static void deleteRec(File f) {
        File[] c = f.listFiles();
        if (c != null) for (File x : c) deleteRec(x);
        f.delete();
    }
}
