package com.odohelper.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
  private WebView web;
  private String base, cookie;
  private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
  @Override public void onCreate(Bundle state) {
    super.onCreate(state); web = new WebView(this); setContentView(web);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setAllowFileAccess(true);
    web.addJavascriptInterface(new Bridge(), "Android");
    web.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
        Intent intent = new Intent(Intent.ACTION_VIEW, request.getUrl());
        try { startActivity(intent); } catch (Exception ignored) { }
        return true;
      }
    });
    web.loadUrl("file:///android_asset/index.html");
  }
  private void send(String js) { runOnUiThread(() -> web.evaluateJavascript(js, null)); }
  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request,result,data);
    if (request != 7 || result != RESULT_OK || data == null) return;
    try (Cursor c = getContentResolver().query(data.getData(), new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER}, null,null,null)) {
      if (c != null && c.moveToFirst()) {
        JSONObject contact = new JSONObject().put("name", c.getString(0)).put("phone", c.getString(1)).put("email", "");
        send("nativeContact(" + contact.toString() + ")");
      }
    } catch (Exception e) { send("document.getElementById('contactMessage').textContent=" + JSONObject.quote("تعذر قراءة جهة الاتصال") ); }
  }
  class Bridge {
    @JavascriptInterface public void pickContact() { runOnUiThread(() -> { try { startActivityForResult(new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), 7); } catch (Exception e) { send("document.getElementById('contactMessage').textContent=" + JSONObject.quote("تعذر فتح جهات الاتصال")); } }); }
    @JavascriptInterface public void request(int id, String path, String json) { worker.execute(() -> {
      JSONObject answer = new JSONObject();
      try { answer.put("result", handle(path, new JSONObject(json))); }
      catch (Exception e) { try { answer.put("error", e.getMessage() == null ? "تعذر الاتصال بأودو" : e.getMessage()); } catch (Exception ignored) {} }
      send("nativeResponse(" + id + "," + answer.toString() + ")");
    }); }
  }
  private JSONObject obj(Object... pairs) throws Exception { JSONObject o = new JSONObject(); for (int i=0;i<pairs.length;i+=2) o.put((String)pairs[i],pairs[i+1]); return o; }
  private JSONArray arr(Object... values) { JSONArray a = new JSONArray(); for(Object v:values) a.put(v); return a; }
  private Object rpc(String path, JSONObject params) throws Exception {
    HttpURLConnection c = (HttpURLConnection)new URL(base + path).openConnection();
    c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(false); c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json");
    if(cookie != null) c.setRequestProperty("Cookie",cookie);
    byte[] bytes = obj("jsonrpc","2.0","method","call","params",params,"id",System.currentTimeMillis()).toString().getBytes(StandardCharsets.UTF_8);
    try(OutputStream out=c.getOutputStream()){out.write(bytes);}
    String setCookie=c.getHeaderField("Set-Cookie"); if(setCookie!=null && setCookie.startsWith("session_id=")) cookie=setCookie.split(";",2)[0];
    if(c.getResponseCode()!=200) throw new IOException("خطأ في اتصال أودو: " + c.getResponseCode());
    ByteArrayOutputStream out=new ByteArrayOutputStream(); try(InputStream in=c.getInputStream()){byte[] b=new byte[4096]; int n; while((n=in.read(b))!=-1) out.write(b,0,n);}
    JSONObject response=new JSONObject(out.toString("UTF-8"));
    if(response.has("error")){JSONObject error=response.getJSONObject("error");JSONObject data=error.optJSONObject("data");throw new Exception(data != null ? data.optString("message",error.optString("message")) : error.optString("message"));}
    return response.get("result");
  }
  private Object call(String model,String method,JSONArray args,JSONObject kwargs) throws Exception {return rpc("/web/dataset/call_kw",obj("model",model,"method",method,"args",args,"kwargs",kwargs));}
  private Object handle(String path,JSONObject body) throws Exception {
    if(path.equals("/login")) {
      URI uri=new URI(body.getString("url")); if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null) throw new Exception("استخدم رابط أودو آمن يبدأ بـ HTTPS");
      base=new URI("https",null,uri.getHost(),uri.getPort(),null,null,null).toString(); cookie=null;
      String db=body.optString("db").trim(); if(db.isEmpty()){JSONArray dbs=(JSONArray)rpc("/web/database/list",new JSONObject()); if(dbs.length()!=1) throw new Exception("اكتب اسم قاعدة البيانات للمتابعة"); db=dbs.getString(0);}
      JSONObject auth=(JSONObject)rpc("/web/session/authenticate",obj("db",db,"login",body.optString("login"),"password",body.optString("password")));
      if(auth.optInt("uid",0)==0) throw new Exception("بيانات الدخول غير صحيحة"); return obj("name",auth.optString("name"));
    }
    if(base==null || cookie==null) throw new Exception("سجّل الدخول أولاً");
    if(path.equals("/logout")){base=null;cookie=null;return new JSONObject();}
    if(path.equals("/contacts")) {
      JSONArray output=new JSONArray(), contacts=body.optJSONArray("contacts"); if(contacts==null)return output;
      for(int i=0;i<contacts.length();i++){JSONObject contact=contacts.getJSONObject(i); String name=contact.optString("name").trim(), phone=contact.optString("phone");if(name.isEmpty())continue;
        String status;try{JSONArray existing=phone.isEmpty()?new JSONArray():(JSONArray)call("res.partner","search_read",arr(arr(arr("phone","=",phone))),obj("fields",arr("name","phone"),"limit",1));
          if(existing.length()>0)status="موجود بالفعل";else {call("res.partner","create",arr(obj("name",name,"phone",phone.isEmpty()?false:phone,"email",contact.optString("email").isEmpty()?false:contact.optString("email"))),new JSONObject());status="تم الرفع";}
        }catch(Exception e){status=e.getMessage();}output.put(obj("name",name,"status",status));}return output;
    }
    if(path.equals("/reports")) {JSONArray output=new JSONArray();String[][] models={{"sale.order","مبيعات"},{"purchase.order","مشتريات"},{"account.move","حسابات"}};
      for(String[] model:models){try{JSONArray rows=(JSONArray)call(model[0],"search_read",arr(arr(arr("name","ilike",body.optString("query")))),obj("fields",arr("name","id"),"limit",20,"order","id desc"));for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);output.put(obj("name",row.optString("name"),"label",model[1],"url",base+"/web#id="+row.getInt("id")+"&model="+model[0]+"&view_type=form"));}}catch(Exception ignored){}}return output;}
    throw new Exception("طلب غير معروف");
  }
  @Override protected void onDestroy(){ worker.shutdownNow(); web.destroy(); super.onDestroy(); }
}
