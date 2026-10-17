package com.dshmobile.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** SAF transfers to a private exchange directory. Workspace editing remains in Harness Web UI. */
public final class FileManagerActivity extends Activity {
    private File selected;
    private LinearLayout list;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout box = new LinearLayout(this); box.setOrientation(1); box.setPadding(24,48,24,24);
        box.addView(Ui.title(this,"Private file exchange"));
        box.addView(Ui.body(this,"Import/export attachments here. This is not a root filesystem browser. Workspace files, Git and terminal are managed by the Harness interface."));
        Button add = Ui.primaryButton(this,"Import file (maximum 32 MiB)"); add.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),1)); box.addView(add);
        ScrollView scroll = new ScrollView(this); list = new LinearLayout(this); list.setOrientation(1); scroll.addView(list); box.addView(scroll); Ui.content(this,box); refresh();
    }
    static File directory(Context context) throws IOException {
        File dir = new File(context.getFilesDir(),"exchange"); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create exchange"); return dir;
    }
    private void refresh() {
        list.removeAllViews();
        try {
            File[] files = directory(this).listFiles(); if (files==null) return;
            Arrays.sort(files,Comparator.comparing(File::getName));
            for (File f: files) { Button b = Ui.outlineButton(this,f.getName()+" ("+f.length()+" bytes)"); b.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(f.getName()).setItems(new String[]{"Export with SAF","Delete"},(dialog,n) -> { if(n==0){ selected=f; startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,f.getName()),2); } else { new AlertDialog.Builder(this).setMessage("Delete this private copy?").setPositiveButton("Delete",(d,w) -> { if(!f.delete()) Toast.makeText(this,"Delete failed",0).show(); refresh(); }).setNegativeButton("Cancel",null).show(); } }).show()); list.addView(b); }
        } catch(IOException e) { list.addView(Ui.body(this,"Exchange directory unavailable")); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data); if(result!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData(); File export=selected;
        new Thread(() -> {
            File incoming=null;
            try {
                if(request==1) { incoming=new File(directory(this),"import-"+UUID.randomUUID()); try(InputStream in=getContentResolver().openInputStream(uri); OutputStream out=new FileOutputStream(incoming)){ copy(in,out,32L*1024*1024); } }
                else if(request==2 && export!=null) { try(InputStream in=new FileInputStream(export); OutputStream out=getContentResolver().openOutputStream(uri)){copy(in,out,32L*1024*1024);} }
                runOnUiThread(this::refresh);
            } catch(Exception e) { if(incoming!=null) incoming.delete(); runOnUiThread(() -> Toast.makeText(this,"Transfer failed or size limit exceeded",Toast.LENGTH_LONG).show()); }
        },"saf-transfer").start();
    }
    static void copy(InputStream in,OutputStream out,long limit)throws IOException {
        if(in==null || out==null)throw new IOException("Provider unavailable"); byte[] bytes=new byte[8192]; long total=0; int n;
        while((n=in.read(bytes))!=-1){total+=n;if(total>limit)throw new IOException("Limit exceeded");out.write(bytes,0,n);}
    }
}
