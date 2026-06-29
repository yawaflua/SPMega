using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace SpMega.Backend.Services;

public static class EncryptionHelper
{
    private static byte[]? _key;

    /// <summary>
    /// Initializes the encryption key. The key will be derived using SHA-256 from the provided secret string
    /// to ensure it is exactly 32 bytes (256 bits) long.
    /// </summary>
    public static void Initialize(string secretKey)
    {
        if (string.IsNullOrWhiteSpace(secretKey))
        {
            throw new ArgumentException("Encryption key cannot be null or empty.", nameof(secretKey));
        }

        _key = SHA256.HashData(Encoding.UTF8.GetBytes(secretKey));
    }

    /// <summary>
    /// Encrypts the plain text using AES-256-CBC.
    /// A unique Initialization Vector (IV) is generated for each encryption and stored alongside the ciphertext.
    /// </summary>
    public static string Encrypt(string plainText)
    {
        if (_key == null)
        {
            throw new InvalidOperationException("EncryptionHelper has not been initialized. Call Initialize() first.");
        }

        if (string.IsNullOrEmpty(plainText))
        {
            return plainText;
        }

        using var aes = Aes.Create();
        aes.Key = _key;
        aes.GenerateIV();
        var iv = aes.IV;

        using var encryptor = aes.CreateEncryptor(aes.Key, iv);
        using var ms = new MemoryStream();
        
        ms.Write(iv, 0, iv.Length);

        using (var cs = new CryptoStream(ms, encryptor, CryptoStreamMode.Write))
        using (var writer = new StreamWriter(cs, Encoding.UTF8))
        {
            writer.Write(plainText);
        }

        return Convert.ToBase64String(ms.ToArray());
    }

    /// <summary>
    /// Decrypts the cipher text using AES-256-CBC.
    /// Reconstructs the Initialization Vector (IV) from the beginning of the ciphertext.
    /// Falls back to returning the input string if decryption fails (e.g., for legacy plaintext tokens).
    /// </summary>
    public static string Decrypt(string cipherText)
    {
        if (_key == null)
        {
            throw new InvalidOperationException("EncryptionHelper has not been initialized. Call Initialize() first.");
        }

        if (string.IsNullOrEmpty(cipherText))
        {
            return cipherText;
        }

        try
        {
            var fullCipher = Convert.FromBase64String(cipherText);

            using var aes = Aes.Create();
            aes.Key = _key;

            var ivLength = aes.BlockSize / 8;
            if (fullCipher.Length < ivLength)
            {
                // Too short to be an encrypted payload, return original
                return cipherText;
            }

            var iv = new byte[ivLength];
            var cipher = new byte[fullCipher.Length - ivLength];

            Buffer.BlockCopy(fullCipher, 0, iv, 0, ivLength);
            Buffer.BlockCopy(fullCipher, ivLength, cipher, 0, cipher.Length);

            aes.IV = iv;

            using var decryptor = aes.CreateDecryptor(aes.Key, aes.IV);
            using var ms = new MemoryStream(cipher);
            using var cs = new CryptoStream(ms, decryptor, CryptoStreamMode.Read);
            using var reader = new StreamReader(cs, Encoding.UTF8);

            return reader.ReadToEnd();
        }
        catch (Exception ex) when (ex is CryptographicException or FormatException)
        {
            // Fallback for legacy unencrypted tokens
            return cipherText;
        }
    }
}
