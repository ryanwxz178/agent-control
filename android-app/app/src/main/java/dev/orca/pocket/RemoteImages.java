package dev.orca.pocket;
import java.io.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;

/** Reply-scoped Doubao CDN images only. No cookies, arbitrary URLs or redirects. */
final class RemoteImages {
 static boolean allowed(String value){try{
  URI u=new URI(value);String h=u.getHost();
  return value.length()<8192&&"https".equalsIgnoreCase(u.getScheme())&&u.getRawUserInfo()==null&&u.getFragment()==null&&(u.getPort()==-1||u.getPort()==443)&&h!=null&&h.toLowerCase(java.util.Locale.ROOT).endsWith(".byteimg.com")&&!u.getPath().isEmpty();
 }catch(Exception e){return false;}}
 static byte[] read(String value)throws Exception{
  if(!allowed(value))throw new IOException("图片来源不受支持");
  HttpsURLConnection c=(HttpsURLConnection)new URL(value).openConnection();
  c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(20000);
  try{if(c.getResponseCode()!=200)throw new IOException("豆包图片读取失败或链接已过期，请重新提取回复图片");
   if(c.getContentLengthLong()>Attachments.LIMIT)throw new IOException("图片超过 20MB");
   String mime=c.getContentType();if(mime==null||!mime.toLowerCase(java.util.Locale.ROOT).startsWith("image/"))throw new IOException("服务器未返回图片");
   ByteArrayOutputStream out=new ByteArrayOutputStream();long deadline=System.nanoTime()+45000000000L;
   try(InputStream in=c.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>Attachments.LIMIT||System.nanoTime()>deadline)throw new IOException("图片过大或读取超时");out.write(b,0,n);}}
   return out.toByteArray();
  }finally{c.disconnect();}
 }
}
