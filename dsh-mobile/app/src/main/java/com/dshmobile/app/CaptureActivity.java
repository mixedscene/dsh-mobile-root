package com.dshmobile.app;

import android.app.*;
import android.content.*;
import android.media.projection.*;
import android.os.*;
import android.widget.*;
import android.graphics.BitmapFactory;
import java.io.*;

/** One user-consented screenshot, previewed before SAF export; never automatically uploaded. */
public final class CaptureActivity extends Activity {
    private File screenshot;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(Ui.body(this,"Authorize one screenshot. Protected screens may be blank. Nothing is sent to a model automatically."));
        if (state==null) startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),1);
        else finish();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==1){
            if(result!=RESULT_OK || data==null){finish();return;}
            ResultReceiver receiver = new ResultReceiver(new Handler(getMainLooper())) {
                @Override protected void onReceiveResult(int code,Bundle bundle) {
                    if(isFinishing() || isDestroyed())return;
                    if(code!=RESULT_OK){Toast.makeText(CaptureActivity.this,"Screenshot failed or timed out",1).show();finish();return;}
                    screenshot=new File(getCacheDir(),"capture-preview.png");
                    ImageView preview=new ImageView(CaptureActivity.this);preview.setAdjustViewBounds(true);preview.setMaxHeight(Ui.dp(CaptureActivity.this,400));preview.setImageBitmap(BitmapFactory.decodeFile(screenshot.getPath()));
                    new AlertDialog.Builder(CaptureActivity.this).setTitle("Preview: confirm before export").setView(preview).setPositiveButton("Export image",(d,w) -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/png").putExtra(Intent.EXTRA_TITLE,"dsh-screenshot.png"),2)).setNegativeButton("Discard",(d,w)->finish()).setOnCancelListener(d->finish()).show();
                }
            };
            startForegroundService(new Intent(this,CaptureService.class).putExtra("result",result).putExtra("permission",data).putExtra("receiver",receiver));
        } else if(request==2){
            if(result==RESULT_OK && data!=null && data.getData()!=null && screenshot!=null){
                try(InputStream in=new FileInputStream(screenshot);OutputStream out=getContentResolver().openOutputStream(data.getData())){FileManagerActivity.copy(in,out,32L*1024*1024);Toast.makeText(this,"Exported. Attach it manually in chat.",1).show();}
                catch(IOException e){Toast.makeText(this,"Image export failed",1).show();}
            }
            finish();
        }
    }
    @Override protected void onDestroy(){ if(screenshot!=null)screenshot.delete(); super.onDestroy(); }
}
