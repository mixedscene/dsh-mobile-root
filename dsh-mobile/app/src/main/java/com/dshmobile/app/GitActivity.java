package com.dshmobile.app;

import java.lang.Process;

import android.app.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Explicit local Git operations only; all output remains on this screen. */
public final class GitActivity extends Activity {
    private EditText workspace,path,message,name,email;
    private TextView output;
    private boolean busy;
    private volatile Process active;
    private volatile boolean cancelRequested;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final class Result { final int code;final String text;Result(int c,String t){code=c;text=t;} }
    private interface Work { String run() throws Exception; }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(24,48,24,24);
        box.addView(Ui.title(this,"Local workspace Git"));
        box.addView(Ui.hint(this,"No clone/fetch/push, credentials, hooks, signing or external diff. Custom clean/smudge filters block mutations. No automatic commit or merge."));
        workspace=field(box,"Runtime path, e.g. /home/dsh/project");workspace.setText("/home/dsh/project");
        path=field(box,"One relative file path (literal, no patterns)");
        LinearLayout row=new LinearLayout(this);box.addView(row);
        button(row,"Status",()->readAction(Arrays.asList("status","--short","--branch","--untracked-files=normal")));
        button(row,"Diff",()->readAction(Arrays.asList("diff","--no-ext-diff","--no-textconv","--")));
        button(row,"Staged",()->readAction(Arrays.asList("diff","--cached","--no-ext-diff","--no-textconv","--")));
        LinearLayout stage=new LinearLayout(this);box.addView(stage);
        button(stage,"Stage file",()->mutate("Stage this file?",Arrays.asList("add","--")));
        button(stage,"Unstage file",()->mutate("Unstage this file?",Arrays.asList("restore","--staged","--")));
        name=field(box,"Commit author name (required)");email=field(box,"Commit author email (required)");message=field(box,"Commit message (required)");
        button(box,"Review staged changes and commit",this::reviewCommit);
        Button cancel=Ui.outlineButton(this,"Cancel running Git operation");cancel.setOnClickListener(v->{if(busy)new AlertDialog.Builder(this).setMessage("Cancel may leave partial repository changes. Inspect status afterward.").setNegativeButton("Continue",null).setPositiveButton("Cancel Git",(d,w)->cancelGit()).show();});box.addView(cancel);
        output=Ui.body(this,"Select an existing repository. Git never initializes or downloads a repository automatically.");output.setTextIsSelectable(true);
        ScrollView scroll=new ScrollView(this);scroll.addView(output);box.addView(scroll,new LinearLayout.LayoutParams(-1,Ui.dp(this,360)));ScrollView page=new ScrollView(this);page.addView(box);Ui.content(this,page);
    }
    private EditText field(LinearLayout box,String hint){EditText field=new EditText(this);field.setSingleLine(true);field.setHint(hint);box.addView(field);return field;}
    private void button(LinearLayout box,String label,Runnable action){Button b=Ui.outlineButton(this,label);b.setOnClickListener(v->{if(!busy)action.run();});box.addView(b);}
    private String repo(){return GitCommands.workspace(workspace.getText().toString().trim());}
    private void readAction(List<String> args){try{String repo=repo();perform(()->checked(repo,args));}catch(IllegalArgumentException e){output.setText(e.getMessage());}}
    private void perform(Work action){
        if(busy)return;busy=true;cancelRequested=false;output.setText("Running local Git (30 second command timeout)...");
        worker.execute(()->{String text;try{text=action.run();}catch(Exception e){text="Git operation failed: "+e.getMessage();}String result=text;runOnUiThread(()->{busy=false;if(!isFinishing())output.setText(result);});});
    }
    private void cancelGit(){
        cancelRequested=true;Process process=active;
        if(process==null)return;
        new Thread(()->{try{RootRuntime.cancel(this,process);}catch(IOException e){runOnUiThread(()->output.setText("Git cancellation not confirmed. Inspect repository and Stop runtime if necessary."));}},"git-cancel").start();
    }
    private Result execute(String repo,List<String> args)throws Exception{
        if(cancelRequested)throw new IOException("Cancelled; inspect repository for partial changes");
        Process process=RootRuntime.execPiped(this,GitCommands.argv(repo,args));active=process;
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();boolean[] truncated={false};
        Thread reader=new Thread(()->{
            try(InputStream in=process.getInputStream()){
                byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){synchronized(bytes){int room=1048576-bytes.size();if(room>0)bytes.write(buffer,0,Math.min(room,n));if(n>room)truncated[0]=true;}}
            }catch(IOException e){synchronized(bytes){truncated[0]=true;}}
        },"git-output");reader.setDaemon(true);reader.start();
        try{
            if(cancelRequested)RootRuntime.cancel(this,process);
            if(!process.waitFor(45,TimeUnit.SECONDS)){RootRuntime.cancel(this,process);throw new IOException("Timed out; repository outcome may be partial. Inspect status.");}
            reader.join(2000);
            synchronized(bytes){return new Result(process.exitValue(),bytes.toString(StandardCharsets.UTF_8.name())+(truncated[0]?"\n[Output truncated or stream incomplete]":""));}
        }finally{if(active==process)active=null;}
    }
    private String checked(String repo,List<String> args)throws Exception{
        Result result=execute(repo,args);if(result.code!=0)throw new IOException("Exit "+result.code+"\n"+result.text);return result.text.isEmpty()?"Completed; no changes/output.":result.text;
    }
    private void filterGuard(String repo)throws Exception{
        Result filters=execute(repo,Arrays.asList("config","--local","--includes","--get-regexp","^filter\\."));
        if(filters.code==0)throw new IOException("Repository defines custom clean/smudge filters. Mutations are disabled; inspect configuration manually.");
        if(filters.code!=1)throw new IOException("Cannot inspect repository filter configuration; mutation refused.");
    }
    private void mutate(String question,List<String> prefix){
        try{
            String repo=repo(),file=GitCommands.file(path.getText().toString());
            new AlertDialog.Builder(this).setMessage(question+"\n"+repo+"\n"+file).setNegativeButton("Cancel",null).setPositiveButton("Confirm",(d,w)->perform(()->{filterGuard(repo);List<String> args=new ArrayList<>(prefix);args.add(file);return checked(repo,args)+"\n"+checked(repo,Arrays.asList("status","--short","--branch"));})).show();
        }catch(IllegalArgumentException e){output.setText(e.getMessage());}
    }
    private void reviewCommit(){
        try{
            String repo=repo(),msg=message.getText().toString(),author=name.getText().toString().trim(),mail=email.getText().toString().trim();
            if(msg.trim().isEmpty()||author.isEmpty()||mail.isEmpty()||author.indexOf('\n')>=0||mail.indexOf('\n')>=0)throw new IllegalArgumentException("A nonempty message, author and email are required");
            perform(()->{
                filterGuard(repo);String summary=checked(repo,Arrays.asList("diff","--cached","--stat","--no-ext-diff","--no-textconv","--"));
                runOnUiThread(()->{if(isFinishing())return;new AlertDialog.Builder(this).setTitle("Commit staged changes?").setMessage(repo+"\n"+author+" <"+mail+">\n"+msg+"\n\n"+summary).setNegativeButton("Cancel",null).setPositiveButton("Commit",(d,w)->perform(()->{filterGuard(repo);return checked(repo,Arrays.asList("-c","user.name="+author,"-c","user.email="+mail,"commit","--no-verify","-m",msg));})).show();});
                return summary+"\nReview the confirmation dialog before committing.";
            });
        }catch(IllegalArgumentException e){output.setText(e.getMessage());}
    }
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
