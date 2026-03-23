package patience.meshchat;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ============================================================================
 * CryptoManager - Message Encryption & Decryption for MeshChat
 * ============================================================================
 *
 * WHY ENCRYPTION MATTERS:
 * ───────────────────────
 * In a mesh network, messages hop through stranger's devices. Without
 * encryption, anyone relaying your message could read its contents.
 * CryptoManager encrypts every message so that only devices with the
 * same passphrase can read it — relay nodes just see scrambled bytes.
 *
 * HOW IT WORKS (simplified):
 * ──────────────────────────
 *   1. All devices in the mesh share a SECRET PASSPHRASE (like a group password)
 *   2. The passphrase is converted into a strong encryption key using PBKDF2
 *      (a special algorithm that makes the key hard to guess)
 *   3. Each message is encrypted with AES-256-GCM before being sent
 *   4. The receiving device decrypts using the same key
 *
 * WHAT IS AES-256-GCM?
 * ────────────────────
 * - AES (Advanced Encryption Standard): The most widely used encryption algorithm
 *   Used by banks, governments, and military worldwide
 * - 256: The key size in bits — longer keys = harder to crack
 *   256-bit AES would take billions of years to brute-force
 * - GCM (Galois/Counter Mode): A mode that provides BOTH:
 *   • Confidentiality: No one can read the message without the key
 *   • Integrity: If someone tampers with the encrypted data, we'll detect it
 *
 * WHAT IS PBKDF2?
 * ───────────────
 * PBKDF2 = Password-Based Key Derivation Function 2
 * It takes a human-readable password (like "MyMeshGroup") and converts
 * it into a strong 256-bit cryptographic key by running it through
 * 65,536 rounds of hashing. This makes it extremely slow for attackers
 * to try every possible password (brute force attack).
 *
 * ============================================================================
 */
public class CryptoManager {

    private static final String TAG = "CryptoManager";

    // ─── Encryption Configuration ───────────────────────────────────────

    /** AES encryption in GCM mode — the gold standard for symmetric encryption */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /**
     * GCM uses a 12-byte (96-bit) Initialization Vector (IV).
     *
     * WHAT IS AN IV?
     * An IV is a random value used to ensure that encrypting the same message
     * twice produces DIFFERENT ciphertext. Without an IV, an attacker could
     * notice when you send identical messages (a security weakness).
     *
     * Think of it like adding a random prefix to your message before encrypting:
     *   "Hello" + random_IV_1 → "x8f2k..." (encrypted)
     *   "Hello" + random_IV_2 → "p3m9a..." (different encrypted output!)
     */
    private static final int GCM_IV_LENGTH = 12;

    /** GCM authentication tag: 128 bits for strong tamper detection */
    private static final int GCM_TAG_LENGTH = 128;

    /**
     * PBKDF2 iteration count — how many times we hash the password.
     * Higher = more secure but slower. 65,536 is a good balance for mobile.
     */
    private static final int PBKDF2_ITERATIONS = 65536;

    /** AES-256 requires a 256-bit (32-byte) key */
    private static final int KEY_LENGTH = 256;

    /**
     * Salt for key derivation — adds randomness to prevent "rainbow table" attacks.
     * In a production app, each mesh network would have its own unique salt.
     */
    private static final byte[] SALT = "MeshChatSalt2026".getBytes(StandardCharsets.UTF_8);

    // ─── Instance Variables ─────────────────────────────────────────────

    /** The AES encryption key, derived from the passphrase */
    private SecretKey secretKey;

    /** Cryptographically secure random number generator for creating IVs */
    private final SecureRandom secureRandom = new SecureRandom();

    // ─── Constructor ────────────────────────────────────────────────────

    /**
     * Creates a CryptoManager with the given passphrase.
     *
     * ALL devices in the same mesh network MUST use the same passphrase.
     * If passphrases don't match, messages will fail to decrypt and
     * show "[Encrypted - wrong key]" instead of the actual content.
     *
     * @param passphrase The shared secret for the mesh network
     */
    public CryptoManager(String passphrase) {
        this.secretKey = deriveKey(passphrase);
    }

    // ─── Key Derivation ─────────────────────────────────────────────────

    /**
     * Converts a human-readable passphrase into a strong AES-256 encryption key.
     *
     * The process (PBKDF2 with HMAC-SHA256):
     *   1. Take the passphrase characters
     *   2. Mix with the SALT (adds uniqueness)
     *   3. Hash 65,536 times (makes brute-force attacks impractical)
     *   4. Output a 256-bit key suitable for AES encryption
     *
     * @param passphrase The user's shared secret
     * @return A SecretKey ready for AES encryption, or null if derivation fails
     */
    private SecretKey deriveKey(String passphrase) {
        try {
            // PBEKeySpec = Password-Based Encryption Key Specification
            PBEKeySpec spec = new PBEKeySpec(
                    passphrase.toCharArray(),  // The password as a char array
                    SALT,                       // Random salt to prevent rainbow tables
                    PBKDF2_ITERATIONS,          // Number of hash rounds (65,536)
                    KEY_LENGTH                  // Desired key length (256 bits)
            );

            // Use the PBKDF2 algorithm with HMAC-SHA256 as the hash function
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();

            // Wrap the raw bytes into an AES SecretKey object
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            Log.e(TAG, "Key derivation failed: " + e.getMessage());
            return null;
        }
    }

    // ─── Encryption ─────────────────────────────────────────────────────

    /**
     * Encrypts a plaintext message and returns a Base64-encoded string.
     *
     * The encrypted output format is:
     *   [12-byte IV][encrypted data + authentication tag]
     *
     * The IV is prepended to the encrypted data so we can extract it
     * during decryption. Think of it like a letter:
     *   Envelope = [IV (return address)] + [Encrypted letter + Seal (auth tag)]
     *
     * Base64 encoding converts the binary encrypted bytes into safe ASCII
     * text that can be stored in a String and transmitted over the network.
     *
     * @param plaintext The readable message text to encrypt
     * @return Base64-encoded encrypted string, or the original text if encryption fails
     */
    public String encrypt(String plaintext) {
        // Safety check — if key derivation failed or input is null, return as-is
        if (secretKey == null || plaintext == null) return plaintext;

        try {
            // Step 1: Generate a fresh random IV for this message
            // NEVER reuse IVs — each message MUST have a unique IV for security
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            // Step 2: Configure the AES-GCM cipher for encryption
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec paramSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, paramSpec);

            // Step 3: Encrypt the message (doFinal returns encrypted bytes + auth tag)
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Step 4: Combine IV + encrypted data into a single byte array
            // We need the IV later for decryption, so we prepend it
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            // Step 5: Convert to Base64 text for safe transport in a String
            return Base64.encodeToString(combined, Base64.NO_WRAP);

        } catch (Exception e) {
            Log.e(TAG, "Encryption failed: " + e.getMessage());
            return plaintext; // Fallback to plaintext if encryption fails
        }
    }

    // ─── Decryption ─────────────────────────────────────────────────────

    /**
     * Decrypts a Base64-encoded ciphertext back to readable plaintext.
     *
     * Reverses the encryption process:
     *   1. Decode from Base64 back to raw bytes
     *   2. Extract the IV from the first 12 bytes
     *   3. Extract the encrypted data from the remaining bytes
     *   4. Decrypt using our key + the extracted IV
     *   5. Return the original readable text
     *
     * If decryption fails (wrong passphrase or corrupted data), returns
     * a placeholder string so the app doesn't crash.
     *
     * @param ciphertext Base64-encoded encrypted string from encrypt()
     * @return The original plaintext, or "[Encrypted - wrong key]" if decryption fails
     */
    public String decrypt(String ciphertext) {
        // Safety check
        if (secretKey == null || ciphertext == null) return ciphertext;

        try {
            // Step 1: Decode from Base64 back to raw bytes
            byte[] combined = Base64.decode(ciphertext, Base64.NO_WRAP);

            // Step 2: Extract the IV (first 12 bytes)
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);

            // Step 3: Extract the encrypted data (everything after the IV)
            byte[] encrypted = new byte[combined.length - iv.length];
            System.arraycopy(combined, iv.length, encrypted, 0, encrypted.length);

            // Step 4: Configure the cipher for decryption with the same IV
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec paramSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, paramSpec);

            // Step 5: Decrypt and return the original text
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);

        } catch (Exception e) {
            // This happens when:
            // - The passphrase is wrong (different key → decryption fails)
            // - The encrypted data was corrupted during transit
            // - The data wasn't actually encrypted (not valid Base64/AES format)
            Log.e(TAG, "Decryption failed — wrong passphrase or corrupted data");
            return "[Encrypted - wrong key]";
        }
    }

    // ─── Key Management ─────────────────────────────────────────────────

    /**
     * Updates the encryption key with a new passphrase.
     *
     * Call this when the user changes the mesh network's shared passphrase.
     * After updating, only devices with the new passphrase can read messages.
     *
     * WARNING: Changing the passphrase means you can no longer decrypt
     * messages that were encrypted with the old passphrase.
     *
     * @param newPassphrase The new shared secret for the mesh
     */
    public void updatePassphrase(String newPassphrase) {
        this.secretKey = deriveKey(newPassphrase);
        Log.d(TAG, "Encryption passphrase updated");
    }
}
