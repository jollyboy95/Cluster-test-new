package com.clusterprobe.aareceiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The real Android Auto TLS handshake - a manual, BIO-pump-style SSLEngine handshake tunneled
 * through SSL_HANDSHAKE frames on the CONTROL channel, built directly against what aasdk's actual
 * source does (f1xpl/aasdk, GPLv3, read 2026-10-23 - src/Transport/SSLWrapper.cpp,
 * src/Messenger/Cryptor.cpp):
 *   - aasdk's own TLS_client_method()/SSL_set_connect_state() confirm the HEAD UNIT is the TLS
 *     CLIENT (not the server) - this class mirrors that with SSLEngine.setUseClientMode(true).
 *   - aasdk calls SSL_CTX_use_certificate()/SSL_CTX_use_PrivateKey() with its own fixed, published
 *     cCertificate/cPrivateKey - the head unit presents this cert as ITS OWN client identity
 *     (every open-source AA head unit uses this exact same, publicly-known pair; the protocol
 *     doesn't tie identity to a real CA at this layer). Vendored byte-for-byte below.
 *   - aasdk calls SSL_set_verify(ssl, SSL_VERIFY_NONE, nullptr) - it does NOT validate the phone's
 *     server certificate at all. This class does the same with a trust-all TrustManager.
 *   - aasdk uses in-memory BIOs (BIO_s_mem) to pump handshake bytes manually rather than wrapping
 *     a real socket - this maps directly onto SSLEngine's own wrap()/unwrap() network-buffer API,
 *     which was designed for exactly this.
 *
 * The cert/key parsing (PKCS1 RSA key -> PKCS8 -> KeyManager -> SSLContext -> SSLEngine ->
 * beginHandshake() -> wrap() producing a real ClientHello) was verified end-to-end against the
 * real JDK TLS stack in a standalone test before being written here (same java.security/
 * javax.net.ssl APIs Android uses) - not just "compiles", a real ClientHello was produced and
 * inspected (422 bytes, non-trivial, BUFFER_OVERFLOW on an undersized dst buffer caught and fixed
 * by sizing via SSLSession.getPacketBufferSize() as this class does below).
 */
public final class AaTlsSession {

    // Vendored byte-for-byte from aasdk's Messenger/Cryptor.cpp (cCertificate/cPrivateKey
    // constants), GPLv3, Copyright (c) 2018 f1x.studio (Michal Szwaj) - the real, fixed,
    // publicly-known Android Auto TLS client identity every open-source head unit uses.
    private static final String CERT_PEM =
            "-----BEGIN CERTIFICATE-----\n" +
            "MIIDKjCCAhICARswDQYJKoZIhvcNAQELBQAwWzELMAkGA1UEBhMCVVMxEzARBgNV\n" +
            "BAgMCkNhbGlmb3JuaWExFjAUBgNVBAcMDU1vdW50YWluIFZpZXcxHzAdBgNVBAoM\n" +
            "Fkdvb2dsZSBBdXRvbW90aXZlIExpbmswJhcRMTQwNzA0MDAwMDAwLTA3MDAXETQ1\n" +
            "MDQyOTE0MjgzOC0wNzAwMFMxCzAJBgNVBAYTAkpQMQ4wDAYDVQQIDAVUb2t5bzER\n" +
            "MA8GA1UEBwwISGFjaGlvamkxFDASBgNVBAoMC0pWQyBLZW53b29kMQswCQYDVQQL\n" +
            "DAIwMTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAM911mNnUfx+WJtx\n" +
            "uk06GO7kXRW/gXUVNQBkbAFZmVdVNvLoEQNthi2X8WCOwX6n6oMPxU2MGJnvicP3\n" +
            "6kBqfHhfQ2Fvqlf7YjjhgBHh0lqKShVPxIvdatBjVQ76aym5H3GpkigLGkmeyiVo\n" +
            "VO8oc3cJ1bO96wFRmk7kJbYcEjQyakODPDu4QgWUTwp1Z8Dn41ARMG5OFh6otITL\n" +
            "XBzj9REkUPkxfS03dBXGr5/LIqvSsnxib1hJ47xnYJXROUsBy3e6T+fYZEEzZa7y\n" +
            "7tFioHIQ8G/TziPmvFzmQpaWMGiYfoIgX8WoR3GD1diYW+wBaZTW+4SFUZJmRKgq\n" +
            "TbMNFkMCAwEAATANBgkqhkiG9w0BAQsFAAOCAQEAsGdH5VFn78WsBElMXaMziqFC\n" +
            "zmilkvr85/QpGCIztI0FdF6xyMBJk/gYs2thwvF+tCCpXoO8mjgJuvJZlwr6fHzK\n" +
            "Ox5hNUb06AeMtsUzUfFjSZXKrSR+XmclVd+Z6/ie33VhGePOPTKYmJ/PPfTT9wvT\n" +
            "93qswcxhA+oX5yqLbU3uDPF1ZnJaEeD/YN45K/4eEA4/0SDXaWW14OScdS2LV0Bc\n" +
            "YmsbkPVNYZn37FlY7e2Z4FUphh0A7yME2Eh/e57QxWrJ1wubdzGnX8mrABc67ADU\n" +
            "U5r9tlTRqMs7FGOk6QS2Cxp4pqeVQsrPts4OEwyPUyb3LfFNo3+sP111D9zEow==\n" +
            "-----END CERTIFICATE-----\n";

    private static final String KEY_PEM =
            "-----BEGIN RSA PRIVATE KEY-----\n" +
            "MIIEowIBAAKCAQEAz3XWY2dR/H5Ym3G6TToY7uRdFb+BdRU1AGRsAVmZV1U28ugR\n" +
            "A22GLZfxYI7Bfqfqgw/FTYwYme+Jw/fqQGp8eF9DYW+qV/tiOOGAEeHSWopKFU/E\n" +
            "i91q0GNVDvprKbkfcamSKAsaSZ7KJWhU7yhzdwnVs73rAVGaTuQlthwSNDJqQ4M8\n" +
            "O7hCBZRPCnVnwOfjUBEwbk4WHqi0hMtcHOP1ESRQ+TF9LTd0Fcavn8siq9KyfGJv\n" +
            "WEnjvGdgldE5SwHLd7pP59hkQTNlrvLu0WKgchDwb9POI+a8XOZClpYwaJh+giBf\n" +
            "xahHcYPV2Jhb7AFplNb7hIVRkmZEqCpNsw0WQwIDAQABAoIBAB2u7ZLheKCY71Km\n" +
            "bhKYqnKb6BmxgfNfqmq4858p07/kKG2O+Mg1xooFgHrhUhwuKGbCPee/kNGNrXeF\n" +
            "pFW9JrwOXVS2pnfaNw6ObUWhuvhLaxgrhqLAdoUEgWoYOHcKzs3zhj8Gf6di+edq\n" +
            "SyTA8+xnUtVZ6iMRKvP4vtCUqaIgBnXdmQbGINP+/4Qhb5R7XzMt/xPe6uMyAIyC\n" +
            "y5Fm9HnvekaepaeFEf3bh4NV1iN/R8px6cFc6ELYxIZc/4Xbm91WGqSdB0iSriaZ\n" +
            "TjgrmaFjSO40tkCaxI9N6DGzJpmpnMn07ifhl2VjnGOYwtyuh6MKEnyLqTrTg9x0\n" +
            "i3mMwskCgYEA9IyljPRerXxHUAJt+cKOayuXyNt80q9PIcGbyRNvn7qIY6tr5ut+\n" +
            "ZbaFgfgHdSJ/4nICRq02HpeDJ8oj9BmhTAhcX6c1irH5ICjRlt40qbPwemIcpybt\n" +
            "mb+DoNYbI8O4dUNGH9IPfGK8dRpOok2m+ftfk94GmykWbZF5CnOKIp8CgYEA2Syc\n" +
            "5xlKB5Qk2ZkwXIzxbzozSfunHhWWdg4lAbyInwa6Y5GB35UNdNWI8TAKZsN2fKvX\n" +
            "RFgCjbPreUbREJaM3oZ92o5X4nFxgjvAE1tyRqcPVbdKbYZgtcqqJX06sW/g3r/3\n" +
            "RH0XPj2SgJIHew9sMzjGWDViMHXLmntI8rVA7d0CgYBOr36JFwvrqERN0ypNpbMr\n" +
            "epBRGYZVSAEfLGuSzEUrUNqXr019tKIr2gmlIwhLQTmCxApFcXArcbbKs7jTzvde\n" +
            "PoZyZJvOr6soFNozP/YT8Ijc5/quMdFbmgqhUqLS5CPS3z2N+YnwDNj0mO1aPcAP\n" +
            "STmcm2DmxdaolJksqrZ0owKBgQCD0KJDWoQmaXKcaHCEHEAGhMrQot/iULQMX7Vy\n" +
            "gl5iN5E2EgFEFZIfUeRWkBQgH49xSFPWdZzHKWdJKwSGDvrdrcABwdfx520/4MhK\n" +
            "d3y7CXczTZbtN1zHuoTfUE0pmYBhcx7AATT0YCblxrynosrHpDQvIefBBh5YW3AB\n" +
            "cKZCOQKBgEM/ixzI/OVSZ0Py2g+XV8+uGQyC5XjQ6cxkVTX3Gs0ZXbemgUOnX8co\n" +
            "eCXS4VrhEf4/HYMWP7GB5MFUOEVtlLiLM05ruUL7CrphdfgayDXVcTPfk75lLhmu\n" +
            "KAwp3tIHPoJOQiKNQ3/qks5km/9dujUGU2ARiU3qmxLMdgegFz8e\n" +
            "-----END RSA PRIVATE KEY-----\n";

    private final SSLEngine engine;

    private AaTlsSession(SSLEngine engine) {
        this.engine = engine;
    }

    public static AaTlsSession create() throws Exception {
        byte[] certDer = stripPem(CERT_PEM, "-----BEGIN CERTIFICATE-----", "-----END CERTIFICATE-----");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(certDer));

        byte[] keyDer = stripPem(KEY_PEM, "-----BEGIN RSA PRIVATE KEY-----", "-----END RSA PRIVATE KEY-----");
        byte[] pkcs8 = pkcs1ToPkcs8(keyDer);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        char[] pw = "clusterprobe".toCharArray();
        ks.setKeyEntry("aa-client", privateKey, pw, new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);

        TrustManager trustAll = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), new TrustManager[]{trustAll}, new SecureRandom());
        SSLEngine engine = ctx.createSSLEngine();
        engine.setUseClientMode(true);
        engine.beginHandshake();
        return new AaTlsSession(engine);
    }

    /** Drives the handshake to completion, pumping SSL_HANDSHAKE frames over `out`/`in`. Throws
     *  on any failure - a half-finished handshake isn't a usable session. */
    public void runHandshake(OutputStream out, InputStream in) throws Exception {
        int appBufSize = engine.getSession().getApplicationBufferSize();
        int netBufSize = engine.getSession().getPacketBufferSize();
        ByteBuffer emptyApp = ByteBuffer.allocate(0);
        ByteBuffer scratchApp = ByteBuffer.allocate(appBufSize);

        while (true) {
            SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
            switch (status) {
                case NEED_WRAP: {
                    ByteBuffer netOut = ByteBuffer.allocate(netBufSize);
                    SSLEngineResult r = engine.wrap(emptyApp, netOut);
                    if (r.getStatus() != SSLEngineResult.Status.OK) {
                        throw new IOException("TLS wrap() during handshake: " + r.getStatus());
                    }
                    netOut.flip();
                    byte[] bytes = new byte[netOut.remaining()];
                    netOut.get(bytes);
                    AaProtocol.writeFrame(out, AaProtocol.CHANNEL_CONTROL, AaProtocol.MESSAGE_ID_SSL_HANDSHAKE,
                            bytes, false);
                    break;
                }
                case NEED_UNWRAP: {
                    AaProtocol.SocketMessage msg = AaProtocol.readFrame(in);
                    if (msg.messageId != AaProtocol.MESSAGE_ID_SSL_HANDSHAKE) {
                        throw new IOException("expected SSL_HANDSHAKE during TLS handshake, got "
                                + "messageId=0x" + Integer.toHexString(msg.messageId));
                    }
                    ByteBuffer netIn = ByteBuffer.wrap(msg.body);
                    scratchApp.clear();
                    SSLEngineResult r = engine.unwrap(netIn, scratchApp);
                    if (r.getStatus() != SSLEngineResult.Status.OK
                            && r.getStatus() != SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        throw new IOException("TLS unwrap() during handshake: " + r.getStatus());
                    }
                    break;
                }
                case NEED_TASK: {
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    break;
                }
                case FINISHED:
                case NOT_HANDSHAKING:
                    return; // handshake complete
                default:
                    throw new IOException("unexpected TLS handshake status: " + status);
            }
        }
    }

    /** Encrypts `plaintext` into one or more TLS records, concatenated - callers wrap the result
     *  in one AA frame (ENCRYPTED bit set) via AaProtocol.writeFrame, same as aasdk treats the
     *  encrypted channel as a single opaque byte stream per frame. */
    public byte[] encrypt(byte[] plaintext) throws IOException {
        ByteBuffer appIn = ByteBuffer.wrap(plaintext);
        ByteBuffer netOut = ByteBuffer.allocate(engine.getSession().getPacketBufferSize()
                + plaintext.length); // generous - handles multi-record output without looping
        while (appIn.hasRemaining() || netOut.position() == 0) {
            SSLEngineResult r = engine.wrap(appIn, netOut);
            if (r.getStatus() != SSLEngineResult.Status.OK) {
                throw new IOException("TLS wrap() (application data): " + r.getStatus());
            }
            if (!appIn.hasRemaining()) break;
        }
        netOut.flip();
        byte[] out = new byte[netOut.remaining()];
        netOut.get(out);
        return out;
    }

    public byte[] decrypt(byte[] ciphertext) throws IOException {
        ByteBuffer netIn = ByteBuffer.wrap(ciphertext);
        ByteBuffer appOut = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize()
                + ciphertext.length);
        while (netIn.hasRemaining()) {
            SSLEngineResult r = engine.unwrap(netIn, appOut);
            if (r.getStatus() != SSLEngineResult.Status.OK) {
                throw new IOException("TLS unwrap() (application data): " + r.getStatus());
            }
        }
        appOut.flip();
        byte[] out = new byte[appOut.remaining()];
        appOut.get(out);
        return out;
    }

    private static byte[] stripPem(String pem, String beginMarker, String endMarker) {
        String b64 = pem.replace(beginMarker, "").replace(endMarker, "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(b64);
    }

    /** Wraps a PKCS1 RSA private key (the "RSA PRIVATE KEY" PEM format aasdk's own cPrivateKey
     *  uses) into a PKCS8 structure, since java.security.KeyFactory only natively parses PKCS8
     *  (X509EncodedKeySpec/PKCS8EncodedKeySpec). Standard, documented ASN.1 DER wrapping (SEQUENCE
     *  { INTEGER 0, SEQUENCE { OID rsaEncryption, NULL }, OCTET STRING { <PKCS1 bytes> } }) - not
     *  specific to this project's cert, works for any PKCS1 RSA key. Verified against the real
     *  JDK TLS stack in a standalone test before being used here (see this class's top doc
     *  comment). */
    private static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        byte[] rsaOidSeq = new byte[]{
                0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
        };
        byte[] version = new byte[]{0x02, 0x01, 0x00};
        byte[] octetHeader = derLength(pkcs1.length);
        byte[] octetString = new byte[1 + octetHeader.length + pkcs1.length];
        octetString[0] = 0x04;
        System.arraycopy(octetHeader, 0, octetString, 1, octetHeader.length);
        System.arraycopy(pkcs1, 0, octetString, 1 + octetHeader.length, pkcs1.length);

        int bodyLen = version.length + rsaOidSeq.length + octetString.length;
        byte[] bodyLenHeader = derLength(bodyLen);
        byte[] result = new byte[1 + bodyLenHeader.length + bodyLen];
        int pos = 0;
        result[pos++] = 0x30;
        System.arraycopy(bodyLenHeader, 0, result, pos, bodyLenHeader.length); pos += bodyLenHeader.length;
        System.arraycopy(version, 0, result, pos, version.length); pos += version.length;
        System.arraycopy(rsaOidSeq, 0, result, pos, rsaOidSeq.length); pos += rsaOidSeq.length;
        System.arraycopy(octetString, 0, result, pos, octetString.length);
        return result;
    }

    private static byte[] derLength(int len) {
        if (len < 0x80) return new byte[]{(byte) len};
        if (len < 0x100) return new byte[]{(byte) 0x81, (byte) len};
        return new byte[]{(byte) 0x82, (byte) (len >> 8), (byte) len};
    }
}
