package local.vivodoubleclick.controller;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Exercises the actual retained LibAdb PairingAuthCtx and optimized BC dependency
 * code. Only the native SPAKE2 boundary is replaced by an explicitly synthetic
 * host stub. These checks cannot establish native pairing or Android TLS exporter
 * correctness. All data is synthetic; no device, identity file or network is used.
 */
public final class PairingCipherValidation {
    private static int checks;
    private static Class<?> type;
    private static Method createAlice, createBob, initialize, encrypt, decrypt, destroy, isDestroyed;
    private static Field secret, encCounter, decCounter;
    private static void check(boolean value,String message) {
        checks++; if(!value) throw new AssertionError(message);
    }
    private static Method method(String name,Class<?>... args) throws Exception {
        Method result=type.getDeclaredMethod(name,args);result.setAccessible(true);return result;
    }
    private static Field field(String name) throws Exception {
        Field result=type.getDeclaredField(name);result.setAccessible(true);return result;
    }
    private static byte[] bytes(String hex) {
        byte[] value=new byte[hex.length()/2];
        for(int i=0;i<value.length;i++) value[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        return value;
    }
    private static byte[] hkdfReference() throws Exception {
        // HKDF-Extract with absent salt, then the first expand block for L=16.
        byte[] ikm=new byte[64];for(int i=0;i<ikm.length;i++)ikm[i]=(byte)i;
        Mac hmac=Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(new byte[32],"HmacSHA256"));
        byte[] prk=hmac.doFinal(ikm);
        hmac.init(new SecretKeySpec(prk,"HmacSHA256"));
        hmac.update("adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.UTF_8));
        hmac.update((byte)1);
        return Arrays.copyOf(hmac.doFinal(),16);
    }
    private static Object context(boolean alice,byte[] key) throws Exception {
        Object result=(alice?createAlice:createBob).invoke(null,(Object)new byte[]{1,2,3,4});
        check(result!=null,"synthetic pairing boundary creates context");
        check(Boolean.TRUE.equals(initialize.invoke(result,(Object)new byte[32])),"real pairing HKDF initializes");
        check(Arrays.equals((byte[])secret.get(result),key),"production HKDF matches independent HMAC reference");
        return result;
    }
    private static byte[] reference(boolean encoding,byte[] key,long counter,byte[] input) throws Exception {
        byte[] iv=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array();
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(encoding?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,
                new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        return cipher.doFinal(input);
    }
    public static void main(String[] ignored) throws Exception {
        type=Class.forName("io.github.muntashirakon.adb.PairingAuthCtx");
        createAlice=method("createAlice",byte[].class);createBob=method("createBob",byte[].class);
        initialize=method("initCipher",byte[].class);encrypt=method("encrypt",byte[].class);
        decrypt=method("decrypt",byte[].class);destroy=method("destroy");isDestroyed=method("isDestroyed");
        secret=field("mSecretKey");encCounter=field("mEncIv");decCounter=field("mDecIv");
        byte[] key=hkdfReference();
        // Independently computed with Python stdlib HMAC-SHA256 over the same
        // all-synthetic vector; this also checks the reference implementation.
        check(Arrays.equals(key,bytes("5e234c26fa41fb42e5d493b262c0def1")),"fixed HKDF vector");
        Object sender=context(true,key),receiver=context(false,key);
        int[] sizes={0,1,15,16,17,8192};
        for(int counter=0;counter<sizes.length;counter++) {
            byte[] plain=new byte[sizes[counter]];
            for(int i=0;i<plain.length;i++)plain[i]=(byte)(i*31+7);
            byte[] sealed=(byte[])encrypt.invoke(sender,(Object)plain);
            check(Arrays.equals(sealed,reference(true,key,counter,plain)),"BC AES-GCM ciphertext and 128-bit tag match JCA");
            check(Arrays.equals(plain,reference(false,key,counter,sealed)),"JCA decrypts production ciphertext");
            check(Arrays.equals(plain,(byte[])decrypt.invoke(receiver,(Object)reference(true,key,counter,plain))),"production decrypts independent ciphertext");
        }
        byte[] plain="synthetic nonce carry".getBytes(StandardCharsets.UTF_8);
        encCounter.setLong(sender,256);decCounter.setLong(receiver,256);
        byte[] sealed=(byte[])encrypt.invoke(sender,(Object)plain);
        check(Arrays.equals(sealed,reference(true,key,256,plain)),"little-endian nonce carry");
        check(Arrays.equals(plain,(byte[])decrypt.invoke(receiver,(Object)sealed)),"nonce carry decryption");
        check(encCounter.getLong(sender)==257&&decCounter.getLong(receiver)==257,"independent monotonically increasing counters");
        Object badTag=context(false,key);
        byte[] corrupted=reference(true,key,0,plain);corrupted[corrupted.length-1]^=1;
        check(decrypt.invoke(badTag,(Object)corrupted)==null,"tampered GCM tag rejected");
        Object badCounter=context(false,key);
        check(decrypt.invoke(badCounter,(Object)reference(true,key,1,plain))==null,"wrong nonce rejected");
        Object shortFrame=context(false,key);
        check(decrypt.invoke(shortFrame,(Object)new byte[3])==null,"truncated authentication frame rejected");
        Object badKey=context(false,key);
        byte[] otherKey=key.clone();otherKey[0]^=1;
        check(decrypt.invoke(badKey,(Object)reference(true,otherKey,0,plain))==null,"different encryption key rejected");
        destroy.invoke(sender);
        check(Boolean.TRUE.equals(isDestroyed.invoke(sender)),"production context marks destruction");
        check(Arrays.equals((byte[])secret.get(sender),new byte[16]),"derived secret cleared on destroy");
        check(encrypt.invoke(sender,(Object)plain)==null,"destroyed context cannot encrypt");
        check(Boolean.FALSE.equals(initialize.invoke(sender,(Object)new byte[32])),"destroyed context cannot derive a new key");
        System.out.println("PairingCipherValidation: "+checks+" assertions passed; synthetic SPAKE2 boundary only, no native pairing validation");
    }
}
