package io.canvasmc.canvas.distributed.identity;

import io.canvasmc.canvas.distributed.config.EnigmaDistributedConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

public final class InstanceIdentity {

    private static final Logger LOGGER = LoggerFactory.getLogger("EnigmaIdentity");
    private static final String ALGORITHM = "Ed25519";
    /**
     * The curve size in bits. {@code KeyPairGenerator#initialize(int, SecureRandom)} passes this
     * straight through to the EdDSA parameters lookup, which only accepts 255 (Ed25519) or 448
     * (Ed448) - a byte count such as 32 is rejected with {@code Unsupported size: 32}.
     */
    private static final int KEY_SIZE_BITS = 255;

    private final UUID instanceId;
    private final KeyPair keyPair;
    private final String publicKeyB64;

    private InstanceIdentity(final UUID instanceId, final KeyPair keyPair) {
        this.instanceId = instanceId;
        this.keyPair = keyPair;
        this.publicKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    public static InstanceIdentity loadOrCreate(final EnigmaDistributedConfig config) {
        final Path keyPath = Path.of(config.identityKeyPath).toAbsolutePath().normalize();
        if (Files.exists(keyPath)) {
            return load(keyPath);
        }
        return create(keyPath);
    }

    private static InstanceIdentity create(final Path keyPath) {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            generator.initialize(KEY_SIZE_BITS, new SecureRandom());
            final KeyPair keyPair = generator.generateKeyPair();
            final UUID instanceId = UUID.randomUUID();

            save(keyPath, instanceId, keyPair);

            LOGGER.info("Generated new Enigma instance identity: {} ({})", instanceId, keyPath);
            return new InstanceIdentity(instanceId, keyPair);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Failed to generate instance identity", e);
        }
    }

    private static InstanceIdentity load(final Path keyPath) {
        try {
            final String content = Files.readString(keyPath).trim();
            final String[] parts = content.split("\n");
            if (parts.length != 3) {
                throw new IllegalStateException("Invalid identity file format: " + keyPath);
            }

            final UUID instanceId = UUID.fromString(parts[0]);
            final PrivateKey privateKey = decodePrivateKey(parts[1]);
            final PublicKey publicKey = decodePublicKey(parts[2]);

            final KeyPair keyPair = new KeyPair(publicKey, privateKey);

            LOGGER.info("Loaded Enigma instance identity: {} ({})", instanceId, keyPath);
            return new InstanceIdentity(instanceId, keyPair);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load instance identity from " + keyPath, e);
        }
    }

    private static void save(final Path keyPath, final UUID instanceId, final KeyPair keyPair) throws IOException {
        final String privateKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        final String publicKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        final String content = instanceId + "\n" + privateKeyB64 + "\n" + publicKeyB64 + "\n";

        Files.createDirectories(keyPath.getParent());
        Files.writeString(keyPath, content);
    }

    private static PrivateKey decodePrivateKey(final String b64) {
        try {
            final byte[] bytes = Base64.getDecoder().decode(b64);
            final PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
            return java.security.KeyFactory.getInstance(ALGORITHM).generatePrivate(spec);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode private key", e);
        }
    }

    private static PublicKey decodePublicKey(final String b64) {
        try {
            final byte[] bytes = Base64.getDecoder().decode(b64);
            final X509EncodedKeySpec spec = new X509EncodedKeySpec(bytes);
            return java.security.KeyFactory.getInstance(ALGORITHM).generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode public key", e);
        }
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public KeyPair getKeyPair() {
        return keyPair;
    }

    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public String getPublicKeyB64() {
        return publicKeyB64;
    }

    public byte[] sign(final byte[] data) {
        try {
            final java.security.Signature signature = java.security.Signature.getInstance(ALGORITHM);
            signature.initSign(keyPair.getPrivate());
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign data", e);
        }
    }

    public boolean verify(final PublicKey publicKey, final byte[] data, final byte[] signature) {
        try {
            final java.security.Signature sig = java.security.Signature.getInstance(ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean verify(final byte[] data, final byte[] signature) {
        return verify(keyPair.getPublic(), data, signature);
    }
}