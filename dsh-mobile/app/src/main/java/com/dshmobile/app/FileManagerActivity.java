package com.dshmobile.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** User-selected runtime workspace operations through the unprivileged Node helper. */
public final class FileManagerActivity extends Activity {
    private EditText workspace;
    private TextView status;
    private LinearLayout entries;
    private String directory="";
    private int page;
    private boolean busy;
    private volatile boolean reloadPending;
    private WorkspaceClient client;
    private JSONObject pendingExport,pendingImport;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private interface Work{void run()throws Exception;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(20,24,20,16);
        box.addView(Ui.title(this,"Runtime workspace files"));
        box.addView(Ui.hint(this,"Only your selected /home/dsh workspace. Symlinks are never traversed. Text preview/edit: 1 MiB; SAF transfer: 32 MiB; pages: 100 entries. Directory deletion requires empty. Directory moves are not supported. Concurrent external writers are not an atomic compare-and-swap boundary."));
        workspace=new EditText(this);workspace.setSingleLine(true);workspace.setText(state==null?"/home/dsh":state.getString("workspace","/home/dsh"));box.addView(workspace);
        if(state!=null){directory=state.getString("directory","");page=state.getInt("page",0);try{if(state.containsKey("export"))pendingExport=new JSONObject(state.getString("export"));if(state.containsKey("import"))pendingImport=new JSONObject(state.getString("import"));}catch(JSONException e){pendingExport=null;pendingImport=null;}}
        LinearLayout nav=new LinearLayout(this);box.addView(nav);
        button(nav,"Open",()->{directory="";page=0;refresh();});
        button(nav,"Up",()->{int slash=directory.lastIndexOf('/');directory=slash<0?"":directory.substring(0,slash);page=0;refresh();});
        button(nav,"Previous",()->{if(page>0){page--;refresh();}});button(nav,"Next",()->{page++;refresh();});
        LinearLayout create=new LinearLayout(this);box.addView(create);
        button(create,"New file",()->askCreate(false));button(create,"New folder",()->askCreate(true));button(create,"SAF import",this::importPicker);
        status=Ui.hint(this,"Tap Open to authorize this runtime workspace for this screen.");box.addView(status);
        ScrollView scroll=new ScrollView(this);entries=new LinearLayout(this);entries.setOrientation(1);scroll.addView(entries);box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));Ui.content(this,box);
        try{client=new WorkspaceClient(this);}catch(IOException e){status.setText("Bundled workspace helper unavailable");}
    }
    private void button(LinearLayout row,String title,Runnable action){Button b=Ui.outlineButton(this,title);b.setOnClickListener(v->{if(!busy && client!=null)action.run();});row.addView(b,new LinearLayout.LayoutParams(0,-2,1));}
    private JSONObject request(String op)throws JSONException{
        String root=workspace.getText().toString().trim();if(root.length()>4096)throw new JSONException("Workspace path too long");
        return new JSONObject().put("op",op).put("workspace",root).put("directory",directory);
    }
    private void task(Work action){
        if(busy || client==null)return;busy=true;status.setText("Working; no operation is retried automatically...");
        worker.execute(()->{try{action.run();}catch(Exception e){runOnUiThread(()->status.setText("Operation failed: "+e.getMessage()+". Refresh before retrying a mutation."));}finally{runOnUiThread(()->{busy=false;if(reloadPending && !isFinishing() && !isDestroyed()){reloadPending=false;refresh();}});}});
    }
    private void refresh(){
        try{JSONObject req=request("list").put("page",page);task(()->render(client.json(req,null),req));}catch(Exception e){status.setText(e.getMessage());}
    }
    private void render(JSONObject result,JSONObject req){runOnUiThread(()->{
        if(isFinishing())return;
        entries.removeAllViews();status.setText(req.optString("workspace")+"/"+req.optString("directory")+" — page "+(req.optInt("page")+1)+", "+result.optInt("total")+" entries (name sorted)");
        JSONArray rows=result.optJSONArray("entries");if(rows==null)return;
        for(int i=0;i<rows.length();i++){
            JSONObject item=rows.optJSONObject(i);if(item==null)continue;
            Button b=Ui.outlineButton(this,item.optString("name")+"  ["+item.optString("kind")+", "+item.optString("size")+" bytes]");
            b.setOnClickListener(v->{if(!busy)actions(item,req);});entries.addView(b);
        }
    });}
    private JSONObject entryRequest(JSONObject context,JSONObject item,String op)throws JSONException{return new JSONObject(context.toString()).put("op",op).put("name",item.getString("name")).put("version",item.getJSONObject("version"));}
    private void actions(JSONObject item,JSONObject context){
        boolean dir="directory".equals(item.optString("kind"));
        String[] choices=dir?new String[]{"Open directory","Delete empty directory"}:new String[]{"View / edit small text","Export with SAF","Rename / move file or link","Delete entry itself"};
        new AlertDialog.Builder(this).setTitle(item.optString("name")).setItems(choices,(d,n)->{
            try{
                if(dir && n==0){workspace.setText(context.optString("workspace"));directory=context.optString("directory")+(context.optString("directory").isEmpty()?"":"/")+item.getString("name");page=0;refresh();}
                else if((dir && n==1)||(!dir && n==3))confirmDelete(item,context);
                else if(n==0)view(item,context);
                else if(n==1){pendingExport=entryRequest(context,item,"export");startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,item.getString("name")),2);}
                else move(item,context);
            }catch(Exception e){status.setText(e.getMessage());}
        }).show();
    }
    private void view(JSONObject item,JSONObject context)throws Exception{
        JSONObject req=entryRequest(context,item,"read");
        task(()->{JSONObject response=client.json(req,null);runOnUiThread(()->{
            EditText editor=new EditText(this);editor.setGravity(android.view.Gravity.TOP);editor.setText(response.optString("text"));editor.setMinLines(8);editor.setMaxLines(18);
            new AlertDialog.Builder(this).setTitle(item.optString("name")).setView(editor).setNegativeButton("Close",null).setPositiveButton("Save atomically",(d,w)->{
                byte[] bytes=editor.getText().toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>1024*1024){status.setText("Text exceeds 1 MiB");return;}
                try{JSONObject save=entryRequest(context,item,"write").put("version",response.getJSONObject("version"));task(()->{File staging=stage(bytes);try{client.json(save,staging);reloadPending=true;}finally{staging.delete();}});}catch(Exception e){status.setText(e.getMessage());}
            }).show();
        });});
    }
    private File stage(byte[] bytes)throws IOException{File file=File.createTempFile("workspace-",".stage",getCacheDir());try(OutputStream out=new FileOutputStream(file)){out.write(bytes);}catch(IOException e){file.delete();throw e;}return file;}
    private void confirmDelete(JSONObject item,JSONObject context)throws Exception{
        JSONObject req=entryRequest(context,item,"delete");new AlertDialog.Builder(this).setMessage("Delete this entry itself? Links are not followed. Directories must be empty.\n"+item.optString("name")).setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->task(()->{client.json(req,null);reloadPending=true;})).show();
    }
    private void move(JSONObject item,JSONObject context){
        EditText target=new EditText(this);target.setSingleLine(true);target.setHint("New relative path from workspace root");target.setText(context.optString("directory")+(context.optString("directory").isEmpty()?"":"/")+item.optString("name"));
        new AlertDialog.Builder(this).setTitle("Move / rename without overwrite").setView(target).setNegativeButton("Cancel",null).setPositiveButton("Move",(d,w)->{try{JSONObject req=entryRequest(context,item,"move").put("destination",target.getText().toString());task(()->{client.json(req,null);reloadPending=true;});}catch(Exception e){status.setText(e.getMessage());}}).show();
    }
    private void askCreate(boolean folder){
        EditText name=new EditText(this);name.setSingleLine(true);new AlertDialog.Builder(this).setTitle(folder?"Create folder":"Create empty file").setView(name).setNegativeButton("Cancel",null).setPositiveButton("Create",(d,w)->{try{JSONObject req=request(folder?"mkdir":"create").put("name",name.getText().toString());task(()->{client.json(req,null);reloadPending=true;});}catch(Exception e){status.setText(e.getMessage());}}).show();
    }
    private void importPicker(){try{pendingImport=request("import");startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),1);}catch(Exception e){status.setText(e.getMessage());}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==1 && pendingImport!=null){
            JSONObject context=pendingImport;pendingImport=null;EditText name=new EditText(this);name.setSingleLine(true);name.setText(displayName(uri));
            new AlertDialog.Builder(this).setTitle("Import as a new workspace file").setView(name).setNegativeButton("Cancel",null).setPositiveButton("Copy",(d,w)->{try{JSONObject req=new JSONObject(context.toString()).put("name",name.getText().toString());task(()->{
                File staging=File.createTempFile("saf-import-",".stage",getCacheDir());try{try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(staging)){copy(in,out,32L*1024*1024);}client.json(req,staging);reloadPending=true;}finally{staging.delete();}
            });}catch(Exception e){status.setText(e.getMessage());}}).show();
        }else if(request==2 && pendingExport!=null){
            JSONObject req=pendingExport;pendingExport=null;task(()->{File staging=File.createTempFile("saf-export-",".stage",getCacheDir());try{client.export(req,staging);try(InputStream in=new FileInputStream(staging);OutputStream out=getContentResolver().openOutputStream(uri)){copy(in,out,32L*1024*1024);}runOnUiThread(()->status.setText("Workspace file exported with SAF"));}finally{staging.delete();}});
        }
    }
    private String displayName(Uri uri){try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null && cursor.moveToFirst())return cursor.getString(0);}catch(Exception e){/* Provider names are optional and never become paths without validation. */}return "imported-file";}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("workspace",workspace.getText().toString());state.putString("directory",directory);state.putInt("page",page);if(pendingImport!=null)state.putString("import",pendingImport.toString());if(pendingExport!=null)state.putString("export",pendingExport.toString());}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
    static void copy(InputStream in,OutputStream out,long limit)throws IOException{
        if(in==null||out==null)throw new IOException("Provider unavailable");byte[] bytes=new byte[8192];long total=0;int n;while((n=in.read(bytes))!=-1){total+=n;if(total>limit)throw new IOException("Transfer exceeds size limit");out.write(bytes,0,n);}
    }
}
