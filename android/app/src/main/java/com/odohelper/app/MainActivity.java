package com.odohelper.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.Manifest;
import android.app.AlertDialog;
import android.graphics.pdf.PdfDocument;
import android.graphics.Paint;
import android.net.Uri;
import java.util.UUID;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
  private WebView web;
  private String base, cookie;
  private int companyId;
  private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
  @Override public void onCreate(Bundle state) {
    super.onCreate(state); web = new WebView(this); setContentView(web);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.setWebChromeClient(new WebChromeClient());
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
      if(auth.optInt("uid",0)==0) throw new Exception("بيانات الدخول غير صحيحة"); JSONObject companies=auth.optJSONObject("user_companies"); companyId=companies==null?0:companies.optInt("current_company",0); return obj("name",auth.optString("name"));
    }
    if(path.equals("/logout")){base=null;cookie=null;return new JSONObject();}
    if(base==null || cookie==null) throw new Exception("سجّل الدخول أولاً");
    if(path.equals("/contacts")) {
      JSONArray output=new JSONArray(), contacts=body.optJSONArray("contacts"); if(contacts==null)return output;
      for(int i=0;i<contacts.length();i++){JSONObject contact=contacts.getJSONObject(i); String name=contact.optString("name").trim(), phone=contact.optString("phone");if(name.isEmpty())continue;
        String status;try{JSONArray existing=phone.isEmpty()?new JSONArray():(JSONArray)call("res.partner","search_read",arr(arr(arr("phone","=",phone))),obj("fields",arr("name","phone"),"limit",1));
          if(existing.length()>0)status="موجود بالفعل";else {call("res.partner","create",arr(obj("name",name,"phone",phone.isEmpty()?false:phone,"email",contact.optString("email").isEmpty()?false:contact.optString("email"))),new JSONObject());status="تم الرفع";}
        }catch(Exception e){status=e.getMessage();}output.put(obj("name",name,"status",status));}return output;
    }
    if(path.equals("/customers")) {
      String query=body.optString("query");
      JSONArray domain=arr("|", arr("name","ilike",query), arr("phone","ilike",query));
      JSONArray rows=(JSONArray)call("res.partner","search_read",arr(domain),obj("fields",arr("name","phone","mobile"),"limit",50,"order","name asc"));
      JSONArray out=new JSONArray(); for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);String phone=r.optString("mobile",""); if(phone.equals("false") || phone.isEmpty()) phone=r.optString("phone","");if(phone.equals("false"))phone="";out.put(obj("id",r.getInt("id"),"name",r.optString("name"),"phone",phone));}return out;
    }
    if(path.equals("/ledger")) return ledger(body);
    if(path.equals("/share_ledger")) { shareLedger(body); return new JSONObject(); }
    if(path.equals("/share_pdf")) { shareReport(body); return new JSONObject(); }
    if(path.equals("/portal")) {
      String model=body.getString("model");int id=body.getInt("id");
      if(id<=0 || !(model.equals("sale.order") || model.equals("purchase.order") || model.equals("account.move"))) throw new Exception("الرابط الخارجي غير متاح لهذا المستند");
      Object portal=call(model,"get_portal_url",arr(arr(id)),new JSONObject());
      if(!(portal instanceof String) || !((String)portal).startsWith("/my/")) throw new Exception("لا يوجد رابط خارجي متاح لهذا المستند");
      return obj("url",base+portal);
    }
    if(path.equals("/reports")) {JSONArray output=new JSONArray();String[][] models={{"sale.order","مبيعات"},{"purchase.order","مشتريات"},{"account.move","فواتير"},{"account.payment","مدفوعات"}};
      String selected=body.optString("type","all"); int partner=body.optInt("partner",0);int failures=0;
      for(String[] model:models){if(!selected.equals("all")&&!selected.equals(model[0]))continue;
        try{JSONArray domain=arr(arr("name","ilike",body.optString("query")));if(partner>0)domain.put(arr("partner_id","child_of",partner));
          JSONArray rows=(JSONArray)call(model[0],"search_read",arr(domain),obj("fields",arr("name","id","partner_id"),"limit",20,"order","id desc"));
          for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);int id=row.getInt("id");JSONObject item=obj("name",row.optString("name"),"label",model[1],"model",model[0],"id",id,"url",base+"/web#id="+id+"&model="+model[0]+"&view_type=form");
            output.put(item);
          }
        }catch(Exception e){failures++;}}
      if(output.length()==0 && failures>0) throw new Exception("تعذر البحث في بعض الأقسام. تحقق من الصلاحيات أو نوع المستند.");return output;}
    throw new Exception("طلب غير معروف");
  }
  private JSONObject ledger(JSONObject body) throws Exception {
    int partner=body.getInt("id"); String from=body.getString("from"),to=body.getString("to");
    if(companyId<=0) throw new Exception("تعذر تحديد الشركة الحالية في أودو.");
    if(!from.matches("\\d{4}-\\d{2}-\\d{2}") || !to.matches("\\d{4}-\\d{2}-\\d{2}") || from.compareTo(to)>0) throw new Exception("حدد فترة صحيحة");
    JSONArray domain=arr(arr("partner_id","child_of",partner),arr("company_id","=",companyId),arr("parent_state","=","posted"),arr("account_id.account_type","=","asset_receivable"),arr("date","<=",to));
    JSONArray rows=(JSONArray)call("account.move.line","search_read",arr(domain),obj("fields",arr("date","name","debit","credit"),"limit",10001,"order","date asc, id asc"));
    if(rows.length()>10000) throw new Exception("عدد الحركات كبير جدًا، يرجى استخدام تقرير أودو الأصلي.");
    double opening=0,balance=0;JSONArray lines=new JSONArray();
    for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);double delta=r.optDouble("debit",0)-r.optDouble("credit",0);balance+=delta;if(r.getString("date").compareTo(from)<0)opening+=delta;else lines.put(r);}
    return obj("opening",opening,"closing",balance,"lines",lines);
  }
  private void shareFile(File file,String phone) {
    Uri uri=Uri.parse("content://com.odohelper.app.pdf/"+file.getName());
    Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("application/pdf");intent.putExtra(Intent.EXTRA_STREAM,uri);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    // WhatsApp accepts a recipient hint on supported versions; the user still confirms sending.
    String digits=phone==null?"":phone.replaceAll("[^0-9]","");
    if(digits.length()>=8 && phone.trim().startsWith("+")) { intent.setPackage("com.whatsapp"); intent.putExtra("jid",digits+"@s.whatsapp.net"); }
    runOnUiThread(() -> {try {startActivity(Intent.createChooser(intent,"مشاركة PDF"));} catch(Exception e){contactMessage("تعذر فتح تطبيق للمشاركة");}});
  }
  private void shareReport(JSONObject body) throws Exception {
    String model=body.getString("model"), report;
    switch(model){case "sale.order":report="sale.report_saleorder";break;case "purchase.order":report="purchase.report_purchaseorder";break;case "account.move":report="account.account_invoices";break;case "account.payment":report="account.report_payment_receipt";break;default:throw new Exception("نوع مستند غير مدعوم");}
    int id=body.getInt("id"); if(id<=0) throw new Exception("مستند غير صالح");
    HttpURLConnection c=(HttpURLConnection)new URL(base+"/report/pdf/"+report+"/"+id).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Cookie",cookie);
    if(c.getResponseCode()!=200 || !String.valueOf(c.getContentType()).toLowerCase().startsWith("application/pdf"))throw new IOException("تعذر إنشاء PDF من أودو. تحقق من صلاحية التقرير وإعداداته.");
    File file=new File(getCacheDir(),UUID.randomUUID().toString()+".pdf");try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(file)){byte[] b=new byte[8192];int n;long size=0;while((n=in.read(b))!=-1){size+=n;if(size>25*1024*1024)throw new IOException("حجم الملف أكبر من 25 ميجابايت");out.write(b,0,n);}} shareFile(file,"");
  }
  private void shareLedger(JSONObject body) throws Exception {
    JSONObject data=ledger(body);JSONArray lines=data.getJSONArray("lines");PdfDocument pdf=new PdfDocument();Paint paint=new Paint();paint.setTextSize(12);int pageNo=1;PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,pageNo).create());float y=45;
    page.getCanvas().drawText("Customer ledger: "+body.optString("name"),35,y,paint);y+=24;
    page.getCanvas().drawText(body.getString("from")+" - "+body.getString("to"),35,y,paint);y+=24;
    page.getCanvas().drawText(String.format(java.util.Locale.US,"Opening: %.2f",data.getDouble("opening")),35,y,paint);y+=24;
    for(int i=0;i<lines.length();i++){if(y>790){pdf.finishPage(page);page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,++pageNo).create());y=45;}
      JSONObject line=lines.getJSONObject(i);String text=String.format(java.util.Locale.US,"%s  %s  Debit: %.2f  Credit: %.2f",line.optString("date"),line.optString("name"),line.optDouble("debit"),line.optDouble("credit"));page.getCanvas().drawText(text,35,y,paint);y+=20;}
    if(y>790){pdf.finishPage(page);page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,++pageNo).create());y=45;}page.getCanvas().drawText(String.format(java.util.Locale.US,"Closing: %.2f",data.getDouble("closing")),35,y,paint);pdf.finishPage(page);
    File file=new File(getCacheDir(),UUID.randomUUID().toString()+".pdf");try(OutputStream out=new FileOutputStream(file)){pdf.writeTo(out);}finally{pdf.close();}shareFile(file,body.optString("phone"));
  }
  @Override protected void onDestroy(){ worker.shutdownNow(); web.destroy(); super.onDestroy(); }
}
