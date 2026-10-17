package com.dshmobile.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Capability evidence and fixed lifecycle diagnostics are displayed without process-output logging. */
public final class SettingsActivity extends Activity {
    private TextView log;
    private HarnessService service;
    private String capabilities="尚未重新探测设备能力。";
    private final Runnable refresh=this::render;
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){service=((HarnessService.LocalBinder)binder).service();service.observe(refresh);}
        public void onServiceDisconnected(ComponentName name){service=null;render();}
    };
    private void render(){if(log!=null)log.setText("设备能力报告\n"+capabilities+"\n\n运行生命周期\n"+(service==null?"服务未连接":service.diagnostics()));}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);if(state!=null)capabilities=state.getString("capabilities",capabilities);
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(24,24,24,16);
        box.addView(Ui.title(this,"DSH Root 诊断设置"));
        box.addView(Ui.hint(this,"实验性云构建 APK。Android 16/17、KernelSU、后台完成和 4KB/16KB 仍需真机测试。任务活动未知时不自动冻结，不持有唤醒锁或轮询保活。"));
        Button probe=Ui.primaryButton(this,"重新探测能力");box.addView(probe);
        probe.setOnClickListener(v->{probe.setEnabled(false);capabilities="正在探测，请确认 KernelSU 授权…";render();new Thread(()->{
            String text;try{text=DiagnosticsText.report(String.valueOf(RootRuntime.probe(this)));}catch(Exception e){text=DiagnosticsText.error(e);}String result=text;
            runOnUiThread(()->{capabilities=result;render();probe.setEnabled(true);});
        },"settings-root-probe").start();});
        LinearLayout filesRow=new LinearLayout(this);box.addView(filesRow);
        Button git=Ui.outlineButton(this,"本地 Git 操作");git.setOnClickListener(v->startActivity(new Intent(this,GitActivity.class)));filesRow.addView(git,new LinearLayout.LayoutParams(0,-2,1));
        Button files=Ui.outlineButton(this,"工作区文件管理");files.setOnClickListener(v->startActivity(new Intent(this,FileManagerActivity.class)));filesRow.addView(files,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout actions=new LinearLayout(this);box.addView(actions);
        Button export=Ui.outlineButton(this,"导出报告与诊断");export.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"dsh-root-diagnostics.txt"),1));actions.addView(export,new LinearLayout.LayoutParams(0,-2,1));
        Button stop=Ui.outlineButton(this,"停止运行环境");stop.setOnClickListener(v->{startService(new Intent(this,HarnessService.class).setAction(HarnessService.STOP));finish();});actions.addView(stop,new LinearLayout.LayoutParams(0,-2,1));
        log=Ui.body(this,"");log.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(log);box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));render();
        Ui.content(this,box);bindService(new Intent(this,HarnessService.class),connection,BIND_AUTO_CREATE);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==1&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IOException("Provider unavailable");out.write(log.getText().toString().getBytes(StandardCharsets.UTF_8));}
            catch(IOException e){Toast.makeText(this,"诊断导出失败："+DiagnosticsText.error(e),Toast.LENGTH_LONG).show();}
        }
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("capabilities",capabilities);}
    @Override protected void onDestroy(){if(service!=null)service.removeObserver(refresh);unbindService(connection);super.onDestroy();}
}
