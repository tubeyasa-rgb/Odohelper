package com.odohelper.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import java.util.ArrayList;
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
  private void contactMessage(String message) {
    send("document.getElementById('contactMessage').textContent=" + JSONObject.quote(message));
  }
  private void chooseContacts() {
    if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 7);
      return;
    }
    contactMessage("جاري تحميل جهات الاتصال...");
    worker.execute(() -> {
      ArrayList<JSONObject> contacts = new ArrayList<>();
      ArrayList<String> labels = new ArrayList<>();
      try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
          new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
          null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")) {
        if (c == null) throw new IOException("تعذر قراءة جهات الاتصال");
        while (c.moveToNext()) {
          String name = c.isNull(0) ? "" : c.getString(0);
          String phone = c.isNull(1) ? "" : c.getString(1);
          contacts.add(obj("name", name, "phone", phone, "email", ""));
          labels.add(name + "\n" + phone);
        }
        runOnUiThread(() -> showContactDialog(contacts, labels));
      } catch (Exception e) { contactMessage("تعذر قراءة جهات الاتصال"); }
    });
  }
  private void showContactDialog(ArrayList<JSONObject> contacts, ArrayList<String> labels) {
    if (contacts.isEmpty()) { contactMessage("لا توجد جهات اتصال بأرقام هاتف."); return; }
    contactMessage("");
    boolean[] checked = new boolean[contacts.size()];
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("اختار جهات الاتصال")
      .setMultiChoiceItems(labels.toArray(new String[0]), checked, (d, which, value) -> checked[which] = value)
      .setPositiveButton("متابعة", (d, which) -> {
        JSONArray chosen = new JSONArray();
        for (int i = 0; i < contacts.size(); i++) if (checked[i]) chosen.put(contacts.get(i));
        if (chosen.length() == 0) contactMessage("لم يتم اختيار جهات اتصال.");
        else send("nativeContacts(" + chosen.toString() + ")");
      }).setNegativeButton("إلغاء", null).setNeutralButton("تحديد الكل", null).create();
    dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
      for (int i = 0; i < checked.length; i++) { checked[i] = true; dialog.getListView().setItemChecked(i, true); }
    }));
    dialog.show();
  }
  @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == 7) {
      if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) chooseContacts();
      else contactMessage("اسمح بالوصول لجهات الاتصال لاختيارها.");
    }
  }
  class Bridge {
    @JavascriptInterface public void pickContact() { runOnUiThread(() -> chooseContacts()); }
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
      String address=body.getString("url").trim();
      if (!address.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) address="https://" + address;
      URI uri=new URI(address); if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null) throw new Exception("استخدم رابط أودو آمن يبدأ بـ HTTPS");
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
