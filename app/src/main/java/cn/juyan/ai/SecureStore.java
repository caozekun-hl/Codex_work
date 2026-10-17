package cn.juyan.ai;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts credentials AND conversations. Android backup is disabled in the manifest. */
final class SecureStore {
    private static final String ALIAS = "juyan.local.v1";
    private static final byte[] AAD = "JuYanAI-v1".getBytes(StandardCharsets.UTF_8);
    private final AtomicFile file;

    SecureStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "vault.bin"));
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            g.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    synchronized AppState load() throws Exception {
        if (!file.getBaseFile().exists()) return new AppState();
        byte[] data = file.readFully();
        if (data.length < 30 || data[0] != 1) throw new IllegalStateException("本地数据格式异常");
        byte[] iv = Arrays.copyOfRange(data, 1, 13);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv)); c.updateAAD(AAD);
        byte[] plain = c.doFinal(data, 13, data.length - 13);
        return AppState.from(new JSONObject(new String(plain, StandardCharsets.UTF_8)));
    }

    synchronized void save(AppState state) throws Exception {
        byte[] plain = state.json().toString().getBytes(StandardCharsets.UTF_8);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key()); c.updateAAD(AAD);
        byte[] encrypted = c.doFinal(plain);
        FileOutputStream out = null;
        try {
            out = file.startWrite(); out.write(1); out.write(c.getIV()); out.write(encrypted);
            file.finishWrite(out);
        } catch (Exception e) {
            if (out != null) file.failWrite(out);
            throw e;
        }
    }

    synchronized void clear() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        if (keys.containsAlias(ALIAS)) keys.deleteEntry(ALIAS);
        file.delete();
    }
}
