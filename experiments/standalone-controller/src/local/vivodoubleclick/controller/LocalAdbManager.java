package local.vivodoubleclick.controller;

import android.content.Context;
import android.os.Build;

import java.io.IOException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/** A local-only ADB client. Invoke pairing and connection methods on a worker thread. */
public final class LocalAdbManager extends AbsAdbConnectionManager {
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final long DISCOVERY_TIMEOUT_MS = 10_000L;

    private final Context applicationContext;
    private final IdentityStore.Identity identity;

    public LocalAdbManager(Context context) throws Exception {
        if (context == null) throw new IllegalArgumentException("Context is required");
        Context app = context.getApplicationContext();
        applicationContext = app != null ? app : context;
        identity = IdentityStore.loadOrCreate(applicationContext);
        setApi(Build.VERSION.SDK_INT);
        setHostAddress(LOCAL_HOST);
        setTimeout(10, TimeUnit.SECONDS);
        setThrowOnUnauthorised(true);
    }

    /** Use the port shown inside the six-digit pairing-code dialog, not the connection port. */
    public boolean pairLocal(int pairingPort, String pairingCode) throws Exception {
        requirePort(pairingPort);
        if (pairingCode == null || !pairingCode.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("请输入完整的 6 位无线调试配对码");
        }
        return pair(LOCAL_HOST, pairingPort, pairingCode);
    }

    /** A positive port is the current TLS connection port; zero selects local mDNS discovery. */
    public boolean connectLocal(int tlsPort) throws Exception {
        if (tlsPort < 0 || tlsPort > 65535) {
            throw new IllegalArgumentException("连接端口必须在 1–65535 之间，或用 0 自动发现");
        }
        if (isConnected()) return true;
        // Release any incomplete previous connection before trying again.
        disconnect();
        boolean connected;
        try {
            connected = tlsPort > 0
                    ? connect(LOCAL_HOST, tlsPort)
                    : connectTls(applicationContext, DISCOVERY_TIMEOUT_MS);
        } catch (Exception failure) {
            try {
                disconnect();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        if (!connected) disconnect();
        return connected;
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return identity.privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return identity.certificate;
    }

    @Override
    protected String getDeviceName() {
        return "VivoCameraController";
    }

    private static void requirePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口必须在 1–65535 之间");
        }
    }
}
