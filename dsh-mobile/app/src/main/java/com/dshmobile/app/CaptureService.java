package com.dshmobile.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.*;
import java.nio.ByteBuffer;

/** MediaProjection foreground owner, automatically stops after one frame or a bounded timeout. */
public final class CaptureService extends Service {
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private ResultReceiver receiver;
    private HandlerThread worker;
    private Handler handler;
    private volatile boolean complete;
    @Override public void onCreate(){super.onCreate();worker=new HandlerThread("single-capture");worker.start();handler=new Handler(worker.getLooper());}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(projection!=null || complete){stopSelf();return START_NOT_STICKY;}
        NotificationManager notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("capture","Single screenshot",NotificationManager.IMPORTANCE_LOW));
        startForeground(2,new Notification.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Taking one screenshot").build());
        if(intent==null){finish(false);return START_NOT_STICKY;}
        receiver=intent.getParcelableExtra("receiver");
        new File(getCacheDir(),"capture-preview.png").delete();
        try{
            Intent permission=intent.getParcelableExtra("permission");
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("result",0),permission);
            projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){finish(false);}},handler);
            DisplayMetrics metrics=getResources().getDisplayMetrics();
            android.graphics.Rect bounds; if(Build.VERSION.SDK_INT>=30) bounds=getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds(); else { DisplayMetrics old=new DisplayMetrics(); getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(old); bounds=new Rect(0,0,old.widthPixels,old.heightPixels); }
            int width=bounds.width(),height=bounds.height();
            if((long)width*height>16000000)throw new IOException("Capture exceeds pixel limit");
            reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
            reader.setOnImageAvailableListener(source -> {
                if(complete)return;
                try(Image image=source.acquireLatestImage()){
                    if(image==null)return;
                    Image.Plane plane=image.getPlanes()[0];ByteBuffer bytes=plane.getBuffer();
                    Bitmap bitmap=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);
                    int[] row=new int[image.getWidth()];
                    for(int y=0;y<image.getHeight();y++){
                        for(int x=0;x<row.length;x++){int offset=y*plane.getRowStride()+x*plane.getPixelStride();int r=bytes.get(offset)&255,g=bytes.get(offset+1)&255,b=bytes.get(offset+2)&255;row[x]=0xff000000|(r<<16)|(g<<8)|b;}
                        bitmap.setPixels(row,0,row.length,0,y,row.length,1);
                    }
                    try(OutputStream out=new FileOutputStream(new File(getCacheDir(),"capture-preview.png"))){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("PNG encoding failed");}
                    bitmap.recycle();finish(true);
                }catch(Exception e){finish(false);}
            },handler);
            display=projection.createVirtualDisplay("DSH-single-capture",width,height,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,handler);
            handler.postDelayed(()->finish(false),10000);
        }catch(Exception e){finish(false);}
        return START_NOT_STICKY;
    }
    private void finish(boolean success){
        if(complete)return;complete=true;
        if(!success)new File(getCacheDir(),"capture-preview.png").delete();
        if(receiver!=null)receiver.send(success?Activity.RESULT_OK:Activity.RESULT_CANCELED,Bundle.EMPTY);
        stopSelf();
    }
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);if(display!=null)display.release();if(reader!=null)reader.close();if(projection!=null)projection.stop();worker.quitSafely();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
