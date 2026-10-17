package com.dshmobile.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Non-secret diagnostics and explicit stop. No periodic diagnostics polling. */
public final class SettingsActivity extends Activity {
    private TextView log;
    private HarnessService service;
    private final Runnable refresh = () -> { if(service!=null)log.setText(service.diagnostics()); };
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){service=((HarnessService.LocalBinder)binder).service();service.observe(refresh);}
        public void onServiceDisconnected(ComponentName name){service=null;}
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout box = new LinearLayout(this); box.setOrientation(1); box.setPadding(24,48,24,24);
        box.addView(Ui.title(this, "DSH Root diagnostics"));
        box.addView(Ui.body(this, "Experimental cloud-built APK. Android 16/17, KernelSU, background completion and 4KB/16KB compatibility require device testing. Automatic freeze is disabled until authoritative task activity is connected. No wake lock or polling keepalive is held."));
        Button git = Ui.outlineButton(this,"Local Git operations"); git.setOnClickListener(v -> startActivity(new Intent(this,GitActivity.class))); box.addView(git);
        Button files = Ui.outlineButton(this,"Import / export files"); files.setOnClickListener(v -> startActivity(new Intent(this, FileManagerActivity.class))); box.addView(files);
        Button export = Ui.outlineButton(this,"Export lifecycle diagnostics"); export.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"dsh-root-diagnostics.txt"),1)); box.addView(export);
        Button stop = Ui.outlineButton(this,"Stop runtime"); stop.setOnClickListener(v -> { startService(new Intent(this,HarnessService.class).setAction(HarnessService.STOP)); finish(); }); box.addView(stop);
        log=Ui.hint(this,"No runtime lifecycle events");ScrollView scroll=new ScrollView(this);scroll.addView(log);box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Ui.content(this,box);bindService(new Intent(this,HarnessService.class),connection,BIND_AUTO_CREATE);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==1 && result==RESULT_OK && data!=null && data.getData()!=null){
            try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IOException("Provider unavailable");out.write(log.getText().toString().getBytes(StandardCharsets.UTF_8));}
            catch(IOException e){Toast.makeText(this,"Diagnostics export failed",1).show();}
        }
    }
    @Override protected void onDestroy(){if(service!=null)service.removeObserver(refresh);unbindService(connection);super.onDestroy();}
}
