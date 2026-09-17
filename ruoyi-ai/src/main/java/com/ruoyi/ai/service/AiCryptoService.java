package com.ruoyi.ai.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;

@Service
public class AiCryptoService
{
    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${AI_MASTER_KEY:}")
    private String aiMasterKey;

    @Value("${token.secret:}")
    private String tokenSecret;

    public String encrypt(String plainText)
    {
        if (StringUtils.isEmpty(plainText))
        {
            return null;
        }
        try
        {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, buildKey(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        }
        catch (Exception e)
        {
            throw new ServiceException("AI Token 加密失败");
        }
    }

    public String decrypt(String cipherText)
    {
        if (StringUtils.isEmpty(cipherText))
        {
            return null;
        }
        if (!cipherText.startsWith(PREFIX))
        {
            throw new ServiceException("AI Token 密文格式不受支持");
        }
        try
        {
            byte[] payload = Base64.getDecoder().decode(cipherText.substring(PREFIX.length()));
            if (payload.length <= IV_LENGTH)
            {
                throw new IllegalArgumentException("invalid encrypted payload");
            }
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, buildKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            throw new ServiceException("AI Token 解密失败，请检查 AI_MASTER_KEY 配置");
        }
    }

    private SecretKeySpec buildKey() throws Exception
    {
        String source = StringUtils.isNotEmpty(aiMasterKey) ? aiMasterKey : tokenSecret;
        if (StringUtils.isEmpty(source))
        {
            throw new ServiceException("缺少 AI_MASTER_KEY，且无法使用 token.secret 作为兼容回退密钥");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return new SecretKeySpec(digest.digest(source.getBytes(StandardCharsets.UTF_8)), "AES");
    }
}
