package com.odohelper.app;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.*;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.net.*;
import java.io.*;
import java.util.*;

public class InvoiceReminders extends JobService {
  private static final String PREF="invoice_reminders", ALIAS="odohelper_reminders_key", CHANNEL="invoice_due";
  private static final int JOB=8421;
  private volatile boolean stopped;
  private static javax.crypto.SecretKey key() throws Exception {
    KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
    if(!store.containsAlias(ALIAS)) {KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();}
    return (javax.crypto.SecretKey)store.getKey(ALIAS,null);
  }
  public static void enable(Context context,String base,String cookie) throws Exception {
    JSONObject data=new JSONObject().put("url",base).put("cookie",cookie);
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());byte[] iv=cipher.getIV(), encrypted=cipher.doFinal(data.toString().getBytes("UTF-8"));
    context.getSharedPreferences(PREF,0).edit().putString("iv",Base64.encodeToString(iv,Base64.NO_WRAP)).putString("data",Base64.encodeToString(encrypted,Base64.NO_WRAP)).apply();schedule(context);
  }
  public static void disable(Context context) {context.getSharedPreferences(PREF,0).edit().clear().apply();((JobScheduler)context.getSystemService(JOB_SCHEDULER_SERVICE)).cancel(JOB);}
  private static JSONObject session(Context c) throws Exception {
    android.content.SharedPreferences prefs=c.getSharedPreferences(PREF,0);String data=prefs.getString("data",null),iv=prefs.getString("iv",null);if(data==null||iv==null)return null;
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));return new JSONObject(new String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),"UTF-8"));
  }
  public static void schedule(Context c) {
    Calendar next=Calendar.getInstance();next.set(Calendar.HOUR_OF_DAY,9);next.set(Calendar.MINUTE,0);next.set(Calendar.SECOND,0);next.set(Calendar.MILLISECOND,0);if(next.getTimeInMillis()<=System.currentTimeMillis())next.add(Calendar.DAY_OF_YEAR,1);
    JobInfo job=new JobInfo.Builder(JOB,new ComponentName(c,InvoiceReminders.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(next.getTimeInMillis()-System.currentTimeMillis()).setPersisted(true).build();
    ((JobScheduler)c.getSystemService(JOB_SCHEDULER_SERVICE)).schedule(job);
  }
  private void notifyUser(int id,String title,String message) {
    NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"الفواتير المستحقة",NotificationManager.IMPORTANCE_DEFAULT));
    Intent open=new Intent(this,MainActivity.class);PendingIntent pending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
    Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
    manager.notify(id,b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message)).setContentIntent(pending).setAutoCancel(true).build());
  }
  public boolean onStartJob(JobParameters params){stopped=false;new Thread(()->{try {
    JSONObject auth=session(this);if(auth==null){jobFinished(params,false);return;}
    String today=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
    JSONArray domain=new JSONArray().put(new JSONArray().put("move_type").put("in").put(new JSONArray().put("out_invoice").put("in_invoice"))).put(new JSONArray().put("state").put("=").put("posted")).put(new JSONArray().put("payment_state").put("in").put(new JSONArray().put("not_paid").put("partial"))).put(new JSONArray().put("invoice_date_due").put("=").put(today));
    JSONObject kw=new JSONObject().put("fields",new JSONArray().put("name").put("partner_id")).put("limit",100).put("order","id desc");JSONObject args=new JSONObject().put("model","account.move").put("method","search_read").put("args",new JSONArray().put(domain)).put("kwargs",kw);
    JSONObject request=new JSONObject().put("jsonrpc","2.0").put("method","call").put("params",args).put("id",1);
    HttpURLConnection conn=(HttpURLConnection)new URL(auth.getString("url")+"/web/dataset/call_kw").openConnection();conn.setConnectTimeout(15000);conn.setReadTimeout(30000);conn.setInstanceFollowRedirects(false);conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");conn.setRequestProperty("Cookie",auth.getString("cookie"));
    try(OutputStream out=conn.getOutputStream()){out.write(request.toString().getBytes("UTF-8"));}if(conn.getResponseCode()!=200)throw new IOException("HTTP "+conn.getResponseCode());
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=conn.getInputStream()){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);}
    JSONObject response=new JSONObject(bytes.toString("UTF-8"));if(response.has("error") || !(response.opt("result") instanceof JSONArray))throw new IOException("session or permission");
    JSONArray invoices=response.getJSONArray("result");if(!stopped && session(this)!=null)for(int i=0;i<invoices.length() && !stopped;i++){JSONObject invoice=invoices.getJSONObject(i);JSONArray partner=invoice.optJSONArray("partner_id");String name=partner==null?"":partner.optString(1);notifyUser(100000+invoice.getInt("id"),"فاتورة مستحقة اليوم",invoice.optString("name")+" · "+name);}
    if(!stopped && session(this)!=null){if(invoices.length()==100)notifyUser(8422,"فواتير مستحقة اليوم","تم عرض أول 100 فاتورة. راجع باقي الفواتير في أودو.");jobFinished(params,false);schedule(this);}else jobFinished(params,false);
  }catch(Exception e){if(!stopped){try{if(session(this)!=null)notifyUser(8423,"تعذر تحديث الفواتير","افتح التطبيق وتحقق من اتصال الإنترنت وجلسة أودو.");}catch(Exception ignored){}jobFinished(params,true);}}}).start();return true;}
  public boolean onStopJob(JobParameters params){stopped=true;return true;}
}
