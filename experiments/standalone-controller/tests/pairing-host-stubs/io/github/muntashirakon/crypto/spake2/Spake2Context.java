package io.github.muntashirakon.crypto.spake2;

/**
 * Host-only boundary for testing LibAdb's real HKDF/AES-GCM code without loading
 * Android arm64 JNI on a desktop JVM. This does not implement or validate SPAKE2.
 * Compile into a dedicated test directory placed before the shrunk application
 * JAR on that test's classpath. Never compile this source into the APK.
 */
public final class Spake2Context implements javax.security.auth.Destroyable {
    private boolean destroyed;
    public Spake2Context(Spake2Role role, byte[] ownName, byte[] peerName) {
        if (role == null || ownName == null || peerName == null) throw new IllegalArgumentException();
    }
    public byte[] generateMessage(byte[] password) {
        if (destroyed || password == null) throw new IllegalStateException();
        return new byte[32];
    }
    public byte[] processMessage(byte[] message) {
        if (destroyed || message == null) throw new IllegalStateException();
        byte[] syntheticSharedSecret = new byte[64];
        for (int i=0; i<syntheticSharedSecret.length; i++) syntheticSharedSecret[i]=(byte)i;
        return syntheticSharedSecret;
    }
    public boolean isDestroyed() { return destroyed; }
    public void destroy() { destroyed=true; }
}
