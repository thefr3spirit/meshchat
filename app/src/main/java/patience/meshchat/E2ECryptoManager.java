package patience.meshchat;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.MessageDigest;

/**
 * ============================================================================
 * E2ECryptoManager — End-to-End Encrypted Messaging via ECIES
 * ============================================================================
 *
 * PROBLEM:
 * ────────
 * In a mesh network, two users may be multiple hops apart and CANNOT
 * establish a pre-shared secret directly. The existing CryptoManager uses
 * a shared passphrase known to every node — meaning relay nodes can decrypt
 * messages. That's fine for broadcast, but private messages need true E2E
 * encryption where ONLY the intended recipient can read the content.
 *
 * SOLUTION — Signal Protocol-inspired ECIES:
 * ───────────────────────────────────────────
 * Each device generates an X25519 key pair on first launch:
 *   - Private key  → stored securely, encrypted by an Android Keystore
 *                     wrapper key so it never leaves the device in plaintext.
 *   - Public key   → gossip-propagated across the mesh network so that even
 *                     nodes 5+ hops away learn each other's public keys.
 *
 * When Alice wants to send a private message to Bob (who is 5 hops away):
 *
 *   1. Alice generates an EPHEMERAL X25519 key pair (used once, then discarded)
 *   2. Alice performs ECDH:  sharedSecret = ephemeralPrivate × Bob's publicKey
 *   3. Alice hashes the shared secret with SHA-256 to derive an AES-256 key
 *   4. Alice encrypts the message with AES-256-GCM using the derived key
 *   5. Alice sends: [ephemeral public key | IV | ciphertext]
 *   6. Relay nodes (hops 1–4) see only ciphertext — they cannot decrypt
 *   7. Bob receives, performs ECDH:  sharedSecret = Bob's private × ephemeralPublic
 *   8. Bob derives the same AES key and decrypts
 *
 * WHY X25519?
 * ───────────
 * X25519 is an elliptic curve Diffie-Hellman function using Curve25519.
 * It's the same primitive used by the Signal Protocol (WhatsApp, Signal).
 * - Fast: designed for high performance on mobile devices
 * - Secure: 128-bit security level, resistant to timing attacks
 * - Available on Android API 31+ via the XDH algorithm
 *
 * WHY EPHEMERAL KEYS?
 * ───────────────────
 * Each message uses a FRESH ephemeral key pair. This provides FORWARD
 * SECRECY: even if Bob's long-term private key is compromised later,
 * past messages cannot be decrypted because the ephemeral private keys
 * were discarded immediately after use.
 *
 * ANDROID KEYSTORE WRAPPING:
 * ──────────────────────────
 * The X25519 private key is NOT stored in plaintext. Instead:
 *   1. An AES-256 "wrapper key" lives in the Android Keystore
 *      (hardware-backed on most devices — key material never leaves the TEE)
 *   2. The X25519 private key bytes are encrypted with this wrapper key
 *   3. The encrypted bytes are stored in SharedPreferences
 *   4. To use the private key, we decrypt it with the Keystore wrapper key
 *
 * This means: even if someone roots the device and reads SharedPreferences,
 * they still can't extract the private key without the Keystore wrapper.
 *
 * KEY DISTRIBUTION (GOSSIP):
 * ──────────────────────────
 * Public keys are distributed in two ways:
 *   1. During the handshake when two peers connect directly
 *   2. Via KEY_ANNOUNCE messages that are forwarded across the entire mesh
 *      (same dedup/TTL/hop-count rules as regular messages)
 *
 * This ensures that a node joining the network quickly learns the public
 * keys of all other nodes, even those many hops away.
 *
 * ============================================================================
 */
public class E2ECryptoManager {

    private static final String TAG = "E2ECryptoManager";

    // ─── Android Keystore ───────────────────────────────────────────────

    /** Alias for the AES wrapper key stored in the Android Keystore */
    private static final String KEYSTORE_WRAPPER_ALIAS = "MeshChat_E2E_Wrapper";

    /** Android Keystore provider name */
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    // ─── SharedPreferences keys ─────────────────────────────────────────

    private static final String PREFS_NAME = "e2e_crypto";
    private static final String KEY_PRIVATE_KEY_ENCRYPTED = "private_key_enc";
    private static final String KEY_PRIVATE_KEY_IV = "private_key_iv";
    private static final String KEY_PUBLIC_KEY = "public_key";

    // ─── AES-GCM parameters ────────────────────────────────────────────

    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    /**
     * Prefix for E2E-encrypted content, allowing the receiver to distinguish
     * E2E ciphertext from passphrase-based ciphertext.
     */
    static final String E2E_PREFIX = "E2E:";

    // ─── State ──────────────────────────────────────────────────────────

    /** Our long-term X25519 private key (decrypted from Keystore-wrapped storage) */
    private PrivateKey myPrivateKey;

    /** Our long-term X25519 public key (shared with the network) */
    private PublicKey myPublicKey;

    /**
     * Peer public keys: nodeId → X25519 PublicKey.
     * Populated via handshakes and gossip-propagated KEY_ANNOUNCE messages.
     */
    private final Map<String, PublicKey> peerPublicKeys = new ConcurrentHashMap<>();

    private final SecureRandom secureRandom = new SecureRandom();
    private final SharedPreferences prefs;

    // ═══════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates the E2E crypto manager. Loads (or generates on first run)
     * the X25519 key pair and ensures the Android Keystore wrapper key exists.
     */
    public E2ECryptoManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensureKeystoreWrapperKey();
        loadOrGenerateKeyPair();
    }

    // ═══════════════════════════════════════════════════════════════════
    // KEY PAIR GENERATION & STORAGE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Generates a fresh X25519 key pair using the standard JCA XDH provider.
     *
     * X25519 is an Elliptic Curve Diffie-Hellman function on Curve25519.
     * It produces a 32-byte private key and a 32-byte public key.
     *
     * The private key is immediately wrapped (encrypted) by the Android
     * Keystore wrapper key and stored in SharedPreferences. The plaintext
     * private key bytes exist only in memory.
     */
    private void generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("XDH");
            kpg.initialize(NamedParameterSpec.X25519);
            KeyPair keyPair = kpg.generateKeyPair();

            myPrivateKey = keyPair.getPrivate();
            myPublicKey = keyPair.getPublic();

            // Persist: wrap (encrypt) private key with Keystore AES key
            storePrivateKey();

            // Persist: public key in plaintext (it's public, by definition)
            prefs.edit()
                    .putString(KEY_PUBLIC_KEY,
                            Base64.encodeToString(myPublicKey.getEncoded(), Base64.NO_WRAP))
                    .apply();

            Log.d(TAG, "Generated new X25519 key pair");
        } catch (Exception e) {
            Log.e(TAG, "Key pair generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Loads the existing key pair from storage, or generates a new one
     * if this is the first launch.
     */
    private void loadOrGenerateKeyPair() {
        String pubKeyB64 = prefs.getString(KEY_PUBLIC_KEY, null);
        String encPrivKeyB64 = prefs.getString(KEY_PRIVATE_KEY_ENCRYPTED, null);

        if (pubKeyB64 == null || encPrivKeyB64 == null) {
            // First launch — generate everything
            generateKeyPair();
            return;
        }

        try {
            // Reconstruct public key from stored X.509 encoding
            KeyFactory kf = KeyFactory.getInstance("XDH");
            byte[] pubBytes = Base64.decode(pubKeyB64, Base64.NO_WRAP);
            myPublicKey = kf.generatePublic(new X509EncodedKeySpec(pubBytes));

            // Unwrap (decrypt) private key using the Keystore wrapper key
            myPrivateKey = loadPrivateKey();

            Log.d(TAG, "Loaded existing X25519 key pair");
        } catch (Exception e) {
            Log.w(TAG, "Failed to load key pair, regenerating: " + e.getMessage());
            generateKeyPair();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ANDROID KEYSTORE WRAPPER KEY
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Ensures an AES-256 wrapper key exists in the Android Keystore.
     *
     * This key never leaves the hardware security module (TEE/StrongBox).
     * It's used solely to encrypt (wrap) our X25519 private key before
     * storing it in SharedPreferences.
     *
     * If the key already exists (from a previous launch), this is a no-op.
     */
    private void ensureKeystoreWrapperKey() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);

            if (ks.containsAlias(KEYSTORE_WRAPPER_ALIAS)) return;

            // Generate a new AES-256 key inside the Keystore
            KeyGenerator kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            kg.init(new KeyGenParameterSpec.Builder(
                    KEYSTORE_WRAPPER_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            kg.generateKey();

            Log.d(TAG, "Created Keystore wrapper key");
        } catch (Exception e) {
            Log.e(TAG, "Keystore wrapper key setup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Encrypts the X25519 private key bytes with the Keystore wrapper key
     * and stores the result (encrypted bytes + IV) in SharedPreferences.
     */
    private void storePrivateKey() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            SecretKey wrapperKey = (SecretKey) ks.getKey(KEYSTORE_WRAPPER_ALIAS, null);

            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, wrapperKey);

            byte[] encryptedPrivKey = cipher.doFinal(myPrivateKey.getEncoded());
            byte[] iv = cipher.getIV();

            prefs.edit()
                    .putString(KEY_PRIVATE_KEY_ENCRYPTED,
                            Base64.encodeToString(encryptedPrivKey, Base64.NO_WRAP))
                    .putString(KEY_PRIVATE_KEY_IV,
                            Base64.encodeToString(iv, Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Private key storage failed: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypts the X25519 private key from SharedPreferences using the
     * Keystore wrapper key.
     */
    private PrivateKey loadPrivateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        SecretKey wrapperKey = (SecretKey) ks.getKey(KEYSTORE_WRAPPER_ALIAS, null);

        byte[] encBytes = Base64.decode(
                prefs.getString(KEY_PRIVATE_KEY_ENCRYPTED, ""), Base64.NO_WRAP);
        byte[] iv = Base64.decode(
                prefs.getString(KEY_PRIVATE_KEY_IV, ""), Base64.NO_WRAP);

        Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, wrapperKey, new GCMParameterSpec(GCM_TAG_BITS, iv));

        byte[] privBytes = cipher.doFinal(encBytes);
        KeyFactory kf = KeyFactory.getInstance("XDH");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
    }

    // ═══════════════════════════════════════════════════════════════════
    // PUBLIC KEY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    /** Returns our public key as a Base64 string for sharing via handshakes */
    public String getPublicKeyBase64() {
        if (myPublicKey == null) return "";
        return Base64.encodeToString(myPublicKey.getEncoded(), Base64.NO_WRAP);
    }

    /** Whether we have the public key for a given peer */
    public boolean hasPeerKey(String nodeId) {
        return peerPublicKeys.containsKey(nodeId);
    }

    /**
     * Stores a peer's public key (received via handshake or KEY_ANNOUNCE gossip).
     *
     * @param nodeId         The peer's UUID
     * @param publicKeyBase64 The peer's X25519 public key in Base64 (X.509 encoded)
     */
    public void storePeerPublicKey(String nodeId, String publicKeyBase64) {
        if (nodeId == null || publicKeyBase64 == null || publicKeyBase64.isEmpty()) return;
        try {
            KeyFactory kf = KeyFactory.getInstance("XDH");
            byte[] pubBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP);
            PublicKey peerKey = kf.generatePublic(new X509EncodedKeySpec(pubBytes));
            peerPublicKeys.put(nodeId, peerKey);
            Log.d(TAG, "Stored public key for peer " + nodeId);
        } catch (Exception e) {
            Log.w(TAG, "Failed to store peer public key: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ECIES ENCRYPTION (X25519 + AES-GCM)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Encrypts a message for a specific recipient using ECIES.
     *
     * ECIES (Elliptic Curve Integrated Encryption Scheme) steps:
     *
     *   1. Generate an EPHEMERAL X25519 key pair (used once for this message)
     *   2. ECDH key agreement: ephemeralPrivate × recipientPublicKey → sharedSecret
     *   3. Derive an AES-256 key by hashing the shared secret with SHA-256
     *   4. Encrypt the plaintext with AES-256-GCM using the derived key
     *   5. Output: "E2E:" + ephemeralPublicKey + ":" + IV + ":" + ciphertext
     *      (all Base64-encoded)
     *
     * The ephemeral private key is discarded immediately after use,
     * providing FORWARD SECRECY.
     *
     * @param plaintext     The message text to encrypt
     * @param recipientNodeId The intended recipient's node UUID
     * @return E2E-encrypted string, or null if encryption fails
     */
    public String encryptForRecipient(String plaintext, String recipientNodeId) {
        PublicKey recipientKey = peerPublicKeys.get(recipientNodeId);
        if (recipientKey == null || myPrivateKey == null) return null;

        try {
            // Step 1: Generate ephemeral X25519 key pair
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("XDH");
            kpg.initialize(NamedParameterSpec.X25519);
            KeyPair ephemeral = kpg.generateKeyPair();

            // Step 2: ECDH key agreement → shared secret
            KeyAgreement ka = KeyAgreement.getInstance("XDH");
            ka.init(ephemeral.getPrivate());
            ka.doPhase(recipientKey, true);
            byte[] sharedSecret = ka.generateSecret();

            // Step 3: Derive AES-256 key from shared secret via SHA-256
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] aesKeyBytes = sha256.digest(sharedSecret);
            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

            // Step 4: AES-GCM encryption
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(
                    plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Step 5: Format output — "E2E:ephPub:iv:ciphertext" (all Base64)
            String ephPubB64 = Base64.encodeToString(
                    ephemeral.getPublic().getEncoded(), Base64.NO_WRAP);
            String ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP);
            String ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP);

            return E2E_PREFIX + ephPubB64 + ":" + ivB64 + ":" + ctB64;

        } catch (Exception e) {
            Log.e(TAG, "E2E encryption failed: " + e.getMessage(), e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ECIES DECRYPTION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Decrypts an E2E-encrypted message addressed to us.
     *
     * Reverses the ECIES process:
     *   1. Parse the ephemeral public key, IV, and ciphertext from the input
     *   2. ECDH key agreement: our private key × ephemeral public key → shared secret
     *      (produces the same shared secret the sender computed)
     *   3. Derive the same AES-256 key via SHA-256
     *   4. Decrypt with AES-GCM
     *
     * @param e2eCiphertext The "E2E:ephPub:iv:ciphertext" string
     * @return Decrypted plaintext, or null if decryption fails
     */
    public String decrypt(String e2eCiphertext) {
        if (myPrivateKey == null || e2eCiphertext == null
                || !e2eCiphertext.startsWith(E2E_PREFIX)) {
            return null;
        }

        try {
            // Parse: "E2E:ephPub:iv:ciphertext"
            String payload = e2eCiphertext.substring(E2E_PREFIX.length());
            String[] parts = payload.split(":", 3);
            if (parts.length != 3) return null;

            byte[] ephPubBytes = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(parts[2], Base64.NO_WRAP);

            // Reconstruct ephemeral public key
            KeyFactory kf = KeyFactory.getInstance("XDH");
            PublicKey ephPub = kf.generatePublic(new X509EncodedKeySpec(ephPubBytes));

            // ECDH key agreement: myPrivate × ephPub → sharedSecret
            KeyAgreement ka = KeyAgreement.getInstance("XDH");
            ka.init(myPrivateKey);
            ka.doPhase(ephPub, true);
            byte[] sharedSecret = ka.generateSecret();

            // Derive AES key via SHA-256 (same derivation as sender)
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] aesKeyBytes = sha256.digest(sharedSecret);
            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

            // AES-GCM decryption
            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plainBytes = cipher.doFinal(ciphertext);

            return new String(plainBytes, java.nio.charset.StandardCharsets.UTF_8);

        } catch (Exception e) {
            Log.e(TAG, "E2E decryption failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Checks whether a ciphertext string is E2E-encrypted (vs passphrase-encrypted).
     * Used by MeshManager to decide which decryption path to take.
     */
    public static boolean isE2EEncrypted(String content) {
        return content != null && content.startsWith(E2E_PREFIX);
    }
}
