package com.odohelper.app;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.OpenableColumns;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
public class PdfProvider extends ContentProvider {
  public boolean onCreate(){ return true; }
  public String getType(Uri uri){ return "application/pdf"; }
  public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException();
    String name=uri.getLastPathSegment();
    if(name==null || !name.matches("[a-f0-9-]+\\.pdf")) throw new FileNotFoundException();
    return ParcelFileDescriptor.open(new File(getContext().getCacheDir(), name),ParcelFileDescriptor.MODE_READ_ONLY);
  }
  public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
    String name=uri.getLastPathSegment(); if(name==null || !name.matches("[a-f0-9-]+\\.pdf"))return null;
    MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
    c.addRow(new Object[]{"odohelper.pdf",new File(getContext().getCacheDir(),name).length()});return c;
  }
  public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
  public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
  public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
