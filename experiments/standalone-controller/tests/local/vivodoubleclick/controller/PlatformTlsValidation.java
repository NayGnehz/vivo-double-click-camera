package local.vivodoubleclick.controller;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/** Host-side TLS 1.3/mutual-certificate test using a synthetic app identity and loopback only. */
public final class PlatformTlsValidation {
    public static void main(String[] args) throws Exception {
        Method generate = IdentityStore.class.getDeclaredMethod("generate");
        generate.setAccessible(true);
        IdentityStore.Identity identity = (IdentityStore.Identity) generate.invoke(null);
        Class<?> keyPairType = Class.forName("io.github.muntashirakon.adb.KeyPair");
        Constructor<?> constructor = keyPairType.getDeclaredConstructor(
                java.security.PrivateKey.class, java.security.cert.Certificate.class);
        constructor.setAccessible(true);
        Object keyPair = constructor.newInstance(identity.privateKey, identity.certificate);
        Class<?> sslUtils = Class.forName("io.github.muntashirakon.adb.SslUtils");
        Method getContext = sslUtils.getDeclaredMethod("getSslContext", keyPairType);
        getContext.setAccessible(true);
        SSLContext context = (SSLContext) getContext.invoke(null, keyPair);
        if (context.getProvider().getName().contains("Conscrypt")) throw new AssertionError("Bundled TLS provider used");
        // LibADB's key manager is a client-only implementation. Use a standard server
        // key manager on the synthetic peer, rather than pretending LibADB is a server.
        char[] password = "synthetic-loopback-only".toCharArray();
        java.security.KeyStore store = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        store.load(null, null);
        store.setKeyEntry("peer", identity.privateKey, password,
                new java.security.cert.Certificate[]{identity.certificate});
        javax.net.ssl.KeyManagerFactory factory = javax.net.ssl.KeyManagerFactory.getInstance(
                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, password);
        SSLContext serverContext = SSLContext.getInstance("TLSv1.3");
        serverContext.init(factory.getKeyManagers(), new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
        }}, new java.security.SecureRandom());
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "synthetic-tls-loopback"); thread.setDaemon(true); return thread;
        });
        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(4000);
            server.setNeedClientAuth(true);
            server.setEnabledProtocols(new String[]{"TLSv1.3"});
            Future<Boolean> echoed = worker.submit(() -> {
                try (SSLSocket peer = (SSLSocket) server.accept()) {
                    peer.setSoTimeout(4000); peer.startHandshake();
                    if (!Arrays.equals(peer.getSession().getPeerCertificates()[0].getEncoded(), identity.certificate.getEncoded()))
                        throw new AssertionError("Client identity changed");
                    int value = peer.getInputStream().read();
                    peer.getOutputStream().write(value); peer.getOutputStream().flush();
                    return value == 83;
                }
            });
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                socket.setSoTimeout(4000); socket.setEnabledProtocols(new String[]{"TLSv1.3"}); socket.startHandshake();
                if (!"TLSv1.3".equals(socket.getSession().getProtocol())) throw new AssertionError("TLS protocol changed");
                socket.getOutputStream().write(83); socket.getOutputStream().flush();
                if (socket.getInputStream().read() != 83 || !echoed.get(8, TimeUnit.SECONDS)) throw new AssertionError("TLS round trip failed");
            }
        } finally { worker.shutdownNow(); }
        System.out.println("Platform TLS validation passed: TLS 1.3, mutual certificate and encrypted round trip");
    }
}
