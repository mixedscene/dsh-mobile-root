package com.dshmobile.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Root probing and runtime installation are separate, explicit user actions. */
public final class SetupActivity extends Activity {
    private TextView status,report;
    private Button probe,install,save;
    private boolean rootReady;
    private String capabilityReport="能力报告将在这里显示。";
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(24,24,24,16);
        box.addView(Ui.title(this,"DSH Root 安装设置"));
        box.addView(Ui.hint(this,"先探测真实 root 并查看、保存能力报告，再确认安装内置运行包。保持 SELinux Enforcing；Android 16/17 真机兼容性仍需测试。"));
        LinearLayout actions=new LinearLayout(this);box.addView(actions);
        probe=Ui.outlineButton(this,"探测 root / 查看能力");actions.addView(probe,new LinearLayout.LayoutParams(0,-2,1));
        save=Ui.outlineButton(this,"保存能力报告");actions.addView(save,new LinearLayout.LayoutParams(0,-2,1));
        install=Ui.primaryButton(this,"确认安装内置运行包");install.setEnabled(false);box.addView(install);
        status=Ui.hint(this,"尚未探测。安装不会自动开始。");status.setMaxLines(3);box.addView(status);
        if(state!=null)capabilityReport=DiagnosticsText.report(state.getString("capabilities","请重新探测 root。"));
        report=Ui.body(this,state==null?capabilityReport:state.getString("report",capabilityReport));report.setTextIsSelectable(true);
        ScrollView scroll=new ScrollView(this);scroll.addView(report);box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        probe.setOnClickListener(v->probe());
        save.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"dsh-root-capabilities.txt"),1));
        install.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("确认安装").setMessage("已查看能力报告？此操作将使用 root 安装 APK 内置运行包。不会在线下载环境。探测成功不代表真机功能全部通过。").setNegativeButton("取消",null).setPositiveButton("确认安装",(d,w)->install()).show());
        Ui.content(this,box);
    }
    private void controls(boolean working){probe.setEnabled(!working);install.setEnabled(!working&&rootReady);save.setEnabled(!working);}
    private void probe(){
        rootReady=false;controls(true);status.setText("正在探测，请确认 KernelSU 授权…");
        new Thread(()->{
            try{RootCapabilities capabilities=RootRuntime.probe(this);if(!capabilities.root)throw new IOException("Real root is required");String text=DiagnosticsText.report(String.valueOf(capabilities));runOnUiThread(()->{rootReady=true;capabilityReport=text;report.setText(text);status.setText("探测成功。请滚动查看或保存报告，再确认安装。");controls(false);});}
            catch(Exception e){String text=DiagnosticsText.error(e);runOnUiThread(()->{capabilityReport=text;report.setText(text);status.setText("探测失败，详细诊断见报告；可保存后反馈。");controls(false);});}
        },"root-probe").start();
    }
    private void install(){
        if(!rootReady)return;controls(true);status.setText("正在安装内置运行包…");
        new Thread(()->{
            try{RuntimeInstaller.install(this,text->runOnUiThread(()->status.setText(DiagnosticsText.report(text))));runOnUiThread(()->{startActivity(new Intent(this,MainActivity.class));finish();});}
            catch(Exception e){String text=DiagnosticsText.error(e);runOnUiThread(()->{report.setText(capabilityReport+"\n\n安装失败：\n"+text);status.setText("安装失败，能力报告保留在下方。请查看诊断后再操作。");controls(false);});}
        },"runtime-install").start();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==1&&result==RESULT_OK&&data!=null&&data.getData()!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IOException("Document provider unavailable");out.write(report.getText().toString().getBytes(StandardCharsets.UTF_8));}catch(IOException e){Toast.makeText(this,"报告保存失败："+DiagnosticsText.error(e),Toast.LENGTH_LONG).show();}}}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("report",report.getText().toString());state.putString("capabilities",capabilityReport);}
}
