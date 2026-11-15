package local.vivodoubleclick.controller;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

public final class IdentityValidation {
    private static int assertions;
    private static Method generate;
    private static Method encode;
    private static Method decode;

    public static void main(String[] ignored) throws Exception {
        generate = method("generate");
        encode = method("encode", IdentityStore.Identity.class);
        decode = method("decode", byte[].class);
        IdentityStore.Identity first = (IdentityStore.Identity) generate.invoke(null);
        IdentityStore.Identity second = (IdentityStore.Identity) generate.invoke(null);
        byte[] persisted = (byte[]) encode.invoke(null, first);
        IdentityStore.Identity loaded = (IdentityStore.Identity) decode.invoke(null, persisted);
        check(Arrays.equals(first.privateKey.getEncoded(), loaded.privateKey.getEncoded()), "PKCS8 round trip");
        check(Arrays.equals(first.certificate.getEncoded(), loaded.certificate.getEncoded()), "certificate round trip");
        check(!Arrays.equals(first.certificate.getPublicKey().getEncoded(), second.certificate.getPublicKey().getEncoded()), "independent installations have distinct keys");
        check("SHA256WITHRSA".equalsIgnoreCase(first.certificate.getSigAlgName()), "SHA256 RSA signature");
        Calendar tenYears = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        tenYears.add(Calendar.YEAR, 10);
        check(Math.abs(first.certificate.getNotAfter().getTime() - tenYears.getTimeInMillis()) < 10_000, "ten year certificate");
        first.certificate.checkValidity(new Date());
        reject(new byte[0]);
        reject(Arrays.copyOf(persisted, 15));
        reject(Arrays.copyOf(persisted, persisted.length - 1));
        reject(Arrays.copyOf(persisted, persisted.length + 1));
        reject(new byte[65537]);
        byte[] header = persisted.clone(); header[0] ^= 1; reject(header);
        byte[] version = persisted.clone(); version[7] = 2; reject(version);
        byte[] negativeLength = persisted.clone(); negativeLength[8] = (byte) 0xFF; reject(negativeLength);
        byte[] damagedCert = persisted.clone(); damagedCert[damagedCert.length - 1] ^= 1; reject(damagedCert);
        IdentityStore.Identity mismatch = new IdentityStore.Identity(first.privateKey, second.certificate);
        reject((byte[]) encode.invoke(null, mismatch));
        // The library calls destroy() on disconnect; this provider must leave the reusable key valid
        // if destruction is unsupported, as expected for a Bouncy Castle software RSA key.
        try { first.privateKey.destroy(); } catch (javax.security.auth.DestroyFailedException expected) { }
        check(Arrays.equals(first.privateKey.getEncoded(), loaded.privateKey.getEncoded()), "key remains reusable after library disconnect");
        System.out.println("Identity checks passed: " + assertions);
    }

    private static Method method(String name, Class<?>... args) throws Exception {
        Method m = IdentityStore.class.getDeclaredMethod(name, args);
        m.setAccessible(true);
        return m;
    }

    private static void reject(byte[] input) throws Exception {
        try {
            decode.invoke(null, input);
            throw new AssertionError("Corrupt identity unexpectedly accepted");
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (!(cause instanceof IOException) && !(cause instanceof GeneralSecurityException)) {
                throw new AssertionError("Unexpected failure type", cause);
            }
            assertions++;
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        assertions++;
    }
}
