package dev.orca.pocket;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.nio.charset.StandardCharsets;

final class SecureStore {
    private final Context context;
    SecureStore(Context c){context=c;}
    private javax.crypto.SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias("pocket-storage")){
            KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder("pocket-storage",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();
        }
        return (javax.crypto.SecretKey)ks.getKey("pocket-storage",null);
    }
    synchronized void put(String name,String text) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
        String data=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(c.doFinal(text.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!context.getSharedPreferences("vault",0).edit().putString(name,data).commit())throw new java.io.IOException("本地保存失败");
    }
    synchronized String get(String name) throws Exception {
        String s=context.getSharedPreferences("vault",0).getString(name,null);if(s==null)return "";
        String[] a=s.split(":",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(a[0],Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(a[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
}
