package local.vivodoubleclick.controller;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Uses synthetic in-memory identities only; never opens an application's private directory. */
public final class IdentityCompatibilityTest {
    public static void main(String[] args) throws Exception {
        Method generate = IdentityStore.class.getDeclaredMethod("generate");
        Method encode = IdentityStore.class.getDeclaredMethod("encode", IdentityStore.Identity.class);
        Method decode = IdentityStore.class.getDeclaredMethod("decode", byte[].class);
        generate.setAccessible(true); encode.setAccessible(true); decode.setAccessible(true);
        if ("write".equals(args[0])) {
            byte[] bytes = (byte[]) encode.invoke(null, generate.invoke(null));
            Files.write(Paths.get(args[1]), bytes);
            System.out.println("Synthetic identity created");
        } else {
            IdentityStore.Identity identity = (IdentityStore.Identity) decode.invoke(null,
                    (Object) Files.readAllBytes(Paths.get(args[1])));
            if (identity.privateKey == null || identity.certificate == null) throw new AssertionError();
            System.out.println("Synthetic identity from the other build validated successfully");
        }
    }
}
