package local.vivodoubleclick.controller;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.util.AtomicFile;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import java.security.Provider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/** Stores one app-installation identity without exporting it or changing it after a read failure. */
final class IdentityStore {
    private static final int MAGIC = 0x56414442; // VADB
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_FILE_BYTES = 64 * 1024;
    private static final int MAX_KEY_BYTES = 16 * 1024;
    private static final int MAX_CERT_BYTES = 32 * 1024;
    // Register only the software RSA key implementations used by persisted ADB identities.
    // Standard platform JCA provides signatures and X.509 parsing; no global provider is installed.
    private static final Provider IDENTITY_KEYS = new RsaIdentityProvider();

    private static final class RsaIdentityProvider extends Provider {
        RsaIdentityProvider() {
            super("VivoIdentityKeys", 1.0, "Software RSA keys for local ADB identity compatibility");
            put("KeyPairGenerator.RSA", "org.bouncycastle.jcajce.provider.asymmetric.rsa.KeyPairGeneratorSpi");
            put("KeyFactory.RSA", "org.bouncycastle.jcajce.provider.asymmetric.rsa.KeyFactorySpi");
        }
    }
    private static final byte[] VALIDATION_MESSAGE =
            "VivoCameraController ADB identity check v1".getBytes(StandardCharsets.UTF_8);

    private IdentityStore() { }

    static final class Identity {
        final PrivateKey privateKey;
        final X509Certificate certificate;

        Identity(PrivateKey privateKey, X509Certificate certificate) {
            this.privateKey = privateKey;
            this.certificate = certificate;
        }
    }

    static synchronized Identity loadOrCreate(Context context) throws Exception {
        // ADB authorization must never migrate to a different device through Android backups.
        File directory = new File(context.getNoBackupFilesDir(), "local-adb");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建 ADB 身份存储目录");
        }
        chmod(directory, 0700);
        File lockFile = new File(directory, "identity.lock");
        try (RandomAccessFile lockAccess = new RandomAccessFile(lockFile, "rw");
             FileLock ignored = lockAccess.getChannel().lock()) {
            chmod(lockFile, 0600);
            File identityFile = new File(directory, "identity.bin");
            AtomicFile file = new AtomicFile(identityFile);
            // AtomicFile.openRead() restores its legacy .bak file if present.
            if (identityFile.exists() || new File(directory, "identity.bin.bak").exists()) {
                try {
                    Identity existing = read(file);
                    chmod(file.getBaseFile(), 0600);
                    return existing;
                } catch (Exception failure) {
                    throw new IOException("无法读取已有 ADB 配对身份；未自动更换身份。请保留文件并查看错误详情。", failure);
                }
            }
            if (new File(directory, "identity.bin.new").exists()) {
                throw new IOException("发现未完成的 ADB 身份写入；未自动生成其他配对身份");
            }

            Identity created = generate();
            write(file, created);
            // Do not permit pairing until the persisted identity has been read back and checked.
            return read(file);
        }
    }

    private static Identity generate() throws Exception {
        SecureRandom random = new SecureRandom();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", IDENTITY_KEYS);
        generator.initialize(2048, random);
        KeyPair pair = generator.generateKeyPair();

        long now = System.currentTimeMillis();
        Calendar expiry = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        expiry.setTimeInMillis(now);
        expiry.add(Calendar.YEAR, 10);
        X500Name subject = new X500Name("CN=Vivo Camera Controller");
        BigInteger serial = new BigInteger(159, random).add(BigInteger.ONE);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, new Date(now - 86_400_000L), expiry.getTime(),
                subject, pair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(pair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));
        Identity identity = new Identity(pair.getPrivate(), certificate);
        validate(identity);
        return identity;
    }

    private static void validate(Identity identity) throws GeneralSecurityException {
        if (!(identity.privateKey instanceof RSAPrivateKey)
                || !(identity.certificate.getPublicKey() instanceof RSAPublicKey)) {
            throw new GeneralSecurityException("ADB 身份不是 RSA 密钥");
        }
        RSAPrivateKey privateKey = (RSAPrivateKey) identity.privateKey;
        RSAPublicKey publicKey = (RSAPublicKey) identity.certificate.getPublicKey();
        if (publicKey.getModulus().bitLength() != 2048
                || !publicKey.getModulus().equals(privateKey.getModulus())
                || !publicKey.getPublicExponent().equals(BigInteger.valueOf(65537))) {
            throw new GeneralSecurityException("ADB 私钥与 2048 位 RSA 证书不匹配");
        }
        identity.certificate.checkValidity();
        identity.certificate.verify(publicKey);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(identity.privateKey);
        signature.update(VALIDATION_MESSAGE);
        byte[] proof = signature.sign();
        signature.initVerify(publicKey);
        signature.update(VALIDATION_MESSAGE);
        if (!signature.verify(proof)) {
            throw new GeneralSecurityException("ADB 私钥校验失败");
        }
    }

    private static Identity read(AtomicFile file) throws Exception {
        try (FileInputStream input = file.openRead()) {
            long size = input.getChannel().size();
            if (size < 16 || size > MAX_FILE_BYTES) {
                throw new IOException("ADB 身份文件长度无效");
            }
            byte[] bytes = new byte[(int) size];
            try {
                DataInputStream data = new DataInputStream(input);
                data.readFully(bytes);
                if (data.read() != -1) throw new IOException("ADB 身份文件发生意外变化");
                return decode(bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private static Identity decode(byte[] bytes) throws Exception {
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("ADB 身份文件过大");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                throw new IOException("ADB 身份文件格式或版本无效");
            }
            byte[] privateBytes = readSized(input, MAX_KEY_BYTES);
            try {
                byte[] certificateBytes = readSized(input, MAX_CERT_BYTES);
                if (input.read() != -1) throw new IOException("ADB 身份文件含有多余数据");
                PrivateKey key = KeyFactory.getInstance("RSA", IDENTITY_KEYS)
                        .generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
                ByteArrayInputStream certificateInput = new ByteArrayInputStream(certificateBytes);
                X509Certificate certificate = (X509Certificate) CertificateFactory
                        .getInstance("X.509").generateCertificate(certificateInput);
                if (certificateInput.available() != 0) {
                    throw new IOException("ADB 证书含有多余数据");
                }
                Identity identity = new Identity(key, certificate);
                validate(identity);
                return identity;
            } finally {
                Arrays.fill(privateBytes, (byte) 0);
            }
        }
    }

    private static byte[] readSized(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > maximum || length > input.available()) {
            throw new IOException("ADB 身份数据长度无效");
        }
        byte[] value = new byte[length];
        input.readFully(value);
        return value;
    }

    private static byte[] encode(Identity identity) throws Exception {
        byte[] privateBytes = identity.privateKey.getEncoded();
        if (privateBytes == null || !"PKCS#8".equals(identity.privateKey.getFormat())) {
            throw new GeneralSecurityException("ADB 私钥无法按 PKCS#8 保存");
        }
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(buffer)) {
            byte[] certificateBytes = identity.certificate.getEncoded();
            if (privateBytes.length > MAX_KEY_BYTES || certificateBytes.length > MAX_CERT_BYTES) {
                throw new IOException("ADB 身份数据过大");
            }
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(privateBytes.length);
            output.write(privateBytes);
            output.writeInt(certificateBytes.length);
            output.write(certificateBytes);
            output.flush();
            return buffer.toByteArray();
        } finally {
            Arrays.fill(privateBytes, (byte) 0);
        }
    }

    private static void write(AtomicFile file, Identity identity) throws Exception {
        byte[] bytes = encode(identity);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            Os.fchmod(output.getFD(), 0600);
            output.write(bytes);
            file.finishWrite(output);
            output = null;
            chmod(file.getBaseFile(), 0600);
        } catch (Exception failure) {
            if (output != null) file.failWrite(output);
            throw failure;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void chmod(File file, int mode) throws IOException {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (ErrnoException failure) {
            throw new IOException("无法设置 ADB 身份文件权限", failure);
        }
    }
}
