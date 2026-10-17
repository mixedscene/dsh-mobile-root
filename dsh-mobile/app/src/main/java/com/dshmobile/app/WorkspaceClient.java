package com.dshmobile.app;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs the bundled unprivileged workspace helper; SAF URIs are never interpreted as paths. */
final class WorkspaceClient {
    private final Context context;
    private final String script;
    WorkspaceClient(Context context)throws IOException {
        this.context=context.getApplicationContext();
        try(InputStream in=context.getAssets().open("workspace-ops.mjs");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            FileManagerActivity.copy(in,out,65536);script=out.toString(StandardCharsets.UTF_8.name());
        }
    }
    JSONObject json(JSONObject request,File input)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();run(request,input,out,8L*1024*1024);
        return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
    }
    void export(JSONObject request,File target)throws Exception {
        try(OutputStream out=new FileOutputStream(target)){run(request,null,out,32L*1024*1024);}
        catch(Exception e){target.delete();throw e;}
    }
    private void run(JSONObject request,File input,OutputStream output,long limit)throws Exception {
        String args=request.toString();if(args.length()>16384)throw new IOException("Workspace request too large");
        Process process=RootRuntime.execPiped(context,Arrays.asList("/opt/node/bin/node","--input-type=module","-e",script,"--",args));
        IOException[] readerError={null};
        Thread reader=new Thread(()->{try(InputStream in=process.getInputStream()){FileManagerActivity.copy(in,output,limit);}catch(IOException e){readerError[0]=e;}},"workspace-output");reader.setDaemon(true);reader.start();
        IOException[] inputError={null};
        Thread writer=new Thread(()->{
            try(OutputStream in=process.getOutputStream()){
                if(input!=null)try(InputStream source=new FileInputStream(input)){FileManagerActivity.copy(source,in,32L*1024*1024);}
            }catch(IOException e){inputError[0]=e;}
        },"workspace-input");writer.setDaemon(true);writer.start();
        if(!process.waitFor(60,TimeUnit.SECONDS)){RootRuntime.cancel(context,process);throw new IOException("Workspace operation timed out; inspect state before retrying");}
        writer.join(3000);reader.join(3000);
        if(writer.isAlive())throw new IOException("Workspace input not complete; inspect state");
        if(reader.isAlive())throw new IOException("Workspace output incomplete");
        if(readerError[0]!=null)throw readerError[0];
        if(process.exitValue()!=0){
            String reason="Workspace helper exit "+process.exitValue();
            if(output instanceof ByteArrayOutputStream){try{reason=new JSONObject(((ByteArrayOutputStream)output).toString(StandardCharsets.UTF_8.name())).optString("error",reason);}catch(Exception e){/* Native failure output is not accepted as a file response. */}}
            throw new IOException(reason);
        }
        if(inputError[0]!=null)throw inputError[0];
    }
}
